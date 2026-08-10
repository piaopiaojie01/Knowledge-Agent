"""RAG API 路由"""
import logging
import asyncio
import json
from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from models.schemas import (
    RagQueryRequest,
    RagSearchRequest,
    RagQueryResponse,
    RagSearchResponse,
    HealthResponse,
)
from core.retriever import retriever
from core.reranker import reranker
from core.generator import generator, count_tokens
from core.orchestrator import orchestrator
from core.query_processor import query_processor
from core.memory import memory as long_term_memory
from store.milvus_client import milvus_client
from embedding.bge_embedder import embedder
from config import settings
from core.observability import llm_tokens, rag_query_seconds, rag_requests

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/rag", tags=["RAG"])

# 后台任务强引用集合，防止事件循环 GC 回收未完成的 create_task
_background_tasks: set = set()


def _retrieve_merged(question: str, kb_names) -> list:
    """查询扩展检索（同步，需用 asyncio.to_thread 调用）：
    原问题 + 最多 2 个扩展变体分别检索，按 (doc_id, title, 内容前100字符) 去重合并，每条保留最高 score
    """
    expanded_queries = query_processor.expand_query(question)[:3]
    logger.info(f"查询预处理: '{question}' -> {len(expanded_queries)} 个变体")

    merged: dict[tuple, dict] = {}
    for q in expanded_queries:
        for doc in retriever.retrieve(query=q, kb_names=kb_names):
            # 去重键含 doc_id 与 title，避免不同文档以相同页标题/空内容开头时误合并
            key = (doc.get("doc_id") or doc.get("id"),
                   doc.get("title", ""), doc.get("content", "")[:100])
            if key not in merged or doc.get("score", 0) > merged[key].get("score", 0):
                merged[key] = doc
    docs = list(merged.values())
    logger.info(f"检索合并: {len(docs)} 条（去重后）")
    return docs


def _build_sources(reranked_docs: list) -> list:
    """构建 sources（只返回有效引用的来源，vector_score 最高分 >= source_threshold）"""
    best_score = max((d.get("vector_score", d.get("score", 0)) for d in reranked_docs), default=0)
    if best_score >= settings.source_threshold and reranked_docs:
        return [
            {
                "title": doc.get("title", ""),
                "content": doc.get("content", "")[:500],
                "score": doc.get("score", 0),
                "kb_name": doc.get("kb_name", ""),
            }
            for doc in reranked_docs
        ]
    return []


@router.post("/query", response_model=RagQueryResponse)
async def rag_query(req: RagQueryRequest):
    """
    完整 RAG 查询流程：
    1. 查询预处理
    2. 向量检索 (BGE-M3 -> Milvus)
    3. 重排序
    4. LLM 生成回答 (DeepSeek)
    5. 提取长期记忆
    """
    import time as _time
    _t0 = _time.monotonic()
    try:
        sid = req.session_id or "default"
        # 同步文件 I/O 挪到线程，避免阻塞事件循环
        ltm_context = await asyncio.to_thread(long_term_memory.as_context, sid)

        # 1+2. 查询预处理 + 扩展检索（原问题 + 变体分别检索，去重合并）
        total_in_kb = await asyncio.to_thread(lambda: milvus_client.get_collection().num_entities)
        docs = await asyncio.to_thread(_retrieve_merged, req.question, req.kb_names)

        # 3. 重排序
        reranked_docs = await asyncio.to_thread(reranker.rerank, req.question, docs)
        best_retrieved = max((d.get("score", 0) for d in docs), default=0)

        # 4. 生成回答（含长期记忆上下文）
        history = [h.model_dump() for h in req.history] if req.history else None
        kb_mode = bool(req.kb_names)
        answer, input_tokens, output_tokens = await asyncio.to_thread(
            generator.generate, req.question, reranked_docs, history, ltm_context,
            False, req.llm_config, req.skills, req.mcp_servers, kb_mode)

        # 5. 异步提取长期记忆（不阻塞响应）
        def _extract():
            try:
                facts = generator.extract_facts(req.question, answer)
                if facts:
                    long_term_memory.update(sid, facts)
                    logger.info(f"长期记忆更新 [{sid}]: +{len(facts)} 条")
            except Exception as e:
                logger.warning(f"记忆提取失败: {e}")
        # 持强引用防止任务被 GC 提前回收
        task = asyncio.create_task(asyncio.to_thread(_extract))
        _background_tasks.add(task)
        task.add_done_callback(_background_tasks.discard)

        # 构建 sources（只返回有效引用的来源）
        sources = _build_sources(reranked_docs)

        metrics = {"total_in_kb": total_in_kb, "retrieved": len(docs), "after_rerank": len(reranked_docs), "best_score": round(best_retrieved, 4), "min_threshold": settings.min_score, "source_threshold": settings.source_threshold}
        rag_query_seconds.observe(_time.monotonic() - _t0)
        rag_requests.labels(result="success").inc()
        llm_tokens.labels(type="input").inc(input_tokens)
        llm_tokens.labels(type="output").inc(output_tokens)
        return RagQueryResponse(
            answer=answer, sources=sources, metrics=metrics,
            input_tokens=input_tokens, output_tokens=output_tokens,
            cache_hit_tokens=generator.last_cache_hit,
            cache_miss_tokens=generator.last_cache_miss)

    except Exception as e:
        rag_query_seconds.observe(_time.monotonic() - _t0)
        rag_requests.labels(result="error").inc()
        logger.error(f"RAG 查询失败: {e}", exc_info=True)
        # P0：不向客户端透传内部异常细节，完整堆栈留在服务端日志
        raise HTTPException(status_code=500, detail="RAG 查询失败，请稍后重试")


@router.post("/query/stream")
async def rag_query_stream(req: RagQueryRequest):
    """
    SSE 流式 RAG 问答：
    - 检索 + 重排序完成后返回 StreamingResponse (text/event-stream)
    - 事件格式：
      data: {"type":"delta","text":"..."}   每个文本增量一行
      data: {"type":"final","sources":[...],"input_tokens":N,"output_tokens":N,"metrics":{...}}
      data: {"type":"error","message":"..."}  异常时
      data: [DONE]                           结束标记
    """
    sid = req.session_id or "default"
    # 同步文件 I/O 挪到线程，避免阻塞事件循环
    ltm_context = await asyncio.to_thread(long_term_memory.as_context, sid)
    history = [h.model_dump() for h in req.history] if req.history else None

    try:
        total_in_kb = await asyncio.to_thread(lambda: milvus_client.get_collection().num_entities)
        docs = await asyncio.to_thread(_retrieve_merged, req.question, req.kb_names)
        reranked_docs = await asyncio.to_thread(reranker.rerank, req.question, docs)
    except Exception as e:
        logger.error(f"流式查询检索失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="RAG 查询失败，请稍后重试")

    best_retrieved = max((d.get("score", 0) for d in docs), default=0)
    sources = _build_sources(reranked_docs)
    metrics = {"total_in_kb": total_in_kb, "retrieved": len(docs), "after_rerank": len(reranked_docs), "best_score": round(best_retrieved, 4), "min_threshold": settings.min_score, "source_threshold": settings.source_threshold}

    def event_stream():
        full_answer = ""
        try:
            for delta in generator.generate_stream(
                    req.question, reranked_docs, history, ltm_context,
                    req.llm_config, req.skills, req.mcp_servers, bool(req.kb_names)):
                full_answer += delta
                yield f"data: {json.dumps({'type': 'delta', 'text': delta}, ensure_ascii=False)}\n\n"
            # 与非流式同口径：generate_stream 内部按全量 messages 统计（history 已含其中，勿重复加）
            input_tokens = generator.last_input_tokens
            output_tokens = count_tokens(full_answer)
            final = {"type": "final", "sources": sources,
                     "input_tokens": input_tokens, "output_tokens": output_tokens,
                     "cache_hit_tokens": generator.last_cache_hit,
                     "cache_miss_tokens": generator.last_cache_miss,
                     "metrics": metrics}
            yield f"data: {json.dumps(final, ensure_ascii=False)}\n\n"
        except Exception as e:
            logger.error(f"流式生成失败: {e}", exc_info=True)
            yield f"data: {json.dumps({'type': 'error', 'message': str(e)}, ensure_ascii=False)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")


@router.post("/search", response_model=RagSearchResponse)
async def rag_search(req: RagSearchRequest):
    """
    纯检索接口（不生成回答）：
    1. BGE-M3 向量化
    2. Milvus 向量检索
    3. 重排序
    """
    try:
        total_in_kb = await asyncio.to_thread(lambda: milvus_client.get_collection().num_entities)
        docs = await asyncio.to_thread(
            retriever.retrieve,
            query=req.question,
            kb_names=req.kb_names,
            top_k=req.top_k,
        )

        reranked_docs = await asyncio.to_thread(reranker.rerank, req.question, docs)

        results = [
            {
                "title": doc.get("title", ""),
                "content": doc.get("content", ""),
                "score": doc.get("score", 0),
                "kb_name": doc.get("kb_name", ""),
            }
            for doc in reranked_docs
        ]

        return RagSearchResponse(results=results)

    except Exception as e:
        logger.error(f"检索失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="检索失败，请稍后重试")


@router.post("/query/orchestrated", response_model=RagQueryResponse)
async def orchestrated_query(req: RagQueryRequest):
    """
    多智能体编排查询流程：
    1. Dispatcher → 分析意图
    2. Retriever/Gatherer → 收集信息
    3. Generator → 生成最终回答
    """
    try:
        sid = req.session_id or "default"
        # 同步文件 I/O 挪到线程，避免阻塞事件循环
        ltm_context = await asyncio.to_thread(long_term_memory.as_context, sid)
        history = [h.model_dump() for h in req.history] if req.history else None

        # Step 1: Dispatch
        intent = await asyncio.to_thread(orchestrator.dispatch, req.question, req.llm_config)
        logger.info(f"编排意图: {intent}")

        # Step 2: Search if needed
        docs = []
        if intent.get("intent") in ("search", "skill", "doc"):
            docs = await asyncio.to_thread(retriever.retrieve, query=req.question, kb_names=req.kb_names)
            docs = await asyncio.to_thread(reranker.rerank, req.question, docs)

        # Step 3: Gather context (retriever may suggest tool use)
        gather_result = await asyncio.to_thread(orchestrator.retrieve, req.question, docs, req.llm_config)
        context = gather_result.get("context", "无相关资料")

        # If retriever suggests tool use, execute the tool
        if gather_result.get("need_tool"):
            from core.skills import execute_tool
            tool_name = gather_result.get("tool_name", "")
            tool_args = gather_result.get("tool_args", {})
            if tool_name:
                # Normalize args: only join lists, keep native types for scalars
                norm_args = {}
                for k, v in tool_args.items():
                    norm_args[k] = ",".join(str(x) for x in v) if isinstance(v, list) else v
                tool_result = await asyncio.to_thread(execute_tool, tool_name, norm_args)
                context = context + "\n\n[工具输出]\n" + tool_result

        # Step 4: Generate final answer
        answer = await asyncio.to_thread(
            orchestrator.generate,
            query=req.question, context=context,
            history=history, long_term_memory=ltm_context, llm_config=req.llm_config
        )

        input_tokens = sum(count_tokens(m.get("content", "")) for m in (history or []))
        output_tokens = count_tokens(answer) if answer else 0

        return RagQueryResponse(
            answer=answer, sources=[], metrics={"orchestrated": True, "intent": intent.get("intent")},
            input_tokens=input_tokens, output_tokens=output_tokens
        )
    except Exception as e:
        logger.error(f"Orchestrated query failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="查询失败，请稍后重试")


@router.get("/health", response_model=HealthResponse)
async def health_check():
    """健康检查"""
    return HealthResponse(
        status="ok",
        version=settings.app_version,
        milvus_connected=milvus_client.is_connected,
        embedding_loaded=embedder.is_loaded,
    )
