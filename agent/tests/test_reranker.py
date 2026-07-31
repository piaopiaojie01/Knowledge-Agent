"""Reranker 测试：阈值判定、降级路径、CE 主路径、加载失败冷却"""
import time
from types import SimpleNamespace

import pytest

import core.reranker as rr
from core.reranker import Reranker, LOAD_RETRY_COOLDOWN


def _doc(vector_score=None, score=0.0, content="内容"):
    """构造测试文档：vector_score 为 None 时省略该字段"""
    d = {"score": score, "content": content, "title": "标题"}
    if vector_score is not None:
        d["vector_score"] = vector_score
    return d


def _make_reranker():
    # 新建实例并置于"加载失败冷却中"，保证 model 属性返回 None（不触发真实加载）
    r = Reranker()
    r._load_failed_at = time.time()
    return r


def test_passes_threshold_vector_score_first():
    # 验证 _passes_threshold 优先用 vector_score 而非融合 score
    r = Reranker()
    assert r._passes_threshold(_doc(vector_score=0.40, score=0.99)) is True   # score 高但 vector_score 达标
    assert r._passes_threshold(_doc(vector_score=0.20, score=0.99)) is False  # score 高但 vector_score 不达标
    assert r._passes_threshold(_doc(vector_score=None, score=0.50)) is True   # 无 vector_score 时回退 score


def test_fallback_filter_sort_truncate():
    # 验证降级路径：vector_score 过滤 + 按融合 score 降序 + top_k 截断
    r = _make_reranker()
    docs = [
        _doc(vector_score=0.9, score=0.5, content="a"),   # 达标
        _doc(vector_score=0.1, score=0.99, content="b"),  # 融合分最高但被阈值过滤
        _doc(vector_score=0.8, score=0.8, content="c"),   # 达标，应排第一
        _doc(vector_score=0.7, score=0.6, content="d"),   # 达标，被 top_k 截掉
    ]
    out = r._fallback_rerank(docs, top_k=2)
    assert [d["content"] for d in out] == ["c", "d"]  # 按融合分降序取前 2
    assert len(out) == 2
    assert all(d["vector_score"] >= r.min_score for d in out)


def test_fallback_all_below_threshold_returns_best():
    # 验证全部低于阈值时兜底返回最高分 1 条
    r = _make_reranker()
    docs = [
        _doc(vector_score=0.1, score=0.3, content="a"),
        _doc(vector_score=0.2, score=0.5, content="b"),
    ]
    out = r._fallback_rerank(docs, top_k=3)
    assert len(out) == 1
    assert out[0]["content"] == "b"


def test_rerank_empty_documents():
    # 验证空文档列表直接返回空
    r = _make_reranker()
    assert r.rerank("查询", []) == []


class _FakeModel:
    """CrossEncoder 替身：按预设分数返回"""

    def __init__(self, scores):
        self._scores = scores

    def predict(self, pairs):
        assert len(pairs) == len(self._scores)
        return self._scores


def test_ce_path_filter_then_sort_by_rerank_score(monkeypatch):
    # 验证 CE 主路径：先按 vector_score 过滤，再按 rerank_score 排序
    r = Reranker()
    # candidates 过滤后剩 a/c（b 被阈值挡掉），CE 打分 a=0.1, c=0.9 → c 在前
    monkeypatch.setattr(Reranker, "model", property(lambda self: _FakeModel([0.1, 0.9])))
    docs = [
        _doc(vector_score=0.9, score=0.9, content="a"),
        _doc(vector_score=0.1, score=0.99, content="b"),  # 低于阈值，CE 轮不到它
        _doc(vector_score=0.8, score=0.5, content="c"),
    ]
    out = r.rerank("查询", docs, top_k=2)
    assert [d["content"] for d in out] == ["c", "a"]
    assert out[0]["rerank_score"] == pytest.approx(0.9)


def test_ce_path_all_below_threshold_fallback_best(monkeypatch):
    # 验证 CE 路径下全部低于阈值时同样兜底最高分 1 条（不调模型）
    r = Reranker()

    def _boom(self):
        raise AssertionError("不应加载模型")

    monkeypatch.setattr(Reranker, "model", property(_boom))
    docs = [_doc(vector_score=0.1, score=0.2, content="a"),
            _doc(vector_score=0.2, score=0.6, content="b")]
    # model 不应被访问；直接走 _best_fallback
    out = r._best_fallback(docs)
    assert len(out) == 1 and out[0]["content"] == "b"


def test_load_failure_cooldown(monkeypatch):
    # 验证模型加载失败后冷却期内不重试，冷却结束后允许重试
    clock = [1000.0]
    monkeypatch.setattr(rr, "time", SimpleNamespace(time=lambda: clock[0]))

    import sentence_transformers
    calls = []

    class _Boom:
        def __init__(self, *a, **k):
            calls.append(1)
            raise RuntimeError("模拟加载失败")

    monkeypatch.setattr(sentence_transformers, "CrossEncoder", _Boom)

    r = Reranker()
    assert r.model is None          # 首次尝试加载，失败
    assert len(calls) == 1

    clock[0] += LOAD_RETRY_COOLDOWN - 1   # 冷却期内
    assert r.model is None
    assert len(calls) == 1                # 未重试

    clock[0] += 2                          # 冷却结束
    assert r.model is None
    assert len(calls) == 2                # 允许重试（仍失败）
