import type { FastifyInstance } from 'fastify';
import type { Db } from '../db.js';
import { config } from '../config.js';

const count = (db: Db, sql: string): number =>
  ((db.prepare(sql).get() as unknown as { n: number } | undefined)?.n ?? 0);

export function healthRoutes(app: FastifyInstance, db: Db): void {
  app.get('/health', async () => ({
    ok: true,
    places: count(db, 'SELECT COUNT(*) AS n FROM places'),
    activeWants: count(db, `SELECT COUNT(*) AS n FROM wants WHERE status = 'active'`),
    scoredPairs: count(db, 'SELECT COUNT(*) AS n FROM place_scores'),
    alerts: count(db, 'SELECT COUNT(*) AS n FROM alerts'),
    thresholds: {
      scoreThreshold: config.scoreThreshold,
      maxFences: config.maxFences,
      umbrellaRadiusM: config.umbrellaRadiusM,
      cooldownDays: config.cooldownDays,
    },
    // Says plainly whether POST /wants will use the model or the fallback.
    extraction: process.env.ANTHROPIC_API_KEY ? 'model' : 'keywords (no ANTHROPIC_API_KEY)',
  }));
}
