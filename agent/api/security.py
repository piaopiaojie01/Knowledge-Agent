"""内部 API Key 鉴权 —— 所有 /api/v1/rag/* 端点必须携带 X-KA-API-Key 请求头。

P0 安全加固：Agent 服务只允许被 Spring Boot 后端调用，
不配置 KA_INTERNAL_API_KEY 时拒绝一切业务请求（fail-closed）。
"""
import hmac
import logging

from fastapi import Header, HTTPException

from config import settings

logger = logging.getLogger(__name__)


def require_internal_key(x_ka_api_key: str = Header(default="")) -> None:
    """FastAPI 依赖：校验内部访问密钥（常量时间比较，防时序侧信道）"""
    expected = (settings.internal_api_key or "").strip()
    if not expected:
        logger.error("Agent 未配置 KA_INTERNAL_API_KEY，拒绝所有业务请求")
        raise HTTPException(status_code=503, detail="Agent 服务未配置内部访问密钥")
    provided = x_ka_api_key or ""
    if not provided or not hmac.compare_digest(provided, expected):
        logger.warning("拒绝未授权请求：X-KA-API-Key 缺失或不匹配")
        raise HTTPException(status_code=401, detail="未授权")
