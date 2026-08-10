"""多智能体编排层 —— 路由 → 检索 → 工具 → 生成"""

import logging
from typing import List, Dict, Any
from openai import OpenAI
from config import settings

logger = logging.getLogger(__name__)

# ═════════════════════════════════════
# 各智能体 System Prompt
# ═════════════════════════════════════

DISPATCHER_PROMPT = """你是请求分配器。分析用户意图，输出JSON：

类型定义：
- "chat" : 闲聊、问候、简单问题（不需要检索）
- "search": 需要从知识库中查找信息的专业问题
- "skill" : 需要调用工具的问题（天气/计算/图表/翻译等）
- "doc"   : 关于文档本身的问题（上传/删除/版本等）

输出格式：{"intent": "search", "reason": "用户问..."}

只输出JSON，不要其他文字："""

RETRIEVER_PROMPT = """你是检索专家。根据用户问题，判断是否需要调用 make_chart/make_icon 等工具。
如果需要，先调用工具生成结果，再将工具结果和检索内容一起交给 Generator。
如果不需要工具，直接将检索到的文档内容整理好传给 Generator。

工具参数要求（重要）：
- make_chart: type="bar"/"line"/"pie", labels="A,B,C", data="10,20,30" (label和data必须用逗号分隔的字符串)
- make_icon: description="图标描述", size=128

输出JSON格式：{"need_tool": false, "tool_name": "", "tool_args": {}, "context": "整理后的文档内容"}

只输出JSON："""

GENERATOR_PROMPT = """你是回答生成专家。基于检索结果和工具输出，生成最终答案。

规则：
1. 优先引用检索到的文档内容
2. 如果有工具生成的图表/图片，必须在回答中包含
3. 如果检索无结果，诚实告知
4. 结合对话历史保持连贯
5. 使用中文回答，内容详细准确"""

CRITIC_PROMPT = """你是审查专家。评估以下回答的质量，找出问题：
- 是否有事实错误或编造信息？
- 是否遗漏了重要内容？
- 是否逻辑不通或表述不清？
- 是否有幻觉（引用不存在的资料）？

原始问题：{query}
检索到的参考资料：{context}
生成的回答：{answer}

输出JSON格式：
{{"passed": false, "issues": ["问题1"], "suggestion": "具体改进方向"}}

如果回答完全正确，passed=true，issues=[]。
只输出JSON："""

SELF_CRITIC_PROMPT = """你是自我反思专家。不依赖外部资料，只评估回答本身的质量：
- 逻辑是否自洽？推理是否有漏洞？
- 回答是否完整覆盖了用户问题的所有要点？
- 表述是否清晰易懂？有没有歧义？
- 语气是否恰当（专业但不冷漠）？
- 有没有过度自信或含糊不清的表述？

原始问题：{query}
生成的回答：{answer}

输出JSON格式：
{{"passed": false, "issues": ["问题1"], "suggestion": "具体改进方向"}}

如果回答高质量，passed=true。只输出JSON："""


class Orchestrator:
    """
    编排器：通过不同 system prompt 模拟多个专家
    底层使用同一个 DeepSeek 模型（节省成本 + 减少延迟）
    """

    def __init__(self):
        self.client = OpenAI(api_key=settings.deepseek_api_key, base_url=settings.deepseek_base_url)
        self.model = settings.deepseek_model

    def _resolve(self, llm_config, temperature, max_tokens):
        """合并管理后台下发配置与本地环境变量"""
        if not llm_config:
            return self.client, self.model, temperature, max_tokens
        client = OpenAI(
            api_key=llm_config.api_key or settings.deepseek_api_key,
            base_url=llm_config.base_url or settings.deepseek_base_url)
        model = llm_config.model or self.model
        temp = llm_config.temperature if llm_config.temperature is not None else temperature
        max_tok = llm_config.max_tokens or max_tokens
        return client, model, temp, max_tok

    def _call(self, system_prompt: str, user_message: str, temperature: float = 0.3,
              max_tokens: int = 1024, llm_config=None) -> str:
        """调用 LLM 并返回文本"""
        client, model, temp, max_tok = self._resolve(llm_config, temperature, max_tokens)
        if not settings.deepseek_api_key and not (llm_config and llm_config.api_key):
            return "{}"
        try:
            resp = client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_message}
                ],
                temperature=temp, max_tokens=max_tok
            )
            return resp.choices[0].message.content or "{}"
        except Exception as e:
            logger.error(f"Orchestrator call failed: {e}")
            return "{}"

    def dispatch(self, query: str, llm_config=None) -> Dict[str, str]:
        """第一步：分析用户意图，决定走哪条路线"""
        result = self._call(DISPATCHER_PROMPT, query, llm_config=llm_config)
        try:
            import json
            return json.loads(result)
        except Exception:
            return {"intent": "search", "reason": "fallback"}

    def retrieve(self, query: str, docs: List[Dict], llm_config=None) -> str:
        """第二步：检索专家整理上下文，判断是否需要工具"""
        context = "\n".join(
            f"[{d.get('title','')}] {d.get('content','')[:500]}"
            for d in docs[:5]
        ) if docs else "无检索结果"
        msg = f"用户问题：{query}\n\n检索结果：\n{context}"
        result = self._call(RETRIEVER_PROMPT, msg, llm_config=llm_config)
        try:
            import json
            return json.loads(result)
        except Exception:
            return {"need_tool": False, "context": context}

    def generate(self, query: str, context: str, history: List[Dict] = None,
                 long_term_memory: str = "", llm_config=None) -> str:
        """第三步：生成最终回答（带反思循环）"""
        answer = self._generate_once(query, context, history, long_term_memory, llm_config)

        # 反思循环：Critic 审查 → 如有问题 → 修正重生成（最多 3 轮）
        for i in range(3):
            critic_result = self._critique(query, context, answer, llm_config)
            if critic_result.get("passed", False):
                logger.info(f"反思通过 (第{i+1}轮)")
                break
            logger.info(f"反思发现问题: {critic_result.get('issues', [])}, 修正中...")
            answer = self._regenerate(query, context, history,
                                       critic_result.get("suggestion", ""), long_term_memory, llm_config)
        return answer

    def _generate_once(self, query: str, context: str, history: List[Dict], ltm: str,
                       llm_config=None) -> str:
        sys = GENERATOR_PROMPT
        if ltm:
            sys += "\n\n用户背景：" + ltm

        msgs = [{"role": "system", "content": sys}]
        if history:
            for h in history[-10:]:
                msgs.append({"role": h.get("role", "user"), "content": h.get("content", "")})

        user_msg = f"用户问题：{query}\n\n上下文资料：\n{context}\n\n请生成详细回答。"
        msgs.append({"role": "user", "content": user_msg})

        if not settings.deepseek_api_key and not (llm_config and llm_config.api_key):
            return f"基于知识库检索结果，关于「{query}」未找到相关信息。"

        try:
            client, model, temp, max_tok = self._resolve(llm_config, 0.3, settings.max_tokens)
            resp = client.chat.completions.create(
                model=model, messages=msgs,
                temperature=temp, max_tokens=max_tok
            )
            return resp.choices[0].message.content or ""
        except Exception as e:
            logger.error(f"Generator failed: {e}")
            return "回答生成失败，请重试"

    def _critique(self, query: str, context: str, answer: str, llm_config=None) -> dict:
        """Critic Agent：审查回答质量（KB 对比 + 自我反思）"""
        # 有 KB 资料 → 事实性审查
        if context and context != "无相关资料" and len(context) > 10:
            prompt = CRITIC_PROMPT.format(query=query, context=context[:2000], answer=answer[:2000])
            temp = 0.1
        else:
            # 无 KB → 纯自我反思（逻辑/表述/完整性）
            prompt = SELF_CRITIC_PROMPT.format(query=query, answer=answer[:2000])
            temp = 0.3
        result = self._call("你是审查专家", prompt, temperature=temp, max_tokens=512, llm_config=llm_config)
        try:
            import json
            return json.loads(result)
        except Exception:
            return {"passed": True}

    def _regenerate(self, query: str, context: str, history: List[Dict],
                     suggestion: str, ltm: str, llm_config=None) -> str:
        """反思后重新生成：把 Critic 建议加入上下文"""
        improved_ctx = context
        if suggestion:
            improved_ctx += "\n\n[改进建议]\n" + suggestion
        return self._generate_once(query, improved_ctx, history, ltm, llm_config)


# 全局单例
orchestrator = Orchestrator()
