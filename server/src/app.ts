import Fastify, { type FastifyInstance } from 'fastify';
import type { Db } from './db.js';
import { registerAuth } from './auth.js';
import { config } from './config.js';
import { healthRoutes } from './routes/health.js';
import { wantRoutes } from './routes/wants.js';
import { fenceRoutes } from './routes/fences.js';
import { alertRoutes } from './routes/alerts.js';

export function buildApp(db: Db, opts: { logger?: boolean } = {}): FastifyInstance {
  const app = Fastify({
    logger: opts.logger ?? false,
    // Only when a proxy you control is the single way in - otherwise the client
    // address can be forged and the rate limit evaded. See config.trustProxy.
    trustProxy: config.trustProxy,
  });
  registerAuth(app);
  healthRoutes(app, db);
  wantRoutes(app, db);
  fenceRoutes(app, db);
  alertRoutes(app, db);
  return app;
}
