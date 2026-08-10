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
    # 有 NVIDIA GPU 时用 cuda 精排（加载失败自动降级 CPU/分数排序）
    reranker_device: str = "cuda"

    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-v4-flash"
    # 入库 QA 生成的 LLM 调用并发度（大文档提速；API 限流严重时调低）
    ingest_llm_concurrency: int = 8

    # 候选池给足，让 CrossEncoder 从更多候选中精排，避免碎片块挤掉正文块
    retrieval_top_k: int = 20
    rerank_top_k: int = 8
    min_score: float = 0.35
    # 有效来源判定阈值（基于未稀释的向量余弦分 vector_score）
    source_threshold: float = 0.55
    # 分块：按 token 估算（中文 1.5 token/字、英文 0.25 token/字符），对齐 embedding 模型上限
    chunk_tokens: int = 450
    chunk_overlap_tokens: int = 60
    # 语义分块：用 BGE 相邻句相似度找语义边界（低于阈值断开）；关闭时回退纯结构分块
    semantic_chunking: bool = True
    semantic_chunk_threshold: float = 0.45
    # 检索关键词召回：选知识库时对分块做精确词扫描，补足向量检索对专名的召回不足
    keyword_recall: bool = True
    # 入库 QA 增强（默认关闭：原文分块入库，保真可溯源；开启后每个分块额外生成问答对）
    ingest_qa_enabled: bool = False

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

    # ═════════════════════════════════════════
    # 安全配置（P0）
    # ═════════════════════════════════════════

    # 内部 API Key：Spring Boot 调用 Agent 时必须携带 X-KA-API-Key 请求头。
    # 默认值仅供本地开发，生产环境必须通过 KA_INTERNAL_API_KEY 覆盖。
    internal_api_key: str = "ka-internal-dev-key"

    # CORS 白名单：Agent 只被后端服务调用（非浏览器），默认留空即可；
    # 本地调试前端直连 Agent 时才需要显式配置。
    cors_origins: list[str] = []

    # url_fetch 工具域名白名单（空 = 禁用该工具；子域名自动匹配）。
    url_fetch_allowlist: list[str] = []
    # web_extract 爬虫域名白名单（空 = 回退 url_fetch 白名单；两者都空则禁用）
    crawl_allowlist: list[str] = []
    # 文档提取类技能（docx/xlsx/pptx/pdf）允许访问的目录（相对 agent 运行目录）
    file_access_dirs: list[str] = ["data"]

    # 上传体积上限（MB）：PDF/图片 base64 超过即拒绝
    max_upload_mb: int = 100

    # 运行时产物目录与访问地址（默认相对仓库根目录；容器部署请挂载卷并显式配置）
    chart_output_dir: str = ""
    icon_output_dir: str = ""
    chart_base_url: str = "http://localhost:8080"

    redis_password: str = ""

    model_config = {"env_prefix": "KA_", "env_file": ".env"}


settings = Settings()
