"""原文分块（中英双语）与入库行测试"""
import pytest

from config import settings
from core.document_processor import DocumentProcessor, chunk_text
from core.text_utils import detect_lang, estimate_tokens, normalize_words


# ── 语言检测 / token 估算 ──


def test_detect_lang_zh_and_en():
    assert detect_lang("这是一个中文测试文档，介绍系统架构。") == "zh"
    assert detect_lang("This is an English document about system architecture.") == "en"


def test_detect_lang_mixed_prefers_dominant():
    assert detect_lang("RAG系统架构设计文档说明") == "zh"
    assert detect_lang("The system uses RAG for retrieval 中文术语") == "en"


def test_estimate_tokens_zh_vs_en():
    # 中文约 1.5 token/字，英文约 0.25 token/字符
    assert estimate_tokens("你好世界", "zh") >= 5
    assert estimate_tokens("hello world", "en") == 2


# ── 中英双语分词 ──


def test_normalize_words_english_filters_stopwords_and_case():
    words = normalize_words("What is the AI and machine learning?", min_len=2)
    assert "ai" in words and "machine" in words and "learning" in words
    assert "the" not in words and "and" not in words and "what" not in words


def test_normalize_words_english_strips_possessive():
    words = normalize_words("The company's AI model", min_len=2)
    assert "company" in words and "ai" in words


def test_normalize_words_chinese_keeps_single_char_real_words():
    words = normalize_words("他画了一张图", min_len=1)
    assert "图" in words and "画" in words
    assert "的" not in words


# ── 分块 ──


def test_chunk_english_no_content_loss():
    text = "\n\n".join(f"Sentence number {i} about enterprise knowledge management." for i in range(60))
    chunks = chunk_text(text)
    assert len(chunks) >= 2
    joined = "\n".join(chunks)
    for i in range(60):
        assert f"Sentence number {i}" in joined
    for c in chunks:
        assert len(c) <= 3800


def test_chunk_chinese_keeps_heading_context():
    text = "## 第一章 系统架构\n\n本系统采用双引擎架构，包含网关与智能引擎两个部分。\n\n## 第二章 部署\n\n支持 Docker 一键部署。"
    chunks = chunk_text(text)
    assert any("## 第一章 系统架构" in c for c in chunks)
    assert any("## 第二章 部署" in c for c in chunks)


def test_chunk_long_paragraph_split_no_loss():
    text = "。".join(f"第{i}句企业知识库分块测试内容" for i in range(80)) + "。"
    chunks = chunk_text(text)
    joined = "\n".join(chunks)
    for i in range(0, 80, 7):
        assert f"第{i}句" in joined
    assert len(chunks) >= 2


def test_chunk_tokens_within_limit():
    text = ("这是一段用于验证分块大小的中文测试文本。" * 40)
    chunks = chunk_text(text)
    for c in chunks:
        assert estimate_tokens(c) <= settings.chunk_tokens + 80  # 标题前缀与重叠留余量


# ── 入库行 ──


def test_process_default_returns_original_chunks(monkeypatch):
    monkeypatch.setattr(settings, "ingest_qa_enabled", False)
    rows = DocumentProcessor().process("## 引言\n\n这是原文内容，不做 LLM 改写。", "测试文档")
    assert rows
    assert rows[0]["content"].startswith("## 引言")
    assert "这是原文内容，不做 LLM 改写。" in rows[0]["content"]
    assert rows[0]["source_content"] == rows[0]["content"]
    assert rows[0]["lang"] == "zh"


def test_process_qa_mode_requires_enabled(monkeypatch):
    monkeypatch.setattr(settings, "ingest_qa_enabled", True)
    monkeypatch.setattr(settings, "deepseek_api_key", "test-key")
    p = DocumentProcessor()
    monkeypatch.setattr(settings, "deepseek_api_key", "")
    # 无 API Key 时即使开启 QA 也回退原文分块
    rows = p.process("英文内容 English content here.", "T")
    assert rows and rows[0]["lang"] == "en"
