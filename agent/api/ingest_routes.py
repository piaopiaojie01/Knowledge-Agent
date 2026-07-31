"""异步文档入库 - PDF → Markdown → 语义切分 → QA → Milvus + OCR

完整流程：
  PDF → pymupdf 提取（字号检测标题→## + 表格→Markdown）→ ## 标题语义切分 → LLM 生成 QA → 向量化入库
  扫描件 → easyocr 识别 → QA → 入库
"""

import logging, threading, re, base64, os, json, time, sqlite3
from pydantic import BaseModel, Field
from fastapi import APIRouter, HTTPException

from store.milvus_client import milvus_client
from embedding.bge_embedder import embedder
from core.document_processor import processor as doc_processor
from api.delete_routes import delete_by_expr

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/rag", tags=["Ingest"])
_task_status: dict[int, dict] = {}

# ═════════════════════════════════
# 任务状态持久化（内存 + SQLite 写穿）
# ═════════════════════════════════

_DB_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "data", "ingest_tasks.db")
# RLock：_try_start_task 持锁内还会调用 _get_task/_set_task（各自再加锁）
_task_lock = threading.RLock()


def _db_conn() -> sqlite3.Connection:
    """首次使用自动建目录建表"""
    os.makedirs(os.path.dirname(_DB_PATH), exist_ok=True)
    conn = sqlite3.connect(_DB_PATH)
    conn.execute("CREATE TABLE IF NOT EXISTS tasks ("
                 "task_id TEXT PRIMARY KEY, status TEXT, message TEXT, updated_at REAL)")
    return conn


def _set_task(doc_id: int, status: dict):
    """写内存并同步 upsert SQLite（message 列存完整状态 dict 的 JSON）"""
    with _task_lock:
        _task_status[doc_id] = status
        try:
            conn = _db_conn()
            with conn:
                conn.execute(
                    "INSERT INTO tasks (task_id, status, message, updated_at) VALUES (?,?,?,?) "
                    "ON CONFLICT(task_id) DO UPDATE SET status=excluded.status, "
                    "message=excluded.message, updated_at=excluded.updated_at",
                    (str(doc_id), status.get("status", ""),
                     json.dumps(status, ensure_ascii=False), time.time()))
            conn.close()
        except Exception as e:
            logger.warning(f"任务状态持久化失败 doc={doc_id}: {e}")


def _get_task(doc_id: int):
    """优先读内存，miss 时读 SQLite 并回填内存"""
    with _task_lock:
        s = _task_status.get(doc_id)
    if s is not None:
        return s
    try:
        conn = _db_conn()
        row = conn.execute("SELECT message FROM tasks WHERE task_id = ?",
                           (str(doc_id),)).fetchone()
        conn.close()
        if row:
            status = json.loads(row[0])
            with _task_lock:
                _task_status[doc_id] = status
            return status
    except Exception as e:
        logger.warning(f"任务状态读取失败 doc={doc_id}: {e}")
    return None


def reset_interrupted_tasks():
    """启动钩子：把残留的 processing 任务批量标记为 interrupted

    重启后旧线程早已死亡，不回填的话该 doc_id 会被 already_processing 守卫永久卡住
    """
    try:
        conn = _db_conn()
        rows = conn.execute(
            "SELECT task_id, message FROM tasks WHERE status = 'processing'").fetchall()
        with conn:
            for task_id, message in rows:
                try:
                    status = json.loads(message)
                except Exception:
                    status = {}
                status["status"] = "interrupted"
                status["message"] = "服务重启，任务中断，请重新入库"
                conn.execute(
                    "UPDATE tasks SET status='interrupted', message=?, updated_at=? "
                    "WHERE task_id=?",
                    (json.dumps(status, ensure_ascii=False), time.time(), task_id))
        conn.close()
        if rows:
            logger.info(f"已将 {len(rows)} 个残留 processing 任务标记为 interrupted")
    except Exception as e:
        logger.warning(f"重置残留任务状态失败: {e}")


class IngestRequest(BaseModel):
    doc_id: int = Field(...)
    title: str = Field(...)
    kb_name: str = Field(...)
    content: str = Field(...)
    chunk_size: int = 512
    chunk_overlap: int = 64


class PdfUpload(BaseModel):
    doc_id: int
    title: str
    kb_name: str
    pdf_base64: str


# ═════════════════════════════════
# 核心入库
# ═════════════════════════════════

def _do_ingest(req: IngestRequest):
    doc_id = req.doc_id
    _set_task(doc_id, {"status": "processing", "total": 0, "done": 0, "message": "结构化处理中..."})
    try:
        if not milvus_client.is_connected:
            _set_task(doc_id, {"status": "failed", "message": "Milvus not connected"}); return
        # 幂等：先清理该文档旧向量，避免重复入库导致向量翻倍
        try:
            delete_by_expr(f"doc_id == {doc_id}")
        except Exception as e:
            logger.warning(f"清理旧向量失败 doc={doc_id}（继续入库）: {e}")
        qa_pairs = doc_processor.process(req.content, req.title)
        if not qa_pairs:
            _set_task(doc_id, {"status": "failed", "message": "empty"}); return

        total = len(qa_pairs); inserted = 0
        _set_task(doc_id, {"status": "processing", "total": total, "done": 0, "message": "向量化 + 入库..."})
        for i in range(0, total, 10):
            batch = qa_pairs[i:i+10]
            texts = [f"{q['title']}\n{q['content']}" for q in batch]
            embs = embedder.encode_documents(texts)
            rows = [{"doc_id": doc_id, "kb_name": req.kb_name,
                     "title": q["title"], "content": q["content"],
                     "source_content": q.get("source_content", ""),
                     "keywords": q.get("keywords", ""),
                     "embedding": e.tolist()}
                    for q, e in zip(batch, embs)]
            inserted += milvus_client.insert(rows)
            _set_task(doc_id, {"status": "processing", "total": total, "done": min(i+10, total)})

        milvus_client.create_index_if_needed()
        _set_task(doc_id, {"status": "done", "total": total, "done": total, "inserted": inserted})
        logger.info(f"Ingest done: doc={doc_id}, {inserted} QA pairs")
    except Exception as e:
        logger.error(f"ingest failed: {e}", exc_info=True)
        _set_task(doc_id, {"status": "failed", "message": str(e)})


# ═════════════════════════════════
# PDF → Markdown 转换
# ═════════════════════════════════

def _pdf_to_markdown(pdf_bytes: bytes) -> str:
    """PDF → Markdown：字号检测标题 + 表格 + 页面标记"""
    doc = __import__("pymupdf").open(stream=pdf_bytes, filetype="pdf")
    parts = []
    for pi, page in enumerate(doc):
        blocks = page.get_text("dict").get("blocks", [])
        page_lines = []
        for block in blocks:
            if block.get("type") != 0:
                continue
            for line in block.get("lines", []):
                text = ""
                font_sizes = []
                for span in line.get("spans", []):
                    text += span.get("text", "")
                    font_sizes.append(span.get("size", 10))
                text = text.strip()
                if not text:
                    continue
                avg_size = sum(font_sizes) / len(font_sizes) if font_sizes else 10
                # 标题检测：字号 > 14 或 匹配章节/编号模式
                heading_pattern = r'^(第[一二三四五六七八九十\d]+[章节篇]|\d+[.、．]|[IVX]+[.、]|序言|前言|目录|附录|参考文献|摘要|Abstract|Introduction|Chapter\s*\d+)'
                is_heading = avg_size > 14 or bool(re.match(heading_pattern, text, re.IGNORECASE))
                if is_heading:
                    page_lines.append(f"## {text}")
                else:
                    page_lines.append(text)

        # 表格提取 → Markdown table
        tabs = page.find_tables()
        if tabs:
            for t in tabs:
                rows = t.extract()
                if not rows:
                    continue
                md = "\n| " + " | ".join(str(c) if c else "" for c in rows[0]) + " |\n"
                md += "| " + " | ".join("---" for _ in rows[0]) + " |\n"
                for row in rows[1:]:
                    md += "| " + " | ".join(str(c) if c else "" for c in row) + " |\n"
                page_lines.append(f"\n[表格]\n{md}")

        if page_lines:
            parts.append(f"## 第{pi+1}页\n" + "\n".join(page_lines))
    doc.close()
    return "\n\n".join(parts)


# ═════════════════════════════════
# API 端点
# ═════════════════════════════════

def _try_start_task(doc_id: int, target, args) -> bool:
    """原子地完成「检查状态 + 置 processing + 启动线程」，已在处理中返回 False"""
    with _task_lock:
        if (_get_task(doc_id) or {}).get("status") == "processing":
            return False
        _set_task(doc_id, {"status": "processing", "total": 0, "done": 0, "message": "排队中..."})
        threading.Thread(target=target, args=args, daemon=True).start()
        return True


@router.post("/ingest")
async def ingest_document(req: IngestRequest):
    if not milvus_client.is_connected: raise HTTPException(status_code=503)
    if not _try_start_task(req.doc_id, _do_ingest, (req,)):
        return {"success": True, "doc_id": req.doc_id, "status": "already_processing"}
    return {"success": True, "doc_id": req.doc_id, "status": "processing"}


@router.get("/ingest/{doc_id}/status")
async def ingest_status(doc_id: int):
    s = _get_task(doc_id)
    return {"doc_id": doc_id, **(s or {"status": "unknown"})}


def _do_ingest_pdf(req: PdfUpload):
    """后台线程：PDF 解析/OCR（重 CPU，避免阻塞事件循环）→ 入库"""
    doc_id = req.doc_id
    _set_task(doc_id, {"status": "processing", "total": 0, "done": 0, "message": "PDF 解析中..."})
    try:
        pdf_bytes = base64.b64decode(req.pdf_base64)
        text = _pdf_to_markdown(pdf_bytes)
        if not text.strip():
            logger.info(f"PDF 无文字，启用 OCR: {req.title}")
            text = _ocr_pdf(pdf_bytes)
    except Exception as e:
        _set_task(doc_id, {"status": "failed", "message": f"PDF error: {e}"})
        return
    if not text.strip():
        _set_task(doc_id, {"status": "failed", "message": "PDF/图片未识别到文字"})
        return
    ir = IngestRequest(doc_id=doc_id, title=req.title, kb_name=req.kb_name, content=text)
    _do_ingest(ir)


@router.post("/ingest-pdf")
async def ingest_pdf(req: PdfUpload):
    """PDF 入库：提取 → Markdown 格式化 → 语义切分 → QA → 入库（后台线程执行，状态走 /ingest/{doc_id}/status）"""
    if not milvus_client.is_connected: raise HTTPException(status_code=503)
    if not _try_start_task(req.doc_id, _do_ingest_pdf, (req,)):
        return {"success": True, "doc_id": req.doc_id, "status": "already_processing"}
    return {"success": True, "doc_id": req.doc_id, "status": "processing"}


def _do_ingest_image(req: PdfUpload):
    """后台线程：图片 OCR（重 CPU）→ 入库"""
    doc_id = req.doc_id
    _set_task(doc_id, {"status": "processing", "total": 0, "done": 0, "message": "图片 OCR 中..."})
    try:
        img_bytes = base64.b64decode(req.pdf_base64)
        text = _ocr_image(img_bytes)
    except Exception as e:
        _set_task(doc_id, {"status": "failed", "message": f"Image error: {e}"})
        return
    if not text.strip():
        _set_task(doc_id, {"status": "failed", "message": "图片未识别到文字"})
        return
    ir = IngestRequest(doc_id=doc_id, title=req.title, kb_name=req.kb_name, content=text)
    _do_ingest(ir)


@router.post("/ingest-image")
async def ingest_image(req: PdfUpload):
    """图片入库：OCR → 语义切分 → QA → 入库（后台线程执行，状态走 /ingest/{doc_id}/status）"""
    if not milvus_client.is_connected: raise HTTPException(status_code=503)
    if not _try_start_task(req.doc_id, _do_ingest_image, (req,)):
        return {"success": True, "doc_id": req.doc_id, "status": "already_processing"}
    return {"success": True, "doc_id": req.doc_id, "status": "processing"}


# ═════════════════════════════════
# OCR
# ═════════════════════════════════

_ocr_reader = None

def _get_ocr():
    global _ocr_reader
    if _ocr_reader is None:
        import easyocr
        _ocr_reader = easyocr.Reader(["ch_sim", "en"], gpu=False)
    return _ocr_reader

def _ocr_pdf(pdf_bytes: bytes) -> str:
    doc = __import__("pymupdf").open(stream=pdf_bytes, filetype="pdf")
    reader = _get_ocr()
    lines = []
    for i, page in enumerate(doc):
        pix = page.get_pixmap(dpi=200)
        img_bytes = pix.tobytes("png")
        results = reader.readtext(img_bytes, detail=0)
        if results:
            lines.append(f"## 第{i+1}页")
            lines.extend(results)
    doc.close()
    return "\n".join(lines)

def _ocr_image(img_bytes: bytes) -> str:
    reader = _get_ocr()
    results = reader.readtext(img_bytes, detail=0)
    return "\n".join(results)
