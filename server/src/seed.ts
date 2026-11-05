/**
 * Load the spike's synthetic Chennai fixture into `places`, so the API can be
 * exercised end to end with no Google key and no network.
 *
 * The names are obviously fictional ("Fixture Malabar Mess") on purpose — this
 * is plumbing test data, never to be mistaken for real place data.
 */
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { openDb, nowIso } from './db.js';

interface FixturePlace {
  id: string;
  source: string;
  name: string;
  lat: number;
  lng: number;
  rating: number | null;
  rating_count: number | null;
  price_level: string | null;
  types: string[];
  primary_type: string | null;
  summary: string;
  reviews: string[];
}

const here = dirname(fileURLToPath(import.meta.url));

/** Walk up looking for the spike's fixture, so this works from src/ or dist/. */
function findFixture(): string {
  const relative = 'spike1-scoring/fixture_candidates.json';
  let dir = here;
  for (let i = 0; i < 6; i++) {
    const candidate = resolve(dir, relative);
    if (existsSync(candidate)) return candidate;
    dir = resolve(dir, '..');
  }
  throw new Error(
    `could not find ${relative} above ${here} - pass a path as the first argument`,
  );
}

const fixturePath = process.argv[2] ?? findFixture();

const places = JSON.parse(readFileSync(fixturePath, 'utf8')) as FixturePlace[];
const db = openDb();

const insert = db.prepare(
  `INSERT INTO places (id, source, name, lat, lng, types, primary_type, price_level,
                       rating, rating_count, summary, fetched_at)
   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
   ON CONFLICT(id) DO UPDATE SET
     name = excluded.name, lat = excluded.lat, lng = excluded.lng,
     rating = excluded.rating, fetched_at = excluded.fetched_at`,
);

db.exec('BEGIN');
for (const p of places) {
  insert.run(
    p.id,
    p.source,
    p.name,
    p.lat,
    p.lng,
    JSON.stringify(p.types),
    p.primary_type,
    p.price_level,
    p.rating,
    p.rating_count,
    p.summary,
    nowIso(),
  );
}
db.exec('COMMIT');

console.log(`seeded ${places.length} places from ${fixturePath}`);
console.log('reviews are NOT stored - the scorer reads them from the fixture, by design');
db.close();
