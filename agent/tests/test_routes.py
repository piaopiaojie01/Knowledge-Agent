"""api/routes.py 测试：检索合并去重与 sources 阈值门控"""
import api.routes as ar
from api.routes import _retrieve_merged, _build_sources


def _patch_retrieval(monkeypatch, docs_by_query, variants=None):
    """打桩查询扩展与检索：按 query 返回固定文档"""
    monkeypatch.setattr(ar.query_processor, "expand_query",
                        lambda q: variants or list(docs_by_query))
    monkeypatch.setattr(ar.retriever, "retrieve",
                        lambda query, kb_names=None: [dict(d) for d in docs_by_query[query]])


def test_merged_same_prefix_diff_docs_not_merged(monkeypatch):
    # 验证标题/内容前缀相同但 doc_id 不同的文档不会被误合并
    _patch_retrieval(monkeypatch, {
        "变体1": [{"doc_id": 1, "title": "年度报告", "content": "相同前缀" * 50, "score": 0.9}],
        "变体2": [{"doc_id": 2, "title": "年度报告", "content": "相同前缀" * 50, "score": 0.8}],
    })
    out = _retrieve_merged("问题", None)
    assert len(out) == 2


def test_merged_duplicate_keeps_highest_score(monkeypatch):
    # 验证同一文档多次召回时按去重键合并并保留最高 score
    same_doc = {"doc_id": 1, "title": "报告", "content": "内容", "score": 0.7}
    _patch_retrieval(monkeypatch, {
        "变体1": [same_doc],
        "变体2": [{**same_doc, "score": 0.95}],
    })
    out = _retrieve_merged("问题", None)
    assert len(out) == 1
    assert out[0]["score"] == 0.95


def test_build_sources_above_threshold():
    # 验证 vector_score 最高分过阈值时返回 sources（字段齐全）
    docs = [{"vector_score": 0.7, "score": 0.66, "title": "t", "content": "c", "kb_name": "kb"}]
    out = _build_sources(docs)
    assert len(out) == 1
    assert out[0]["title"] == "t" and out[0]["kb_name"] == "kb"


def test_build_sources_below_threshold_empty():
    # 验证 vector_score 低于 source_threshold 时返回空（即使融合 score 高）
    docs = [{"vector_score": 0.5, "score": 0.99, "title": "t", "content": "c"}]
    assert _build_sources(docs) == []


def test_build_sources_empty_input():
    # 验证空文档列表返回空
    assert _build_sources([]) == []
