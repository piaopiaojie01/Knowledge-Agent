"""入库分布式锁：锁占用跳过、完成后释放、状态 Redis 镜像"""
import os
os.environ.setdefault("HF_HUB_OFFLINE", "1")
import time
from api import ingest_routes as ir


class FakeRedis:
    """最小 Redis 桩：记录调用，按 lock_available 决定 SET NX 是否成功"""

    def __init__(self, lock_available=True):
        self.lock_available = lock_available
        self.calls = []
        self.store = {}

    def set(self, key, value, nx=False, ex=None):
        self.calls.append(("set", key, value))
        if nx and key in self.store:
            return False
        self.store[key] = value
        return self.lock_available

    def eval(self, script, numkeys, *args):
        self.calls.append(("eval", args[0]))
        key, token = args[0], args[1]
        if self.store.get(key) == token:
            self.store.pop(key, None)
            return 1
        return 0

    def get(self, key):
        return self.store.get(key)


def test_lock_busy_skips_task(monkeypatch):
    fake = FakeRedis(lock_available=False)
    monkeypatch.setattr(ir, "_redis", lambda: fake)
    ran = []

    ok = ir._try_start_task(9001, lambda: ran.append(1), ())

    assert ok is False
    assert ran == []


def test_lock_released_after_task_done(monkeypatch):
    fake = FakeRedis(lock_available=True)
    monkeypatch.setattr(ir, "_redis", lambda: fake)
    ran = []

    def worker():
        ran.append(1)
        ir._set_task(9002, {"status": "done", "total": 1, "done": 1, "percent": 100})

    ok = ir._try_start_task(9002, worker, ())
    assert ok is True
    for _ in range(50):
        if (ir._get_task(9002) or {}).get("status") == "done":
            break
        time.sleep(0.05)

    assert ran == [1]
    assert fake.store.get("ka:ingest:lock:9002") is None, "任务完成后锁应被释放"
    assert any(c[0] == "eval" for c in fake.calls), "应执行 Lua 释放锁"


def test_status_mirrored_to_redis(monkeypatch):
    fake = FakeRedis(lock_available=True)
    monkeypatch.setattr(ir, "_redis", lambda: fake)

    ir._set_task(9003, {"status": "processing", "total": 0, "done": 0, "message": "排队中"})

    assert any(
        c[0] == "set" and c[1] == "ka:ingest:status:9003"
        for c in fake.calls
    ), "任务状态应镜像到 Redis"
