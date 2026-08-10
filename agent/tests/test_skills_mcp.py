"""技能过滤与 MCP 工具管理测试"""
import json
from types import SimpleNamespace

import pytest

import core.generator as cg
import core.skills as sk
from config import settings
from core.generator import Generator
from core.mcp_manager import McpManager, _field


@pytest.fixture
def gen(monkeypatch):
    monkeypatch.setattr(settings, "deepseek_api_key", "test-key")
    return Generator()


# ── 技能过滤 ──


def test_build_tools_filters_by_enabled_names():
    tools = sk.build_tools(["calculate", "get_current_time"], None)
    names = [t["function"]["name"] for t in tools]
    assert set(names) == {"calculate", "get_current_time"}


def test_build_tools_empty_disables_all_builtins():
    assert sk.build_tools([], None) == []


def test_build_tools_none_keeps_all_builtins():
    assert len(sk.build_tools(None, None)) == len(sk.TOOLS)


def test_is_builtin_tool():
    assert sk.is_builtin_tool("calculate")
    assert not sk.is_builtin_tool("mcp__whatever")


# ── MCP 管理器 ──


def test_mcp_field_dict_and_object():
    assert _field({"name": "a"}, "name") == "a"
    assert _field(SimpleNamespace(name="b"), "name") == "b"
    assert _field({}, "missing", "x") == "x"


def test_mcp_manager_call_unknown_tool_returns_error():
    m = McpManager()
    m.configure([{"name": "srv", "url": "http://x"}])
    out = m.call_tool("srv__nope", {})
    assert "未知 MCP 工具" in out


def test_mcp_manager_configure_dict_and_object():
    m = McpManager()
    m.configure([{"name": "a", "url": "http://a"}, SimpleNamespace(name="b", url="http://b")])
    assert m._server_configs == {"a": "http://a", "b": "http://b"}


# ── 生成器按配置构建工具并路由 MCP 调用 ──


def test_generate_uses_filtered_tools(gen):
    calls = {}

    def create(**k):
        calls.update(k)
        return SimpleNamespace(choices=[SimpleNamespace(
            message=SimpleNamespace(content="ok", tool_calls=None))])

    gen.client = SimpleNamespace(
        chat=SimpleNamespace(completions=SimpleNamespace(create=create)))

    answer, _, _ = gen.generate("你好", [], skill_names=["calculate"])

    assert answer == "ok"
    names = [t["function"]["name"] for t in calls["tools"]]
    assert names == ["calculate"]


def test_generate_dispatches_mcp_tool(gen, monkeypatch):
    fake_mcp = SimpleNamespace(
        list_tools=lambda servers: [{
            "type": "function",
            "function": {
                "name": "srv__weather",
                "description": "查询天气",
                "parameters": {"type": "object", "properties": {}},
            },
        }],
        call_tool=lambda name, args: "MCP 天气结果：晴")
    monkeypatch.setattr("core.mcp_manager.mcp_manager", fake_mcp)

    creates = []

    def create(**k):
        creates.append(k)
        if len(creates) == 1:
            tc = SimpleNamespace(
                id="c1",
                function=SimpleNamespace(
                    name="srv__weather",
                    arguments=json.dumps({"city": "上海"})))
            return SimpleNamespace(choices=[SimpleNamespace(
                message=SimpleNamespace(content=None, tool_calls=[tc]))])
        return SimpleNamespace(choices=[SimpleNamespace(
            message=SimpleNamespace(content="最终回答", tool_calls=None))])

    gen.client = SimpleNamespace(
        chat=SimpleNamespace(completions=SimpleNamespace(create=create)))

    answer, _, _ = gen.generate(
        "上海天气怎么样", [], mcp_servers=[{"name": "srv", "url": "http://x"}])

    assert answer == "最终回答"
    tool_msgs = [m for m in creates[1]["messages"] if m.get("role") == "tool"]
    assert any("MCP 天气结果" in m["content"] for m in tool_msgs)
