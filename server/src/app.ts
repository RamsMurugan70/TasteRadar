import Fastify, { type FastifyInstance } from 'fastify';
import type { Db } from './db.js';
import { healthRoutes } from './routes/health.js';
import { wantRoutes } from './routes/wants.js';
import { fenceRoutes } from './routes/fences.js';
import { alertRoutes } from './routes/alerts.js';

export function buildApp(db: Db, opts: { logger?: boolean } = {}): FastifyInstance {
  const app = Fastify({ logger: opts.logger ?? false });
  healthRoutes(app, db);
  wantRoutes(app, db);
  fenceRoutes(app, db);
  alertRoutes(app, db);
  return app;
}
