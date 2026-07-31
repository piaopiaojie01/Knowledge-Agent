"""BGE-M3 Embedding 模块"""
import numpy as np
from sentence_transformers import SentenceTransformer
from config import settings


class BGEEmbedder:
    """BGE-M3 向量化模型封装"""

    def __init__(self):
        self.model_name = settings.embedding_model
        self.device = settings.embedding_device
        self.dim = settings.embedding_dim
        self._model: SentenceTransformer | None = None

    @property
    def model(self) -> SentenceTransformer:
        if self._model is None:
            self._model = SentenceTransformer(
                self.model_name,
                device=self.device
            )
        return self._model

    @property
    def is_loaded(self) -> bool:
        return self._model is not None

    def encode(self, texts: list[str]) -> np.ndarray:
        """将文本列表转为向量数组，返回 shape=(n, dim) 的 float32 ndarray"""
        embeddings = self.model.encode(
            texts,
            normalize_embeddings=True,
            show_progress_bar=False
        )
        return np.array(embeddings, dtype=np.float32)

    # BGE 系列模型标准查询指令（BAAI 官方推荐）
    QUERY_INSTRUCTION = "Represent this sentence for searching relevant passages:"

    def encode_query(self, query: str) -> np.ndarray:
        """编码查询文本（BGE 模型查询需添加 instruction prefix）"""
        text = f"{self.QUERY_INSTRUCTION} {query}"
        return self.encode([text])[0]

    def encode_documents(self, texts: list[str]) -> np.ndarray:
        """编码文档文本"""
        return self.encode(texts)

    def get_dimension(self) -> int:
        return self.dim


# 全局单例
embedder = BGEEmbedder()
