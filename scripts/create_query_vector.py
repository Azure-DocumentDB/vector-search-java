#!/usr/bin/env python3
"""Extract a smoke-query vector from the pinned fixture; never calls a model."""
import hashlib
import json
import math
from pathlib import Path
import sys

EXPECTED = "60c08c2cdd439444ba8601d94a1f3487f4a9ee56cd48ba201d983c96722de6e4"


def main():
    if len(sys.argv) != 3:
        raise ValueError("usage: create_query_vector.py INPUT OUTPUT")
    raw = Path(sys.argv[1]).read_bytes()
    if hashlib.sha256(raw).hexdigest() != EXPECTED:
        raise ValueError("fixture checksum mismatch")
    records = json.loads(raw)
    if len(records) != 50:
        raise ValueError("fixture must have 50 records")
    for record in records:
        vector = record["DescriptionVector"]
        if len(vector) != 1536 or not all(type(v) in (int, float) and math.isfinite(v) for v in vector):
            raise ValueError("invalid vector")
    with open(sys.argv[2], "x", encoding="utf-8") as output:
        json.dump(records[0]["DescriptionVector"], output, allow_nan=False)
        output.write("\n")
    print("Created a 1536-dimensional smoke-query vector; no model call.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Query extraction failed: verify arguments/checksum and use a new output path.", file=sys.stderr)
        sys.exit(1)
