"""基于线上 /api/v1/rag/search 端点评估召回率（真实链路）

判定规则（关键词命中，替代旧的标题精确匹配——LLM 每次生成的 QA 标题措辞不同，
精确匹配会把语义正确的命中误判为 MISS）：
  每题给 expect_keywords，top-k 内任一条结果的 title+content 同时包含全部关键词即算命中。
  golden 文件向下兼容 expect_title（精确匹配）。

用法: python scripts/eval_api.py [--golden path.jsonl] [--kb 心理学] [--topk 5]
"""
import argparse
import json
import os
import urllib.request

AGENT_URL = "http://localhost:8000/api/v1/rag/search"
DEFAULT_GOLDEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval_golden2.jsonl")


def search(question: str, kb: str, top_k: int) -> list:
    body = json.dumps({"question": question, "kb_names": [kb], "top_k": top_k}).encode("utf-8")
    req = urllib.request.Request(AGENT_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data.get("results", [])


def is_hit(case: dict, result: dict) -> bool:
    text = (result.get("title", "") or "") + "\n" + (result.get("content", "") or "")
    kws = case.get("expect_keywords")
    if kws:
        return all(k in text for k in kws)
    return case.get("expect_title", "") == (result.get("title", "") or "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--golden", default=DEFAULT_GOLDEN)
    ap.add_argument("--kb", default="心理学")
    ap.add_argument("--topk", type=int, default=5)
    args = ap.parse_args()

    cases = []
    with open(args.golden, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    print(f"黄金集: {len(cases)} 题 | KB={args.kb} | top_k={args.topk}")

    hit1 = hit5 = 0
    rr_sum = 0.0  # reciprocal rank 之和,最后算 MRR
    for i, case in enumerate(cases, 1):
        try:
            results = search(case["question"], args.kb, args.topk)
        except Exception as e:
            print(f"[{i}/{len(cases)}] ERROR {case['question']}: {e}")
            continue
        rank = next((j + 1 for j, r in enumerate(results) if is_hit(case, r)), None)
        r1 = rank == 1
        r5 = rank is not None
        hit1 += r1
        hit5 += r5
        rr_sum += 1.0 / rank if rank else 0.0
        top1 = results[0].get("title", "") if results else "(空)"
        print(f"[{i}/{len(cases)}] {'HIT ' if r5 else 'MISS'} 问: {case['question'][:30]}")
        print(f"     关键词: {case.get('expect_keywords', case.get('expect_title'))} | 命中排名: {rank or '-'}")
        print(f"     top1: {top1[:55]}")

    total = len(cases)
    print("=" * 60)
    print(f"汇总: 共 {total} 题")
    print(f"recall@1 = {hit1}/{total} = {hit1 / total:.3f}")
    print(f"recall@{args.topk} = {hit5}/{total} = {hit5 / total:.3f}")
    print(f"MRR      = {rr_sum / total:.3f}")


if __name__ == "__main__":
    main()
