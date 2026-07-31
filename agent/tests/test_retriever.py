"""Retriever 测试：分词、关键词加权、融合排序（embedder/milvus 全部 monkeypatch）"""
from types import SimpleNamespace

import pytest

import core.retriever as cr
from core.retriever import Retriever, VECTOR_WEIGHT, KEYWORD_WEIGHT


def test_query_words_filters_stopwords():
    # 验证 _query_words 去掉多字停用词、保留实词（jieba: 什么/是/机器/学习）
    r = Retriever()
    words = r._query_words("什么是机器学习")
    assert words == {"机器", "学习"}
    assert "什么" not in words


def test_query_words_filters_single_chars():
    # 验证单字虚词进不了结果（len>=2 约束）
    r = Retriever()
    words = r._query_words("如何学习编程")
    assert words == {"学习", "编程"}
    assert all(len(w) >= 2 for w in words)


def test_keyword_score_title_weight_clipped():
    # 验证 title 命中 ×2 加权后 clip 到 1.0（两个词都在 title 命中：2*2/2=2.0 → 1.0）
    r = Retriever()
    score = r._keyword_score({"苹果", "香蕉"}, title="苹果香蕉对比", content="无关内容")
    assert score == 1.0


def test_keyword_score_content_only_partial():
    # 验证仅 content 命中时按命中比例计分
    r = Retriever()
    score = r._keyword_score({"苹果", "香蕉"}, title="无关标题", content="这里有苹果没有别的")
    assert score == pytest.approx(0.5)  # 命中 1/2


def test_keyword_score_empty_query_words():
    # 验证空 query_words 返回 0（除零保护）
    r = Retriever()
    assert r._keyword_score(set(), title="t", content="c") == 0.0


def _patch_deps(monkeypatch, hits):
    """替换 embedder 与 milvus_client，返回固定检索结果"""
    fake_embedder = SimpleNamespace(
        encode_query=lambda q: SimpleNamespace(tolist=lambda: [0.1] * 512))
    fake_milvus = SimpleNamespace(
        search=lambda query_vector, top_k, kb_names: [dict(h) for h in hits])
    monkeypatch.setattr(cr, "embedder", fake_embedder)
    monkeypatch.setattr(cr, "milvus_client", fake_milvus)


def test_retrieve_fusion_formula(monkeypatch):
    # 验证融合分公式 score = 0.7*vector + 0.3*keyword（keyword_score 打桩固定）
    _patch_deps(monkeypatch, [{"score": 0.8, "title": "t", "content": "c"}])
    r = Retriever()
    monkeypatch.setattr(r, "_keyword_score", lambda qw, t, c: 0.5)
    out = r.retrieve("查询", top_k=5)
    assert len(out) == 1
    h = out[0]
    assert h["vector_score"] == 0.8
    assert h["keyword_score"] == 0.5
    assert h["score"] == pytest.approx(VECTOR_WEIGHT * 0.8 + KEYWORD_WEIGHT * 0.5)


def test_retrieve_sort_and_topk(monkeypatch):
    # 验证按融合分降序排序并按 top_k 截断
    hits = [
        {"score": 0.5, "title": "低", "content": "1"},
        {"score": 0.9, "title": "高", "content": "2"},
        {"score": 0.7, "title": "中", "content": "3"},
    ]
    _patch_deps(monkeypatch, hits)
    r = Retriever()
    monkeypatch.setattr(r, "_keyword_score", lambda qw, t, c: 0.0)  # 融合分退化为 0.7*vector
    out = r.retrieve("查询", top_k=2)
    assert [h["title"] for h in out] == ["高", "中"]
    assert len(out) == 2
