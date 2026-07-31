import logging
import re
from pydantic import BaseModel
from fastapi import APIRouter

from pymilvus import connections, Collection, utility

from config import settings

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/rag")


class DeleteByKbRequest(BaseModel):
    kb_name: str


class DeleteByDocRequest(BaseModel):
    doc_id: int


def delete_by_expr(expr: str) -> int:
    """按表达式删除向量并返回删除条数（删除失败抛异常）

    供 /delete-by-kb、/delete-by-doc 端点与入库前的幂等清理共用。
    """
    import uuid
    alias = f"delete_{uuid.uuid4().hex[:8]}"
    connections.connect(alias=alias, host=settings.milvus_host, port=settings.milvus_port)
    try:
        if not utility.has_collection(settings.milvus_collection_name, using=alias):
            return 0

        col = Collection(settings.milvus_collection_name, using=alias)
        col.load()

        # 先统计匹配数量，再整体删除（Milvus delete 按表达式全量删除）
        try:
            count_result = col.query(expr=expr, output_fields=["count(*)"])
            total = count_result[0]["count(*)"] if count_result else 0
        except Exception as e:
            # 计数失败仅记日志，直接按表达式删除（删除本身不依赖先计数）
            logger.warning(f"删除前计数失败，将直接删除: {e}")
            total = None

        if total is None or total > 0:
            col.delete(expr)
            col.flush()
            try:
                col.compact()
            except Exception as e:
                # compact 失败不影响删除结果（delete + flush 已生效）
                logger.warning(f"compact 失败（删除已生效）: {e}")

        return total or 0
    finally:
        connections.disconnect(alias)


@router.post("/delete-by-kb")
async def delete_vectors_by_kb(req: DeleteByKbRequest):
    """删除指定知识库的所有向量"""
    # 过滤表达式注入校验：只允许字母数字下划线与中文，含其他字符直接拒绝（不改写，避免删错库）
    safe_kb = re.sub(r'[^a-zA-Z0-9_\u4e00-\u9fff]', '', req.kb_name)
    if not safe_kb or safe_kb != req.kb_name:
        return {"success": False, "error": "知识库名称包含非法字符", "deleted": 0}

    try:
        deleted = delete_by_expr(f'kb_name == "{safe_kb}"')
        return {"success": True, "deleted": deleted, "kb_name": req.kb_name}
    except Exception as e:
        return {"success": False, "error": str(e), "deleted": 0}


@router.post("/delete-by-doc")
async def delete_vectors_by_doc(req: DeleteByDocRequest):
    """删除指定文档的所有向量"""
    # doc_id 为 int，直接拼接表达式，无需转义
    try:
        deleted = delete_by_expr(f"doc_id == {req.doc_id}")
        return {"success": True, "deleted": deleted, "doc_id": req.doc_id}
    except Exception as e:
        return {"success": False, "error": str(e), "deleted": 0, "doc_id": req.doc_id}
