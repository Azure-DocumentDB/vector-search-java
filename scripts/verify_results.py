#!/usr/bin/env python3
"""Validate JSON-lines CLI output against exact pinned fixture cosine rankings.
Usage: python3 scripts/verify_results.py DATA QUERY CLI_LOG [all|ivf|hnsw|diskann]
No database/network/model calls. Intended for a fresh, isolated 50-record smoke run.
"""
import hashlib
import json
import math
from pathlib import Path
import sys

EXPECTED = "60c08c2cdd439444ba8601d94a1f3487f4a9ee56cd48ba201d983c96722de6e4"


def verify(data, query, lines, mode="all"):
    if hashlib.sha256(data).hexdigest() != EXPECTED:
        raise ValueError("fixture checksum mismatch")
    fixture = json.loads(data)
    if len(fixture) != 50 or len(query) != 1536:
        raise ValueError("fixture dimensions mismatch")
    if query != fixture[0]["DescriptionVector"]:
        raise ValueError("gate requires extracted first-record query")
    qnorm = math.sqrt(sum(x*x for x in query))
    scores = {}
    for doc in fixture:
        v = doc["DescriptionVector"]
        scores["hotel:" + doc["HotelId"]] = sum(x*y for x, y in zip(query, v)) / qnorm / math.sqrt(sum(x*x for x in v))
    rows, summaries = [], []
    for line in lines.splitlines():
        if not line.startswith("{"):
            continue
        row = json.loads(line)
        if "algorithm" in row:
            rows.append(row)
        if "summary" in row:
            summaries.append(row["summary"])
    algorithms = ["ivf", "hnsw", "diskann"] if mode == "all" else [mode]
    if mode not in ("all", "ivf", "hnsw", "diskann") or [r["algorithm"] for r in rows] != algorithms:
        raise ValueError("missing/duplicate algorithm results")
    if summaries != [{"attempted": len(rows), "succeeded": len(rows), "failed": 0}]:
        raise ValueError("missing or failed summary")
    proof = []
    for row in rows:
        if row["status"] != "PASS" or row["metric"] != "COS" or row["fixtureDocuments"] != 50 or row["totalDocuments"] != 50:
            raise ValueError("case failed or not isolated fixture")
        hits = row["hits"]
        if not hits or len({h["_id"] for h in hits}) != len(hits):
            raise ValueError("invalid results")
        expected = sorted(scores.values(), reverse=True)[:len(hits)]
        for hit, target in zip(hits, expected):
            actual = scores[hit["_id"]]
            if not math.isclose(actual, target, rel_tol=1e-6, abs_tol=1e-6) or not math.isclose(hit["score"], actual, rel_tol=1e-4, abs_tol=1e-4):
                raise ValueError("rank/score mismatch")
        if hits[0]["_id"] != "hotel:" + fixture[0]["HotelId"] or not math.isclose(hits[0]["score"], 1, abs_tol=1e-4):
            raise ValueError("self-match mismatch")
        proof.append({"algorithm": row["algorithm"], "metric": "COS", "selfMatch": True,
                      "ranksAndScoresMatch": True, "ids": [h["_id"] for h in hits]})
    return proof


if __name__ == "__main__":
    try:
        if len(sys.argv) not in (4, 5):
            raise ValueError("arguments")
        print(json.dumps(verify(Path(sys.argv[1]).read_bytes(), json.loads(Path(sys.argv[2]).read_text()),
                                Path(sys.argv[3]).read_text(), sys.argv[4] if len(sys.argv) == 5 else "all"), indent=2))
    except Exception:
        print("Result verification failed: check fixture/query, all cases, isolation, ranks and scores.", file=sys.stderr)
        sys.exit(1)
