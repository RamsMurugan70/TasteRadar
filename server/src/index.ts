import { openDb } from './db.js';
import { buildApp } from './app.js';
import { config } from './config.js';

const db = openDb();
const app = buildApp(db, { logger: true });

if (!config.authToken) {
  app.log.warn(
    'AUTH_TOKEN is not set, so this server is bound to 127.0.0.1 and the phone ' +
      'cannot reach it. Set AUTH_TOKEN to a long random string in .env and use ' +
      'the same value in the app.',
  );
}

try {
  await app.listen({ port: config.port, host: config.bindHost });
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
