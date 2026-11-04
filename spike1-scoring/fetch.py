#!/usr/bin/env python3
"""Pull restaurant candidates for a Chennai area into candidates.json.

Two sources, both optional:
  --source google   Google Places API (New). Needs GOOGLE_PLACES_API_KEY.
                    Richest data: rating, review count, price level, review text.
  --source osm      Overpass / OpenStreetMap. No key, no ratings, no reviews.
                    Use this to see how far the free fallback gets you.
  --source fixture  Bundled offline sample, for testing the pipeline with no network.

The point of the spike is DISCRIMINATION, so this deliberately fetches every
restaurant in the area, not only the ones that look like they serve beef. If the
scorer cannot separate them, the idea does not work.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

HERE = Path(__file__).parent

# Three overlapping circles over the part of Chennai with the most Kerala,
# Muslim and Anglo-Indian kitchens. Adjust freely - these are just a starting box.
CHENNAI_TILES = [
    {"name": "Nungambakkam / Thousand Lights", "lat": 13.0604, "lng": 80.2496},
    {"name": "Teynampet / Royapettah", "lat": 13.0418, "lng": 80.2510},
    {"name": "Triplicane / Chepauk", "lat": 13.0569, "lng": 80.2740},
]
TILE_RADIUS_M = 1200

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


def fetch_google(tiles, radius_m, verbose=True):
    import requests

    key = os.environ.get("GOOGLE_PLACES_API_KEY")
    if not key:
        sys.exit("GOOGLE_PLACES_API_KEY is not set. Export it, or use --source osm.")

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
        if verbose:
            print(f"  {tile['name']}: {len(places)} places")
        for p in places:
            out[p["id"]] = normalise_google(p, tile["name"])
        time.sleep(0.3)
    return list(out.values())


def normalise_google(p, area):
    reviews = [
        rv.get("text", {}).get("text", "")
        for rv in (p.get("reviews") or [])
        if rv.get("text")
    ]
    return {
        "id": p["id"],
        "source": "google",
        "name": p.get("displayName", {}).get("text", "(unnamed)"),
        "area": area,
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


def fetch_osm(tiles, radius_m, verbose=True):
    import requests

    clauses = []
    for t in tiles:
        for amenity in ("restaurant", "fast_food"):
            clauses.append(
                f'node["amenity"="{amenity}"](around:{radius_m},{t["lat"]},{t["lng"]});'
            )
    query = "[out:json][timeout:60];(" + "".join(clauses) + ");out body;"
    r = requests.post(
        "https://overpass-api.de/api/interpreter",
        data={"data": query},
        timeout=90,
    )
    if r.status_code != 200:
        sys.exit(f"Overpass {r.status_code}: {r.text[:400]}")
    elements = r.json().get("elements", [])
    if verbose:
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
                "area": "chennai",
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", choices=["google", "osm", "fixture"], default="google")
    ap.add_argument("--radius", type=int, default=TILE_RADIUS_M)
    ap.add_argument("--out", default=str(HERE / "candidates.json"))
    args = ap.parse_args()

    print(f"Fetching Chennai restaurants from {args.source} ...")
    if args.source == "google":
        rows = fetch_google(CHENNAI_TILES, args.radius)
    elif args.source == "osm":
        rows = fetch_osm(CHENNAI_TILES, args.radius)
    else:
        rows = json.loads((HERE / "fixture_candidates.json").read_text())

    Path(args.out).write_text(json.dumps(rows, indent=2, ensure_ascii=False))
    with_reviews = sum(1 for r in rows if r["reviews"])
    print(f"\n{len(rows)} candidates -> {args.out}")
    print(f"{with_reviews} of them carry review text (the strongest beef signal)")
    if args.source == "osm" and rows:
        cuisines = sum(1 for r in rows if any(t not in ("restaurant", "fast_food") for t in r["types"]))
        print(f"{cuisines} carry a cuisine tag - that is all OSM gives you to work with")


if __name__ == "__main__":
    main()
