"""重排序模块 - CrossEncoder (bge-reranker-v2-m3) 精排，失败时降级为分数排序"""
import logging
import re
import threading
import time
from typing import List, Dict, Any
from config import settings

logger = logging.getLogger(__name__)

RERANK_MODEL_NAME = "BAAI/bge-reranker-v2-m3"
# 模型加载失败后的重试冷却时间（秒）
LOAD_RETRY_COOLDOWN = 300
# 元信息类提问（作者/版权/ISBN/简介/目录等）：给元信息块额外加分。
# bge-reranker 对“作者简介周一南，袓籍安徽……”这类描述式段落打分偏低，
# 但这类段落恰是作者/版权类问题的正确答案，需要叠加 meta 分修正排序。
META_QUERY_RE = re.compile(
    r"作者|谁写|谁著|著者|版权|isbn|出版社|出版信息|简介|序言|前言|目录|定价|多少钱|价格|页数|多少页",
    re.IGNORECASE)


class Reranker:

    def __init__(self):
        self.top_k = settings.rerank_top_k
        self.min_score = settings.min_score
        self._model = None
        self._load_failed_at = 0.0
        self._lock = threading.Lock()

    @property
    def model(self):
        """懒加载 CrossEncoder（CPU），线程安全；
        加载失败返回 None 走降级逻辑，冷却 LOAD_RETRY_COOLDOWN 秒后允许重试"""
        if self._model is None and time.time() - self._load_failed_at > LOAD_RETRY_COOLDOWN:
            with self._lock:
                if self._model is None and time.time() - self._load_failed_at > LOAD_RETRY_COOLDOWN:
                    try:
                        from sentence_transformers import CrossEncoder
                        self._model = CrossEncoder(RERANK_MODEL_NAME, device=settings.reranker_device)
                        logger.info(f"重排序模型加载完成: {RERANK_MODEL_NAME} ({settings.reranker_device})")
                    except Exception as e:
                        self._load_failed_at = time.time()
                        logger.warning(f"重排序模型加载失败，降级为分数排序: {e}")
        return self._model

    def _passes_threshold(self, d: Dict[str, Any]) -> bool:
        """min_score 阈值判定基于未稀释的向量余弦分（融合分仅用于排序展示）"""
        return d.get("vector_score", d.get("score", 0)) >= self.min_score

    def _meta_bonus(self, query: str, d: Dict[str, Any]) -> float:
        """元信息提问时，对作者简介/版权页等元信息块加权，避免被重排器压掉"""
        if not META_QUERY_RE.search(query):
            return 0.0
        meta = d.get("meta_score", 0)
        return 0.30 if meta >= 1.0 else (0.15 if meta >= 0.5 else 0.0)

    def _best_fallback(self, documents: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """全部低于阈值时兜底返回最高分 1 条"""
        best = max(documents, key=lambda x: x.get("score", 0))
        logger.warning(f"全低于阈值 {self.min_score}, 兜底最高分 {best.get('score',0):.3f}")
        return [best]

    def _fallback_rerank(self, documents: List[Dict[str, Any]],
                         top_k: int) -> List[Dict[str, Any]]:
        """降级逻辑: vector_score 阈值过滤 + 按融合 score 排序 + 截断 + 兜底最高分"""
        filtered = [d for d in documents if self._passes_threshold(d)]
        sorted_docs = sorted(filtered, key=lambda x: x.get("score", 0), reverse=True)
        reranked = sorted_docs[:top_k]

        if not reranked:
            return self._best_fallback(documents)
        return reranked

    def rerank(self, query: str, documents: List[Dict[str, Any]],
               top_k: int | None = None) -> List[Dict[str, Any]]:
        if top_k is None:
            top_k = self.top_k
        if not documents:
            return []

        model = self.model
        if model is None:
            reranked = self._fallback_rerank(documents, top_k)
            logger.info(f"重排序(降级): {len(documents)} → {len(reranked)} 条")
            return reranked

        # CE 主路径同样先按 vector_score 阈值过滤，rerank_score 只作排序键
        candidates = [d for d in documents if self._passes_threshold(d)]
        if not candidates:
            return self._best_fallback(documents)

        try:
            pairs = [[query, d.get("content", "")] for d in candidates]
            scores = model.predict(pairs)
            for d, s in zip(candidates, scores):
                d["rerank_score"] = float(s)
            reranked = sorted(candidates,
                              key=lambda x: x.get("rerank_score", float("-inf"))
                                           + self._meta_bonus(query, x),
                              reverse=True)[:top_k]
            logger.info(f"重排序(cross-encoder): {len(documents)} → {len(reranked)} 条")
            return reranked
        except Exception as e:
            logger.warning(f"CrossEncoder 打分异常，降级为分数排序: {e}")
            reranked = self._fallback_rerank(documents, top_k)
            logger.info(f"重排序(降级): {len(documents)} → {len(reranked)} 条")
            return reranked


reranker = Reranker()
