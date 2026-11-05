# Porting to Postgres

Only worth doing when more than one person writes at once, or when you want a
managed backup story. For a single user, SQLite is the better answer and this
document is insurance, not a plan.

Everything that touches the database is in `server/src/db.ts`, `server/src/schema.ts`
and the two Python workers. Nothing else needs to change.

## The schema

| SQLite | Postgres |
| --- | --- |
| `TEXT PRIMARY KEY` holding a UUID | `UUID PRIMARY KEY DEFAULT gen_random_uuid()` |
| `TEXT` timestamps in ISO-8601 | `TIMESTAMPTZ` |
| `TEXT` columns holding JSON (`criteria`, `types`, `settings`) | `JSONB` |
| `REAL` lat/lng plus two indexes | keep as-is, or `GEOGRAPHY(POINT, 4326)` with a GiST index |
| `PRAGMA journal_mode = WAL` | drop it |
| `CHECK (... IN (...))` | keep, or promote to an `ENUM` |

## The queries

- Placeholders change from `?` to `$1, $2, …`. This is the only change in most
  statements, and the one most likely to be missed in the `IN (...)` list that
  `candidateFences` builds dynamically.
- `INSERT OR IGNORE` becomes `INSERT ... ON CONFLICT DO NOTHING`.
- `INSERT OR REPLACE` becomes `INSERT ... ON CONFLICT (...) DO UPDATE SET ...`.
  The `ON CONFLICT ... DO UPDATE` statements already written are valid Postgres.
- `db.prepare(...).run()` / `.get()` / `.all()` become `pool.query(...)` and all
  of it becomes asynchronous. `candidateFences` and `recordFenceSet` are the only
  synchronous functions that need to become `async`.

## If you adopt PostGIS as well

Replace the bounding box plus haversine in `candidateFences` with:

```sql
WHERE ST_DWithin(p.location, ST_MakePoint($1, $2)::geography, $3)
```

and drop `boundingBox` from `server/src/lib/geo.ts`. Keep `haversineM` — the API
still reports a distance per fence, and the tests cover it.

This is a real win above roughly 100,000 places or when you need radius queries
that are not centred on the user. Below that it is slower than the bounding box,
because the planner work exceeds the scan it saves.

## What stays the same

Row-level security is the reason every table already carries `user_id`. Once on
Postgres, enable it per table and key the policy to the authenticated user, and a
forgotten `WHERE` clause stops being able to leak across accounts. That is the
actual argument for Postgres, and it only matters once there is more than one
account.
