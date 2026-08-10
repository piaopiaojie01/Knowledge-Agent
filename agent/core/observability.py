"""可观测性：请求 ID 透传 + Prometheus 指标（Agent 侧）"""
import logging
import time
from contextvars import ContextVar

from prometheus_client import Counter, Histogram

REQUEST_ID_HEADER = "X-Request-Id"
_request_id: ContextVar[str] = ContextVar("request_id", default="-")


def set_request_id(rid: str | None) -> None:
    _request_id.set(rid or "-")


def get_request_id() -> str:
    return _request_id.get()


class RequestIdFilter(logging.Filter):
    """把当前请求 ID 注入日志记录的 request_id 字段"""

    def filter(self, record):
        record.request_id = get_request_id()
        return True


# ── Prometheus 指标 ──
rag_requests = Counter("ka_rag_requests_total", "RAG 查询次数", ["result"])
rag_query_seconds = Histogram("ka_rag_query_seconds", "RAG 查询耗时", buckets=(0.1, 0.5, 1, 2, 5, 10, 30, 60))
llm_tokens = Counter("ka_llm_tokens_total", "LLM token 消耗", ["type"])
ingest_chunks = Counter("ka_ingest_chunks_total", "入库分块数")
agent_health = Counter("ka_agent_health_checks_total", "Agent 健康检查次数")


class Timing:
    """上下文计时器：with Timing() as t: ... t.elapsed"""

    def __enter__(self):
        self._t0 = time.monotonic()
        return self

    def __exit__(self, *exc):
        self.elapsed = time.monotonic() - self._t0
        return False
