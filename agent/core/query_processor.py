"""查询预处理模块 - 查询改写与扩展（中英双语）"""
import re
from typing import List

from core.text_utils import detect_lang, normalize_words


class QueryProcessor:
    """查询预处理：分词、关键词提取、查询扩展"""

    def segment(self, text: str) -> List[str]:
        """中英双语分词：英文小写化+去停用词；中文 jieba 分词+去停用词（保留单字实词）"""
        return normalize_words(text, min_len=1)

    def extract_keywords(self, text: str, top_k: int = 5) -> List[str]:
        """提取关键词"""
        words = self.segment(text)
        # 简单 TF 统计
        word_freq = {}
        for w in words:
            word_freq[w] = word_freq.get(w, 0) + 1
        sorted_words = sorted(word_freq.items(), key=lambda x: x[1], reverse=True)
        return [w for w, _ in sorted_words[:top_k]]

    def expand_query(self, query: str) -> List[str]:
        """查询扩展：生成多个查询变体用于混合检索"""
        variants = [query]

        # 变体1: 去除问句语气词与标点
        cleaned = re.sub(r'[？?！!。，,、\s]+', ' ', query).strip()
        if cleaned != query:
            variants.append(cleaned)

        # 变体2: 关键词拼接（中文无空格拼接，英文保持空格分词）
        keywords = self.extract_keywords(query, top_k=5)
        if keywords:
            variants.append(" ".join(keywords) if detect_lang(query) == "en" else "".join(keywords))

        return variants


query_processor = QueryProcessor()
