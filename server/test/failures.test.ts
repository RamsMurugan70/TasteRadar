import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FailureTracker } from '../src/lib/failures.js';

const MAX = 3;
const WINDOW = 60_000;
const LOCKOUT = 30_000;

const tracker = () => new FailureTracker(MAX, WINDOW, LOCKOUT);

test('below the limit nothing is locked', () => {
  const t = tracker();
  const now = 1_000_000;
  for (let i = 0; i < MAX - 1; i++) t.record('a', now + i);
  assert.equal(t.lockedFor('a', now + 100), 0);
});

test('reaching the limit locks for the full lockout', () => {
  const t = tracker();
  const now = 1_000_000;
  // All three failures at the same instant, so the arithmetic is obvious.
  for (let i = 0; i < MAX; i++) t.record('a', now);
  assert.equal(t.lockedFor('a', now), LOCKOUT, 'the clock starts at the last failure');
  assert.equal(t.lockedFor('a', now + 1), LOCKOUT - 1);
});

test('the lockout counts down and then expires', () => {
  const t = tracker();
  const now = 1_000_000;
  for (let i = 0; i < MAX; i++) t.record('a', now);
  assert.equal(t.lockedFor('a', now + 10_000), LOCKOUT - 10_000);
  assert.equal(t.lockedFor('a', now + LOCKOUT), 0, 'exactly at the boundary it is free');
  assert.equal(t.lockedFor('a', now + LOCKOUT + 1), 0);
});

test('failures older than the window stop counting', () => {
  const t = tracker();
  const now = 1_000_000;
  for (let i = 0; i < MAX; i++) t.record('a', now + i);
  // Past the window, the old failures are forgotten entirely, so a fresh
  // failure is the first one again rather than the fourth.
  const later = now + WINDOW + 1;
  t.record('a', later);
  assert.equal(t.lockedFor('a', later), 0);
});

test('a correct token forgives what came before', () => {
  const t = tracker();
  const now = 1_000_000;
  for (let i = 0; i < MAX - 1; i++) t.record('a', now);
  t.clear('a');
  t.record('a', now);
  assert.equal(t.lockedFor('a', now), 0, 'the count restarted');
});

test('clients are tracked separately', () => {
  const t = tracker();
  const now = 1_000_000;
  for (let i = 0; i < MAX; i++) t.record('attacker', now);
  assert.ok(t.lockedFor('attacker', now) > 0);
  assert.equal(t.lockedFor('me', now), 0, 'one bad client must not lock out another');
});

test('memory is bounded when an attacker rotates addresses', () => {
  const t = new FailureTracker(MAX, WINDOW, LOCKOUT, 50);
  for (let i = 0; i < 500; i++) t.record(`client-${i}`, 1_000_000 + i);
  assert.ok(t.trackedClients <= 50, `tracked ${t.trackedClients}, expected at most 50`);
});

test('an entry that ages out is not kept forever', () => {
  const t = tracker();
  t.record('a', 1_000_000);
  assert.equal(t.trackedClients, 1);
  // Reading past the window drops the stale entry rather than retaining it.
  t.lockedFor('a', 1_000_000 + WINDOW + 1);
  assert.equal(t.trackedClients, 0);
});
