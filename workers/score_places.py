#!/usr/bin/env python3
"""Score every unscored place against every active want, and write place_scores.

This is the job that costs money and takes time, which is exactly why it runs
here and not in the alert path. By the time the phone asks for a fence set, the
answer is already a row in a table.

  python3 score_places.py --db ../server/taste-radar.db --dry-run \
      --reviews-from ../spike1-scoring/fixture_candidates.json

  --dry-run        keyword baseline, no API calls, no key needed. Also the
                   baseline the model has to beat.
  --reviews-from   review text for scoring, read from a fetch dump. Reviews are
                   deliberately not stored in the database, so they are supplied
                   per run and discarded; only the score and reason persist.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import sys
from datetime import datetime, timezone

SCORE_MODEL = "claude-sonnet-5-5"
PROMPT_VERSION = "score-v1"
BATCH = 10

SCORE_PROMPT = """You are scoring places against one person's stated want.

Their want: {want}
Extracted criteria: {criteria}

Score each place from 0 to 100 for how well it satisfies the want. Be harsh:
80+ means you would stake your reputation on sending them there. Under 30 means
the want is not served here at all. Judge mainly on whether the specific thing
they asked for is actually available, then on quality, then on price fit.

The strongest evidence is review text that names the dish. A place type like
steak_house is weaker evidence. Absence of evidence is not evidence of absence,
but it should cap the score around 40. A review that says the thing is NOT
served is strong evidence against, however positive the rest of it is.

Places:
{places}

Reply with a JSON array only, no prose, one object per place, in the same order:
[{{"id": "...", "score": 0, "reason": "under 12 words, names the evidence"}}]"""


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_json_array(text: str):
    text = re.sub(r"```(?:json)?", "", text).strip()
    start, end = text.find("["), text.rfind("]")
    if start == -1 or end == -1:
        raise ValueError(f"no JSON array in reply: {text[:200]}")
    return json.loads(text[start : end + 1])


def place_blob(p: dict, reviews: list[str]) -> str:
    bits = [f"id: {p['id']}", f"name: {p['name']}"]
    if p.get("primary_type"):
        bits.append(f"type: {p['primary_type']}")
    if p.get("rating"):
        bits.append(f"rating: {p['rating']} from {p.get('rating_count')} reviews")
    if p.get("price_level"):
        bits.append(f"price: {p['price_level']}")
    if p.get("summary"):
        bits.append(f"summary: {p['summary']}")
    for rv in reviews[:6]:
        bits.append(f"review: {rv[:300]}")
    return "\n".join(bits)


def dry_score(want_text: str, criteria: dict, places: list[dict], reviews: dict) -> list[dict]:
    """Keyword counting. Cannot read negation - that is the point."""
    words = [w for w in criteria.get("keywords", []) if len(w) >= 4]
    if not words:
        words = [w for w in re.findall(r"[a-z]{4,}", want_text.lower())]
    out = []
    for p in places:
        hay = " ".join(
            [p["name"], p.get("summary") or "", p.get("primary_type") or ""]
            + reviews.get(p["id"], [])
        ).lower()
        hits = sum(hay.count(w) for w in words)
        score = min(100, hits * 25) if hits else 0
        if score and p.get("rating"):
            score = min(100, int(score * (p["rating"] / 4.0)))
        out.append({"id": p["id"], "score": score, "reason": f"{hits} keyword hits (baseline)"})
    return out


def model_score(want_text: str, criteria: dict, places: list[dict], reviews: dict) -> list[dict]:
    try:
        import anthropic
    except ImportError:
        sys.exit("pip install anthropic  (or run with --dry-run)")
    if not os.environ.get("ANTHROPIC_API_KEY"):
        sys.exit("ANTHROPIC_API_KEY is not set (or run with --dry-run)")

    client = anthropic.Anthropic()
    scored: list[dict] = []
    for i in range(0, len(places), BATCH):
        chunk = places[i : i + BATCH]
        blob = "\n\n".join(place_blob(p, reviews.get(p["id"], [])) for p in chunk)
        msg = client.messages.create(
            model=SCORE_MODEL,
            max_tokens=4000,
            messages=[
                {
                    "role": "user",
                    "content": SCORE_PROMPT.format(
                        want=want_text, criteria=json.dumps(criteria), places=blob
                    ),
                }
            ],
        )
        text = "".join(b.text for b in msg.content if b.type == "text")
        scored.extend(parse_json_array(text))
        print(f"    scored {min(i + BATCH, len(places))}/{len(places)}")
    return scored


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="../server/taste-radar.db")
    ap.add_argument("--reviews-from")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--rescore", action="store_true", help="also re-score pairs already scored")
    args = ap.parse_args()

    reviews: dict[str, list[str]] = {}
    if args.reviews_from:
        for row in json.loads(open(args.reviews_from, encoding="utf-8").read()):
            reviews[row["id"]] = row.get("reviews", [])

    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row

    wants = con.execute(
        """SELECT id, raw_text, criteria FROM wants
            WHERE status = 'active'
              AND (expires_at IS NULL OR expires_at > ?)""",
        (now_iso(),),
    ).fetchall()
    if not wants:
        print("no active wants - nothing to score")
        return

    version = f"{PROMPT_VERSION}/{'baseline' if args.dry_run else SCORE_MODEL}"

    for want in wants:
        criteria = json.loads(want["criteria"] or "{}")
        if args.rescore:
            places = con.execute("SELECT * FROM places").fetchall()
        else:
            places = con.execute(
                """SELECT p.* FROM places p
                    WHERE NOT EXISTS (SELECT 1 FROM place_scores s
                                       WHERE s.want_id = ? AND s.place_id = p.id)""",
                (want["id"],),
            ).fetchall()

        print(f'want "{want["raw_text"][:50]}": {len(places)} places to score')
        if not places:
            continue

        rows = [dict(p) for p in places]
        scored = (dry_score if args.dry_run else model_score)(
            want["raw_text"], criteria, rows, reviews
        )

        con.executemany(
            """INSERT INTO place_scores (want_id, place_id, score, reason, model_version, computed_at)
               VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT(want_id, place_id) DO UPDATE SET
                 score = excluded.score, reason = excluded.reason,
                 model_version = excluded.model_version, computed_at = excluded.computed_at""",
            [
                (want["id"], s["id"], max(0, min(100, int(s["score"]))), s.get("reason", ""), version, now_iso())
                for s in scored
                if s.get("id")
            ],
        )
        con.commit()

        kept = sum(1 for s in scored if s["score"] >= 70)
        print(f"  wrote {len(scored)} scores, {kept} at or above the alert threshold")

    con.close()


if __name__ == "__main__":
    main()
