import { DatabaseSync } from 'node:sqlite';
import { randomUUID } from 'node:crypto';
import { SCHEMA_SQL } from './schema.js';
import { config } from './config.js';
import { boundingBox, haversineM, type Point } from './lib/geo.js';

export type Db = DatabaseSync;

export function openDb(file: string = config.databaseFile): Db {
  const db = new DatabaseSync(file);
  db.exec(SCHEMA_SQL);
  addMissingColumns(db);
  ensureUser(db, config.defaultUserId);
  return db;
}

/**
 * CREATE TABLE IF NOT EXISTS does nothing to a table that already exists, so a
 * column added after someone has been running the server needs its own step.
 * ALTER TABLE ADD COLUMN errors if the column is there, hence the check.
 */
function addMissingColumns(db: Db): void {
  const wanted: Array<[table: string, column: string, type: string]> = [
    ['places', 'timezone', 'TEXT'],
  ];
  for (const [table, column, type] of wanted) {
    const existing = db.prepare(`PRAGMA table_info(${table})`).all() as unknown as {
      name: string;
    }[];
    if (!existing.some((c) => c.name === column)) {
      db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${type}`);
    }
  }
}

export function nowIso(): string {
  return new Date().toISOString();
}

export function ensureUser(db: Db, userId: string): void {
  db.prepare('INSERT OR IGNORE INTO users (id, created_at) VALUES (?, ?)').run(
    userId,
    nowIso(),
  );
}

export interface WantRow {
  id: string;
  raw_text: string;
  kind: string;
  criteria: string;
  restated: string | null;
  status: string;
  expires_at: string | null;
  created_at: string;
}

export function activeWants(db: Db, userId: string): WantRow[] {
  return db
    .prepare(
      `SELECT id, raw_text, kind, criteria, restated, status, expires_at, created_at
         FROM wants
        WHERE user_id = ?
          AND status = 'active'
          AND (expires_at IS NULL OR expires_at > ?)
        ORDER BY created_at DESC`,
    )
    .all(userId, nowIso()) as unknown as WantRow[];
}

export interface Candidate {
  placeId: string;
  wantId: string;
  name: string;
  lat: number;
  lng: number;
  score: number;
  reason: string;
  rating: number | null;
  distanceM: number;
  lastAlertedAt: string | null;
  /** Opening hours as the worker normalised them, or null when unknown. */
  hours: string | null;
  /** The IANA zone those hours are in, or null to mean the phone's own zone. */
  timezone: string | null;
}

/**
 * The fences the phone should be holding right now.
 *
 * SQL does what indexes make cheap — the bounding box, the score threshold, the
 * cooldown and the corrections. The exact distance and the ranking happen in JS,
 * because a bounding box over-selects by about 27% at the corners and the
 * ranking needs novelty, which does not express well as ORDER BY.
 */
export function candidateFences(db: Db, userId: string, centre: Point): Candidate[] {
  const wants = activeWants(db, userId);
  if (wants.length === 0) return [];

  const box = boundingBox(centre, config.candidateRadiusM);
  const wantIds = wants.map((w) => w.id);
  const placeholders = wantIds.map(() => '?').join(', ');
  const cooldownCutoff = new Date(
    Date.now() - config.cooldownDays * 86_400_000,
  ).toISOString();
  const notNowCutoff = new Date(
    Date.now() - config.notNowDays * 86_400_000,
  ).toISOString();

  const rows = db
    .prepare(
      `SELECT p.id    AS placeId,
              s.want_id AS wantId,
              p.name,
              p.lat,
              p.lng,
              p.rating,
              p.hours,
              p.timezone,
              s.score,
              s.reason,
              (SELECT MAX(a.fired_at) FROM alerts a
                WHERE a.user_id = ? AND a.place_id = p.id) AS lastAlertedAt
         FROM place_scores s
         JOIN places p ON p.id = s.place_id
        WHERE s.want_id IN (${placeholders})
          AND s.score >= ?
          AND p.lat BETWEEN ? AND ?
          AND p.lng BETWEEN ? AND ?
          AND NOT EXISTS (
                SELECT 1 FROM corrections c
                 WHERE c.user_id = ? AND c.place_id = p.id AND c.want_id = s.want_id)
          AND NOT EXISTS (
                SELECT 1 FROM alerts a
                 WHERE a.user_id = ? AND a.place_id = p.id
                   AND (a.fired_at > ?
                        OR (a.action = 'not_now' AND a.fired_at > ?)))`,
    )
    .all(
      userId,
      ...wantIds,
      config.scoreThreshold,
      box.minLat,
      box.maxLat,
      box.minLng,
      box.maxLng,
      userId,
      userId,
      cooldownCutoff,
      notNowCutoff,
    ) as unknown as Omit<Candidate, 'distanceM'>[];

  const user = db
    .prepare('SELECT home_lat, home_lng, work_lat, work_lng FROM users WHERE id = ?')
    .get(userId) as
    | { home_lat: number | null; home_lng: number | null; work_lat: number | null; work_lng: number | null }
    | undefined;

  const familiar: Point[] = [];
  if (user?.home_lat != null && user.home_lng != null) {
    familiar.push({ lat: user.home_lat, lng: user.home_lng });
  }
  if (user?.work_lat != null && user.work_lng != null) {
    familiar.push({ lat: user.work_lat, lng: user.work_lng });
  }

  const withDistance = rows
    .map((r) => ({ ...r, distanceM: Math.round(haversineM(centre, { lat: r.lat, lng: r.lng })) }))
    .filter((r) => r.distanceM <= config.candidateRadiusM)
    .filter((r) =>
      familiar.every(
        (f) => haversineM(f, { lat: r.lat, lng: r.lng }) > config.familiarRadiusM,
      ),
    );

  // One fence per place, keeping its best-scoring want.
  const best = new Map<string, Candidate>();
  for (const r of withDistance) {
    const existing = best.get(r.placeId);
    if (!existing || r.score > existing.score) best.set(r.placeId, r);
  }

  // Novelty beats fit on purpose: the fourth alert about the same excellent
  // place is worth less than the first about a new one.
  return [...best.values()]
    .sort((a, b) => {
      const aNew = a.lastAlertedAt ? 1 : 0;
      const bNew = b.lastAlertedAt ? 1 : 0;
      if (aNew !== bNew) return aNew - bNew;
      if (b.score !== a.score) return b.score - a.score;
      return a.distanceM - b.distanceM;
    })
    .slice(0, config.maxFences);
}

export function recordFenceSet(db: Db, userId: string, centre: Point): string {
  const id = randomUUID();
  // Rounded to about 2 km before it is stored: enough to run the product,
  // not enough to reconstruct where you were.
  const coarse = (n: number) => Math.round(n * 50) / 50;
  db.prepare(
    `INSERT INTO fence_sets (id, user_id, centre_lat, centre_lng, radius_m, created_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
  ).run(id, userId, coarse(centre.lat), coarse(centre.lng), config.umbrellaRadiusM, nowIso());
  return id;
}
