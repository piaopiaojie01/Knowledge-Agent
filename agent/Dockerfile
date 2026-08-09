# Python 智能引擎（FastAPI + BGE + OCR + DeepSeek）
# 说明：依赖包含 torch/docling 等大体积包，首次构建较慢（约 5-10GB 镜像）。
# GPU 推理请基于 CUDA 基础镜像或自行安装对应 torch 版本。

FROM python:3.11-slim
WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HUB_OFFLINE=0 \
    PIP_NO_CACHE_DIR=1

# curl 用于健康检查；依赖解析所需的基础库
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
# setuptools<81：pymilvus 2.3.5 依赖 pkg_resources
RUN pip install "setuptools<81" \
    && pip install -r requirements.txt

COPY . .

# 运行时产物目录（compose 用命名卷挂载 /data）
RUN mkdir -p /data/charts /data/icons /data/memory \
    && chmod -R a+rwx /data

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
  CMD ["curl", "-fsS", "http://127.0.0.1:8000/", "-o", "/dev/null"] || true

CMD ["python", "main.py"]
