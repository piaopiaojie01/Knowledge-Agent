"""长期记忆模块 - 跨会话持久化关键事实"""
import json
import logging
import os
from pathlib import Path
from threading import Lock

logger = logging.getLogger(__name__)

DATA_DIR = Path(__file__).parent.parent / "data" / "memory"


class LongTermMemory:
    """长期记忆，按 session_id 隔离，文件持久化"""

    def __init__(self):
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        self._locks: dict[str, Lock] = {}

    def _path(self, session_id: str) -> Path:
        safe = "".join(c for c in session_id if c.isalnum() or c in "-_")
        return DATA_DIR / f"{safe or 'default'}.json"

    def _lock(self, session_id: str) -> Lock:
        if session_id not in self._locks:
            self._locks[session_id] = Lock()
        return self._locks[session_id]

    def load(self, session_id: str) -> list[str]:
        """加载记忆（事实列表）"""
        p = self._path(session_id)
        if p.exists():
            try:
                data = json.loads(p.read_text(encoding="utf-8"))
                return data.get("facts", [])
            except Exception as e:
                logger.warning(f"加载记忆失败 [{session_id}]: {e}")
        return []

    def save(self, session_id: str, facts: list[str]):
        """保存记忆"""
        with self._lock(session_id):
            try:
                self._path(session_id).write_text(
                    json.dumps({"facts": facts, "updated": __import__("datetime").datetime.now().isoformat()},
                               ensure_ascii=False, indent=2),
                    encoding="utf-8")
            except Exception as e:
                logger.error(f"保存记忆失败 [{session_id}]: {e}")

    def update(self, session_id: str, new_facts: list[str], max_facts: int = 50):
        """合并新事实（去重，保留最新 max_facts 条）"""
        existing = self.load(session_id)
        seen = set(existing)
        for f in new_facts:
            f = f.strip()
            if f and f not in seen:
                seen.add(f)
                existing.append(f)
        existing = existing[-max_facts:]
        self.save(session_id, existing)

    def as_context(self, session_id: str) -> str:
        """生成注入系统提示的上下文文本"""
        facts = self.load(session_id)
        if not facts:
            return ""
        return "已知用户信息：\n" + "\n".join(f"- {f}" for f in facts)

    def delete(self, session_id: str):
        """清除记忆"""
        p = self._path(session_id)
        if p.exists():
            p.unlink()
            logger.info(f"已清除记忆 [{session_id}]")


# 全局单例
memory = LongTermMemory()
