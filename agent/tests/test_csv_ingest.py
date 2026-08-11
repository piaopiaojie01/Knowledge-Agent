"""CSV 文本入库：识别、转 Markdown 表格、分块保留行结构"""
import os
import sys
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("KA_EMBEDDING_DEVICE", "cpu")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from api.ingest_routes import _looks_like_csv, csv_to_markdown
from core.document_processor import semantic_chunk_text


CSV_TEXT = """产品,销量,单价,地区
苹果,1200,8.5,华东
香蕉,850,3.2,华南
橙子,600,5.0,华中
葡萄,400,12.8,西南
西瓜,1500,2.5,华北
草莓,300,25.0,东北
芒果,200,18.0,西北
梨,700,4.6,华东
桃子,500,6.9,华中
柚子,350,7.8,华南"""


def test_looks_like_csv_yes():
    assert _looks_like_csv(CSV_TEXT)


def test_looks_like_csv_no_for_prose():
    assert not _looks_like_csv("这是一段普通文本。\n它没有逗号分隔的结构。")


def test_csv_to_markdown_keeps_rows():
    md = csv_to_markdown(CSV_TEXT)
    assert md.startswith("| 产品 | 销量 | 单价 | 地区 |")
    assert "| 香蕉 | 850 | 3.2 | 华南 |" in md
    assert "| --- |" in md
    # 转表格后不再有逗号粘连行
    assert "地区苹果" not in md.replace("\n", "")


def test_csv_chunking_keeps_row_structure(monkeypatch):
    class FakeEmbedder:
        def encode_documents(self, sentences):
            return [[0.5] * 8 for _ in sentences]

    monkeypatch.setattr("embedding.bge_embedder.embedder", FakeEmbedder())
    chunks = semantic_chunk_text(csv_to_markdown(CSV_TEXT))
    joined = "\n".join(chunks)
    assert "| 香蕉 | 850 | 3.2 | 华南 |" in joined
    assert "| 柚子 | 350 | 7.8 | 华南 |" in joined


def test_csv_escapes_pipe_in_cell():
    md = csv_to_markdown("名称,备注\n测试,含|竖线")
    assert "含\\|竖线" in md
