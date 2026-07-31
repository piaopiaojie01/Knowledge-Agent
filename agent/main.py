"""Knowledge Agent - FastAPI 入口"""
import os
os.environ.setdefault("HF_HUB_OFFLINE", "1")
import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from config import settings
from store.milvus_client import milvus_client
from embedding.bge_embedder import embedder
from api.routes import router
from api.ingest_routes import router as ingest_router, reset_interrupted_tasks
from api.delete_routes import router as delete_router

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger(__name__)

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"Starting {settings.app_name} v{settings.app_version}")
    milvus_client.connect()
    embedder.model
    if milvus_client.is_connected: milvus_client.ensure_collection()
    # 重启后残留的 processing 任务线程已死，批量标记 interrupted 避免永久卡死
    reset_interrupted_tasks()
    logger.info(f"{settings.app_name} started")
    yield
    milvus_client.close()

app = FastAPI(title=settings.app_name, version=settings.app_version, lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])
app.include_router(router)
app.include_router(ingest_router)
app.include_router(delete_router)

@app.get("/")
async def root(): return {"service": settings.app_name, "version": settings.app_version}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=settings.host, port=settings.port, reload=False)
