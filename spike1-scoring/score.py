#!/usr/bin/env python3
"""Compile a free-text want into criteria, then score every candidate against it.

This is the real two-stage pipeline from the spec, shrunk to one file:
  stage 1  claude-haiku-5-5   your sentence  ->  structured criteria
  stage 2  claude-sonnet-5-5  criteria + place metadata + reviews  ->  score + reason

  --dry-run  swaps stage 2 for a dumb keyword matcher and makes no API calls.
             Use it to check the plumbing, and as the baseline the LLM has to beat.
             If the keyword matcher scores as well as the model, you do not need
             the model.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).parent

EXTRACT_MODEL = "claude-haiku-5-5"
SCORE_MODEL = "claude-sonnet-5-5"
BATCH = 10

EXTRACT_PROMPT = """Turn this spoken want into match criteria for a places database.

Want: {want}

Reply with JSON only, no prose, in exactly this shape:
{{"category": "food|shopping|other",
  "place_types": ["google place types that would match"],
  "keywords": ["words that would appear in a review of a good match"],
  "exclusions": ["words that rule a place out"],
  "price_band": "any|cheap|moderate|expensive",
  "min_rating": 0.0,
  "restated": "one short line restating the want for the user to confirm"}}"""

SCORE_PROMPT = """You are scoring restaurants against one person's stated want.

Their want: {want}
Extracted criteria: {criteria}

Score each place from 0 to 100 for how well it satisfies the want. Be harsh:
80+ means you would stake your reputation on sending them there. Under 30 means
the want is not served here at all. Judge mainly on whether the specific thing
they asked for is actually available, then on quality, then on price fit.

The strongest evidence is review text that names the dish. A place type like
steak_house is weaker evidence. Absence of evidence is not evidence of absence,
but it should cap the score around 40.

Places:
{places}

Reply with a JSON array only, no prose, one object per place, in the same order:
[{{"id": "...", "score": 0, "reason": "under 12 words, names the evidence"}}]"""


def client():
    try:
        import anthropic
    except ImportError:
        sys.exit("pip install anthropic  (or run with --dry-run)")
    if not os.environ.get("ANTHROPIC_API_KEY"):
        sys.exit("ANTHROPIC_API_KEY is not set (or run with --dry-run)")
    return anthropic.Anthropic()


def parse_json(text, want_list=False):
    text = re.sub(r"^```(?:json)?|```$", "", text.strip(), flags=re.M).strip()
    opener, closer = ("[", "]") if want_list else ("{", "}")
    start, end = text.find(opener), text.rfind(closer)
    if start == -1 or end == -1:
        raise ValueError(f"no JSON found in model reply: {text[:200]}")
    return json.loads(text[start : end + 1])


def extract(c, want):
    msg = c.messages.create(
        model=EXTRACT_MODEL,
        max_tokens=2000,
        messages=[{"role": "user", "content": EXTRACT_PROMPT.format(want=want)}],
    )
    return parse_json("".join(b.text for b in msg.content if b.type == "text"))


def place_blob(p):
    bits = [f"id: {p['id']}", f"name: {p['name']}"]
    if p.get("primary_type"):
        bits.append(f"type: {p['primary_type']} ({', '.join(p.get('types', [])[:4])})")
    if p.get("rating"):
        bits.append(f"rating: {p['rating']} from {p.get('rating_count')} reviews")
    if p.get("price_level"):
        bits.append(f"price: {p['price_level']}")
    if p.get("summary"):
        bits.append(f"summary: {p['summary']}")
    for rv in p.get("reviews", [])[:6]:
        bits.append(f"review: {rv[:300]}")
    return "\n".join(bits)


def score_batch(c, want, criteria, places):
    blob = "\n\n".join(place_blob(p) for p in places)
    msg = c.messages.create(
        model=SCORE_MODEL,
        max_tokens=4000,
        messages=[
            {
                "role": "user",
                "content": SCORE_PROMPT.format(
                    want=want, criteria=json.dumps(criteria), places=blob
                ),
            }
        ],
    )
    return parse_json("".join(b.text for b in msg.content if b.type == "text"), want_list=True)


def dry_score(want, places):
    """The baseline the model has to beat: naive keyword counting."""
    words = [w for w in re.findall(r"[a-z]{4,}", want.lower()) if w not in ("good", "near", "under", "want", "need")]
    out = []
    for p in places:
        hay = " ".join([p["name"], p.get("summary", ""), " ".join(p.get("types", []))] + p.get("reviews", [])).lower()
        hits = sum(hay.count(w) for w in words)
        score = min(100, hits * 25) if hits else 0
        if score and p.get("rating"):
            score = min(100, int(score * (p["rating"] / 4.0)))
        out.append({"id": p["id"], "score": score, "reason": f"{hits} keyword hits"})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--want", default="craving a proper beef dish, not too expensive")
    ap.add_argument("--candidates", default=str(HERE / "candidates.json"))
    ap.add_argument("--out", default=str(HERE / "scored.json"))
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    places = json.loads(Path(args.candidates).read_text())
    print(f'{len(places)} candidates, want: "{args.want}"')

    if args.dry_run:
        criteria = {"note": "dry run, no extraction"}
        scores = dry_score(args.want, places)
        print("dry run: keyword baseline, no API calls")
    else:
        c = client()
        criteria = extract(c, args.want)
        print(f'  restated as: {criteria.get("restated", "?")}')
        scores = []
        for i in range(0, len(places), BATCH):
            chunk = places[i : i + BATCH]
            scores.extend(score_batch(c, args.want, criteria, chunk))
            print(f"  scored {min(i + BATCH, len(places))}/{len(places)}")

    by_id = {s["id"]: s for s in scores}
    rows = []
    for p in places:
        s = by_id.get(p["id"], {"score": 0, "reason": "not scored"})
        rows.append({**p, "score": s["score"], "reason": s["reason"]})
    rows.sort(key=lambda r: -r["score"])

    Path(args.out).write_text(
        json.dumps({"want": args.want, "criteria": criteria, "places": rows}, indent=2, ensure_ascii=False)
    )
    print(f"\n-> {args.out}\n")
    print("Top 10:")
    for r in rows[:10]:
        print(f"  {r['score']:3d}  {r['name'][:38]:38s}  {r['reason'][:44]}")


if __name__ == "__main__":
    main()
