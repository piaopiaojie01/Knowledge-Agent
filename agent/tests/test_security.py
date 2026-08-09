"""P0 安全改造测试：安全计算器、url_fetch 白名单/SSRF 防护、内部 API Key 依赖"""
import pytest
from fastapi import HTTPException

import api.security as sec
from config import settings
from core.skills import _url_fetch_allowed, safe_calculate


# ── 安全计算器（替代 eval）──


def test_safe_calculate_basic():
    assert safe_calculate("1 + 2 * 3") == 7
    assert safe_calculate("2 ** 10") == 1024
    assert safe_calculate("(3 + 5) / 2") == 4.0
    assert safe_calculate("sqrt(16) + pi") == pytest.approx(3.141592653589793 + 4)


def test_safe_calculate_rejects_code():
    # 经典 Python 沙箱逃逸表达式必须全部被拒绝
    for expr in [
        "__import__('os').system('id')",
        "().__class__.__bases__[0].__subclasses__()",
        "open('/etc/passwd').read()",
        "1; import os",
        "[].__class__",
        "globals()",
    ]:
        with pytest.raises(Exception):
            safe_calculate(expr)


def test_safe_calculate_rejects_division_by_zero():
    with pytest.raises(Exception):
        safe_calculate("1 / 0")


# ── url_fetch 白名单 + SSRF 防护 ──


def test_url_fetch_disabled_without_allowlist(monkeypatch):
    monkeypatch.setattr(settings, "url_fetch_allowlist", [])
    ok, err = _url_fetch_allowed("https://example.com/a")
    assert ok is False
    assert "未启用" in err


def test_url_fetch_allowlist_matching(monkeypatch):
    monkeypatch.setattr(settings, "url_fetch_allowlist", ["example.com"])
    monkeypatch.setattr(
        "core.skills.socket.getaddrinfo",
        lambda host, port: [(2, 1, 6, "", ("93.184.216.34", 0))])
    ok, _ = _url_fetch_allowed("https://example.com/a")
    assert ok is True
    ok2, _ = _url_fetch_allowed("https://sub.example.com/a")
    assert ok2 is True
    ok3, err3 = _url_fetch_allowed("https://notexample.com/a")
    assert ok3 is False
    assert "白名单" in err3


def test_url_fetch_rejects_private_ip(monkeypatch):
    # 域名解析到内网/回环地址必须被拦截（防 SSRF）
    monkeypatch.setattr(settings, "url_fetch_allowlist", ["example.com"])
    monkeypatch.setattr(
        "core.skills.socket.getaddrinfo",
        lambda host, port: [(2, 1, 6, "", ("127.0.0.1", 0))])
    ok, err = _url_fetch_allowed("https://example.com/a")
    assert ok is False
    assert "内网" in err


def test_url_fetch_rejects_non_http_scheme(monkeypatch):
    monkeypatch.setattr(settings, "url_fetch_allowlist", ["example.com"])
    ok, err = _url_fetch_allowed("file:///etc/passwd")
    assert ok is False
    assert "http" in err


# ── 内部 API Key 鉴权依赖 ──


def test_require_internal_key_ok(monkeypatch):
    monkeypatch.setattr(settings, "internal_api_key", "secret-key")
    sec.require_internal_key("secret-key")  # 不应抛异常


def test_require_internal_key_rejects_wrong(monkeypatch):
    monkeypatch.setattr(settings, "internal_api_key", "secret-key")
    with pytest.raises(HTTPException) as ei:
        sec.require_internal_key("wrong-key")
    assert ei.value.status_code == 401
    with pytest.raises(HTTPException) as ei2:
        sec.require_internal_key("")
    assert ei2.value.status_code == 401


def test_require_internal_key_fail_closed_without_config(monkeypatch):
    # 未配置密钥时拒绝一切业务请求（fail-closed）
    monkeypatch.setattr(settings, "internal_api_key", "")
    with pytest.raises(HTTPException) as ei:
        sec.require_internal_key("anything")
    assert ei.value.status_code == 503
