"""Generator 图表相关测试：触发词、_force_chart 数据提取、generate_stream 流式行为"""
from types import SimpleNamespace

import pytest

import core.generator as cg
import core.skills as skills
from config import settings
from core.generator import Generator, _is_chart_request, _force_chart


def _make_client(create_fn):
    """构造 openai 风格 client 替身：client.chat.completions.create"""
    return SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create_fn)))


def _chunk(text):
    return SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content=text))])


def _tc_chunk(index, id=None, name=None, arguments=None):
    fn = None
    if name is not None or arguments is not None:
        fn = SimpleNamespace(name=name, arguments=arguments)
    return SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(
        content=None, tool_calls=[SimpleNamespace(index=index, id=id, function=fn)]))])


@pytest.fixture
def api_key(monkeypatch):
    # 有 key 才走真实 LLM 路径（client 已被替身替换，不会真发请求）
    monkeypatch.setattr(settings, "deepseek_api_key", "test-key")


def test_is_chart_request_hit():
    # 验证画图触发词命中（含"饼状图"）
    assert _is_chart_request("帮我画个饼状图看看占比") is True
    assert _is_chart_request("用 bar chart 展示一下") is True


def test_is_chart_request_miss():
    # 验证普通提问不触发画图（"这张图说明了什么"不含任何触发词）
    assert _is_chart_request("这张图说明了什么") is False
    assert _is_chart_request("今天天气怎么样") is False


def _capture_make_chart(monkeypatch):
    """替换 skills._make_chart，捕获参数并返回假图表 HTML"""
    captured = {}

    def fake(chart_type, labels, data, title, unit):
        captured.update(chart_type=chart_type, labels=labels, data=data, unit=unit)
        return "<img src='fake'>"

    monkeypatch.setattr(skills, "_make_chart", fake)
    return captured


def test_force_chart_pie_type(monkeypatch):
    # 验证含"饼"时图表类型为 pie，且正确提取标签/数值
    captured = _capture_make_chart(monkeypatch)
    html = _force_chart("画个饼图 北京300 上海500")
    assert html == "<img src='fake'>"
    assert captured["chart_type"] == "pie"
    assert captured["labels"] == "北京,上海"
    assert captured["data"] == "300,500"


def test_force_chart_line_and_default_bar(monkeypatch):
    # 验证含"折/线"为 line，否则默认 bar
    captured = _capture_make_chart(monkeypatch)
    _force_chart("画个折线图 一月10 二月20")
    assert captured["chart_type"] == "line"
    _force_chart("画个柱状图 苹果100 香蕉200")
    assert captured["chart_type"] == "bar"


def test_force_chart_garbage_labels_filtered(monkeypatch):
    # 验证"大约/左右"等虚词标签被过滤，只留真实名称
    captured = _capture_make_chart(monkeypatch)
    html = _force_chart("画个图 大约100 左右200 北京300 上海400")
    assert html == "<img src='fake'>"
    assert captured["labels"] == "北京,上海"


def test_force_chart_insufficient_data_returns_empty(monkeypatch):
    # 验证有效数据不足 2 条时返回空（交 LLM tool calling）
    _capture_make_chart(monkeypatch)
    assert _force_chart("画个图 北京300") == ""
    assert _force_chart("画个图但没有数字") == ""


def test_stream_reraise_after_partial_output(api_key):
    # 验证已产出 delta 后流中断要 re-raise（交给路由层发 error 事件）
    def gen():
        yield _chunk("你好")
        raise RuntimeError("模拟断流")

    g = Generator()
    g.client = _make_client(lambda **k: gen())
    with pytest.raises(RuntimeError):
        list(g.generate_stream("你好", []))


def test_stream_mock_fallback_when_zero_output(api_key):
    # 验证零产出时异常走 mock 兜底而不是抛出
    def create(**k):
        raise RuntimeError("连接失败")

    g = Generator()
    g.client = _make_client(create)
    out = list(g.generate_stream("你好", []))
    assert len(out) == 1
    assert "未找到与" in out[0]  # _mock_generate 的兜底文案


def test_stream_need_tool_falls_back_to_nonstream(api_key, monkeypatch):
    # 验证 chart_result == "__NEED_TOOL__" 时回退非流式 generate 一次性产出
    g = Generator()
    g.client = _make_client(lambda **k: (_ for _ in ()).throw(
        AssertionError("流式路径不应直接调 LLM")))
    calls = []
    monkeypatch.setattr(g, "generate",
                        lambda *a, **k: (calls.append(a) or ("完整答案", 0, 0)))
    out = list(g.generate_stream("画个图", []))  # 无数字 → _force_chart 返回空 → __NEED_TOOL__
    assert out == ["完整答案"]
    assert len(calls) == 1


def test_stream_executes_tool_then_continues(api_key, monkeypatch):
    # 验证流式路径支持工具调用：收集 tool_call → 执行 → 继续流式输出
    monkeypatch.setattr(cg, "execute_tool", lambda n, a: f"新闻结果:{a.get('topic')}")
    calls = []

    def create(**k):
        calls.append(k)
        if len(calls) == 1:
            def gen1():
                yield _tc_chunk(0, id="c1", name="news_headlines", arguments='{"topic":')
                yield _tc_chunk(0, arguments='"tech"}')
            return gen1()

        def gen2():
            yield _chunk("新闻")
            yield _chunk("来了")
        return gen2()

    g = Generator()
    g.client = _make_client(create)

    out = list(g.generate_stream("查一下科技新闻", []))

    assert "".join(out) == "新闻来了"
    tool_msgs = [m for m in calls[1]["messages"] if m.get("role") == "tool"]
    assert any("新闻结果:tech" in m["content"] for m in tool_msgs)
