"""原文分块（中英双语）与入库行测试"""
import pytest

from config import settings
from core.document_processor import (
    DocumentProcessor, chunk_text, semantic_chunk_text,
    split_sentences, _group_sentences_by_similarity, _join_unit_lines, _is_toc)
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


# ── 语义分块 ──


def test_split_sentences_zh():
    s = split_sentences("第一句。第二句！第三句？\n换行句。")
    assert len(s) == 4
    assert any(x.startswith("第一句") for x in s)
    assert any(x.startswith("换行句") for x in s)


def test_join_unit_lines_merges_short_prose_keeps_tables():
    lines = [
        "作者简介",
        "周一南，袓籍安徽，毕业于福建仰恩大学，现居北京。",
        "| 产品 | 销量 |",
        "| 苹果 | 100 |",
        "一位热爱文学、潜心研究中外经典作品的学者。",
    ]
    out = _join_unit_lines(lines)
    assert "作者简介周一南" in out
    assert "| 产品 | 销量 |" in out
    assert "\n| 苹果 | 100 |" in out


def test_is_toc_detects():
    assert _is_toc("目  录序言第一章 欲望长啥样第二章 透视欲望第三章 读懂人心")
    assert not _is_toc("这是普通正文，没有目录结构。")


def test_chunk_text_keeps_toc_whole():
    toc = ("目  录\n序言\n第一章 欲望长啥样：练就火眼金睛，让隐藏的欲望无所遁形\n"
           "相由心生：相貌不等于心态，但能反映心态\n"
           "第二章 透视欲望：做交际场上的太阳，吸引别人围着自己转\n"
           "感觉剥夺实验：交际能力与个人成就有着密不可分的关系\n"
           "第三章 洞察他人：读懂行为背后的欲望密码\n"
           "第四章 掌控欲望：让欲望成为你的助力")
    chunks = chunk_text(toc, chunk_tokens=10, overlap_tokens=2)
    joined = "\n".join(chunks)
    assert "第三章" in joined and "第四章" in joined
    assert len(chunks) == 1


def test_split_units_merges_toc_continuations():
    from core.document_processor import _split_units
    text = (
        "目  录\n序言\n第一章 欲望长啥样\n相由心生：相貌不等于心态，但能反映心态\n"
        "\n"
        "刺猬法则：因为关系好，你就能侵占我的空间吗\n"
        "暗示效应：用心理暗示诱导人心\n"
        "尊重对方：渴望金钱，更渴望获得尊重\n"
        "多看效应：频繁露面，只为留下更深的印象\n"
        "心理测试\n"
        "第三章 掌控欲望\n情绪定律：不懂什么叫理性的人，才会说自己理性\n"
        "野马结局：被人激怒，尽力克制发泄怒火的欲望\n"
        "\n"
        "第一章\n欲望长啥样\n说到欲望，很多人的第一反应都是十分深奥难以琢磨。")
    units = _split_units(text)
    joined = "\n".join(c for _, c in units)
    assert "第三章" in joined and "刺猬法则" in joined
    assert "说到欲望" in joined


def test_extract_toc_detects_range():
    from core.document_processor import _extract_toc
    text = ("前言……\n目  录\n序言\n第一章 标题：内容\n条目一：xxx\n第二章 标题：yyy\n心理测试\n"
            "第一章\n正文标题\n这是正文的第一句话，这一段是较长的正文内容，"
            "讲的是欲望心理学的基本概念和主要观点。\n继续补充正文内容，确保累计长度超过六十个字符。")
    toc, rest = _extract_toc(text)
    assert toc and "第一章" in toc and "第二章" in toc and "心理测试" in toc
    assert "这是正文" in rest


def test_group_sentences_breaks_on_topic_change():
    emb = [[1, 0, 0], [0.9, 0.1, 0], [0.95, -0.1, 0], [0, 1, 0], [0, 0.9, 0.1]]
    sentences = [f"s{i}" for i in range(5)]
    groups = _group_sentences_by_similarity(sentences, emb, 0.5, 1000)
    assert groups == [[0, 1, 2], [3, 4]]


def test_semantic_chunk_text_splits_topic_boundary(monkeypatch):
    text = ("苹果种植需要充足光照与水分。果树修剪能提高产量。"
            "冷链物流要求全程温控。仓储管理涉及库存周转。")

    class FakeEmbedder:
        def encode_documents(self, sentences):
            return [[1, 0, 0], [0.92, 0.1, 0], [0, 1, 0], [0.05, 0.95, 0]]

    monkeypatch.setattr("embedding.bge_embedder.embedder", FakeEmbedder())
    chunks = semantic_chunk_text(text, chunk_tokens=60, overlap_tokens=20, threshold=0.5)
    assert len(chunks) == 2
    assert "苹果种植" in chunks[0] and "冷链物流" in chunks[1]


def test_semantic_chunk_text_fallback_without_embedder(monkeypatch):
    monkeypatch.setattr("embedding.bge_embedder.embedder", None)
    chunks = semantic_chunk_text("第一句。第二句。" * 40, chunk_tokens=200,
                                 overlap_tokens=20, threshold=0.5)
    assert chunks


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
