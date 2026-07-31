"""Redis 会话管理"""
import json
import logging
from typing import Optional, Dict, Any
import redis
from config import settings

logger = logging.getLogger(__name__)


class SessionStore:
    """Redis 会话与 Agent 状态管理"""

    def __init__(self):
        self._client: Optional[redis.Redis] = None

    @property
    def client(self) -> redis.Redis:
        if self._client is None:
            self._client = redis.Redis(
                host=settings.redis_host,
                port=settings.redis_port,
                db=settings.redis_db,
                decode_responses=True,
            )
        return self._client

    @property
    def is_connected(self) -> bool:
        try:
            self.client.ping()
            return True
        except Exception:
            return False

    def save_session(self, session_id: str, data: Dict[str, Any], ttl: int | None = None):
        """保存会话数据"""
        if ttl is None:
            ttl = settings.session_ttl
        key = f"session:{session_id}"
        self.client.setex(key, ttl, json.dumps(data, ensure_ascii=False))

    def get_session(self, session_id: str) -> Optional[Dict[str, Any]]:
        """获取会话数据"""
        key = f"session:{session_id}"
        data = self.client.get(key)
        if data:
            return json.loads(data)
        return None

    def delete_session(self, session_id: str):
        """删除会话"""
        key = f"session:{session_id}"
        self.client.delete(key)

    def save_agent_state(self, agent_id: str, state: Dict[str, Any], ttl: int | None = None):
        """保存 Agent 状态"""
        if ttl is None:
            ttl = settings.session_ttl
        key = f"agent_state:{agent_id}"
        self.client.setex(key, ttl, json.dumps(state, ensure_ascii=False))

    def get_agent_state(self, agent_id: str) -> Optional[Dict[str, Any]]:
        """获取 Agent 状态"""
        key = f"agent_state:{agent_id}"
        data = self.client.get(key)
        if data:
            return json.loads(data)
        return None


# 全局单例
session_store = SessionStore()
