"""MCP（Model Context Protocol）工具管理器

通过官方 mcp SDK 连接管理后台配置的 MCP 服务器（Streamable HTTP / SSE），
发现并缓存工具定义，供 LLM function calling 调用；工具名按 server__tool 命名避免冲突。
"""
import asyncio
import logging
import time
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

try:
    from mcp import ClientSession
    try:
        from mcp.client.streamable_http import streamable_http_client as _STREAMABLE_FACTORY
    except ImportError:  # mcp 1.x 旧命名
        from mcp.client.streamable_http import streamablehttp_client as _STREAMABLE_FACTORY
    MCP_SDK_AVAILABLE = True
except Exception as e:  # pragma: no cover - 依赖缺失时优雅降级
    MCP_SDK_AVAILABLE = False
    logger.warning("mcp SDK 不可用，MCP 工具将被禁用: %s", e)


async def _open_stream_session(url):
    """兼容 mcp 1.x（返回三元组）与 2.x（返回 TransportStreams）的会话上下文"""
    async with _STREAMABLE_FACTORY(url) as streams:
        if isinstance(streams, (tuple, list)):
            read, write = streams[0], streams[1]
        else:
            read, write = streams.read_stream, streams.write_stream
        async with ClientSession(read, write) as session:
            yield session


def _field(obj, key, default=None):
    """兼容 pydantic 对象与 dict 的取字段"""
    if isinstance(obj, dict):
        return obj.get(key, default)
    return getattr(obj, key, default)


class McpManager:
    """按服务器缓存工具清单（TTL 60s），连接/调用失败不阻塞主流程"""

    def __init__(self, cache_ttl: float = 60.0):
        self._cache_ttl = cache_ttl
        self._tools: Dict[str, Dict[str, Any]] = {}
        self._server_configs: Dict[str, str] = {}
        self._synced_at: float = 0.0

    def configure(self, servers: Optional[List[Any]] = None) -> None:
        cfg = {}
        for s in servers or []:
            name = _field(s, "name")
            url = _field(s, "url")
            if name and url:
                cfg[str(name)] = str(url)
        self._server_configs = cfg

    def list_tools(self, servers: Optional[List[Any]] = None) -> List[Dict[str, Any]]:
        """返回 MCP 工具定义（OpenAI function calling 格式）；失败或未装 SDK 时返回 []"""
        if not MCP_SDK_AVAILABLE:
            return []
        self.configure(servers)
        if not self._server_configs:
            self._tools = {}
            return []
        if self._tools and (time.time() - self._synced_at) < self._cache_ttl:
            return list(self._tools.values())
        try:
            tools = self._run_async(self._discover())
            self._tools = tools
            self._synced_at = time.time()
            return list(tools.values())
        except Exception as e:
            logger.warning("MCP 工具发现失败: %s", e)
            return list(self._tools.values())

    def call_tool(self, tool_name: str, arguments: Dict[str, Any]) -> str:
        """调用 MCP 工具（按 server__tool 命名路由）"""
        info = self._tools.get(tool_name)
        if not info:
            return f"错误：未知 MCP 工具 {tool_name}"
        server = info["server"]
        url = self._server_configs.get(server, "")
        try:
            return self._run_async(self._call(server, url, info["name"], arguments or {}))
        except Exception as e:
            logger.warning("MCP 工具调用失败 %s: %s", tool_name, e)
            return f"错误：MCP 工具 {tool_name} 调用失败({e})"

    @staticmethod
    def _run_async(coro):
        """在非事件循环线程中执行；事件循环线程内调用时优雅降级"""
        try:
            return asyncio.run(coro)
        except RuntimeError:
            logger.warning("MCP 调用发生在事件循环线程，跳过（流式场景 MCP 工具暂不可用）")
            raise

    async def _discover(self) -> Dict[str, Dict[str, Any]]:
        tools: Dict[str, Dict[str, Any]] = {}
        for name, url in self._server_configs.items():
            try:
                async for session in _open_stream_session(url):
                    await session.initialize()
                    listed = await session.list_tools()
                    for t in listed.tools:
                        key = f"{name}__{t.name}"
                        tools[key] = {
                            "server": name,
                            "name": t.name,
                            "definition": {
                                "type": "function",
                                "function": {
                                    "name": key,
                                    "description": t.description or f"MCP 工具 {name}/{t.name}",
                                    "parameters": t.inputSchema
                                    or {"type": "object", "properties": {}},
                                },
                            },
                        }
            except Exception as e:
                logger.warning("连接 MCP 服务器 %s(%s) 失败: %s", name, url, e)
        return tools

    async def _call(self, server: str, url: str, tool: str, args: Dict[str, Any]) -> str:
        async for session in _open_stream_session(url):
            await session.initialize()
            res = await session.call_tool(tool, arguments=args)
            if res.isError:
                return f"错误：{res.content}"
            parts = [c.text for c in res.content if getattr(c, "type", "") == "text"]
            return "\n".join(parts) if parts else str(res.content)
        return "错误：MCP 会话未建立"


mcp_manager = McpManager()
