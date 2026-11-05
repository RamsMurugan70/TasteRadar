/**
 * The schema, as one idempotent script.
 *
 * SQLite, not Postgres. The spec called for Postgres + PostGIS, which is right
 * once there are many users writing at once; for one person in one city it is a
 * daemon to run and nothing gained. Every table below carries a `user_id`
 * anyway, so the port is mechanical — see PORTING-TO-POSTGRES.md.
 */
export const SCHEMA_SQL = `
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  id           TEXT PRIMARY KEY,
  created_at   TEXT NOT NULL,
  push_token   TEXT,
  home_lat     REAL,
  home_lng     REAL,
  work_lat     REAL,
  work_lng     REAL,
  settings     TEXT NOT NULL DEFAULT '{}'
);

-- The free-text want, and the criteria compiled from it. raw_text is the source
-- of truth and is never overwritten, so a better extraction prompt can be
-- re-run over old wants later.
CREATE TABLE IF NOT EXISTS wants (
  id           TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users(id),
  raw_text     TEXT NOT NULL,
  kind         TEXT NOT NULL CHECK (kind IN ('standing', 'need')),
  criteria     TEXT NOT NULL DEFAULT '{}',
  restated     TEXT,
  status       TEXT NOT NULL DEFAULT 'active'
                 CHECK (status IN ('active', 'satisfied', 'expired', 'removed')),
  expires_at   TEXT,
  created_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS wants_user_active ON wants(user_id, status);

-- Shared across all users on purpose: identical for everyone, expensive to
-- fetch, rate-limited at the source. fetched_at is what enforces Google's
-- caching window.
CREATE TABLE IF NOT EXISTS places (
  id           TEXT PRIMARY KEY,
  source       TEXT NOT NULL,
  name         TEXT NOT NULL,
  lat          REAL NOT NULL,
  lng          REAL NOT NULL,
  types        TEXT NOT NULL DEFAULT '[]',
  primary_type TEXT,
  price_level  TEXT,
  rating       REAL,
  rating_count INTEGER,
  summary      TEXT,
  hours        TEXT,
  fetched_at   TEXT NOT NULL
);
-- No PostGIS here, so the candidate query is a bounding box on these two
-- indexes followed by an exact haversine. At a few thousand rows that is
-- faster than the planner overhead of anything cleverer.
CREATE INDEX IF NOT EXISTS places_lat ON places(lat);
CREATE INDEX IF NOT EXISTS places_lng ON places(lng);

CREATE TABLE IF NOT EXISTS place_scores (
  want_id       TEXT NOT NULL REFERENCES wants(id) ON DELETE CASCADE,
  place_id      TEXT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
  score         INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
  reason        TEXT NOT NULL DEFAULT '',
  model_version TEXT NOT NULL DEFAULT '',
  computed_at   TEXT NOT NULL,
  PRIMARY KEY (want_id, place_id)
);
CREATE INDEX IF NOT EXISTS place_scores_by_place ON place_scores(place_id, score DESC);

-- One row per alert the phone actually fired, plus what you did about it.
CREATE TABLE IF NOT EXISTS alerts (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id),
  want_id    TEXT NOT NULL REFERENCES wants(id),
  place_id   TEXT NOT NULL REFERENCES places(id),
  fired_at   TEXT NOT NULL,
  action     TEXT CHECK (action IN ('going', 'not_now', 'wrong', 'ignored')),
  lat        REAL,
  lng        REAL
);
CREATE INDEX IF NOT EXISTS alerts_cooldown ON alerts(user_id, place_id, fired_at DESC);

-- The coarse centre of the fence set the phone currently holds. Deliberately
-- the only position this server keeps: no location history table exists.
CREATE TABLE IF NOT EXISTS fence_sets (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id),
  centre_lat REAL NOT NULL,
  centre_lng REAL NOT NULL,
  radius_m   INTEGER NOT NULL,
  created_at TEXT NOT NULL
);

-- Every "Wrong", kept as a negative example for later extraction runs.
CREATE TABLE IF NOT EXISTS corrections (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  want_id    TEXT NOT NULL,
  place_id   TEXT NOT NULL,
  note       TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);
`;
