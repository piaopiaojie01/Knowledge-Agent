"""生成模块 - DeepSeek Flash LLM 回答生成

核心职责：
  - 构建对话上下文（智能窗口 + 自动压缩）
  - 调用 DeepSeek API 生成 RAG 回答
  - 支持 function calling 工具调用（12 个 Skill）
  - 提取长期记忆关键事实
"""
import logging
import time
from typing import List, Dict, Any, Tuple, Iterator
import json
from openai import OpenAI
from config import settings
from .skills import TOOLS, execute_tool

logger = logging.getLogger(__name__)

# 简易 token 估算（中文 1 char ≈ 1.5 tokens，英文 1 char ≈ 0.3 tokens）
def count_tokens(text: str) -> int:
    cn = sum(1 for c in text if '\u4e00' <= c <= '\u9fff')
    en = len(text) - cn
    return int(cn * 1.5 + en * 0.3)

def compress_history(history: list, keep_recent: int) -> list:
    """压缩历史：保留最近 N 条，其余用 LLM 摘要"""
    if len(history) <= keep_recent:
        return history
    older = history[:-keep_recent]
    recent = history[-keep_recent:]
    lines = []
    for h in older:
        role = "用户" if h.get("role") == "user" else "助手"
        content = h.get("content", "")[:200]
        lines.append(f"[{role}]: {content}")
    summary = "\n".join(lines)
    return [{"role": "system", "content": f"【历史摘要】\n{summary}"}] + recent

def build_messages(system_prompt: str, history: list, user_msg: str, window: int, threshold: float, keep_recent: int) -> list:
    """
    构建发送给 LLM 的消息列表
    
    策略：
      1. 从历史末尾倒序填充，每条消息估算 token 数
      2. 总 token 达到 window * threshold（默认 95%）时停止
      3. 如果部分历史被截断 → 压缩为摘要 + 保留最近 N 条原文
      4. 返回 [system_prompt] + history + [user_msg]
    """
    sys_tokens = count_tokens(system_prompt)
    user_tokens = count_tokens(user_msg)
    limit = int(window * threshold)

    msgs = []
    total = sys_tokens + user_tokens
    for h in reversed(history):
        t = count_tokens(h.get("content", "")) + 4
        if total + t > limit:
            break
        msgs.insert(0, h)
        total += t
    if len(msgs) < len(history):
        msgs = compress_history(history, keep_recent)
    return [{"role": "system", "content": system_prompt}] + msgs + [{"role": "user", "content": user_msg}]


SYSTEM_PROMPT = """你是一个知识库问答助手。回答规则：
1. 优先基于参考资料回答，不要编造信息
2. 参考资料足够时 → 详细准确回答，末尾列出来源
3. 参考资料无关或低质量时 → 直接告知"知识库中暂无相关信息"，然后可以用你自己的知识简单说明
4. 对于问候、自我介绍等简单问题，自然友好地回答
5. 结合对话历史理解上下文，保持回答连贯
6. 如果已知用户信息中包含相关事实，应该优先利用这些信息
7. 使用中文回答
8. 当用户要求画图/生成图表/可视化数据时，必须调用 make_chart 工具；把工具返回的图片 HTML/URL 原样包含在回答中，不要省略或替换成文字描述
9. 调用 web_search/wikipedia_lookup 工具后，必须引用工具返回的具体内容"""


def _is_chart_request(query: str) -> bool:
    """判断用户是否要求画图"""
    ql = query.lower()
    return any(kw in ql for kw in settings.chart_keywords)

def _force_chart(query: str) -> str:
    """从 query 提取数据预生成图表；数据质量不够则返回空（交 LLM tool calling）"""
    from .skills import _make_chart
    import re
    pairs = re.findall(r'([A-Za-z\u4e00-\u9fff]+)\s*(\d+)', query)
    if not pairs:
        return ""
    # ── 数据质量：标签必须像"名称"而非虚词/碎片 ──
    garbage = {"近", "约", "大概", "左右", "大约", "差不多", "的", "了", "是", "有", "在",
               "第一", "第二", "第三", "第", "个", "种", "些", "每", "各"}
    clean = []
    for label, val in pairs:
        label = label.strip()
        # 虚词 / 太短 / 纯数字 → 跳过
        if label in garbage or len(label) < 2 or label.isdigit():
            continue
        # 英文缩写（A/B/C 等单字母）也跳过
        if len(label) == 1 and label.isascii():
            continue
        clean.append((label, val))
    if len(clean) < 2:
        return ""
    labels = ",".join(p[0] for p in clean[:10])
    data = ",".join(p[1] for p in clean[:10])
    chart_type = "pie" if ("饼" in query or "pie" in query.lower()) else ("barh" if ("横向" in query or "barh" in query.lower()) else ("line" if ("线" in query or "折" in query or "line" in query.lower()) else "bar"))
    # 提取单位
    unit = ""
    for u in settings.chart_units:
        if u in query:
            unit = u
            break
    try:
        return _make_chart(chart_type, labels, data, "", unit)
    except Exception:
        return ""
    return ""


class Generator:
    """基于 DeepSeek 的 RAG 回答生成器"""

    def __init__(self):
        self.client = OpenAI(api_key=settings.deepseek_api_key, base_url=settings.deepseek_base_url)
        self.model = settings.deepseek_model
        # 最近一次 generate_stream 的输入 token 数（与非流式 generate 同口径，全量 messages）
        self.last_input_tokens = 0

    def _prepare_messages(self, query: str, sources: List[Dict[str, Any]],
                          history: List[Dict[str, str]] = None,
                          long_term_memory: str = "") -> Tuple[list, int, str, str]:
        """构建发送给 LLM 的消息（generate / generate_stream 共用）

        返回 (messages, input_tokens, chart_result, user_message)
        """
        # 判断来源质量：未稀释向量余弦分 >= source_threshold 才作为有效 RAG 上下文
        best_score = max((s.get("vector_score", s.get("score", 0)) for s in sources), default=0)
        if best_score >= settings.source_threshold:
            context_parts = []
            for i, doc in enumerate(sources, 1):
                context_parts.append(
                    f"[文档{i}] {doc.get('title', '无标题')}\n"
                    f"来源: {doc.get('kb_name', '未知')}\n"
                    f"内容: {doc.get('content', '')}\n")
            user_message = f"参考资料：\n{chr(10).join(['---'] + context_parts)}\n用户问题：{query}\n请根据参考资料回答。"
        else:
            user_message = f"用户问题：{query}\n（知识库中未找到高质量参考资料，请基于常识详细回答）"

        # ── 图表前置生成（模拟 tool call 注入，LLM 感知为"自己调的工具"）──
        chart_result = ""
        if _is_chart_request(query):
            chart_result = _force_chart(query)
            if chart_result:
                logger.info(f"模拟 tool call: make_chart for {query[:40]}...")
            else:
                # 无可用数据 → 强制提示 LLM 用 tool
                chart_result = "__NEED_TOOL__"
                user_message += "\n\n[系统指令] 你必须调用 make_chart 工具，根据你的知识提供数据来生成图表。"
                logger.info(f"强制 LLM tool call: {query[:40]}...")

        # 构建对话消息（智能窗口 + 自动压缩）
        sys_content = SYSTEM_PROMPT
        if long_term_memory:
            sys_content += "\n\n" + long_term_memory
        messages = build_messages(
            sys_content, history or [], user_message,
            settings.context_window, settings.compress_threshold, settings.compress_keep_recent
        )
        # 统计输入 token
        input_tokens = sum(count_tokens(m.get("content", "")) for m in messages)

        # ── 模拟 tool call 注入（仅当 chart_result 是真实图表 HTML 时）──
        if chart_result and chart_result.startswith("<img"):
            from .skills import TOOLS as _TOOLS
            # 找 make_chart 的工具定义来构造 tool_call
            make_chart_def = next((t for t in _TOOLS if t["function"]["name"] == "make_chart"), None)
            if make_chart_def:
                fake_tc = {
                    "id": "call_chart_pregen_001",
                    "type": "function",
                    "function": {"name": "make_chart", "arguments": "{}"}
                }
                messages.append({"role": "assistant", "content": None, "tool_calls": [fake_tc]})
                messages.append({"role": "tool", "tool_call_id": "call_chart_pregen_001", "content": chart_result})
                logger.info(f"已注入模拟 tool call → chart {len(chart_result)} chars")

        return messages, input_tokens, chart_result, user_message

    def generate(self, query: str, sources: List[Dict[str, Any]],
                 history: List[Dict[str, str]] = None,
                 long_term_memory: str = "",
                 stream: bool = False) -> Tuple[str, int, int]:
        if not settings.deepseek_api_key:
            return self._mock_generate(query, sources), 0, 0

        messages, input_tokens, chart_result, user_message = self._prepare_messages(
            query, sources, history, long_term_memory)

        try:
            # ── Tool Calling 循环（最多 5 轮）──
            tool_calls_made = 0
            chart_generated = bool(chart_result and chart_result.startswith("<img"))  # 预生成成功的
            for _ in range(5):
                # LLM 调用失败重试 3 次（指数退避），仍失败则抛给外层兜底
                response = None
                last_err = None
                for attempt in range(3):
                    try:
                        response = self.client.chat.completions.create(
                            model=self.model,
                            messages=messages,
                            tools=TOOLS,
                            temperature=0.3, max_tokens=settings.max_tokens)
                        break
                    except Exception as e:
                        last_err = e
                        logger.warning(f"LLM 调用失败(第{attempt + 1}/3次): {e}")
                        if attempt < 2:
                            time.sleep(1.0 * (attempt + 1))
                if response is None:
                    raise last_err
                msg = response.choices[0].message
                if msg.tool_calls:
                    tool_calls_made += 1
                    for tc in msg.tool_calls:
                        name = tc.function.name
                        # 参数 JSON 解析失败 → 错误反馈给 LLM 让其自我纠正重试
                        try:
                            args = json.loads(tc.function.arguments)
                        except (json.JSONDecodeError, TypeError) as e:
                            logger.warning(f"工具参数解析失败 {name}: {e}")
                            result = f"错误：工具参数 JSON 解析失败({e})，请修正参数格式后重新调用。"
                            messages.append({"role": "assistant", "content": None, "tool_calls": [tc]})
                            messages.append({"role": "tool", "tool_call_id": tc.id, "content": result})
                            continue
                        # 工具执行失败 → 错误反馈给 LLM 重试，不再中断整个生成
                        try:
                            result = execute_tool(name, args)
                        except Exception as e:
                            logger.warning(f"工具执行失败 {name}({args}): {e}")
                            result = f"错误：工具 {name} 执行失败({e})，请检查参数后重试，或改用文字回答。"
                        logger.info(f"Skill 调用: {name}({args}) → {result[:80]}")
                        if name == "make_chart" and "<img" in result:
                            chart_generated = True
                        elif name == "make_chart":
                            # 图表工具调了但失败
                            result += "\n[通知] 图表生成失败，请检查参数或稍后重试。"
                        messages.append({"role": "assistant", "content": None, "tool_calls": [tc]})
                        messages.append({"role": "tool", "tool_call_id": tc.id, "content": result})
                    continue
                answer = msg.content or ""
                # ── 重试：图表请求没有任何图表 → 提示 LLM 再试 ──
                if _is_chart_request(query) and not chart_generated and "<img" not in answer and "![" not in answer and chart_result != "__TRIED__":
                    logger.info(f"LLM 未调 make_chart，system 提示重试: {query[:40]}")
                    chart_result = "__TRIED__"
                    messages.append({"role": "system", "content": "[紧急] 你必须调用 make_chart 工具来生成图表！不要只给文字回答。现在立刻调用 make_chart 工具。"})
                    continue
                # ── 最终通知：图表请求最终无图 ──
                if _is_chart_request(query) and not chart_generated and "<img" not in answer and "![" not in answer:
                    answer = (answer or "") + "\n\n⚠️ 图表生成未成功，请尝试更具体的描述（如：柱状图 北京300 上海500）。"
                output_tokens = count_tokens(answer)
                return answer, input_tokens, output_tokens
            # 5 轮工具调用耗尽：再做一次不带 tools 的调用，让 LLM 基于已有工具结果直接作答
            try:
                response = self.client.chat.completions.create(
                    model=self.model,
                    messages=messages,
                    temperature=0.3, max_tokens=settings.max_tokens)
                answer = response.choices[0].message.content or ""
                if answer:
                    return answer, input_tokens, count_tokens(answer)
            except Exception as e:
                logger.warning(f"工具耗尽后的最终调用失败: {e}")
            return "工具调用次数过多，请简化问题。", input_tokens, 0
        except Exception as e:
            logger.error(f"LLM 生成失败: {e}")
            return self._mock_generate(query, sources), count_tokens(user_message), 0

    def generate_stream(self, query: str, sources: List[Dict[str, Any]],
                        history: List[Dict[str, str]] = None,
                        long_term_memory: str = "") -> Iterator[str]:
        """流式生成回答，逐块 yield 文本增量

        - 图表预生成成功 → 模拟 tool call 已在 messages 里，LLM 正常引用
        - 图表预生成失败（__NEED_TOOL__）→ 流式无法调工具，回退到带
          tool calling 的非流式 generate()，一次性 yield 完整答案
        """
        if not settings.deepseek_api_key:
            yield self._mock_generate(query, sources)
            return

        messages, input_tokens, chart_result, _ = self._prepare_messages(
            query, sources, history, long_term_memory)
        # 与非流式 generate 同口径：全量 messages 的 token 数，供路由层 final 事件使用
        self.last_input_tokens = input_tokens

        # ── 图表预生成失败：流式不传 tools，LLM 无法调 make_chart ──
        # 回退到非流式 generate()（带 tools=TOOLS + 重试机制），保证出图
        if chart_result == "__NEED_TOOL__":
            logger.info(f"流式图表请求回退非流式 tool calling: {query[:40]}...")
            try:
                answer, _, _ = self.generate(query, sources, history, long_term_memory)
            except Exception as e:
                logger.error(f"图表非流式回退失败: {e}")
                answer = self._mock_generate(query, sources)
            yield answer
            return

        produced = False
        try:
            stream = self.client.chat.completions.create(
                model=self.model,
                messages=messages,
                temperature=0.3, max_tokens=settings.max_tokens,
                stream=True)
            for chunk in stream:
                if not chunk.choices:
                    continue
                delta = chunk.choices[0].delta.content
                if delta:
                    produced = True
                    yield delta
        except Exception as e:
            logger.error(f"LLM 流式生成失败: {e}")
            # 已产出 delta：无法再拼接 mock 兜底（会接在残句后），抛给路由层发 error 事件
            if produced:
                raise
            # 尚未产出任何内容：允许 mock 兜底
            yield self._mock_generate(query, sources)

    def extract_facts(self, query: str, answer: str) -> list[str]:
        """
        从本轮对话中提取用户的关键个人信息，存入长期记忆
        
        规则：
          - 只提取用户明确说出的个人信息（姓名、偏好、职业等）
          - 每行一条，格式为「用户名XXX」或「用户名喜欢XXX」
          - 禁止输出否定/空泛陈述（如「未提供」）
          - 无有效信息时输出「无」
        """
        if not settings.deepseek_api_key:
            return []
        prompt = f"""从以下对话中提取关于用户的唯一确定事实。
规则：
- 只提取用户明确说出的个人信息（姓名、偏好、职业等）
- 每行一条，用"用户名XXX"或"用户名喜欢XXX"格式
- 禁止输出"没有/未提供/无/不确定"等否定或空泛陈述
- 如果用户没有说出任何个人信息，输出"无"

用户问题：{query[:500]}
助手回答：{answer[:500]}

关键事实（每行一条，无则输出"无"）："""

        try:
            response = self.client.chat.completions.create(
                model=self.model,
                messages=[{"role": "user", "content": prompt}],
                temperature=0.1, max_tokens=512)
            text = response.choices[0].message.content.strip()
            if not text or text == "无":
                return []
            # 过滤否定/空泛陈述（行首列表标记用正则剥离，避免截掉数字开头的真实事实；
            # "无" 只做整词相等比较，避免误杀含"无"的正常事实）
            import re
            # 只剥离行首列表标记（-、•、· 及 "1." "2)" 等编号），裸数字开头的事实不受影响
            lines = [re.sub(r'^[-•·\s]*(?:\d+[.、)．]\s*)?', '', line).strip()
                    for line in text.split("\n") if line.strip()]
            return [l for l in lines if l and l != "无"
                    and not any(w in l for w in ("没有", "未提供", "不确定", "无值得"))]
        except Exception as e:
            logger.error(f"事实提取失败: {e}")
            return []

    def _mock_generate(self, query: str, sources: List[Dict[str, Any]]) -> str:
        if not sources:
            return f"未找到与「{query}」相关的参考资料。"
        parts = [f"根据知识库检索结果，关于「{query}」的相关信息如下：\n"]
        for i, doc in enumerate(sources, 1):
            parts.append(f"\n**{i}. {doc.get('title', '')}** (来源: {doc.get('kb_name', '')}, "
                         f"相关度: {doc.get('score', 0):.2f})\n{doc.get('content', '')[:300]}...\n")
        parts.append("\n> 提示: 配置 DeepSeek API Key 后可获得更智能的答案生成。")
        return "".join(parts)


generator = Generator()
