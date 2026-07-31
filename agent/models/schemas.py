"""Pydantic 数据模型"""
from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any


class HistoryMessage(BaseModel):
    role: str = Field(..., description="角色: user/assistant")
    content: str = Field(..., description="消息内容")


class RagQueryRequest(BaseModel):
    question: str = Field(..., description="用户问题")
    kb_names: Optional[List[str]] = Field(default=None, description="知识库名称列表，为空则查全部")
    history: Optional[List[HistoryMessage]] = Field(default=None, description="最近对话历史")
    session_id: Optional[str] = Field(default=None, description="会话ID，用于长期记忆")


class RagSearchRequest(BaseModel):
    question: str = Field(..., description="查询文本")
    kb_names: Optional[List[str]] = Field(default=None, description="知识库名称列表")
    top_k: int = Field(default=5, description="返回结果数")


class SourceDocument(BaseModel):
    title: str = Field(..., description="文档标题")
    content: str = Field(..., description="文档片段内容")
    score: float = Field(..., description="相似度分数")
    kb_name: str = Field(default="", description="所属知识库")


class RagQueryResponse(BaseModel):
    answer: str = Field(..., description="生成的回答")
    sources: List[Dict[str, Any]] = Field(default_factory=list, description="参考来源")
    metrics: Optional[Dict[str, Any]] = Field(default=None, description="检索指标")
    input_tokens: int = 0
    output_tokens: int = 0


class RagSearchResponse(BaseModel):
    results: List[Dict[str, Any]] = Field(default_factory=list, description="检索结果")


class HealthResponse(BaseModel):
    status: str = "ok"
    version: str
    milvus_connected: bool = False
    embedding_loaded: bool = False
