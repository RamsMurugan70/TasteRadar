import { openDb } from './db.js';
import { buildApp } from './app.js';
import { config } from './config.js';

const db = openDb();
const app = buildApp(db, { logger: true });

try {
  await app.listen({ port: config.port, host: '0.0.0.0' });
} catch (err) {
  app.log.error(err);
  process.exit(1);
}

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    void app.close().then(() => {
      db.close();
      process.exit(0);
    });
  });
}
