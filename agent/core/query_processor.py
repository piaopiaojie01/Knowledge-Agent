"""查询预处理模块 - 查询改写与扩展"""
import jieba
import re
from typing import List, Tuple


class QueryProcessor:
    """查询预处理：分词、关键词提取、查询扩展"""

    def __init__(self):
        # 停用词表（中文常见停用词）
        self.stopwords = set([
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
            "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
            "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "些",
            "什么", "怎么", "如何", "为什么", "吗", "呢", "吧", "啊", "哦", "嗯",
        ])

    def segment(self, text: str) -> List[str]:
        """中文分词，过滤停用词"""
        words = jieba.lcut(text)
        return [w for w in words if w.strip() and w not in self.stopwords and len(w) > 1]

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

        # 变体1: 去除问句语气词
        cleaned = re.sub(r'[？?！!。，,、\s]+', ' ', query).strip()
        if cleaned != query:
            variants.append(cleaned)

        # 变体2: 关键词拼接（用于关键词检索增强）
        keywords = self.extract_keywords(query, top_k=5)
        if keywords:
            variants.append(" ".join(keywords))

        return variants


query_processor = QueryProcessor()
