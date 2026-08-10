"""中英双语查询预处理与关键词融合测试"""
from core.query_processor import query_processor
from core.retriever import Retriever


def test_segment_english():
    words = query_processor.segment("What is the AI architecture of this system?")
    assert "ai" in words and "architecture" in words and "system" in words
    assert not any(w in {"what", "is", "the", "of", "this"} for w in words)


def test_segment_chinese():
    words = query_processor.segment("知识库的分块参数是什么")
    assert "知识库" in words and "分块" in words and "参数" in words
    assert "的" not in words and "是什么" not in words


def test_expand_query_chinese_no_space():
    variants = query_processor.expand_query("知识库的分块参数是多少")
    kw = [v for v in variants if len(v) > 5][-1]
    assert " " not in kw


def test_expand_query_english_keeps_space():
    variants = query_processor.expand_query("What is the chunk size in the knowledge base?")
    assert any("chunk size knowledge base" in v for v in variants)


def test_retriever_keyword_score_english_case_insensitive():
    r = Retriever()
    words = r._query_words("AI and machine learning")
    assert "ai" in words and "and" not in words
    score = r._keyword_score(words, "AI Overview", "machine learning based retrieval")
    assert score > 0


def test_retriever_keyword_score_english_stopwords_only_zero():
    r = Retriever()
    words = r._query_words("What is the and of")
    assert words == set()
    assert r._keyword_score(words, "anything", "content") == 0.0
