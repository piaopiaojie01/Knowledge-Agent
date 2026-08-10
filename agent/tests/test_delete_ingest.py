"""api/delete_routes.py 与 api/ingest_routes.py 测试（SQLite 用临时文件，Milvus 全替身）"""
import asyncio
import json
import sqlite3

import api.delete_routes as dr
import api.ingest_routes as ir
from api.delete_routes import DeleteByKbRequest, delete_by_expr, delete_vectors_by_kb


def test_kb_name_illegal_chars_rejected():
    # 验证 kb_name 含非法字符直接拒绝，不做任何删除
    req = DeleteByKbRequest(kb_name='坏名字"; DROP TABLE--')
    out = asyncio.run(delete_vectors_by_kb(req))
    assert out["success"] is False
    assert "非法字符" in out["error"]
    assert out["deleted"] == 0


def test_kb_name_legal_passes(monkeypatch):
    # 验证合法 kb_name（中文/字母/数字/下划线）通过校验并调用删除
    monkeypatch.setattr(dr, "delete_by_expr", lambda expr: 3)
    req = DeleteByKbRequest(kb_name="技术文档_kb1")
    out = asyncio.run(delete_vectors_by_kb(req))
    assert out["success"] is True and out["deleted"] == 3


def _patch_milvus(monkeypatch, col):
    """替换 pymilvus 连接/集合为替身"""
    monkeypatch.setattr(dr.connections, "connect", lambda **k: None)
    monkeypatch.setattr(dr.connections, "disconnect", lambda *a, **k: None)
    monkeypatch.setattr(dr.utility, "has_collection", lambda *a, **k: True)
    monkeypatch.setattr(dr, "Collection", lambda *a, **k: col)


def test_delete_by_expr_count_fail_still_deletes(monkeypatch):
    # 验证 count 查询失败仅记日志，删除照常执行
    deleted = []

    class FakeCol:
        def load(self):
            pass

        def query(self, **k):
            raise RuntimeError("count 不可用")

        def delete(self, expr):
            deleted.append(expr)

        def flush(self):
            pass

        def compact(self):
            pass

    _patch_milvus(monkeypatch, FakeCol())
    assert delete_by_expr("doc_id == 1") == 0
    assert deleted == ["doc_id == 1"]


def test_delete_by_expr_compact_fail_ignored(monkeypatch):
    # 验证 compact 失败不影响删除结果
    class FakeCol:
        def load(self):
            pass

        def query(self, **k):
            return [{"count(*)": 5}]

        def delete(self, expr):
            pass

        def flush(self):
            pass

        def compact(self):
            raise RuntimeError("compact 失败")

    _patch_milvus(monkeypatch, FakeCol())
    assert delete_by_expr("doc_id == 1") == 5


def _use_tmp_db(monkeypatch, tmp_path):
    """把 ingest 任务库指到临时 SQLite 文件，并清空内存状态"""
    monkeypatch.setattr(ir, "_DB_PATH", str(tmp_path / "tasks.db"))
    monkeypatch.setattr(ir, "_task_status", {})


def test_reset_interrupted_tasks(monkeypatch, tmp_path):
    # 验证残留的 processing 任务被批量翻转为 interrupted，done 不受影响
    _use_tmp_db(monkeypatch, tmp_path)
    conn = ir._db_conn()
    with conn:
        conn.execute("INSERT INTO tasks VALUES ('1','processing',?,0)",
                     (json.dumps({"status": "processing", "done": 3}),))
        conn.execute("INSERT INTO tasks VALUES ('2','done',?,0)",
                     (json.dumps({"status": "done"}),))
    conn.close()

    ir.reset_interrupted_tasks()

    conn = sqlite3.connect(str(tmp_path / "tasks.db"))
    rows = dict(conn.execute("SELECT task_id, status FROM tasks").fetchall())
    msg = conn.execute("SELECT message FROM tasks WHERE task_id='1'").fetchone()[0]
    conn.close()
    assert rows == {"1": "interrupted", "2": "done"}
    assert json.loads(msg)["status"] == "interrupted"


def test_try_start_task_rejects_duplicate(monkeypatch, tmp_path):
    # 验证原子守卫：同一 doc_id 处理中时第二次启动被拒绝
    _use_tmp_db(monkeypatch, tmp_path)
    assert ir._try_start_task(42, lambda: None, ()) is True
    assert ir._try_start_task(42, lambda: None, ()) is False


def test_excel_to_markdown_converts_sheets():
    import io
    import openpyxl
    from api.ingest_routes import _excel_to_markdown

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "销售数据"
    ws.append(["产品", "销量"])
    ws.append(["苹果", 100])
    ws.append(["香蕉", 200])
    buf = io.BytesIO()
    wb.save(buf)

    md = _excel_to_markdown(buf.getvalue())

    assert "## 销售数据" in md
    assert "| 产品 | 销量 |" in md
    assert "| 苹果 | 100 |" in md
