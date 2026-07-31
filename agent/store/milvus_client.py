"""Milvus 向量库客户端"""
import logging
from pymilvus import connections, Collection, CollectionSchema, FieldSchema, DataType, utility
from config import settings

logger = logging.getLogger(__name__)

COLLECTION_NAME = settings.milvus_collection_name
DIM = settings.embedding_dim


def _create_schema() -> CollectionSchema:
    return CollectionSchema([
        FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
        FieldSchema(name="doc_id", dtype=DataType.INT64),
        FieldSchema(name="kb_name", dtype=DataType.VARCHAR, max_length=256),
        FieldSchema(name="title", dtype=DataType.VARCHAR, max_length=512),
        FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=4096),
        FieldSchema(name="source_content", dtype=DataType.VARCHAR, max_length=4096),
        FieldSchema(name="keywords", dtype=DataType.VARCHAR, max_length=512),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=DIM),
    ], description="KA docs")


class MilvusClient:

    def __init__(self):
        self._connected = False
        self._collection: Collection | None = None

    def connect(self) -> bool:
        try:
            alias = f"ka_{id(self)}"
            connections.connect(alias=alias, host=settings.milvus_host, port=settings.milvus_port)
            self._alias = alias
            self._connected = True
            logger.info(f"Milvus connected [{alias}]")
            return True
        except Exception as e:
            logger.error(f"Milvus connect failed: {e}")
            return False

    @property
    def is_connected(self) -> bool:
        return self._connected

    def ensure_collection(self) -> Collection:
        if not self._connected:
            raise RuntimeError("not connected")
        if utility.has_collection(COLLECTION_NAME, using=self._alias):
            self._collection = Collection(COLLECTION_NAME, using=self._alias)
        else:
            self._collection = Collection(COLLECTION_NAME, _create_schema(), using=self._alias)
            logger.info(f"Collection created: {COLLECTION_NAME}")
        return self._collection

    def get_collection(self) -> Collection:
        if self._collection is None:
            return self.ensure_collection()
        return self._collection

    def insert(self, rows: list[dict]) -> int:
        if not rows: return 0
        col = self.get_collection()
        fields = ["doc_id","kb_name","title","content","source_content","keywords","embedding"]
        values = [[r.get(k, "") for r in rows] for k in fields]
        mr = col.insert(values)
        col.flush()
        return mr.insert_count

    def create_index_if_needed(self, col=None) -> bool:
        if col is None:
            col = self.get_collection()
        try:
            col.load(); return True
        except Exception:
            pass
        try:
            col.create_index(field_name="embedding",
                             index_params={"metric_type":"IP","index_type":"IVF_FLAT","params":{"nlist":128}})
            col.load()
        except Exception:
            pass
        return True

    def _ensure_ready(self):
        try:
            col = self.get_collection()
            col.load()
            return col
        except Exception:
            logger.warning("Collection stale, reconnecting...")
            try: connections.disconnect(self._alias)
            except: pass
            self._connected = False
            self._collection = None
            self.connect()
            col = self.ensure_collection()
            self.create_index_if_needed(col)
            return col

    def search(self, query_vector: list[float], top_k: int = 5,
               kb_names: list[str] | None = None) -> list[dict]:
        self._ensure_ready()
        col = Collection(COLLECTION_NAME, using=self._alias)
        if col.is_empty:
            return []
        results = col.search(data=[query_vector], anns_field="embedding",
                             param={"metric_type":"IP","params":{"nprobe":16}},
                             limit=top_k * 2 if kb_names else top_k,
                             output_fields=["doc_id","kb_name","title","content","source_content","keywords"])
        hits = []
        for results_list in results:
            for h in results_list:
                e = h.entity
                # pymilvus 3.0 Entity.get() 只接受一个参数，逐字段安全取值
                def _safe(field: str) -> str:
                    try:
                        v = e.get(field)
                        return v if v is not None else ""
                    except Exception:
                        return ""
                hits.append({"doc_id": _safe("doc_id"), "kb_name": _safe("kb_name"),
                             "title": _safe("title"), "content": _safe("content"),
                             "source_content": _safe("source_content"),
                             "keywords": _safe("keywords"),
                             "score": float(h.score)})
        if kb_names:
            kb_set = set(kb_names)
            hits = [h for h in hits if h["kb_name"] in kb_set]
        return hits[:top_k]

    def close(self):
        if self._connected:
            try: connections.disconnect(self._alias)
            except: pass
            self._connected = False


milvus_client = MilvusClient()
