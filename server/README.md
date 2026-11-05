# Server

The part that knows what matches. The phone asks it one question — *what should
I be watching around here?* — and gets back an umbrella fence plus up to fifteen
scored places.

Runs today. Tested end to end against a live server.

## Quick start

```bash
npm install
```

Set a token first, or the server will only listen on loopback and the phone will
not be able to reach it:

```bash
echo "AUTH_TOKEN=$(openssl rand -hex 32)" >> .env
```

```bash
npm run build && npm run migrate
```

```bash
npm run seed
```

```bash
npm start
```

Then, from `../workers`, score what you seeded and ask for a fence set:

```bash
python3 score_places.py --db ../server/taste-radar.db --dry-run --reviews-from ../spike1-scoring/fixture_candidates.json
```

```bash
curl -X POST localhost:5090/fence-set -H 'content-type: application/json' -d '{"lat":13.0604,"lng":80.2496}'
```

Every request needs `Authorization: Bearer $AUTH_TOKEN`, so the curl above
becomes:

```bash
curl -X POST localhost:5090/fence-set -H "content-type: application/json" -H "authorization: Bearer $AUTH_TOKEN" -d '{"lat":13.0604,"lng":80.2496}'
```

No Google or Anthropic keys are needed for any of that. `POST /wants` falls back to a keyword
extractor without `ANTHROPIC_API_KEY`, and says so in its response rather than
pretending a model read your sentence.

## Endpoints

| Method | Path | What it does |
| --- | --- | --- |
| `GET` | `/health` | Row counts, the live thresholds, and whether extraction is using the model |
| `POST` | `/wants` | Free text in. Compiles it to criteria, returns the restatement to confirm |
| `GET` | `/wants` | Active wants |
| `DELETE` | `/wants/:id` | Retires a want; its fences disappear on the next request |
| `POST` | `/fence-set` | **The one the phone depends on.** Lat/lng in, umbrella + fences out |
| `POST` | `/alerts` | The phone reports an alert it has shown |
| `POST` | `/alerts/:id/feedback` | `going` / `not_now` / `wrong` / `ignored` |

## Where the gates live

Split by what each side knows. The server owns what needs history: the score
threshold, the 30-day place cooldown, the 90-day silence after a *Not now*, the
permanent exclusion after a *Wrong*, and the home/work suppression. The phone
owns what needs the moment: time of day, whether you are doing 60 km/h, whether
the place is open, quiet hours, and the two-a-day rate limit.

Every server-side threshold is in `src/config.ts`, read from the environment, so
they can be tuned without shipping an app build.

## SQLite, not Postgres

The spec called for Postgres + PostGIS. That is right once several people are
writing at once; for one person in one city it is a daemon to run and nothing
gained. So this uses SQLite — no server process, one file to back up, and the
same choice EquiStar already makes.

Nothing is painted into a corner. Every table carries a `user_id`, the SQL is
plain, and all of it sits behind `src/db.ts`. See
[PORTING-TO-POSTGRES.md](../PORTING-TO-POSTGRES.md) for the exact changes and
when they become worth making.

The spatial query is a bounding box on `(lat, lng)` followed by an exact
haversine in `src/lib/geo.ts`, rather than `ST_DWithin`. At a few thousand
places that is faster than PostGIS would be, and it drops an extension
dependency that many managed Postgres offerings make awkward.

## Caveats

- `node:sqlite` is still marked experimental in Node 22. It is stable in Node 24.
  If that bothers you, `better-sqlite3` is a drop-in for `src/db.ts` alone.
- Authentication is one shared bearer token, which is the right size for one
  user and no accounts. With no token set the server binds to loopback only, so
  the open configuration cannot be reached from the network by accident.
- There is no rate limiting on token attempts. Behind a tunnel on a home network
  that is proportionate; it would not be if this were public.
