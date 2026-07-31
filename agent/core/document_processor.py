"""文档处理器 - PDF → 结构化JSON → LLM生成QA"""
import json
import logging
import re
from typing import List, Dict, Any
from openai import OpenAI
from config import settings

logger = logging.getLogger(__name__)

SECTION_PROMPT = """分析以下文档片段，提取结构化信息。输出严格的JSON格式（不要markdown代码块）。

文档标题：{title}
文档片段：
{chunk}

输出JSON格式：
{{"module": "章节或模块名（如: 第一章/作者简介/序言）", "title": "本节标题", "summary": "一句话摘要", "keywords": ["关键词1", "关键词2", "关键词3"], "content": "原文关键段落"}}

只输出JSON，不要任何其他文字："""

QA_PROMPT = """根据以下结构化文档信息，生成3-5个问答对。必须包含一个直接回答本节核心信息的问题。

重要规则：
- 如果模块是"作者简介" → 必须生成"《{title}》的作者是谁？"这个问题
- 如果模块是"内容简介/序言" → 必须生成"《{title}》这本书讲什么？"
- 如果模块是"第X章" → 生成该章核心概念/理论/人物的问答
- 问题和答案中都要包含书名《{title}》作为上下文

文档标题：{title}
模块：{module}
本节标题：{section_title}
摘要：{summary}
关键词：{keywords}
原文内容：{text}

输出JSON数组（不要markdown代码块）：
[{{"question": "《{title}》的作者是谁？", "answer": "答案"}}, ...]

只输出JSON数组，不要任何其他文字："""

SECTION_PROMPT_EN = """Analyze the following document segment and extract structured information. Output strict JSON format (no markdown code blocks).

Document Title: {title}
Segment:
{chunk}

Output JSON format:
{{"module": "Chapter/section name", "title": "Section title", "summary": "One sentence summary", "keywords": ["keyword1", "keyword2", "keyword3"], "content": "Key paragraph"}}

Output only JSON, nothing else:"""

QA_PROMPT_EN = """Based on the following structured document information, generate 3-5 Q&A pairs. Must include one question directly answering the core information of this section.

Important rules:
- If the module is "Author/About the Author" → must generate "Who is the author of '{title}'?"
- If the module is "Introduction/Preface" → must generate "What is '{title}' about?"
- If the module is a chapter → generate questions about core concepts/theories/people in that chapter
- Include the book title '{title}' in questions and answers as context

Document Title: {title}
Module: {module}
Section Title: {section_title}
Summary: {summary}
Keywords: {keywords}
Content: {text}

Output JSON array (no markdown code blocks):
[{{"question": "Who is the author of '{title}'?", "answer": "Answer"}}, ...]

Output only JSON array, nothing else:"""


def detect_lang(text: str) -> str:
    """检测文档语言：zh/en"""
    cn = sum(1 for c in text[:2000] if '\u4e00' <= c <= '\u9fff')
    return "zh" if cn > len(text[:2000]) * 0.15 else "en"


class DocumentProcessor:
    """文档处理流水线"""

    def __init__(self):
        self.client = OpenAI(api_key=settings.deepseek_api_key, base_url=settings.deepseek_base_url)
        self.model = settings.deepseek_model

    def _call_llm(self, prompt: str, max_tokens: int = 1024) -> str:
        try:
            resp = self.client.chat.completions.create(
                model=self.model,
                messages=[{"role": "user", "content": prompt}],
                temperature=0.1, max_tokens=max_tokens)
            return resp.choices[0].message.content.strip()
        except Exception as e:
            logger.error(f"LLM call failed: {e}")
            return ""

    def _split_sections(self, text: str, max_chars: int = 1200) -> List[str]:
        """语义切分：优先按 ## 标题切，无标题则按段落 + 长度切"""
        # 检测是否有 Markdown 标题
        has_headers = bool(re.search(r'^#{1,3}\s+', text, re.MULTILINE))
        if has_headers:
            raw_chunks = re.split(r'\n(?=#{1,3}\s)', text)
            chunks = []
            for c in raw_chunks:
                c = c.strip()
                if not c:
                    continue
                if len(c) > max_chars * 3:
                    sub = self._split_by_paragraphs(c, max_chars)
                    chunks.extend(sub)
                else:
                    chunks.append(c)
            return chunks if chunks else [text]
        return self._split_by_paragraphs(text, max_chars)

    def _split_by_paragraphs(self, text: str, max_chars: int) -> List[str]:
        paragraphs = re.split(r"\n\s*\n", text)
        chunks = []
        buf = ""
        for p in paragraphs:
            p = p.strip()
            if not p:
                continue
            if len(buf) + len(p) > max_chars and buf:
                chunks.append(buf)
                buf = p
            else:
                buf = buf + "\n" + p if buf else p
        if buf:
            chunks.append(buf)
        return chunks

    def process(self, text: str, title: str) -> List[Dict[str, Any]]:
        """主流程：文本 → 结构化 → QA → Milvus 行"""
        if not settings.deepseek_api_key:
            return self._fallback_process(text, title)

        # Step 1: 检测语言 + 切分
        lang = detect_lang(text)
        sections = self._split_sections(text, max_chars=2000 if lang == "en" else 1200)
        s_prompt = SECTION_PROMPT_EN if lang == "en" else SECTION_PROMPT
        q_prompt = QA_PROMPT_EN if lang == "en" else QA_PROMPT
        logger.info(f"文档[{title}]: 检测语言={lang}, {len(sections)} 个段落")

        all_qa = []
        for i, chunk in enumerate(sections):
            try:
                # Step 2: 结构化提取
                prompt = s_prompt.format(title=title, chunk=chunk[:2000])
                raw = self._call_llm(prompt, max_tokens=512)
                info = self._parse_json(raw)
                if not info:
                    continue

                # Step 3: 生成 QA 对
                qa_prompt = q_prompt.format(
                    title=title,
                    module=info.get("module", ""),
                    section_title=info.get("title", ""),
                    summary=info.get("summary", ""),
                    keywords=", ".join(info.get("keywords", [])),
                    text=info.get("content", chunk[:800])
                )
                qa_raw = self._call_llm(qa_prompt, max_tokens=1024)
                qa_pairs = self._parse_json(qa_raw)

                if isinstance(qa_pairs, list):
                    for qa in qa_pairs:
                        if isinstance(qa, dict) and qa.get("question"):
                            all_qa.append({
                                "title": qa["question"],
                                "content": qa.get("answer", ""),
                                "source_content": info.get("content", chunk[:500]),
                                "module": info.get("module", ""),
                                "keywords": ", ".join(info.get("keywords", []))
                            })
                logger.info(f"  段{i+1}: 模块={info.get('module','')} → {len(qa_pairs) if isinstance(qa_pairs,list) else 0} QA")
            except Exception as e:
                logger.warning(f"段{i+1}处理失败: {e}")
                continue

        logger.info(f"文档[{title}]共生成 {len(all_qa)} 个 QA 对")
        return all_qa

    def _parse_json(self, raw: str) -> Any:
        """鲁棒 JSON 解析"""
        if not raw:
            return None
        # 移除可能的 markdown 代码块
        raw = re.sub(r"```(?:json)?\s*", "", raw)
        raw = raw.strip()
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            # 尝试提取最外层 JSON
            m = re.search(r'\[.*\]', raw, re.DOTALL) or re.search(r'\{.*\}', raw, re.DOTALL)
            if m:
                try:
                    return json.loads(m.group())
                except json.JSONDecodeError:
                    pass
            return None

    def _fallback_process(self, text: str, title: str) -> List[Dict[str, Any]]:
        """无 API Key 时的回退：简单切片"""
        sections = self._split_sections(text, max_chars=500)
        return [{"title": f"{title} §{i+1}", "content": s,
                 "source_content": s, "module": "", "keywords": ""}
                for i, s in enumerate(sections)]


processor = DocumentProcessor()
