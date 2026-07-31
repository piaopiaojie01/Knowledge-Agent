"""文档入库脚本 - MySQL 文档 -> BGE-M3 向量化 -> Milvus 存储

用法:
    python scripts/ingest_documents.py              # 入库全部文档
    python scripts/ingest_documents.py --kb-id 1    # 只入库指定知识库
    python scripts/ingest_documents.py --dry-run    # 预览模式，不实际写入
"""
import argparse
import logging
import re
import sys
import os
from typing import List, Dict, Any

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pymysql
from config import settings
from embedding.bge_embedder import embedder
from store.milvus_client import milvus_client

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)

MYSQL_CONFIG = {
    "host": os.getenv("KA_MYSQL_HOST", "localhost"),
    "port": int(os.getenv("KA_MYSQL_PORT", "3306")),
    "user": os.getenv("KA_MYSQL_USER", "ka_user"),
    "password": os.getenv("KA_MYSQL_PASSWORD", "ka_pass_2024"),
    "database": os.getenv("KA_MYSQL_DATABASE", "knowledge_agent"),
    "charset": "utf8mb4",
}


def get_mysql_connection():
    return pymysql.connect(**MYSQL_CONFIG)


def fetch_documents(kb_id: int | None = None) -> List[Dict[str, Any]]:
    conn = get_mysql_connection()
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cursor:
            if kb_id:
                sql = """
                    SELECT d.id, d.kb_id, d.title, d.content, d.file_type,
                           kb.name AS kb_name
                    FROM documents d
                    JOIN knowledge_bases kb ON d.kb_id = kb.id
                    WHERE d.doc_status = 'ACTIVE' AND d.kb_id = %s
                    ORDER BY d.id
                """
                cursor.execute(sql, (kb_id,))
            else:
                sql = """
                    SELECT d.id, d.kb_id, d.title, d.content, d.file_type,
                           kb.name AS kb_name
                    FROM documents d
                    JOIN knowledge_bases kb ON d.kb_id = kb.id
                    WHERE d.doc_status = 'ACTIVE'
                    ORDER BY d.id
                """
                cursor.execute(sql)
            return cursor.fetchall()
    finally:
        conn.close()


def update_chunk_count(doc_id: int, chunk_count: int):
    conn = get_mysql_connection()
    try:
        with conn.cursor() as cursor:
            cursor.execute(
                "UPDATE documents SET chunk_count = %s WHERE id = %s",
                (chunk_count, doc_id),
            )
        conn.commit()
        logger.info(f"  文档 ID={doc_id} chunk_count 更新为 {chunk_count}")
    finally:
        conn.close()


def chunk_text(text: str, chunk_size: int = 512, overlap: int = 64) -> List[str]:
    if not text or not text.strip():
        return []
    paragraphs = re.split(r"\n\s*\n", text)
    paragraphs = [p.strip() for p in paragraphs if p.strip()]
    chunks = []
    for para in paragraphs:
        if len(para) <= chunk_size:
            chunks.append(para)
        else:
            sentences = re.split(r"(?<=[。！？；\n])", para)
            current = ""
            for sent in sentences:
                if len(current) + len(sent) <= chunk_size:
                    current += sent
                else:
                    if current.strip():
                        chunks.append(current.strip())
                    if len(sent) > chunk_size:
                        for i in range(0, len(sent), chunk_size - overlap):
                            chunks.append(sent[i : i + chunk_size].strip())
                        current = ""
                    else:
                        if current and len(current) >= overlap:
                            current = current[-overlap:] + sent
                        else:
                            current = sent
            if current.strip():
                chunks.append(current.strip())
    return chunks


def clear_collection():
    """清空 Milvus Collection（重新入库时使用）"""
    from pymilvus import Collection, utility

    col_name = settings.milvus_collection_name
    alias = getattr(milvus_client, "_alias", "default")
    if utility.has_collection(col_name, using=alias):
        col = Collection(col_name, using=alias)
        col.drop()
        logger.info(f"已删除 Collection: {col_name}")

    milvus_client._collection = None
    milvus_client.ensure_collection()
    logger.info(f"已重建 Collection: {col_name}")


def ingest_documents(kb_id: int | None = None, dry_run: bool = False, clear_first: bool = False):
    logger.info("=" * 60)
    logger.info("文档入库开始")
    logger.info(f"  知识库ID: {kb_id if kb_id else '全部'}")
    logger.info(f"  模式: {'预览模式 (dry-run)' if dry_run else '正式入库'}")
    logger.info("=" * 60)

    if not milvus_client.is_connected:
        logger.info("正在连接 Milvus...")
        if not milvus_client.connect():
            logger.error("Milvus 连接失败，请确认 Docker 容器已启动")
            return

    logger.info(f"正在加载模型: {settings.embedding_model}...")
    _ = embedder.model
    logger.info(f"模型加载完成, 维度={embedder.get_dimension()}")

    if clear_first and not dry_run:
        clear_collection()

    documents = fetch_documents(kb_id)
    logger.info(f"从 MySQL 读取到 {len(documents)} 篇文档")

    if not documents:
        logger.warning("没有找到待入库文档")
        return

    total_chunks = 0
    total_inserted = 0

    for doc in documents:
        doc_id = doc["id"]
        title = doc["title"]
        content = doc["content"] or ""
        kb_name = doc["kb_name"]

        logger.info(f"\n处理文档: [{doc_id}] {title} (知识库: {kb_name})")
        chunks = chunk_text(content, settings.chunk_size, settings.chunk_overlap)
        if not chunks:
            logger.warning(f"  文档 [{doc_id}] 无有效内容，跳过")
            continue

        logger.info(f"  分块: {len(chunks)} 块")
        total_chunks += len(chunks)

        if dry_run:
            for i, chunk in enumerate(chunks):
                logger.info(f"    [预览] 块 {i+1}: {chunk[:80]}...")
            continue

        logger.info(f"  正在向量化 {len(chunks)} 个文本块...")
        embeddings = embedder.encode_documents(chunks)

        insert_data = []
        for i, chunk in enumerate(chunks):
            insert_data.append({
                "doc_id": doc_id,
                "kb_name": kb_name,
                "title": title,
                "content": chunk,
                "embedding": embeddings[i].tolist(),
            })

        try:
            count = milvus_client.insert(insert_data)
            total_inserted += count
            logger.info(f"  已写入 Milvus: {count} 条向量")
            update_chunk_count(doc_id, len(chunks))
        except Exception as e:
            logger.error(f"  写入 Milvus 失败: {e}")

    if not dry_run and total_inserted > 0:
        milvus_client.create_index_if_needed()

    logger.info("\n" + "=" * 60)
    logger.info("文档入库完成")
    logger.info(f"  文档数: {len(documents)}")
    logger.info(f"  总块数: {total_chunks}")
    logger.info(f"  写入数: {total_inserted}")
    logger.info("=" * 60)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Knowledge Agent 文档入库工具")
    parser.add_argument("--kb-id", type=int, default=None, help="只入库指定知识库ID")
    parser.add_argument("--dry-run", action="store_true", help="预览模式，不实际写入")
    parser.add_argument("--clear", action="store_true", help="清空 Milvus Collection 后重新入库")
    args = parser.parse_args()

    ingest_documents(kb_id=args.kb_id, dry_run=args.dry_run, clear_first=args.clear)
