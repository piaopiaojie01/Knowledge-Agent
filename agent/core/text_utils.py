"""中英双语文本工具：语言检测、token 估算、双语停用词与词归一"""
import re

import jieba


ZH_STOPWORDS = {
    "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
    "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
    "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "些",
    "什么", "怎么", "如何", "为什么", "吗", "呢", "吧", "啊", "哦", "嗯",
    "与", "及", "或", "对", "中", "下", "为", "等", "可以", "进行", "我们",
}

EN_STOPWORDS = {
    "the", "a", "an", "and", "or", "but", "if", "then", "than", "so",
    "is", "are", "was", "were", "be", "been", "being", "am",
    "of", "to", "in", "for", "on", "with", "at", "by", "from", "as",
    "that", "this", "these", "those", "it", "its", "he", "she", "they",
    "we", "you", "i", "me", "my", "your", "their", "our", "his", "her",
    "what", "which", "who", "whom", "how", "why", "when", "where",
    "do", "does", "did", "not", "no", "can", "could", "should", "would",
    "will", "shall", "may", "might", "about", "into", "over", "under",
    "between", "after", "before", "during", "without", "through",
    "out", "up", "down", "just", "only", "very", "too", "much", "more",
    "most", "such", "each", "both", "either", "neither", "there", "here",
}


def detect_lang(text: str) -> str:
    """逐块语言检测：中文汉字明显占优为 zh，否则 en（支持中英混排）"""
    sample = text[:2000]
    if not sample.strip():
        return "zh"
    cn = sum(1 for c in sample if '\u4e00' <= c <= '\u9fff')
    latin = sum(1 for c in sample if c.isascii() and c.isalpha())
    return "zh" if cn >= max(latin, 1) else "en"


def estimate_tokens(text: str, lang: str | None = None) -> int:
    """估算 token 数：中文约 1.5 token/字，英文约 0.25 token/字符"""
    if not text:
        return 0
    if lang is None:
        lang = detect_lang(text)
    if lang == "zh":
        cn = sum(1 for c in text if '\u4e00' <= c <= '\u9fff')
        return int(cn * 1.5 + (len(text) - cn) * 0.3)
    return max(1, int(len(text) / 4))


def normalize_words(text: str, min_len: int = 1) -> list[str]:
    """中英双语词归一：
    - 英文：小写化、按单词提取（保留连字符/撇号）、过滤英文停用词
    - 中文：jieba 分词、过滤中文停用词与纯标点
    """
    if not text or not text.strip():
        return []
    if detect_lang(text) == "en":
        words = re.findall(r"[a-zA-Z]+(?:['-][a-zA-Z]+)*", text.lower())
        # 词形基础归一：剥离属格 's / ’s（company's → company），复数暂不处理
        words = [re.sub(r"['’]s$", "", w) for w in words]
        return [w for w in words if len(w) >= min_len and w not in EN_STOPWORDS]
    words = jieba.lcut(text)
    return [
        w for w in words
        if w.strip() and len(w) >= min_len
        and w not in ZH_STOPWORDS
        and not re.fullmatch(r"[\W_]+", w)
    ]
