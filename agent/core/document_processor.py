"""文档处理器 - PDF → 结构化JSON → LLM生成QA"""
import json
import logging
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List, Dict, Any
from openai import OpenAI
from config import settings
from core.text_utils import detect_lang, estimate_tokens

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


def _heading_prefix(headings) -> str:
    """把父标题链拼成 Markdown 前缀（保证分块自带章节上下文）"""
    if not headings:
        return ""
    return "\n".join(f"{'#' * lv} {t}" for lv, t in headings) + "\n"


def _split_long_unit(content: str, lang: str, chunk_tokens: int, overlap_tokens: int) -> List[str]:
    """超长单元：按句子组装 + 相邻块重叠；单句仍超限按字符窗口硬切，不截断丢内容"""
    sentences = [s.strip() for s in re.split(r'(?<=[。！？!?；;])\s*|\n+', content) if s.strip()]
    if not sentences:
        sentences = [content]
    sep = " " if lang == "en" else "\n"
    result = []
    buf, buf_t = [], 0
    for s in sentences:
        t = estimate_tokens(s, lang)
        if buf and buf_t + t > chunk_tokens:
            result.append(sep.join(buf))
            keep, kt = [], 0
            for bs in reversed(buf):
                bt = estimate_tokens(bs, lang)
                if kt + bt > overlap_tokens:
                    break
                keep.insert(0, bs)
                kt += bt
            buf, buf_t = keep, kt
        buf.append(s)
        buf_t += t
    if buf:
        result.append(sep.join(buf))

    # 单块仍超限（超长单句等）→ 按字符窗口 + 重叠硬切
    rate = 1.5 if lang == "zh" else 0.25  # token/字符
    window = max(int(chunk_tokens / rate), 1)
    overlap_chars = min(int(overlap_tokens / rate), window - 1)
    final = []
    for r in result:
        if estimate_tokens(r, lang) <= chunk_tokens:
            final.append(r)
            continue
        i = 0
        while i < len(r):
            final.append(r[i:i + window])
            if i + window >= len(r):
                break
            i += window - overlap_chars
    return final


def chunk_text(text: str, chunk_tokens: int | None = None,
               overlap_tokens: int | None = None) -> List[str]:
    """原文分块（中英自适应）：
    - 按 token 估算对齐 embedding 模型上限（默认 450 token）
    - 保留父标题链上下文；超长段落按句/窗口二次拆分并重叠，不截断丢内容
    """
    chunk_tokens = chunk_tokens or settings.chunk_tokens
    overlap_tokens = overlap_tokens or settings.chunk_overlap_tokens

    units = []
    headings = []  # [(level, text)]
    buf = []

    def flush():
        if buf:
            units.append((list(headings), "\n".join(buf).strip()))
            buf.clear()

    for line in text.splitlines():
        s = line.strip()
        if not s:
            flush()
            continue
        m = re.match(r'^(#{1,6})\s+(.*)$', s)
        if m:
            flush()
            level = len(m.group(1))
            headings = [h for h in headings if h[0] < level]
            headings.append((level, m.group(2).strip()))
            continue
        buf.append(line)
    flush()
    if not units:
        units = [([], text.strip())]

    chunks = []
    cur_headings = []
    cur_parts = []
    cur_tokens = 0

    def finish():
        nonlocal cur_parts, cur_tokens
        if not cur_parts:
            return
        chunk = (_heading_prefix(cur_headings) + "\n\n".join(cur_parts)).strip()
        if chunk:
            chunks.append(chunk)
        cur_parts = []
        cur_tokens = 0

    for headings, content in units:
        if not content:
            continue
        lang = detect_lang(content)
        unit_tokens = estimate_tokens(content, lang)
        prefix_tokens = estimate_tokens(_heading_prefix(headings), lang)
        if headings != cur_headings:
            finish()
            cur_headings = headings
        if unit_tokens > chunk_tokens:
            finish()
            cur_headings = headings
            for sub in _split_long_unit(content, lang, chunk_tokens, overlap_tokens):
                chunk = (_heading_prefix(headings) + sub).strip()
                if chunk:
                    chunks.append(chunk)
            continue
        if cur_parts and cur_tokens + prefix_tokens + unit_tokens > chunk_tokens:
            finish()
            cur_headings = headings
        cur_parts.append(content)
        cur_tokens += unit_tokens
    finish()

    # Milvus content 字段上限 4096 的保护性硬切（保留全部内容）
    max_chars = 3800
    out = []
    for c in chunks:
        if len(c) <= max_chars:
            out.append(c)
            continue
        lang = detect_lang(c)
        rate = 1.5 if lang == "zh" else 0.25
        window = int(chunk_tokens / rate)
        for i in range(0, len(c), window):
            out.append(c[i:i + window])
    return out


class DocumentProcessor:
    """文档处理流水线"""

    def __init__(self):
        self.client = OpenAI(api_key=settings.deepseek_api_key, base_url=settings.deepseek_base_url)
        self.model = settings.deepseek_model

    def _call_llm(self, prompt: str, max_tokens: int = 1024) -> str:
        """调 LLM：90s 超时防止限流时长时间挂死，失败指数退避重试，3 次失败放弃该段"""
        for attempt in range(3):
            try:
                resp = self.client.chat.completions.create(
                    model=self.model,
                    messages=[{"role": "user", "content": prompt}],
                    temperature=0.1, max_tokens=max_tokens, timeout=90)
                return resp.choices[0].message.content.strip()
            except Exception as e:
                if attempt == 2:
                    logger.error(f"LLM call failed after 3 attempts: {e}")
                    return ""
                wait = 2 * (attempt + 1)
                logger.warning(f"LLM call failed (attempt {attempt + 1}/3), retry in {wait}s: {e}")
                time.sleep(wait)

    def process(self, text: str, title: str, progress_cb=None) -> List[Dict[str, Any]]:
        """主流程：原文分块（中英自适应）→ 入库行；
        KA_INGEST_QA_ENABLED=true 时额外生成问答对（默认关闭）。"""
        chunks = chunk_text(text)
        total = len(chunks)
        if not settings.ingest_qa_enabled or not settings.deepseek_api_key:
            rows = []
            for i, c in enumerate(chunks):
                rows.append(self._to_row(c, title))
                if progress_cb:
                    try:
                        progress_cb(i + 1, total)
                    except Exception:
                        pass
            return rows

        # QA 增强模式：每个分块生成问答对（保留原标题上下文）
        lang = detect_lang(text)
        s_prompt = SECTION_PROMPT_EN if lang == "en" else SECTION_PROMPT
        q_prompt = QA_PROMPT_EN if lang == "en" else QA_PROMPT
        logger.info(f"文档[{title}]: 检测语言={lang}, {len(chunks)} 个分块")

        # 分块并发：OpenAI SDK 客户端线程安全
        workers = max(1, settings.ingest_llm_concurrency)
        all_qa = []
        done_count = 0
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {pool.submit(self._process_section, title, s_prompt, q_prompt, chunk, i)
                       for i, chunk in enumerate(chunks)}
            for fut in as_completed(futures):
                all_qa.extend(fut.result())
                done_count += 1
                if progress_cb:
                    try:
                        progress_cb(done_count, len(chunks))
                    except Exception:
                        pass  # 进度回报失败不影响入库主流程

        logger.info(f"文档[{title}]共生成 {len(all_qa)} 个 QA 对")
        return all_qa

    def _to_row(self, chunk: str, title: str) -> Dict[str, Any]:
        """原文分块 → 入库行（标题取块内首个标题行，无则用文档标题）"""
        first_heading = next((l for l in chunk.splitlines() if l.startswith('#')), '')
        t = re.sub(r'^#+\s*', '', first_heading).strip()
        return {
            "title": f"{title} · {t}" if t and t != title else title,
            "content": chunk,
            "source_content": chunk,
            "module": first_heading,
            "keywords": "",
            "lang": detect_lang(chunk),
        }

    def _process_section(self, title: str, s_prompt: str, q_prompt: str,
                         chunk: str, index: int) -> List[Dict[str, Any]]:
        """单段处理：结构化提取 → QA 生成（无共享状态，供线程池并发调用）"""
        try:
            # Step 2: 结构化提取
            prompt = s_prompt.format(title=title, chunk=chunk[:3000])
            raw = self._call_llm(prompt, max_tokens=512)
            info = self._parse_json(raw)
            # LLM 偶尔返回 JSON 数组而非对象，取首个 dict 兼容，避免整段被丢弃
            if isinstance(info, list):
                info = next((x for x in info if isinstance(x, dict)), None)
            if not isinstance(info, dict) or not info:
                return []

            # Step 3: 生成 QA 对
            qa_prompt = q_prompt.format(
                title=title,
                module=info.get("module", ""),
                section_title=info.get("title", ""),
                summary=info.get("summary", ""),
                keywords=", ".join(info.get("keywords", [])),
                text=info.get("content", chunk[:1200])
            )
            qa_raw = self._call_llm(qa_prompt, max_tokens=1024)
            qa_pairs = self._parse_json(qa_raw)

            result = []
            if isinstance(qa_pairs, list):
                for qa in qa_pairs:
                    if isinstance(qa, dict) and qa.get("question"):
                        result.append({
                            "title": qa["question"],
                            "content": qa.get("answer", ""),
                            "source_content": info.get("content", chunk[:1200]),
                            "module": info.get("module", ""),
                            "keywords": ", ".join(info.get("keywords", []))
                        })
            logger.info(f"  段{index+1}: 模块={info.get('module','')} → {len(qa_pairs) if isinstance(qa_pairs,list) else 0} QA")
            return result
        except Exception as e:
            logger.warning(f"段{index+1}处理失败: {e}")
            return []

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

processor = DocumentProcessor()
