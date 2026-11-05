#!/usr/bin/env python3
"""Fill and refresh the shared `places` table.

Two jobs in one, both of which have to happen on a schedule:

  fetch   pull every restaurant around a point into `places`
  prune   delete rows older than the caching window

The prune is not housekeeping, it is the licence terms. Google lets you keep a
place ID indefinitely but requires most other cached content to be refreshed or
deleted on roughly a 30-day cycle, so a row that goes stale must leave rather
than quietly keep serving alerts.

  python3 refresh_places.py --db ../server/taste-radar.db --source osm
  python3 refresh_places.py --db ../server/taste-radar.db --prune-only

Review text is NOT written to the database. It is dumped to --reviews-out for
the scorer to read on the same run and then discard, so the only thing that
persists is a score and a one-line reason.

(The Google and Overpass queries here are a deliberate second copy of the ones
in spike1-scoring/fetch.py. The spike is frozen and deletable; the server should
not depend on it.)
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
import time
from datetime import datetime, timedelta, timezone

CACHE_WINDOW_DAYS = 30

# Three circles over the part of Chennai with the most Kerala, Muslim and
# Anglo-Indian kitchens.
CHENNAI_TILES = [
    {"name": "Nungambakkam / Thousand Lights", "lat": 13.0604, "lng": 80.2496},
    {"name": "Teynampet / Royapettah", "lat": 13.0418, "lng": 80.2510},
    {"name": "Triplicane / Chepauk", "lat": 13.0569, "lng": 80.2740},
]

GOOGLE_FIELDS = ",".join(
    [
        "places.id",
        "places.displayName",
        "places.location",
        "places.rating",
        "places.userRatingCount",
        "places.priceLevel",
        "places.types",
        "places.primaryType",
        "places.editorialSummary",
        "places.reviews",
    ]
)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def fetch_google(tiles, radius_m):
    import requests

    key = os.environ.get("GOOGLE_PLACES_API_KEY")
    if not key:
        sys.exit("GOOGLE_PLACES_API_KEY is not set. Use --source osm or --source fixture.")

    out = {}
    for tile in tiles:
        body = {
            "includedTypes": ["restaurant"],
            "maxResultCount": 20,
            "locationRestriction": {
                "circle": {
                    "center": {"latitude": tile["lat"], "longitude": tile["lng"]},
                    "radius": float(radius_m),
                }
            },
        }
        r = requests.post(
            "https://places.googleapis.com/v1/places:searchNearby",
            headers={
                "Content-Type": "application/json",
                "X-Goog-Api-Key": key,
                "X-Goog-FieldMask": GOOGLE_FIELDS,
            },
            json=body,
            timeout=30,
        )
        if r.status_code != 200:
            sys.exit(f"Places API {r.status_code}: {r.text[:400]}")
        places = r.json().get("places", [])
        print(f"  {tile['name']}: {len(places)}")
        for p in places:
            reviews = [
                rv.get("text", {}).get("text", "")
                for rv in (p.get("reviews") or [])
                if rv.get("text")
            ]
            out[p["id"]] = {
                "id": p["id"],
                "source": "google",
                "name": p.get("displayName", {}).get("text", "(unnamed)"),
                "lat": p.get("location", {}).get("latitude"),
                "lng": p.get("location", {}).get("longitude"),
                "rating": p.get("rating"),
                "rating_count": p.get("userRatingCount"),
                "price_level": p.get("priceLevel"),
                "types": p.get("types", []),
                "primary_type": p.get("primaryType"),
                "summary": (p.get("editorialSummary") or {}).get("text", ""),
                "reviews": reviews[:10],
            }
        time.sleep(0.3)
    return list(out.values())


def fetch_osm(tiles, radius_m):
    import requests

    clauses = []
    for t in tiles:
        for amenity in ("restaurant", "fast_food"):
            clauses.append(
                f'node["amenity"="{amenity}"](around:{radius_m},{t["lat"]},{t["lng"]});'
            )
    query = "[out:json][timeout:60];(" + "".join(clauses) + ");out body;"
    r = requests.post(
        "https://overpass-api.de/api/interpreter", data={"data": query}, timeout=90
    )
    if r.status_code != 200:
        sys.exit(f"Overpass {r.status_code}: {r.text[:400]}")
    elements = r.json().get("elements", [])
    print(f"  overpass: {len(elements)} nodes")
    out = []
    for e in elements:
        tags = e.get("tags", {})
        if not tags.get("name"):
            continue
        out.append(
            {
                "id": f"osm:{e['id']}",
                "source": "osm",
                "name": tags["name"],
                "lat": e.get("lat"),
                "lng": e.get("lon"),
                "rating": None,
                "rating_count": None,
                "price_level": None,
                "types": [v for k, v in tags.items() if k in ("amenity", "cuisine")],
                "primary_type": tags.get("amenity"),
                "summary": tags.get("description", ""),
                "reviews": [],
            }
        )
    return out


def upsert(con, rows) -> tuple[int, int]:
    before = con.execute("SELECT COUNT(*) FROM places").fetchone()[0]
    con.executemany(
        """INSERT INTO places (id, source, name, lat, lng, types, primary_type,
                               price_level, rating, rating_count, summary, fetched_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(id) DO UPDATE SET
             name = excluded.name, lat = excluded.lat, lng = excluded.lng,
             types = excluded.types, primary_type = excluded.primary_type,
             price_level = excluded.price_level, rating = excluded.rating,
             rating_count = excluded.rating_count, summary = excluded.summary,
             fetched_at = excluded.fetched_at""",
        [
            (
                r["id"], r["source"], r["name"], r["lat"], r["lng"],
                json.dumps(r.get("types", [])), r.get("primary_type"),
                r.get("price_level"), r.get("rating"), r.get("rating_count"),
                r.get("summary", ""), now_iso(),
            )
            for r in rows
            if r.get("lat") is not None and r.get("lng") is not None
        ],
    )
    con.commit()
    after = con.execute("SELECT COUNT(*) FROM places").fetchone()[0]
    return after - before, len(rows)


def prune(con) -> int:
    cutoff = (datetime.now(timezone.utc) - timedelta(days=CACHE_WINDOW_DAYS)).isoformat()
    stale = con.execute("SELECT COUNT(*) FROM places WHERE fetched_at < ?", (cutoff,)).fetchone()[0]
    if stale:
        con.execute("DELETE FROM place_scores WHERE place_id IN (SELECT id FROM places WHERE fetched_at < ?)", (cutoff,))
        con.execute("DELETE FROM places WHERE fetched_at < ?", (cutoff,))
        con.commit()
    return stale


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default="../server/taste-radar.db")
    ap.add_argument("--source", choices=["google", "osm", "fixture"], default="google")
    ap.add_argument("--radius", type=int, default=1200)
    ap.add_argument("--reviews-out", default="reviews.json")
    ap.add_argument("--prune-only", action="store_true")
    ap.add_argument("--fixture", default="../spike1-scoring/fixture_candidates.json")
    args = ap.parse_args()

    con = sqlite3.connect(args.db)

    if args.prune_only:
        print(f"pruned {prune(con)} rows older than {CACHE_WINDOW_DAYS} days")
        con.close()
        return

    print(f"fetching from {args.source} ...")
    if args.source == "google":
        rows = fetch_google(CHENNAI_TILES, args.radius)
    elif args.source == "osm":
        rows = fetch_osm(CHENNAI_TILES, args.radius)
    else:
        rows = json.loads(open(args.fixture, encoding="utf-8").read())

    added, seen = upsert(con, rows)
    pruned = prune(con)

    with open(args.reviews_out, "w", encoding="utf-8") as f:
        json.dump(
            [{"id": r["id"], "reviews": r.get("reviews", [])} for r in rows],
            f, ensure_ascii=False, indent=2,
        )

    total = con.execute("SELECT COUNT(*) FROM places").fetchone()[0]
    con.close()
    print(f"\n{seen} fetched, {added} new, {pruned} stale rows pruned, {total} in the table")
    print(f"review text for this run -> {args.reviews_out} (not stored in the database)")
    print(f"next: python3 score_places.py --db {args.db} --reviews-from {args.reviews_out}")


if __name__ == "__main__":
    main()
