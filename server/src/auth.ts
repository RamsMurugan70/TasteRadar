import { timingSafeEqual } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { config } from './config.js';
import { FailureTracker } from './lib/failures.js';

/**
 * A shared bearer token. One user, one secret, no accounts — anything more is
 * the multi-user phase's problem.
 *
 * Two things do the real work here. `config.bindHost` means that with no token
 * set the server binds to loopback only, so the insecure configuration is
 * unreachable by construction. And the failure tracker means the token cannot be
 * ground down by repetition, which matters once the server is behind a tunnel
 * and reachable from anywhere.
 */
export function registerAuth(app: FastifyInstance): void {
  const expected = config.authToken;
  if (!expected) return; // loopback-only mode; nothing to check

  const expectedBuffer = Buffer.from(expected, 'utf8');
  const failures = new FailureTracker(
    config.authMaxFailures,
    config.authWindowMs,
    config.authLockoutMs,
  );

  app.addHook('onRequest', async (request, reply) => {
    const now = Date.now();
    const client = request.ip;

    // Checked before the token is even looked at: a locked-out client gets no
    // comparison, and no information about how close its guess was.
    const lockedFor = failures.lockedFor(client, now);
    if (lockedFor > 0) {
      const seconds = Math.ceil(lockedFor / 1000);
      request.log.warn({ client }, 'locked out, refusing without checking the token');
      return reply
        .code(429)
        .header('Retry-After', String(seconds))
        .send({ error: 'too many failed attempts', retryAfterSeconds: seconds });
    }

    const header = request.headers.authorization;
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) {
      failures.record(client, now);
      return reply.code(401).send({ error: 'missing bearer token' });
    }
    const given = Buffer.from(header.slice('Bearer '.length), 'utf8');

    // Compare in constant time, and only when the lengths match -
    // timingSafeEqual throws on a length mismatch rather than returning false.
    const ok = given.length === expectedBuffer.length && timingSafeEqual(given, expectedBuffer);
    if (!ok) {
      failures.record(client, now);
      return reply.code(401).send({ error: 'bad bearer token' });
    }

    // A correct token forgives the failures before it, so fat-fingering the
    // token in the app a few times does not then lock you out for five minutes.
    failures.clear(client);
  });
}
