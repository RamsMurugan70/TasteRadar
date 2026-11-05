import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import type { FastifyInstance } from 'fastify';

const TOKEN = 'c'.repeat(64);
process.env.AUTH_TOKEN = TOKEN;
process.env.AUTH_MAX_FAILURES = '3';
process.env.AUTH_WINDOW_MS = '60000';
process.env.AUTH_LOCKOUT_MS = '60000';

const { openDb } = await import('../src/db.js');
const { buildApp } = await import('../src/app.js');

let app: FastifyInstance;
let db: ReturnType<typeof openDb>;

const bad = (ip: string) =>
  app.inject({
    method: 'GET',
    url: '/health',
    headers: { authorization: 'Bearer wrong' },
    remoteAddress: ip,
  });

const good = (ip: string) =>
  app.inject({
    method: 'GET',
    url: '/health',
    headers: { authorization: `Bearer ${TOKEN}` },
    remoteAddress: ip,
  });

before(async () => {
  db = openDb(':memory:');
  app = buildApp(db);
  await app.ready();
});

after(async () => {
  await app.close();
  db.close();
});

test('repeated wrong tokens turn into a lockout', async () => {
  const ip = '10.1.1.1';
  for (let i = 0; i < 3; i++) {
    assert.equal((await bad(ip)).statusCode, 401, `attempt ${i + 1} should be 401`);
  }
  const locked = await bad(ip);
  assert.equal(locked.statusCode, 429);
  assert.ok(Number(locked.headers['retry-after']) > 0, 'Retry-After must be set');
  assert.equal(locked.json().error, 'too many failed attempts');
});

test('the lockout holds even against the correct token', async () => {
  // Deliberate: otherwise an attacker who eventually guesses right is rewarded
  // for persistence, which is exactly what the limit exists to stop.
  const res = await good('10.1.1.1');
  assert.equal(res.statusCode, 429);
});

test('a locked client does not lock out everyone else', async () => {
  const res = await good('10.2.2.2');
  assert.equal(res.statusCode, 200, 'a different client is unaffected');
});

test('a wrong token from a fresh client is 401, not 429', async () => {
  const res = await bad('10.3.3.3');
  assert.equal(res.statusCode, 401);
});

test('a correct token clears the failures before it', async () => {
  const ip = '10.4.4.4';
  await bad(ip);
  await bad(ip);
  assert.equal((await good(ip)).statusCode, 200);

  // Two more failures would have been the third and fourth without the clear.
  assert.equal((await bad(ip)).statusCode, 401);
  assert.equal((await bad(ip)).statusCode, 401);
});

test('a missing header counts towards the limit too', async () => {
  const ip = '10.5.5.5';
  for (let i = 0; i < 3; i++) {
    const res = await app.inject({ method: 'GET', url: '/health', remoteAddress: ip });
    assert.equal(res.statusCode, 401);
  }
  const locked = await app.inject({ method: 'GET', url: '/health', remoteAddress: ip });
  assert.equal(locked.statusCode, 429, 'otherwise sending no header is an unlimited probe');
});
