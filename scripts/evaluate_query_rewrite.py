#!/usr/bin/env python3
"""Query Rewrite A/B 评测脚本。

对比同一评测集中 original_query 与 rewritten_query 的检索效果：
- hit@1 / hit@k / MRR
- win / tie / loss

用法：
  python3 scripts/evaluate_query_rewrite.py \
    --csv sample-data/fintech-评测集-rewrite-ab.csv \
    --base-url http://localhost \
    --dataset-id <dataset_id> \
    --api-key <dataset_api_key> \
    --top-k 5
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import urllib.request
import urllib.error


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Query Rewrite A/B evaluation")
    parser.add_argument("--csv", required=True, help="CSV with original_query, rewritten_query, expected_keyword")
    parser.add_argument("--base-url", default="http://localhost", help="Dify base URL")
    parser.add_argument("--dataset-id", required=True, help="Dify dataset ID")
    parser.add_argument("--api-key", required=True, help="Dify dataset API key")
    parser.add_argument("--top-k", type=int, default=5, help="top_k for retrieval")
    parser.add_argument("--rerank", default="true", choices=["true", "false"], help="enable rerank")
    parser.add_argument("--rerank-provider", default="langgenius/siliconflow/siliconflow")
    parser.add_argument("--rerank-model", default="BAAI/bge-reranker-v2-m3")
    return parser.parse_args()


def retrieve(base_url: str, dataset_id: str, api_key: str, query: str, args: argparse.Namespace) -> list[dict]:
    retrieval_model: dict = {
        "search_method": "hybrid_search",
        "reranking_enable": args.rerank == "true",
        "top_k": args.top_k,
        "score_threshold_enabled": False,
    }
    if args.rerank == "true":
        retrieval_model["reranking_model"] = {
            "reranking_provider_name": args.rerank_provider,
            "reranking_model_name": args.rerank_model,
        }
        retrieval_model["reranking_mode"] = "reranking_model"

    body = json.dumps({"query": query, "retrieval_model": retrieval_model}, ensure_ascii=False).encode("utf-8")
    url = f"{args.base_url.rstrip('/')}/v1/datasets/{dataset_id}/retrieve"
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Authorization", f"Bearer {api_key}")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req) as resp:
        data = json.load(resp)
    return data.get("records", [])


def first_hit_rank(records: list[dict], keyword: str) -> int | None:
    for i, record in enumerate(records):
        content = (record.get("segment") or {}).get("content") or ""
        if keyword in content:
            return i + 1
    return None


def evaluate_query(base_url: str, dataset_id: str, api_key: str, query: str, keyword: str, args: argparse.Namespace):
    records = retrieve(base_url, dataset_id, api_key, query, args)
    rank = first_hit_rank(records, keyword)
    return {
        "hit1": rank == 1,
        "hitk": rank is not None and rank <= args.top_k,
        "mrr": (1.0 / rank) if rank else 0.0,
        "rank": rank,
    }


def main() -> None:
    args = parse_args()

    with open(args.csv, encoding="utf-8-sig") as f:
        reader = csv.DictReader(f)
        rows = list(reader)

    if not rows:
        print("CSV is empty")
        sys.exit(1)

    required = {"original_query", "rewritten_query", "expected_keyword"}
    missing = required - set(rows[0].keys())
    if missing:
        print(f"CSV missing columns: {sorted(missing)}")
        sys.exit(1)

    results = []
    for row in rows:
        orig = evaluate_query(args.base_url, args.dataset_id, args.api_key, row["original_query"], row["expected_keyword"], args)
        rew = evaluate_query(args.base_url, args.dataset_id, args.api_key, row["rewritten_query"], row["expected_keyword"], args)
        if orig["hit1"] == rew["hit1"]:
            verdict = "tie"
        elif rew["hit1"]:
            verdict = "win"
        else:
            verdict = "loss"
        results.append((row, orig, rew, verdict))

    n = len(results)
    hit1_orig = sum(1 for _, o, _, _ in results if o["hit1"])
    hit1_rew = sum(1 for _, _, r, _ in results if r["hit1"])
    hitk_orig = sum(1 for _, o, _, _ in results if o["hitk"])
    hitk_rew = sum(1 for _, _, r, _ in results if r["hitk"])
    mrr_orig = sum(o["mrr"] for _, o, _, _ in results) / n
    mrr_rew = sum(r["mrr"] for _, _, r, _ in results) / n
    wins = sum(1 for _, _, _, v in results if v == "win")
    losses = sum(1 for _, _, _, v in results if v == "loss")
    ties = n - wins - losses

    print(f"{'id':<4} {'verdict':<6} {'orig_rank':<10} {'rew_rank':<10} {'orig_hit1':<9} {'rew_hit1':<9} query")
    for row, o, r, v in results:
        print(f"{row.get('id', '?'):<4} {v:<6} {str(o['rank']):<10} {str(r['rank']):<10} "
              f"{str(o['hit1']):<9} {str(r['hit1']):<9} {row['original_query'][:40]}")

    print()
    print(f"total={n}")
    print(f"hit@1  original={hit1_orig}/{n}  rewritten={hit1_rew}/{n}")
    print(f"hit@{args.top_k} original={hitk_orig}/{n}  rewritten={hitk_rew}/{n}")
    print(f"MRR    original={mrr_orig:.4f}  rewritten={mrr_rew:.4f}")
    print(f"win={wins}  tie={ties}  loss={losses}")


if __name__ == "__main__":
    main()
