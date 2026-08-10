"""混合检索模块 - 向量检索 + jieba 关键词加权融合"""
import logging
from typing import List, Dict, Any
import numpy as np
from embedding.bge_embedder import embedder
from store.milvus_client import milvus_client
from config import settings
from core.text_utils import normalize_words

logger = logging.getLogger(__name__)

# 融合权重: final = VECTOR_WEIGHT * vector_score + KEYWORD_WEIGHT * keyword_score
VECTOR_WEIGHT = 0.7
KEYWORD_WEIGHT = 0.3
# 元信息页（作者简介/版权/封面/序言等）额外权重：这类块常承载“作者/版本/出版信息”类答案
META_WEIGHT = 0.15
_META_STRONG = ("作者简介", "关于作者", "版权页", "版权", "isbn", "封面", "内容简介")
_META_WEAK = ("简介", "目录", "序言", "前言", "author", "copyright")
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
        # 短碎片（页眉/断行残留）不给关键词分，避免虚高抢占候选位
        if len((content or "").strip()) < 15:
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

    def _meta_score(self, title: str, content: str) -> float:
        """元信息页权重：命中强标记（作者简介/版权/ISBN/封面）计 1，弱标记（简介/序言/目录）计 0.5"""
        t = ((title or "") + " " + (content or "")).lower()
        strong = any(m in t for m in _META_STRONG)
        weak = any(m in t for m in _META_WEAK)
        return 1.0 if strong else (0.5 if weak else 0.0)

    def _keyword_recall(self, query_words: set, query_vector, kb_names,
                        scan_limit: int = 5000) -> List[Dict[str, Any]]:
        """精确关键词召回：向量检索对专名（人名/产品名）召回不足时，
        对选中知识库的分块做内存子串扫描（Milvus 2.3 不支持 %term% 模糊），
        命中块按查询向量与块向量余弦算分。仅弱向量结果时调用，失败静默跳过。"""
        if not query_words or not kb_names:
            return []
        col = getattr(milvus_client, "get_collection", None)
        if col is None:
            return []
        try:
            collection = col()
            collection.load()
        except Exception as e:
            logger.warning("关键词召回：集合不可用，跳过 %s", e)
            return []
        names = [n.replace("\\", "\\\\").replace('"', '\\"') for n in kb_names]
        kb_expr = f'kb_name == "{names[0]}"' if len(names) == 1 \
            else "kb_name in " + str(tuple(names))
        qv = np.asarray(query_vector, dtype=float)
        out = []
        seen = set()
        try:
            rows = collection.query(
                expr=kb_expr,
                output_fields=["id", "doc_id", "kb_name", "title", "content", "embedding"],
                limit=scan_limit)
        except Exception as e:
            logger.warning("关键词召回：分块扫描失败 %s", e)
            return []
        for w in query_words:
            for r in rows:
                if w not in (r.get("content") or ""):
                    continue
                key = r.get("id")
                if key in seen:
                    continue
                seen.add(key)
                emb = np.asarray(r.get("embedding"), dtype=float)
                denom = (np.linalg.norm(qv) * np.linalg.norm(emb)) or 1e-9
                vs = float(np.dot(qv, emb) / denom)
                out.append({**r, "vector_score": vs, "keyword_score": 0.0, "score": vs})
        return out

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
        # 精确关键词召回补充：选知识库时始终扫描（保证专名/精确词不漏召回）
        if settings.keyword_recall and kb_names:
            try:
                keyword_hits = self._keyword_recall(query_words, query_vector_list, kb_names)
                merged = {}
                for h in list(hits) + list(keyword_hits):
                    key = h.get("id") or (h.get("doc_id"), h.get("content", ""))
                    merged[key] = h
                hits = list(merged.values())
            except Exception as e:
                logger.warning("关键词召回合并失败，仅用向量结果: %s", e)

        for h in hits:
            vector_score = h.get("score", 0)
            kw = self._keyword_score(query_words, h.get("title", ""), h.get("content", ""))
            meta = self._meta_score(h.get("title", ""), h.get("content", ""))
            h["vector_score"] = vector_score
            h["keyword_score"] = kw
            h["meta_score"] = meta
            h["score"] = min(1.0, VECTOR_WEIGHT * vector_score + KEYWORD_WEIGHT * kw + META_WEIGHT * meta)

        hits.sort(key=lambda h: h.get("score", 0), reverse=True)
        result = hits[:top_k]

        logger.info(f"混合检索: '{query[:50]}', 向量{fetch_k}→融合排序取{len(result)}")
        return result


retriever = Retriever()
