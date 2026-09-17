"""Summarize frozen holdout retrieval rows; never tune profiles or labels here."""

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path

from tokenizers import Tokenizer


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    low = math.floor(position)
    high = math.ceil(position)
    return round(ordered[low] + (ordered[high] - ordered[low]) * (position - low), 3)


def quality(rows: list[dict]) -> dict:
    answerable = [row for row in rows if row["expectedSource"]]
    no_answer = [row for row in rows if not row["expectedSource"]]
    result = {"n": len(answerable)}
    if answerable:
        for cutoff in (1, 3, 5):
            result[f"recallAt{cutoff}"] = round(sum(0 < row["rank"] <= cutoff for row in answerable) / len(answerable), 4)
        result["mrr"] = round(sum(1 / row["rank"] if row["rank"] > 0 else 0 for row in answerable) / len(answerable), 4)
        result["top1EvidenceSupport"] = round(sum(row["top1Supported"] for row in answerable) / len(answerable), 4)
        result["evidenceSupportAt5"] = round(sum(any(source["supportsLabel"] for source in row["sources"])
                                                for row in answerable) / len(answerable), 4)
    if no_answer:
        result["noAnswerN"] = len(no_answer)
        result["noAnswerReturnedCandidateRate"] = round(sum(bool(row["sources"]) for row in no_answer) / len(no_answer), 4)
        result["noAnswerPotentialMiscitationRate"] = result["noAnswerReturnedCandidateRate"]
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw", type=Path)
    parser.add_argument("summary", type=Path)
    parser.add_argument("--holdout", type=Path, default=Path("src/test/resources/retrieval/holdout-v1.json"))
    parser.add_argument("--tokenizer", type=Path, default=Path("target/models/paraphrase-multilingual-MiniLM-L12-v2/tokenizer.json"))
    args = parser.parse_args()
    raw = json.loads(args.raw.read_text(encoding="utf-8"))
    tokenizer = Tokenizer.from_file(str(args.tokenizer))
    tokenizer.enable_truncation(max_length=128)
    token_counts = {case["id"]: len(tokenizer.encode(case["query"]).ids)
                    for case in json.loads(args.holdout.read_text(encoding="utf-8"))}
    grouped = defaultdict(list)
    for row in raw["rows"]:
        grouped[(row["profile"], row["category"])].append(row)
        grouped[(row["profile"], "overall")].append(row)
    summary = {}
    for (profile, category), rows in sorted(grouped.items()):
        first_round = [row for row in rows if row["round"] == 1]
        scores = quality(first_round)
        latencies = [row["latencyMs"] for row in rows]
        scores["latencyP50Ms"] = percentile(latencies, 0.5)
        scores["latencyP95Ms"] = percentile(latencies, 0.95)
        scores["latencySamples"] = len(latencies)
        scores["measuredEmbeddingCalls"] = 0 if profile == "bm25-only" else len(rows)
        scores["measuredEmbeddingTokens"] = 0 if profile == "bm25-only" else sum(
            token_counts[row["case"]] for row in rows)
        scores["roundsAgree"] = all(
            quality([row for row in rows if row["round"] == round]) == quality(first_round)
            for round in (2, 3))
        summary.setdefault(profile, {})[category] = scores
    args.summary.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
