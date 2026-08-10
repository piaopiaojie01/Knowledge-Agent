"""Generator 工具调用/重试/事实提取测试（LLM 全部用 openai 风格替身）"""
import json
from types import SimpleNamespace

import pytest

import core.generator as cg
from config import settings
from core.generator import Generator, count_tokens


def _make_client(create_fn):
    return SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create_fn)))


def _resp(content=None, tool_calls=None):
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(content=content, tool_calls=tool_calls))])


def _tc(name, arguments, call_id="call_1"):
    return SimpleNamespace(id=call_id, function=SimpleNamespace(name=name, arguments=arguments))


@pytest.fixture
def gen(monkeypatch):
    # 有 key 才走真实 LLM 路径；同时把退避 sleep 打桩掉
    monkeypatch.setattr(settings, "deepseek_api_key", "test-key")
    monkeypatch.setattr(cg, "time", SimpleNamespace(sleep=lambda *_: None))
    return Generator()


def test_count_tokens():
    # 验证 token 估算：中文 1.5/字，英文 0.3/字
    assert count_tokens("你好abc") == int(2 * 1.5 + 3 * 0.3)


def test_generate_retry_then_success(gen):
    # 验证 LLM 调用失败重试，第 3 次成功后正常返回
    calls = []

    def create(**k):
        calls.append(k)
        if len(calls) < 3:
            raise RuntimeError(f"第{len(calls)}次失败")
        return _resp(content="重试后的答案")

    gen.client = _make_client(create)
    answer, _, _ = gen.generate("什么是机器学习", [])
    assert answer == "重试后的答案"
    assert len(calls) == 3


def test_generate_retry_exhausted_falls_back_mock(gen):
    # 验证 3 次重试全部失败后抛给外层，走 mock 兜底
    calls = []

    def create(**k):
        calls.append(k)
        raise RuntimeError("一直失败")

    gen.client = _make_client(create)
    answer, _, _ = gen.generate("你好", [])
    assert "未找到与" in answer  # _mock_generate 文案
    assert len(calls) == 3       # 恰好重试 3 次


def test_tool_execution_error_fed_back(gen, monkeypatch):
    # 验证工具执行异常被回喂给 LLM 而不是中断生成
    monkeypatch.setattr(cg, "execute_tool",
                        lambda n, a: (_ for _ in ()).throw(RuntimeError("boom")))
    calls = []

    def create(**k):
        calls.append(k)
        if len(calls) == 1:
            return _resp(tool_calls=[_tc("calculate", "{}")])
        return _resp(content="修正后的答案")

    gen.client = _make_client(create)
    answer, _, _ = gen.generate("查一下资料", [])
    assert answer == "修正后的答案"
    tool_msgs = [m for m in calls[1]["messages"] if m.get("role") == "tool"]
    assert any("执行失败" in m["content"] and "boom" in m["content"] for m in tool_msgs)


def test_tool_bad_json_args_fed_back(gen, monkeypatch):
    # 验证工具参数 JSON 解析失败回喂 LLM，且 execute_tool 不会被调用
    monkeypatch.setattr(cg, "execute_tool",
                        lambda n, a: (_ for _ in ()).throw(AssertionError("不应执行工具")))
    calls = []

    def create(**k):
        calls.append(k)
        if len(calls) == 1:
            return _resp(tool_calls=[_tc("some_tool", "{不是合法json")])
        return _resp(content="纠正参数后的答案")

    gen.client = _make_client(create)
    answer, _, _ = gen.generate("查一下资料", [])
    assert answer == "纠正参数后的答案"
    tool_msgs = [m for m in calls[1]["messages"] if m.get("role") == "tool"]
    assert any("工具参数 JSON 解析失败" in m["content"] for m in tool_msgs)


def test_tool_loop_exhausted_final_call_without_tools(gen, monkeypatch):
    # 验证 5 轮工具调用耗尽后，做最后一次不带 tools 的调用
    monkeypatch.setattr(cg, "execute_tool", lambda n, a: "ok")
    calls = []

    def create(**k):
        calls.append(k)
        if "tools" in k:
            return _resp(tool_calls=[_tc("some_tool", json.dumps({"x": 1}))])
        return _resp(content="工具耗尽后的最终答案")

    gen.client = _make_client(create)
    answer, _, _ = gen.generate("查一下资料", [])
    assert answer == "工具耗尽后的最终答案"
    assert len(calls) == 6                    # 5 轮带 tools + 1 次兜底
    assert all("tools" in c for c in calls[:5])
    assert "tools" not in calls[5]


def test_extract_facts_keeps_real_facts(gen):
    # 验证剥离行首编号、保留数字开头事实、整词"无"被过滤
    def create(**k):
        return _resp(content="1. 用户名张三\n2. 1985年出生\n无")

    gen.client = _make_client(create)
    facts = gen.extract_facts("我叫张三，1985年出生", "好的")
    assert facts == ["用户名张三", "1985年出生"]


def test_extract_facts_keeps_fact_containing_wu(gen):
    # 验证含"无"的正常事实不被误杀（只过滤整词"无"）
    gen.client = _make_client(lambda **k: _resp(content="用户喜欢无糖可乐"))
    facts = gen.extract_facts("我喜欢无糖可乐", "好的")
    assert facts == ["用户喜欢无糖可乐"]


def test_extract_facts_only_wu_returns_empty(gen):
    # 验证 LLM 只回"无"时返回空列表
    gen.client = _make_client(lambda **k: _resp(content="无"))
    assert gen.extract_facts("今天天气如何", "晴天") == []


def test_prepare_messages_gated_by_source_threshold(gen):
    # 验证 vector_score >= source_threshold 才注入参考资料，否则走常识回答
    high = [{"vector_score": 0.7, "title": "t", "content": "c", "kb_name": "kb"}]
    low = [{"vector_score": 0.5, "title": "t", "content": "c", "kb_name": "kb"}]
    _, _, _, msg_high = gen._prepare_messages("问题", high)
    _, _, _, msg_low = gen._prepare_messages("问题", low)
    assert "参考资料" in msg_high
    assert "未找到高质量参考资料" in msg_low


def test_generate_uses_llm_config_override(gen, monkeypatch):
    # 验证管理后台下发的 llm_config 覆盖模型名/接口/密钥/temperature/max_tokens
    calls = {}

    class FakeCompletions:
        def __init__(self):
            self.create = lambda **k: calls.update(k) or _resp(content="覆盖模型回答")

    class FakeChat:
        completions = FakeCompletions()

    class FakeOpenAI:
        def __init__(self, **kw):
            calls["client_kwargs"] = kw
            self.chat = FakeChat()

    monkeypatch.setattr(cg, "OpenAI", FakeOpenAI)
    cfg = SimpleNamespace(model="gpt-x", base_url="http://llm.local",
                          api_key="k-abc", temperature=0.1, max_tokens=100)

    answer, _, _ = gen.generate("你好", [], llm_config=cfg)

    assert answer == "覆盖模型回答"
    assert calls["model"] == "gpt-x"
    assert calls["temperature"] == 0.1
    assert calls["max_tokens"] == 100
    assert calls["client_kwargs"]["base_url"] == "http://llm.local"
    assert calls["client_kwargs"]["api_key"] == "k-abc"


def test_generate_captures_cache_usage(gen):
    # 验证从 DeepSeek usage 中提取缓存命中/未命中 token
    def create(**k):
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="ok", tool_calls=None))],
            usage=SimpleNamespace(prompt_cache_hit_tokens=120, prompt_cache_miss_tokens=80))

    gen.client = _make_client(create)
    gen.generate("你好", [])

    assert gen.last_cache_hit == 120
    assert gen.last_cache_miss == 80
