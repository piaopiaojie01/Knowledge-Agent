"""全局配置管理"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "Knowledge Agent"
    app_version: str = "1.0.0"
    host: str = "0.0.0.0"
    port: int = 8000

    milvus_host: str = "localhost"
    milvus_port: int = 19530
    milvus_collection_name: str = "knowledge_agent_docs"

    embedding_model: str = "BAAI/bge-small-zh-v1.5"
    embedding_dim: int = 512
    # 推理设备：cpu / cuda（有 NVIDIA GPU 时在 .env 里改成 cuda 可大幅提速）
    embedding_device: str = "cpu"
    ocr_device: str = "cpu"
    reranker_device: str = "cpu"

    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-v4-flash"

    retrieval_top_k: int = 5
    rerank_top_k: int = 3
    min_score: float = 0.35
    # 有效来源判定阈值（基于未稀释的向量余弦分 vector_score）
    source_threshold: float = 0.62
    chunk_size: int = 512
    chunk_overlap: int = 64

    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_db: int = 0
    session_ttl: int = 3600

    context_window: int = 1048576
    compress_threshold: float = 0.95
    compress_keep_recent: int = 6

    max_tokens: int = 124800

    # 图表触发词（含任一即触发画图）
    chart_keywords: list[str] = [
        # 显式画图
        "画图", "画个图", "做个图", "生成图", "图表",
        "柱状图", "折线图", "饼图",
        "饼状图", "柱形图", "条形图",
        "画柱状图", "画折线图", "画饼图",
        "画个柱状图", "画个折线图", "画个饼图",
        "make chart", "bar chart", "line chart", "pie chart",
        "可视化",
        # 续图/改图（不预生成，只触发 LLM tool calling）
        "再加", "加上", "加个", "换成", "改成", "重新画",
        "再来", "也画", "也来", "补上", "补个", "续",
    ]

    # 图表单位自动提取词（按优先级排列，长单位在前避免被短词截断）
    chart_units: list[str] = [
        "万元", "亿元", "万", "亿", "%", "个", "人", "次",
        "元", "件", "笔", "吨", "kg", "km", "m",
        "小时", "天", "月", "年",
    ]

    model_config = {"env_prefix": "KA_", "env_file": ".env"}


settings = Settings()
