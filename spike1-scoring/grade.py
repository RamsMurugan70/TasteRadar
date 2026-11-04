#!/usr/bin/env python3
"""Blind-grade the scorer against your own judgement.

  python3 grade.py --sheet      writes grading.csv, shuffled, WITHOUT the scores
  ... you fill in the verdict column: y / n / m (maybe) ...
  python3 grade.py --compare    scores the scorer

Blind matters. If you can see the model's score while grading you will anchor to
it and the spike tells you nothing.

The gate from the spec: 70% agreement, and at least 7 of the model's top 10
should be places you actually said yes to. Below that, the problem is the data,
not the app.
"""
from __future__ import annotations

import argparse
import csv
import json
import random
from pathlib import Path

HERE = Path(__file__).parent
THRESHOLD = 70  # the score at which the app would fire an alert


def write_sheet(scored_path, sheet_path, seed):
    data = json.loads(Path(scored_path).read_text())
    rows = list(data["places"])
    random.Random(seed).shuffle(rows)
    with open(sheet_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id", "name", "area", "rating", "types", "verdict"])
        for r in rows:
            w.writerow([r["id"], r["name"], r.get("area", ""), r.get("rating", ""),
                        " ".join(r.get("types", [])[:3]), ""])
    print(f'Want: "{data["want"]}"')
    print(f"{len(rows)} places -> {sheet_path}")
    print("\nFill the verdict column with y / n / m, then: python3 grade.py --compare")
    print("y = I would go for this want.  n = no.  m = maybe, would not be annoyed.")


def spearman(a, b):
    def ranks(xs):
        order = sorted(range(len(xs)), key=lambda i: -xs[i])
        r = [0.0] * len(xs)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and xs[order[j + 1]] == xs[order[i]]:
                j += 1
            avg = (i + j) / 2 + 1
            for k in range(i, j + 1):
                r[order[k]] = avg
            i = j + 1
        return r

    ra, rb = ranks(a), ranks(b)
    n = len(a)
    ma, mb = sum(ra) / n, sum(rb) / n
    num = sum((ra[i] - ma) * (rb[i] - mb) for i in range(n))
    da = sum((x - ma) ** 2 for x in ra) ** 0.5
    db = sum((x - mb) ** 2 for x in rb) ** 0.5
    return num / (da * db) if da and db else 0.0


def compare(scored_path, sheet_path):
    data = json.loads(Path(scored_path).read_text())
    by_id = {p["id"]: p for p in data["places"]}
    verdicts = {}
    with open(sheet_path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            v = (row.get("verdict") or "").strip().lower()[:1]
            if v in ("y", "n", "m"):
                verdicts[row["id"]] = v

    graded = [by_id[i] for i in verdicts if i in by_id]
    if len(graded) < 10:
        raise SystemExit(f"only {len(graded)} graded rows - fill in more of grading.csv")

    human = {"y": 100, "m": 50, "n": 0}
    model_scores = [p["score"] for p in graded]
    human_scores = [human[verdicts[p["id"]]] for p in graded]

    # binary agreement: would the app have fired, and would you have wanted it to
    agree = sum(
        1 for p in graded
        if (p["score"] >= THRESHOLD) == (verdicts[p["id"]] == "y")
    )
    pct = 100 * agree / len(graded)

    ranked = sorted(graded, key=lambda p: -p["score"])[:10]
    hits = sum(1 for p in ranked if verdicts[p["id"]] == "y")

    print(f'Want: "{data["want"]}"')
    print(f"Graded: {len(graded)} places\n")
    print(f"  Agreement        {pct:.0f}%   (gate: 70%)")
    print(f"  Top-10 precision {hits}/10  (gate: 7)")
    print(f"  Rank correlation {spearman(model_scores, human_scores):+.2f}")

    misses = [p for p in graded if p["score"] >= THRESHOLD and verdicts[p["id"]] == "n"]
    buried = [p for p in graded if p["score"] < THRESHOLD and verdicts[p["id"]] == "y"]

    if misses:
        print(f"\nWould have alerted you wrongly ({len(misses)}) - these are the ones that kill the app:")
        for p in misses:
            print(f"  {p['score']:3d}  {p['name'][:36]:36s}  {p['reason'][:40]}")
    if buried:
        print(f"\nMissed, and you wanted them ({len(buried)}):")
        for p in buried:
            print(f"  {p['score']:3d}  {p['name'][:36]:36s}  {p['reason'][:40]}")

    print()
    if pct >= 70 and hits >= 7:
        print("PASS - the data supports the idea. Build the server.")
    else:
        print("FAIL - better data sources, not more app. See the spec's data section.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", action="store_true")
    ap.add_argument("--compare", action="store_true")
    ap.add_argument("--scored", default=str(HERE / "scored.json"))
    ap.add_argument("--csv", default=str(HERE / "grading.csv"))
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    if args.sheet:
        write_sheet(args.scored, args.csv, args.seed)
    elif args.compare:
        compare(args.scored, args.csv)
    else:
        ap.error("pass --sheet or --compare")


if __name__ == "__main__":
    main()
