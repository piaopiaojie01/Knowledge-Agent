"""Skill 工具集 —— LLM 可调用的外部能力（全部基于免费 API）

所有工具均通过 OpenAI function calling 协议注册，LLM 自动选择调用。
收费情况：除 DeepSeek 调用费用外，所有工具 API 均免费。
"""
import requests
import math
import re
import secrets
import string
from datetime import datetime, timedelta
from typing import Dict, Any

# ── 工具定义 ──
TOOLS = [
  {
    "type": "function",
    "function": {
      "name": "get_current_time",
      "description": "获取当前日期和时间",
      "parameters": {
        "type": "object",
        "properties": {},
        "required": []
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "calculate",
      "description": "执行数学计算",
      "parameters": {
        "type": "object",
        "properties": {
          "expression": {
            "type": "string"
          }
        },
        "required": [
          "expression"
        ]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "web_search",
      "description": "搜索互联网",
      "parameters": {
        "type": "object",
        "properties": {
          "query": {
            "type": "string"
          }
        },
        "required": [
          "query"
        ]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "url_fetch",
      "description": "抓取网页内容",
      "parameters": {
        "type": "object",
        "properties": {
          "url": {
            "type": "string"
          }
        },
        "required": [
          "url"
        ]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "get_weather",
      "description": "查询天气",
      "parameters": {
        "type": "object",
        "properties": {
          "city": {
            "type": "string"
          }
        },
        "required": [
          "city"
        ]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "get_exchange_rate",
      "description": "查询汇率",
      "parameters": {
        "type": "object",
        "properties": {
          "from_currency": {
            "type": "string"
          },
          "to_currency": {
            "type": "string"
          }
        },
        "required": [
          "from_currency",
          "to_currency"
        ]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "wikipedia_lookup",
      "description": "查询百科",
      "parameters": {
        "type": "object",
        "properties": {
          "query": {
            "type": "string"
          },
          "lang": {
            "type": "string"
          }
        },
        "required": [
          "query"
        ]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "date_calc",
      "description": "日期计算",
      "parameters": {
        "type": "object",
        "properties": {
          "date1": {
            "type": "string"
          },
          "date2": {
            "type": "string"
          },
          "add_days": {
            "type": "integer"
          }
        },
        "required": [
          "date1"
        ]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "generate_password",
      "description": "生成密码",
      "parameters": {
        "type": "object",
        "properties": {
          "length": {
            "type": "integer"
          }
        },
        "required": []
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "make_chart",
      "description": "当用户要求画图/做图表/可视化数据时必须调用。生成柱状图(bar)/横向柱状图(barh)/折线图(line)/饼图(pie)，返回可直接嵌入的图片HTML标签",
      "parameters": {
        "type": "object",
        "properties": {
          "type": {
            "type": "string",
            "description": "图表类型",
            "enum": ["bar", "barh", "line", "pie"],
          },
          "labels": {
            "type": "string",
            "description": "标签，逗号分隔，如：一月,二月,三月"
          },
          "data": {
            "type": "string",
            "description": "数据值，逗号分隔，如：100,200,150"
          },
          "title": {
            "type": "string",
            "description": "图表标题（可选）"
          },
          "unit": {
            "type": "string",
            "description": "数据单位（可选），如：万元,%,人"
          }
        },
        "required": [
          "type",
          "labels",
          "data"
        ]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "news_headlines",
      "description": "获取新闻头条",
      "parameters": {
        "type": "object",
        "properties": {
          "topic": {
            "type": "string"
          }
        },
        "required": []
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "make_icon",
      "description": "生成SVG图标",
      "parameters": {
        "type": "object",
        "properties": {
          "description": {
            "type": "string"
          },
          "size": {
            "type": "integer"
          }
        },
        "required": [
          "description"
        ]
      }
    }
  }
]# ── 安全计算内置函数（防止 eval 执行恶意代码）──
#   白名单模式：只暴露 math 模块中的常用数学函数
_safe_builtins = {
    "abs": abs, "round": round, "min": min, "max": max,
    "pow": pow, "sqrt": math.sqrt, "sin": math.sin, "cos": math.cos,
    "log": math.log, "log10": math.log10, "pi": math.pi, "e": math.e
}

# ── RSS 源（BBC 在国内被封，改用 DuckDuckGo 新闻搜索兜底）──
_RSS_FEEDS = {
    "tech": "https://feeds.bbci.co.uk/news/technology/rss.xml",
    "world": "https://feeds.bbci.co.uk/news/world/rss.xml",
    "finance": "https://feeds.bbci.co.uk/news/business/rss.xml",
    "default": "https://feeds.bbci.co.uk/news/rss.xml"
}


# ═════════════════════════════════════════
# 工具执行入口 —— 按名称分发到具体实现
# ═════════════════════════════════════════

def execute_tool(name: str, args: Dict[str, Any]) -> str:
    """执行指定的工具，返回结果字符串"""

    if name == "get_current_time":
        now = datetime.now()
        return f"当前时间: {now.strftime('%Y年%m月%d日 %H:%M:%S')} (星期{['一','二','三','四','五','六','日'][now.weekday()]})"

    if name == "calculate":
        expr = args.get("expression", "")
        try:
            result = eval(expr, {"__builtins__": {}}, _safe_builtins)
            return f"计算结果: {expr} = {result}"
        except Exception as e:
            return f"计算失败: {e}"

    if name == "web_search":
        return _duckduckgo_search(args.get("query", ""))

    if name == "url_fetch":
        return _fetch_url(args.get("url", ""))

    if name == "get_weather":
        return _weather(args.get("city", "Beijing"))

    if name == "get_exchange_rate":
        return _exchange_rate(args.get("from_currency", "USD"), args.get("to_currency", "CNY"))

    if name == "wikipedia_lookup":
        return _wiki(args.get("query", ""), args.get("lang", "zh"))

    if name == "date_calc":
        return _date_calc(args.get("date1", ""), args.get("date2"), args.get("add_days"))

    if name == "generate_password":
        length = args.get("length", 16)
        return _gen_password(min(length, 64))

    if name == "make_icon":
        return _make_icon_svg(args.get("description", "icon"), args.get("size", 128))

    if name == "make_chart":
        return _make_chart(args.get("type","bar"), args.get("labels",""), args.get("data",""), args.get("title",""), args.get("unit",""))

    if name == "news_headlines":
        return _news(args.get("topic", "default"))

    return f"未知工具: {name}"

# ═══════════════════════════════════════════
# 工具实现（按功能分组）
# ═══════════════════════════════════════════



# --- 网页抓取 ---
def _fetch_url(url: str) -> str:
    try:
        r = requests.get(url, headers={"User-Agent": "Mozilla/5.0"}, timeout=10, allow_redirects=True)
        r.raise_for_status()
        # 简单提取正文：去掉 script/style 标签和 HTML
        html = r.text
        html = re.sub(r'<script[^>]*>.*?</script>', '', html, flags=re.DOTALL | re.IGNORECASE)
        html = re.sub(r'<style[^>]*>.*?</style>', '', html, flags=re.DOTALL | re.IGNORECASE)
        text = re.sub(r'<[^>]+>', ' ', html)
        text = re.sub(r'\s+', ' ', text).strip()
        return f"【{url}】\n{text[:2000]}"
    except Exception as e:
        return f"抓取失败: {e}"



# --- 天气 / 汇率 / 百科 ---
def _weather(city: str) -> str:
    try:
        r = requests.get(f"https://wttr.in/{city}?format=%C+%t+%h+%w&lang=zh", timeout=5)
        return f"{city} 天气: {r.text.strip()}"
    except Exception as e:
        return f"天气查询失败: {e}"


def _exchange_rate(frm: str, to: str) -> str:
    try:
        r = requests.get(f"https://api.exchangerate-api.com/v4/latest/{frm.upper()}", timeout=5)
        data = r.json()
        rate = data.get("rates", {}).get(to.upper(), 0)
        return f"1 {frm.upper()} = {rate} {to.upper()}"
    except Exception as e:
        return f"汇率查询失败: {e}"


def _wiki(query: str, lang: str) -> str:
    try:
        r = requests.get(
            f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{query}",
            headers={"User-Agent": "KnowledgeAgent/1.0"}, timeout=8
        )
        if r.status_code == 200:
            data = r.json()
            return f"【{data.get('title', query)}】\n{data.get('extract', '')[:800]}"
        return f"未找到'{query}'的百科条目"
    except Exception as e:
        return f"百科查询失败: {e}"



# --- 日期计算 / 密码生成 / 新闻 ---
def _date_calc(date1: str, date2: str = None, add_days: int = None) -> str:
    try:
        d1 = datetime.strptime(date1, "%Y-%m-%d")
        if date2:
            d2 = datetime.strptime(date2, "%Y-%m-%d")
            diff = (d2 - d1).days
            return f"{date1} 到 {date2} 间隔 {abs(diff)} 天"
        if add_days is not None:
            result = d1 + timedelta(days=add_days)
            return f"{date1} + {add_days} 天 = {result.strftime('%Y-%m-%d')} (星期{['一','二','三','四','五','六','日'][result.weekday()]})"
        return f"{date1} 是 星期{['一','二','三','四','五','六','日'][d1.weekday()]}"
    except Exception as e:
        return f"日期计算失败: {e}"


def _gen_password(length: int) -> str:
    """生成随机安全密码（大小写字母 + 数字 + 特殊符号）"""
    chars = string.ascii_letters + string.digits + "!@#$%^&*"
    pwd = ''.join(secrets.choice(chars) for _ in range(length))
    return f"随机密码 ({length}位):\n{pwd}"


def _news(topic: str) -> str:
    """获取新闻头条（RSS + DuckDuckGo 兜底）"""
    # 先尝试 RSS
    feed_url = _RSS_FEEDS.get(topic, _RSS_FEEDS["default"])
    try:
        r = requests.get(feed_url, headers={"User-Agent": "Mozilla/5.0"}, timeout=3)
        items = re.findall(r'<item>.*?<title>(.*?)</title>.*?<description>(.*?)</description>.*?</item>',
                          r.text, re.DOTALL)
        if items:
            topic_names = {"tech": "科技", "world": "国际", "finance": "财经", "default": "综合"}
            lines = [f"📰 最新新闻 ({topic_names.get(topic, topic)}):"]
            for title, desc in items[:8]:
                title = re.sub(r'<[^>]+>', '', title).strip()
                lines.append(f"• {title}")
            return "\n".join(lines)
    except Exception: pass
    # 🚫 RSS 不可用 → 用 DuckDuckGo 搜索新闻关键词兜底
    try:
        topic_q = {"tech": "科技新闻", "world": "世界新闻", "finance": "财经新闻", "default": "今日新闻"}
        return _duckduckgo_search(topic_q.get(topic, "今日新闻"))
    except Exception:
        return "新闻服务暂时不可用（请检查网络连接）"


def _duckduckgo_search(query: str) -> str:
    """搜索（国内 DDG 不可用，请基于 LLM 已有知识回答）"""
    try:
        resp = requests.get(
            "https://api.duckduckgo.com/",
            params={"q": query, "format": "json", "no_html": 1, "skip_disambig": 1},
            timeout=3
        )
        data = resp.json()
        abstract = data.get("AbstractText", "")
        if abstract:
            return f"搜索结果: {abstract[:800]}"
        related = data.get("RelatedTopics", [])
        if related:
            snippets = [r.get("Text", "")[:200] for r in related[:3] if r.get("Text")]
            return "搜索结果:\n" + "\n".join(f"- {s}" for s in snippets) if snippets else "未找到相关结果"
        return "未找到相关结果"
    except Exception:
        return f"搜索 \"{query[:50]}\" 失败 (国内网络) —— 请基于已有知识回答"


# --- 图表生成（Matplotlib）/ 图标生成（SVG）---
def _make_icon_svg(desc: str, size: int = 128) -> str:
    """根据描述生成简单的 SVG 图标"""
    import os
    desc_lower = desc.lower()
    s = size
    stroke = "#333"
    fill = "#3b82f6"
    if "蓝" in desc or "blue" in desc_lower:
        fill = "#3b82f6"
    elif "绿" in desc or "green" in desc_lower:
        fill = "#22c55e"
    elif "红" in desc or "red" in desc_lower:
        fill = "#ef4444"
    elif "橙" in desc or "orange" in desc_lower:
        fill = "#f59e0b"
    elif "紫" in desc or "purple" in desc_lower:
        fill = "#8b5cf6"

    svg = f"""<?xml version="1.0" encoding="UTF-8"?>
<svg width="{s}" height="{s}" viewBox="0 0 {s} {s}" xmlns="http://www.w3.org/2000/svg">
  <rect width="{s}" height="{s}" rx="{s//8}" fill="{fill}" opacity="0.1"/>
"""
    # 根据关键词选择图形
    if "邮件" in desc or "mail" in desc_lower or "email" in desc_lower:
        svg += f"""  <rect x="{s//4}" y="{s//3}" width="{s//2}" height="{s//3}" rx="{s//32}" fill="none" stroke="{fill}" stroke-width="{s//24}"/>
  <polyline points="{s//4},{s//3} {s//2},{s//2} {s*3//4},{s//3}" fill="none" stroke="{fill}" stroke-width="{s//24}" stroke-linecap="round"/>
"""
    elif "搜索" in desc or "search" in desc_lower or "放大" in desc:
        svg += f"""  <circle cx="{s//2}" cy="{s*2//5}" r="{s//5}" fill="none" stroke="{fill}" stroke-width="{s//20}"/>
  <line x1="{s*3//5}" y1="{s*3//5}" x2="{s*4//5}" y2="{s*4//5}" stroke="{fill}" stroke-width="{s//20}" stroke-linecap="round"/>
"""
    elif "用户" in desc or "user" in desc_lower or "人" in desc or "头像" in desc:
        svg += f"""  <circle cx="{s//2}" cy="{s*2//5}" r="{s//6}" fill="{fill}"/>
  <ellipse cx="{s//2}" cy="{s*4//5}" rx="{s//3}" ry="{s//5}" fill="{fill}"/>
"""
    elif "设置" in desc or "settings" in desc_lower or "齿轮" in desc:
        svg += f"""  <circle cx="{s//2}" cy="{s//2}" r="{s//4}" fill="none" stroke="{fill}" stroke-width="{s//16}"/>
  <circle cx="{s//2}" cy="{s//2}" r="{s//12}" fill="{fill}"/>
"""
    elif "星星" in desc or "star" in desc_lower or "收藏" in desc:
        pts = f"{s//2},{s//10} {s*3//5},{s*2//5} {s*9//10},{s*2//5} {s*2//3},{s*3//5} {s*3//4},{s*9//10} {s//2},{s*7//10} {s//4},{s*9//10} {s//3},{s*3//5} {s//10},{s*2//5} {s*2//5},{s*2//5}"
        svg += f"""  <polygon points="{pts}" fill="{fill}"/>
"""
    elif "主页" in desc or "home" in desc_lower or "房子" in desc:
        svg += f"""  <polyline points="{s//4},{s*3//5} {s//2},{s//5} {s*3//4},{s*3//5}" fill="none" stroke="{fill}" stroke-width="{s//20}" stroke-linecap="round" stroke-linejoin="round"/>
  <rect x="{s*2//5}" y="{s*3//5}" width="{s//5}" height="{s//3}" fill="{fill}"/>
"""
    elif "笔记" in desc or "文档" in desc or "文件" in desc or "doc" in desc_lower:
        svg += f"""  <rect x="{s//4}" y="{s//8}" width="{s//2}" height="{s*3//4}" rx="{s//20}" fill="none" stroke="{fill}" stroke-width="{s//24}"/>
  <line x1="{s*2//5}" y1="{s//2}" x2="{s*3//5}" y2="{s//2}" stroke="{fill}" stroke-width="{s//24}" stroke-linecap="round"/>
  <line x1="{s*2//5}" y1="{s*3//5}" x2="{s*3//5}" y2="{s*3//5}" stroke="{fill}" stroke-width="{s//24}" stroke-linecap="round"/>
"""
    else:
        # 默认：圆形+文字缩写
        svg += f"""  <circle cx="{s//2}" cy="{s//2}" r="{s//3}" fill="{fill}" opacity="0.2"/>
  <text x="{s//2}" y="{s//2}" text-anchor="middle" dy="{s//10}" font-size="{s//3}" fill="{fill}" font-weight="bold">{desc[0]}</text>
"""
    svg += "</svg>"
    # 写文件
    name = "".join(c for c in desc[:20] if c.isalnum() or c in " _-")
    name = name.strip() or "icon"
    out_dir = r"g:\Knowledge Agent\backend\src\main\resources\static\icons"
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, f"{name}.svg")
    with open(path, "w", encoding="utf-8") as f:
        f.write(svg)
    return f"✅ 图标已生成: {name}.svg\n可通过 http://localhost:8080/icons/{name}.svg 访问\n\nSVG代码:\n```svg\n{svg}\n```"


import json, urllib.parse

def _make_chart(chart_type: str, labels: str, data: str, title: str = "", unit: str = "") -> str:
    """生成图表（基于开源 Matplotlib，本地渲染，无限制）"""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import os, hashlib
    plt.rcParams["font.family"] = "Microsoft YaHei"
    plt.rcParams["axes.unicode_minus"] = False
    labels_list = [l.strip() for l in labels.split(",") if l.strip()]
    data_list = [float(v.strip()) for v in data.split(",") if v.strip()]
    if not labels_list or not data_list:
        return "错误：标签和数据不能为空"
    if len(labels_list) != len(data_list):
        return f"错误：标签数({len(labels_list)})和数据数({len(data_list)})不匹配"
    fig, ax = plt.subplots(figsize=(8, 5))
    colors = ["#3b82f6","#22c55e","#ef4444","#f59e0b","#8b5cf6","#ec4899","#14b8a6","#6366f1"]
    if chart_type == "pie" or chart_type == "doughnut":
        ax.pie(data_list, labels=labels_list, autopct="%1.1f%%", colors=colors[:len(data_list)], startangle=90, wedgeprops={"edgecolor": "white", "linewidth": 2})
        if title: ax.set_title(title, fontsize=14, pad=16)
        if chart_type == "doughnut": fig.gca().add_artist(plt.Circle((0,0),0.58,fc="white"))
    else:
        x = range(len(labels_list))
        if chart_type == "bar" or chart_type == "column":
            bars = ax.bar(x, data_list, color=colors[:len(data_list)], width=0.6, edgecolor="white", linewidth=0.5)
            for bar, v in zip(bars, data_list): ax.text(bar.get_x()+bar.get_width()/2, bar.get_height(), f"{v:.0f}", ha="center", va="bottom", fontsize=10)
        elif chart_type == "barh":
            bars = ax.barh(x, data_list, color=colors[:len(data_list)], height=0.6, edgecolor="white", linewidth=0.5)
            for bar, v in zip(bars, data_list): ax.text(bar.get_width(), bar.get_y()+bar.get_height()/2, f" {v:.0f}", ha="left", va="center", fontsize=10)
            ax.set_yticks(list(x)); ax.set_yticklabels(labels_list, fontsize=11)
            if unit: ax.set_xlabel(unit, fontsize=11)
        elif chart_type == "line":
            ax.plot(x, data_list, "o-", color=colors[0], linewidth=2, markersize=8)
            for i, v in enumerate(data_list): ax.text(i, v+max(data_list)*0.02, f"{v:.0f}", ha="center", fontsize=10)
        else:
            bars = ax.bar(x, data_list, color=colors[:len(data_list)], width=0.6)
            for bar, v in zip(bars, data_list): ax.text(bar.get_x()+bar.get_width()/2, bar.get_height(), f"{v:.0f}", ha="center", va="bottom", fontsize=10)
        if chart_type != "barh":
            ax.set_xticks(list(x)); ax.set_xticklabels(labels_list, fontsize=11)
            if unit: ax.set_ylabel(unit, fontsize=11)
        if title: ax.set_title(title, fontsize=14, pad=12)
        ax.spines["top"].set_visible(False); ax.spines["right"].set_visible(False)
        ax.grid(axis="y", alpha=0.3)
    plt.tight_layout()
    key = hashlib.md5((labels + data + title + chart_type).encode()).hexdigest()[:12]
    # 保存到 backend/charts/（Spring Boot WebConfig 映射此目录）
    import os as _os
    chart_dir = _os.path.join(_os.path.dirname(_os.path.dirname(_os.path.dirname(_os.path.abspath(__file__)))), "backend", "charts")
    _os.makedirs(chart_dir, exist_ok=True)
    path = _os.path.join(chart_dir, f"chart_{key}.png")
    fig.savefig(path, dpi=150, bbox_inches="tight")
    plt.close(fig)
    url = f"http://localhost:8080/charts/chart_{key}.png"
    return '<img src="' + url + '" style="max-width:100%;border-radius:8px;margin:8px 0"/><br><small>📊 ' + (title or '图表') + ' · 基于 Matplotlib 渲染</small>'