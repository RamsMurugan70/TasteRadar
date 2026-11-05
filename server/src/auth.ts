import { timingSafeEqual } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { config } from './config.js';

/**
 * A shared bearer token. One user, one secret, no accounts — anything more is
 * the multi-user phase's problem.
 *
 * The important part is not this file but `config.bindHost`: with no token set
 * the server binds to loopback only, so the insecure configuration is
 * unreachable from the network by construction rather than by remembering to
 * read a warning.
 */
export function registerAuth(app: FastifyInstance): void {
  const expected = config.authToken;
  if (!expected) return; // loopback-only mode; nothing to check

  const expectedBuffer = Buffer.from(expected, 'utf8');

  app.addHook('onRequest', async (request, reply) => {
    const header = request.headers.authorization;
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) {
      return reply.code(401).send({ error: 'missing bearer token' });
    }
    const given = Buffer.from(header.slice('Bearer '.length), 'utf8');

    // Compare in constant time, and only when the lengths match -
    // timingSafeEqual throws on a length mismatch rather than returning false.
    const ok = given.length === expectedBuffer.length && timingSafeEqual(given, expectedBuffer);
    if (!ok) return reply.code(401).send({ error: 'bad bearer token' });
  });
}
