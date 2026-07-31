"""基于线上 /api/v1/rag/search 端点评估召回率（真实链路）"""
import json
import os
import sys
import urllib.request

AGENT_URL = "http://localhost:8000/api/v1/rag/search"
GOLDEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval_golden2.jsonl")
EVAL_TOP_K = 5


def search(question: str) -> list:
    body = json.dumps({"question": question, "kb_names": ["心理学"], "top_k": EVAL_TOP_K}).encode("utf-8")
    req = urllib.request.Request(AGENT_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data.get("results", [])


def main():
    cases = []
    with open(GOLDEN, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    print(f"黄金集: {len(cases)} 题")

    hit1 = hit5 = 0
    for i, case in enumerate(cases, 1):
        try:
            results = search(case["question"])
        except Exception as e:
            print(f"[{i}/{len(cases)}] ERROR {case['question']}: {e}")
            continue
        titles = [r.get("title", "") for r in results]
        expect = case["expect_title"]
        r1 = expect in titles[:1]
        r5 = expect in titles[:EVAL_TOP_K]
        hit1 += r1
        hit5 += r5
        mark = "HIT " if r5 else "MISS"
        print(f"[{i}/{len(cases)}] {mark} 问: {case['question'][:30]}")
        print(f"     期望: {expect[:50]}")
        print(f"     top1: {titles[0][:50] if titles else '(空)'}  recall@1={'Y' if r1 else 'N'} recall@5={'Y' if r5 else 'N'}")

    total = len(cases)
    print("=" * 60)
    print(f"汇总: 共 {total} 题")
    print(f"recall@1 = {hit1}/{total} = {hit1 / total:.3f}")
    print(f"recall@5 = {hit5}/{total} = {hit5 / total:.3f}")
    # 准确率：top5 中命中正确答案的比例（每题的 precision@5 平均）
    prec_sum = 0
    for case in cases:
        try:
            results = search(case["question"])
        except Exception:
            continue
        titles = [r.get("title", "") for r in results]
        prec_sum += (1.0 if case["expect_title"] in titles[:EVAL_TOP_K] else 0.0) / EVAL_TOP_K
    print(f"precision@5 = {prec_sum / total:.3f}")


if __name__ == "__main__":
    main()
