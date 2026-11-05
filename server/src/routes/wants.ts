import type { FastifyInstance } from 'fastify';
import { randomUUID } from 'node:crypto';
import type { Db } from '../db.js';
import { activeWants, nowIso } from '../db.js';
import { extractCriteria } from '../lib/extract.js';
import { config } from '../config.js';

interface CreateBody {
  text: string;
  kind?: 'standing' | 'need';
  expiresInDays?: number;
}

export function wantRoutes(app: FastifyInstance, db: Db): void {
  app.post<{ Body: CreateBody }>(
    '/wants',
    {
      schema: {
        body: {
          type: 'object',
          required: ['text'],
          properties: {
            text: { type: 'string', minLength: 3, maxLength: 500 },
            kind: { type: 'string', enum: ['standing', 'need'] },
            expiresInDays: { type: 'number', minimum: 1, maximum: 3650 },
          },
        },
      },
    },
    async (request, reply) => {
      const { text, kind = 'need', expiresInDays } = request.body;

      // Everything this person has already marked wrong, so the extractor can
      // be told what not to match again.
      const negatives = (
        db
          .prepare(
            `SELECT DISTINCT p.name FROM corrections c
               JOIN places p ON p.id = c.place_id
              WHERE c.user_id = ? LIMIT 20`,
          )
          .all(config.defaultUserId) as unknown as { name: string }[]
      ).map((r) => r.name);

      const extraction = await extractCriteria(text, negatives);
      const id = randomUUID();
      const expiresAt =
        kind === 'standing'
          ? null
          : new Date(Date.now() + (expiresInDays ?? 30) * 86_400_000).toISOString();

      db.prepare(
        `INSERT INTO wants (id, user_id, raw_text, kind, criteria, restated, status, expires_at, created_at)
         VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?)`,
      ).run(
        id,
        config.defaultUserId,
        text,
        kind,
        JSON.stringify(extraction.criteria),
        extraction.restated,
        expiresAt,
        nowIso(),
      );

      return reply.code(201).send({
        id,
        restated: extraction.restated,
        criteria: extraction.criteria,
        extractedBy: extraction.via,
        expiresAt,
        // Scores are not computed here. Nothing will match until the scoring
        // worker has run over this want.
        needsScoring: true,
      });
    },
  );

  app.get('/wants', async () => ({
    wants: activeWants(db, config.defaultUserId).map((w) => ({
      id: w.id,
      text: w.raw_text,
      kind: w.kind,
      restated: w.restated,
      expiresAt: w.expires_at,
      criteria: JSON.parse(w.criteria) as unknown,
    })),
  }));

  app.delete<{ Params: { id: string } }>('/wants/:id', async (request, reply) => {
    const result = db
      .prepare(`UPDATE wants SET status = 'removed' WHERE id = ? AND user_id = ?`)
      .run(request.params.id, config.defaultUserId);
    if (result.changes === 0) return reply.code(404).send({ error: 'no such want' });
    return reply.send({ ok: true });
  });
}
