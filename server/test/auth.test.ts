import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';

/**
 * config.ts reads the environment when it is imported, so the token has to be
 * set before any dynamic import of it. Node's test runner gives each file its
 * own process, so this does not leak into the other suite.
 */
const TOKEN = 'a'.repeat(64);
process.env.AUTH_TOKEN = TOKEN;

const { openDb } = await import('../src/db.js');
const { buildApp } = await import('../src/app.js');
const { config } = await import('../src/config.js');

let app: FastifyInstance;
let db: ReturnType<typeof openDb>;

before(async () => {
  db = openDb(':memory:');
  app = buildApp(db);
  await app.ready();
});

after(async () => {
  await app.close();
  db.close();
});

test('a request with no token is refused', async () => {
  const res = await app.inject({ method: 'GET', url: '/health' });
  assert.equal(res.statusCode, 401);
});

test('a wrong token of the same length is refused', async () => {
  const res = await app.inject({
    method: 'GET',
    url: '/health',
    headers: { authorization: `Bearer ${'b'.repeat(64)}` },
  });
  assert.equal(res.statusCode, 401);
});

test('a token of the wrong length is refused, not thrown on', async () => {
  // timingSafeEqual throws on a length mismatch, so this would be a 500 if the
  // lengths were not checked first.
  const res = await app.inject({
    method: 'GET',
    url: '/health',
    headers: { authorization: 'Bearer short' },
  });
  assert.equal(res.statusCode, 401);
});

test('a malformed authorization header is refused', async () => {
  for (const header of ['', 'Basic abc', TOKEN, 'bearer ' + TOKEN]) {
    const res = await app.inject({
      method: 'GET',
      url: '/health',
      headers: { authorization: header },
    });
    assert.equal(res.statusCode, 401, `header "${header}" should be refused`);
  }
});

test('the right token is let through', async () => {
  const res = await app.inject({
    method: 'GET',
    url: '/health',
    headers: { authorization: `Bearer ${TOKEN}` },
  });
  assert.equal(res.statusCode, 200);
  assert.equal(res.json().ok, true);
});

test('every endpoint is behind the token, not just health', async () => {
  const unauthenticated = [
    { method: 'POST' as const, url: '/fence-set', payload: { lat: 13.06, lng: 80.25 } },
    { method: 'GET' as const, url: '/wants' },
    { method: 'POST' as const, url: '/wants', payload: { text: 'beef fry' } },
    { method: 'POST' as const, url: '/alerts', payload: { placeId: 'x', wantId: 'y' } },
  ];
  for (const req of unauthenticated) {
    const res = await app.inject(req);
    assert.equal(res.statusCode, 401, `${req.method} ${req.url} should be refused`);
  }
});

test('a configured token means the server listens on the network', () => {
  assert.equal(config.bindHost, '0.0.0.0');
});
