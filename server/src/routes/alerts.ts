import type { FastifyInstance } from 'fastify';
import { randomUUID } from 'node:crypto';
import type { Db } from '../db.js';
import { nowIso } from '../db.js';
import { config } from '../config.js';

interface AlertBody {
  placeId: string;
  wantId: string;
  lat?: number;
  lng?: number;
}

interface FeedbackBody {
  action: 'going' | 'not_now' | 'wrong' | 'ignored';
}

export function alertRoutes(app: FastifyInstance, db: Db): void {
  /** The phone reports an alert it has already shown. */
  app.post<{ Body: AlertBody }>(
    '/alerts',
    {
      schema: {
        body: {
          type: 'object',
          required: ['placeId', 'wantId'],
          properties: {
            placeId: { type: 'string' },
            wantId: { type: 'string' },
            lat: { type: 'number' },
            lng: { type: 'number' },
          },
        },
      },
    },
    async (request, reply) => {
      const { placeId, wantId, lat, lng } = request.body;
      const known = db
        .prepare('SELECT 1 FROM places WHERE id = ?')
        .get(placeId) as unknown;
      if (!known) return reply.code(404).send({ error: 'no such place' });

      const id = randomUUID();
      db.prepare(
        `INSERT INTO alerts (id, user_id, want_id, place_id, fired_at, lat, lng)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
      ).run(id, config.defaultUserId, wantId, placeId, nowIso(), lat ?? null, lng ?? null);
      return reply.code(201).send({ alertId: id });
    },
  );

  /**
   * Going / Not now / Wrong. "Wrong" is the valuable one: it drops the score to
   * zero for good and becomes a negative example for later extraction.
   */
  app.post<{ Params: { id: string }; Body: FeedbackBody }>(
    '/alerts/:id/feedback',
    {
      schema: {
        body: {
          type: 'object',
          required: ['action'],
          properties: {
            action: { type: 'string', enum: ['going', 'not_now', 'wrong', 'ignored'] },
          },
        },
      },
    },
    async (request, reply) => {
      const { action } = request.body;
      const alert = db
        .prepare('SELECT want_id, place_id FROM alerts WHERE id = ? AND user_id = ?')
        .get(request.params.id, config.defaultUserId) as
        | { want_id: string; place_id: string }
        | undefined;
      if (!alert) return reply.code(404).send({ error: 'no such alert' });

      db.prepare('UPDATE alerts SET action = ? WHERE id = ?').run(action, request.params.id);

      if (action === 'wrong') {
        // OR IGNORE, because the phone retries feedback that failed to send and
        // the same Wrong may legitimately arrive twice.
        db.prepare(
          `INSERT OR IGNORE INTO corrections (id, user_id, want_id, place_id, created_at)
           VALUES (?, ?, ?, ?, ?)`,
        ).run(randomUUID(), config.defaultUserId, alert.want_id, alert.place_id, nowIso());
        db.prepare(
          `UPDATE place_scores SET score = 0, reason = 'marked wrong by the user'
            WHERE want_id = ? AND place_id = ?`,
        ).run(alert.want_id, alert.place_id);
      }

      return reply.send({ ok: true, action });
    },
  );
}
