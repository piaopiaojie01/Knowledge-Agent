"""向量检索评估脚本 - 基于黄金集统计 recall@1 / recall@5

用法:
    python scripts/evaluate.py                          # 使用默认黄金集
    python scripts/evaluate.py --golden path/to.jsonl   # 指定黄金集文件
"""
import argparse
import json
import logging
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from embedding.bge_embedder import embedder
from store.milvus_client import milvus_client
from core.retriever import retriever

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)

DEFAULT_GOLDEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval_golden.jsonl")
EVAL_TOP_K = 5


def load_golden(path: str) -> list[dict]:
    cases = []
    with open(path, "r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError as e:
                raise ValueError(f"第 {line_no} 行 JSON 解析失败: {e}") from e
            if "question" not in item or "expect_title" not in item:
                raise ValueError(f"第 {line_no} 行缺少 question/expect_title 字段")
            cases.append(item)
    return cases


def main():
    parser = argparse.ArgumentParser(description="向量检索 recall 评估")
    parser.add_argument("--golden", default=DEFAULT_GOLDEN, help="黄金集 jsonl 路径")
    args = parser.parse_args()

    cases = load_golden(args.golden)
    if not cases:
        logger.error(f"黄金集为空: {args.golden}，评估终止")
        sys.exit(1)
    logger.info(f"加载黄金集 {len(cases)} 条: {args.golden}")

    if not milvus_client.is_connected:
        logger.info("Milvus 未连接，正在连接...")
        if not milvus_client.connect():
            logger.error("Milvus 连接失败，评估终止")
            sys.exit(1)

    logger.info("加载 embedding 模型...")
    _ = embedder.model

    hit_at_1 = 0
    hit_at_5 = 0
    for i, case in enumerate(cases, 1):
        question = case["question"]
        expect_title = case["expect_title"]
        docs = retriever.retrieve(question, top_k=EVAL_TOP_K)
        titles = [d.get("title", "") for d in docs]

        r1 = expect_title in titles[:1]
        r5 = expect_title in titles[:EVAL_TOP_K]
        hit_at_1 += r1
        hit_at_5 += r5

        print(f"[{i}/{len(cases)}] {'HIT ' if r5 else 'MISS'} {question}")
        print(f"    期望: {expect_title} | recall@1={'Y' if r1 else 'N'} | 返回: {titles}")

    total = len(cases)
    print("=" * 60)
    print(f"汇总: 共 {total} 题")
    print(f"recall@1 = {hit_at_1}/{total} = {hit_at_1 / total:.3f}")
    print(f"recall@5 = {hit_at_5}/{total} = {hit_at_5 / total:.3f}")


if __name__ == "__main__":
    main()
