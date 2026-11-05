import { openDb } from './db.js';
import { config } from './config.js';

const db = openDb();
const tables = db
  .prepare(`SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name`)
  .all() as unknown as { name: string }[];

console.log(`${config.databaseFile}: ${tables.length} tables`);
for (const t of tables) console.log(`  ${t.name}`);
db.close();
