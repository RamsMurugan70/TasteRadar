import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { openDb, nowIso, type Db } from '../src/db.js';
import { buildApp } from '../src/app.js';
import { config } from '../src/config.js';
import type { FastifyInstance } from 'fastify';

let db: Db;
let app: FastifyInstance;
let wantId: string;

/** Three fixture places at known distances from the test centre. */
const CENTRE = { lat: 13.0604, lng: 80.2496 };
const PLACES = [
  { id: 'p-near', name: 'Near Mess', lat: 13.0614, lng: 80.2506 },      // ~150 m
  { id: 'p-mid', name: 'Mid Mess', lat: 13.0704, lng: 80.2596 },        // ~1.5 km
  { id: 'p-far', name: 'Far Mess', lat: 13.2004, lng: 80.4496 },        // ~30 km, outside
];

function seedPlace(p: { id: string; name: string; lat: number; lng: number }): void {
  db.prepare(
    `INSERT OR REPLACE INTO places (id, source, name, lat, lng, rating, fetched_at)
     VALUES (?, 'test', ?, ?, ?, 4.4, ?)`,
  ).run(p.id, p.name, p.lat, p.lng, nowIso());
}

function score(placeId: string, value: number): void {
  db.prepare(
    `INSERT OR REPLACE INTO place_scores (want_id, place_id, score, reason, computed_at)
     VALUES (?, ?, ?, 'test reason', ?)`,
  ).run(wantId, placeId, value, nowIso());
}

async function fenceSet(): Promise<{ fences: { placeId: string; score: number }[] }> {
  const res = await app.inject({ method: 'POST', url: '/fence-set', payload: CENTRE });
  assert.equal(res.statusCode, 200);
  return res.json();
}

before(async () => {
  db = openDb(':memory:');
  app = buildApp(db);
  await app.ready();
});

after(async () => {
  await app.close();
  db.close();
});

test('health reports an empty database', async () => {
  const res = await app.inject({ method: 'GET', url: '/health' });
  assert.equal(res.statusCode, 200);
  const body = res.json();
  assert.equal(body.ok, true);
  assert.equal(body.places, 0);
  assert.equal(body.activeWants, 0);
});

test('a want is compiled and stored', async () => {
  const res = await app.inject({
    method: 'POST',
    url: '/wants',
    payload: { text: 'craving a proper beef fry, under 500 a head', kind: 'need' },
  });
  assert.equal(res.statusCode, 201);
  const body = res.json();
  wantId = body.id;
  assert.ok(wantId);
  // No ANTHROPIC_API_KEY in the test environment, so the fallback must be used
  // AND must say so rather than pretending a model read the sentence.
  assert.equal(body.extractedBy, 'keywords');
  assert.equal(body.criteria.category, 'food');
  assert.ok(body.criteria.keywords.includes('beef'));
  assert.equal(body.needsScoring, true);
});

test('a bad want is rejected', async () => {
  const res = await app.inject({ method: 'POST', url: '/wants', payload: { text: 'x' } });
  assert.equal(res.statusCode, 400);
});

test('no scores means no fences, even with places nearby', async () => {
  PLACES.forEach(seedPlace);
  const body = await fenceSet();
  assert.deepEqual(body.fences, []);
});

test('only places over the threshold and inside the radius become fences', async () => {
  score('p-near', 85);
  score('p-mid', 72);
  score('p-far', 99);   // high score but 30 km away
  const body = await fenceSet();
  const ids = body.fences.map((f) => f.placeId);
  assert.deepEqual(ids, ['p-near', 'p-mid'], 'far place must be excluded by distance');
});

test('a below-threshold score is not a fence', async () => {
  score('p-mid', config.scoreThreshold - 1);
  const ids = (await fenceSet()).fences.map((f) => f.placeId);
  assert.deepEqual(ids, ['p-near']);
  score('p-mid', 72);
});

test('an alert puts the place on cooldown', async () => {
  const created = await app.inject({
    method: 'POST',
    url: '/alerts',
    payload: { placeId: 'p-near', wantId },
  });
  assert.equal(created.statusCode, 201);

  const ids = (await fenceSet()).fences.map((f) => f.placeId);
  assert.ok(!ids.includes('p-near'), 'a just-alerted place must not come back');
  assert.deepEqual(ids, ['p-mid']);
});

test('an alert for an unknown place is refused', async () => {
  const res = await app.inject({
    method: 'POST',
    url: '/alerts',
    payload: { placeId: 'nope', wantId },
  });
  assert.equal(res.statusCode, 404);
});

test('"wrong" zeroes the score and records a correction for good', async () => {
  const created = await app.inject({
    method: 'POST',
    url: '/alerts',
    payload: { placeId: 'p-mid', wantId },
  });
  const alertId = created.json().alertId as string;

  const fb = await app.inject({
    method: 'POST',
    url: `/alerts/${alertId}/feedback`,
    payload: { action: 'wrong' },
  });
  assert.equal(fb.statusCode, 200);

  const row = db
    .prepare('SELECT score FROM place_scores WHERE want_id = ? AND place_id = ?')
    .get(wantId, 'p-mid') as unknown as { score: number };
  assert.equal(row.score, 0);

  const corrections = db
    .prepare('SELECT COUNT(*) AS n FROM corrections WHERE place_id = ?')
    .get('p-mid') as unknown as { n: number };
  assert.equal(corrections.n, 1);

  // Even re-scored later, a corrected place stays out.
  score('p-mid', 95);
  const ids = (await fenceSet()).fences.map((f) => f.placeId);
  assert.ok(!ids.includes('p-mid'), 'a place marked wrong must never return');
});

test('feedback on an unknown alert is refused', async () => {
  const res = await app.inject({
    method: 'POST',
    url: `/alerts/${randomUUID()}/feedback`,
    payload: { action: 'going' },
  });
  assert.equal(res.statusCode, 404);
});

test('the fence set is capped and nothing is stored at full precision', async () => {
  for (let i = 0; i < 25; i++) {
    const p = { id: `bulk-${i}`, name: `Bulk ${i}`, lat: 13.0604 + i * 0.001, lng: 80.2496 };
    seedPlace(p);
    score(p.id, 80);
  }
  const body = await fenceSet();
  assert.equal(body.fences.length, config.maxFences);

  const stored = db
    .prepare('SELECT centre_lat, centre_lng FROM fence_sets ORDER BY created_at DESC LIMIT 1')
    .get() as unknown as { centre_lat: number; centre_lng: number };
  assert.notEqual(stored.centre_lat, CENTRE.lat, 'centre must be rounded before storage');
  assert.ok(Math.abs(stored.centre_lat - CENTRE.lat) < 0.02);
});

test('deleting a want removes its fences', async () => {
  await app.inject({ method: 'DELETE', url: `/wants/${wantId}` });
  const body = await fenceSet();
  assert.deepEqual(body.fences, []);
});
