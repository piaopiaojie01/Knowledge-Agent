"""Knowledge Agent - FastAPI 入口"""
import os
os.environ.setdefault("HF_HUB_OFFLINE", "1")
import logging
from contextlib import asynccontextmanager
from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from config import settings
from store.milvus_client import milvus_client
from embedding.bge_embedder import embedder
from api.routes import router
from api.ingest_routes import router as ingest_router, reset_interrupted_tasks
from api.delete_routes import router as delete_router
from api.security import require_internal_key
from core.observability import (
    REQUEST_ID_HEADER, RequestIdFilter, agent_health, get_request_id, set_request_id,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [req=%(request_id)s] %(name)s: %(message)s")
# 过滤器必须挂在 handler 上（logger 级过滤器不作用于向上传播的记录）
for _handler in logging.getLogger().handlers:
    _handler.addFilter(RequestIdFilter())
logger = logging.getLogger(__name__)


_INTERNAL_DEFAULT_KEY = "ka-internal-dev-key"


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"Starting {settings.app_name} v{settings.app_version}")
    if not settings.internal_api_key:
        logger.error("KA_INTERNAL_API_KEY 未配置，业务接口将全部拒绝访问（fail-closed）")
    elif settings.internal_api_key == _INTERNAL_DEFAULT_KEY:
        logger.warning("正在使用默认内部密钥，生产环境务必通过 KA_INTERNAL_API_KEY 覆盖")
    milvus_client.connect()
    embedder.model
    if milvus_client.is_connected: milvus_client.ensure_collection()
    # 重启后残留的 processing 任务线程已死，批量标记 interrupted 避免永久卡死
    reset_interrupted_tasks()
    logger.info(f"{settings.app_name} started")
    yield
    milvus_client.close()

app = FastAPI(title=settings.app_name, version=settings.app_version, lifespan=lifespan)


@app.middleware("http")
async def request_id_middleware(request, call_next):
    """透传/生成请求 ID，供日志关联；响应头回传"""
    rid = request.headers.get(REQUEST_ID_HEADER) or request.query_params.get("request_id") or "-"
    set_request_id(rid)
    response = await call_next(request)
    response.headers[REQUEST_ID_HEADER] = rid
    return response


# CORS 收紧：Agent 只服务后端调用，默认不开放跨域；调试前端直连时才配置 KA_CORS_ORIGINS
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins or [],
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type", "X-KA-API-Key"],
)
# P0：所有业务端点强制内部 API Key 鉴权（health 也纳入，根路径 / 仅作存活探针）
app.include_router(router, dependencies=[Depends(require_internal_key)])
app.include_router(ingest_router, dependencies=[Depends(require_internal_key)])
app.include_router(delete_router, dependencies=[Depends(require_internal_key)])

@app.get("/")
async def root(): return {"service": settings.app_name, "version": settings.app_version}


@app.get("/metrics")
async def metrics():
    """Prometheus 指标端点（内网采集用）"""
    from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
    agent_health.inc()
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=False)
