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
from config import settings

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
    title: str = Field(..., max_length=500)
    kb_name: str = Field(..., max_length=200)
    content: str = Field(..., max_length=10_000_000)
    chunk_size: int = 512
    chunk_overlap: int = 64


class PdfUpload(BaseModel):
    doc_id: int
    title: str = Field(..., max_length=500)
    kb_name: str = Field(..., max_length=200)
    pdf_base64: str = Field(..., min_length=1)
    device: str | None = None  # cpu / cuda，缺省用服务端配置 ocr_device


def _set_progress(doc_id: int, percent: int, message: str, total: int = 0, done: int = 0):
    """写入带整体百分比的任务状态（percent 0-99 由阶段加权计算，100 只由完成时落定）"""
    _set_task(doc_id, {"status": "processing", "total": total, "done": done,
                       "percent": max(0, min(99, percent)), "message": message})


# ═════════════════════════════════
# 核心入库
# ═════════════════════════════════

def _do_ingest(req: IngestRequest, qa_lo: int = 0, qa_hi: int = 85):
    """分块处理（qa_lo~qa_hi%）→ 向量化入库（~100%）。PDF 路径从 30% 起（前 30% 是解析）"""
    doc_id = req.doc_id
    _set_progress(doc_id, qa_lo, "文本分块处理中...")
    try:
        if not milvus_client.is_connected:
            _set_task(doc_id, {"status": "failed", "message": "Milvus not connected"}); return
        # 幂等：先清理该文档旧向量，避免重复入库导致向量翻倍
        try:
            delete_by_expr(f"doc_id == {doc_id}")
        except Exception as e:
            logger.warning(f"清理旧向量失败 doc={doc_id}（继续入库）: {e}")
        qa_pairs = doc_processor.process(
            req.content, req.title,
            progress_cb=lambda d, t: _set_progress(
                doc_id, int(qa_lo + (qa_hi - qa_lo) * d / max(t, 1)),
                f"分块处理中（{d}/{t}）...", t, d))
        if not qa_pairs:
            _set_task(doc_id, {"status": "failed", "message": "未能从文档中提取到有效文本内容"}); return

        total = len(qa_pairs); inserted = 0
        _set_progress(doc_id, qa_hi, "向量化 + 入库...", total, 0)
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
            done = min(i+10, total)
            _set_progress(doc_id, int(qa_hi + (100 - qa_hi) * done / total),
                          f"向量化 + 入库（{done}/{total}）...", total, done)

        milvus_client.create_index_if_needed()
        _set_task(doc_id, {"status": "done", "total": total, "done": total,
                           "percent": 100, "inserted": inserted})
        logger.info(f"Ingest done: doc={doc_id}, {inserted} QA pairs")
    except Exception as e:
        logger.error(f"ingest failed: {e}", exc_info=True)
        _set_task(doc_id, {"status": "failed", "message": str(e)})


# ═════════════════════════════════
# PDF → Markdown 转换
# ═════════════════════════════════

def _pdf_to_markdown(pdf_bytes: bytes, progress_cb=None) -> str:
    """PDF → Markdown：字号检测标题 + 表格 + 页面标记；progress_cb(done_pages, total_pages) 逐页回报"""
    doc = __import__("pymupdf").open(stream=pdf_bytes, filetype="pdf")
    total_pages = doc.page_count
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
        if progress_cb:
            try:
                progress_cb(pi + 1, total_pages)
            except Exception:
                pass  # 进度回报失败不影响解析
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


def _validate_pdf_upload(req: PdfUpload) -> None:
    """P0：base64 体积上限校验，拒绝超大上传（服务端不信任请求方声明）"""
    max_bytes = settings.max_upload_mb * 1024 * 1024
    # base64 长度 ≈ 4/3 * 原始字节数，留 4KB 余量
    if len(req.pdf_base64) > (max_bytes * 4 // 3) + 4096:
        raise HTTPException(status_code=413,
                            detail=f"文件超过大小上限（{settings.max_upload_mb}MB）")


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
    """后台线程：PDF 解析/OCR（重 CPU，避免阻塞事件循环）→ 入库；解析 0~30%，QA 30~85%，入库 ~100%"""
    doc_id = req.doc_id
    _set_progress(doc_id, 0, "PDF 解析中...")
    try:
        pdf_bytes = base64.b64decode(req.pdf_base64)
        text = _pdf_to_markdown(
            pdf_bytes,
            progress_cb=lambda d, t: _set_progress(
                doc_id, int(30 * d / max(t, 1)), f"PDF 解析中（{d}/{t} 页）...", t, d))
        if not text.strip():
            logger.info(f"PDF 无文字，启用 OCR({ _resolve_device(req.device) }): {req.title}")
            _set_progress(doc_id, 5, "扫描件 OCR 识别中（Docling）...")
            text = _ocr_pdf(
                pdf_bytes, req.device,
                progress_cb=lambda d, t: _set_progress(
                    doc_id, int(30 * d / max(t, 1)), f"OCR 识别中（{d}/{t} 页）...", t, d))
    except Exception as e:
        _set_task(doc_id, {"status": "failed", "message": f"PDF error: {e}"})
        return
    if not text.strip():
        _set_task(doc_id, {"status": "failed", "message": "PDF/图片未识别到文字"})
        return
    ir = IngestRequest(doc_id=doc_id, title=req.title, kb_name=req.kb_name, content=text)
    _do_ingest(ir, qa_lo=30)


@router.post("/ingest-pdf")
async def ingest_pdf(req: PdfUpload):
    """PDF 入库：提取 → Markdown 格式化 → 语义切分 → QA → 入库（后台线程执行，状态走 /ingest/{doc_id}/status）"""
    if not milvus_client.is_connected: raise HTTPException(status_code=503)
    _validate_pdf_upload(req)
    if not _try_start_task(req.doc_id, _do_ingest_pdf, (req,)):
        return {"success": True, "doc_id": req.doc_id, "status": "already_processing"}
    return {"success": True, "doc_id": req.doc_id, "status": "processing"}


def _do_ingest_image(req: PdfUpload):
    """后台线程：图片 OCR（重 CPU/GPU）→ 入库"""
    doc_id = req.doc_id
    _set_progress(doc_id, 5, "图片 OCR 中...")
    try:
        img_bytes = base64.b64decode(req.pdf_base64)
        text = _ocr_image(img_bytes, req.device)
    except Exception as e:
        _set_task(doc_id, {"status": "failed", "message": f"Image error: {e}"})
        return
    if not text.strip():
        _set_task(doc_id, {"status": "failed", "message": "图片未识别到文字"})
        return
    ir = IngestRequest(doc_id=doc_id, title=req.title, kb_name=req.kb_name, content=text)
    _do_ingest(ir, qa_lo=30)


@router.post("/ingest-image")
async def ingest_image(req: PdfUpload):
    """图片入库：OCR → 语义切分 → QA → 入库（后台线程执行，状态走 /ingest/{doc_id}/status）"""
    if not milvus_client.is_connected: raise HTTPException(status_code=503)
    _validate_pdf_upload(req)
    if not _try_start_task(req.doc_id, _do_ingest_image, (req,)):
        return {"success": True, "doc_id": req.doc_id, "status": "already_processing"}
    return {"success": True, "doc_id": req.doc_id, "status": "processing"}


# ═════════════════════════════════
# OCR
# ═════════════════════════════════

def _resolve_device(device: str | None) -> str:
    """请求级设备参数校验：只认 cpu/cuda，缺省或非法值回落服务端配置"""
    return device if device in ("cpu", "cuda") else settings.ocr_device


# 按设备分别缓存识别器（CPU/GPU 各一份，避免重复加载模型）
_ocr_readers: dict = {}

def _get_ocr(device: str | None = None):
    device = _resolve_device(device)
    if device not in _ocr_readers:
        import easyocr
        _ocr_readers[device] = easyocr.Reader(["ch_sim", "en"], gpu=(device == "cuda"))
    return _ocr_readers[device]

_docling_converters: dict = {}

def _get_docling(device: str | None = None):
    """懒加载 Docling 转换器（版面分析 + RapidOCR/PP-OCRv6 中文识别，输出结构化 Markdown）"""
    device = _resolve_device(device)
    if device not in _docling_converters:
        from docling.document_converter import DocumentConverter, PdfFormatOption
        from docling.datamodel.pipeline_options import PdfPipelineOptions, RapidOcrOptions
        from docling.datamodel.base_models import InputFormat
        from docling.datamodel.accelerator_options import AcceleratorOptions, AcceleratorDevice
        opts = PdfPipelineOptions()
        opts.do_ocr = True
        # 必须显式指定 RapidOCR：默认 OcrAutoOptions 选错引擎时扫描件几乎识别不出内容
        opts.ocr_options = RapidOcrOptions()
        accel = AcceleratorDevice.CUDA if device == "cuda" else AcceleratorDevice.CPU
        opts.accelerator_options = AcceleratorOptions(device=accel)
        _docling_converters[device] = DocumentConverter(
            format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts)})
        logger.info(f"Docling 转换器加载完成（RapidOCR, {device}）")
    return _docling_converters[device]

def _ocr_pdf(pdf_bytes: bytes, device: str | None = None, progress_cb=None) -> str:
    """扫描件 OCR：Docling 版面分析 + RapidOCR，直接产出带标题/表格结构的 Markdown；
    Docling 异常时降级 easyocr 纯文本；progress_cb(done_pages, total_pages) 仅在 easyocr 路径逐页回报"""
    import tempfile
    try:
        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
            f.write(pdf_bytes)
            tmp_path = f.name
        try:
            result = _get_docling(device).convert(tmp_path)
            md = result.document.export_to_markdown()
            if md.strip():
                return md
            logger.warning("Docling 输出为空，降级 easyocr")
        finally:
            os.unlink(tmp_path)
    except Exception as e:
        logger.warning(f"Docling 识别失败，降级 easyocr: {e}")
    return _ocr_pdf_easyocr(pdf_bytes, device, progress_cb)

def _ocr_pdf_easyocr(pdf_bytes: bytes, device: str | None = None, progress_cb=None) -> str:
    doc = __import__("pymupdf").open(stream=pdf_bytes, filetype="pdf")
    total_pages = doc.page_count
    reader = _get_ocr(device)
    lines = []
    for i, page in enumerate(doc):
        pix = page.get_pixmap(dpi=200)
        img_bytes = pix.tobytes("png")
        results = reader.readtext(img_bytes, detail=0)
        if results:
            lines.append(f"## 第{i+1}页")
            lines.extend(results)
        if progress_cb:
            try:
                progress_cb(i + 1, total_pages)
            except Exception:
                pass  # 进度回报失败不影响 OCR
    doc.close()
    return "\n".join(lines)

def _ocr_image(img_bytes: bytes, device: str | None = None) -> str:
    reader = _get_ocr(device)
    results = reader.readtext(img_bytes, detail=0)
    return "\n".join(results)
