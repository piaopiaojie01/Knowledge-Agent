"""混合检索模块 - 向量检索 + jieba 关键词加权融合"""
import logging
from typing import List, Dict, Any
from embedding.bge_embedder import embedder
from store.milvus_client import milvus_client
from config import settings
from core.text_utils import normalize_words

logger = logging.getLogger(__name__)

# 融合权重: final = VECTOR_WEIGHT * vector_score + KEYWORD_WEIGHT * keyword_score
VECTOR_WEIGHT = 0.7
KEYWORD_WEIGHT = 0.3
# title 命中相对 content 命中的权重倍数
TITLE_HIT_WEIGHT = 2


class Retriever:
    """混合检索器：向量召回 + 关键词得分融合排序"""

    def __init__(self):
        self.top_k = settings.retrieval_top_k

    def _query_words(self, query: str) -> set:
        """query 中英双语分词（英文小写化、双语言停用词过滤）后的词集合"""
        return set(normalize_words(query, min_len=2))

    def _keyword_score(self, query_words: set, title: str, content: str) -> float:
        """关键词加权重合度：content 命中计 1，title 命中计 TITLE_HIT_WEIGHT，归一化到 0-1
        中英双语：英文统一小写并去停用词，AI/ai、Company's/company 可命中"""
        if not query_words:
            return 0.0
        content_words = set(normalize_words(content or "", min_len=2))
        title_words = set(normalize_words(title or "", min_len=2))
        hit = 0
        for w in query_words:
            if w in title_words:
                hit += TITLE_HIT_WEIGHT
            elif w in content_words:
                hit += 1
        # title 命中加权后最高可达 TITLE_HIT_WEIGHT，clip 到 0-1
        return min(1.0, hit / len(query_words))

    def retrieve(
        self,
        query: str,
        kb_names: List[str] | None = None,
        top_k: int | None = None,
    ) -> List[Dict[str, Any]]:
        if top_k is None:
            top_k = self.top_k

        query_vector = embedder.encode_query(query)
        query_vector_list = query_vector.tolist()

        fetch_k = top_k * 4
        hits = milvus_client.search(
            query_vector=query_vector_list,
            top_k=fetch_k,
            kb_names=kb_names,
        )

        query_words = self._query_words(query)
        for h in hits:
            vector_score = h.get("score", 0)
            kw = self._keyword_score(query_words, h.get("title", ""), h.get("content", ""))
            h["vector_score"] = vector_score
            h["keyword_score"] = kw
            h["score"] = VECTOR_WEIGHT * vector_score + KEYWORD_WEIGHT * kw

        hits.sort(key=lambda h: h.get("score", 0), reverse=True)
        result = hits[:top_k]

        logger.info(f"混合检索: '{query[:50]}', 向量{fetch_k}→融合排序取{len(result)}")
        return result


retriever = Retriever()
