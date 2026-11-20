import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { migrations } from './migrations.js';

/**
 * Opens SQLite and applies migrations (contract §6). `:memory:` is used by most tests; the
 * ciphertext test needs a real file, so callers choose the path.
 */
export function openDatabase(path: string): DatabaseSync {
  if (path !== ':memory:') {
    mkdirSync(dirname(path), { recursive: true });
  }
  const db = new DatabaseSync(path);
  db.exec('PRAGMA foreign_keys = ON');
  db.exec('PRAGMA busy_timeout = 5000');
  if (path !== ':memory:') {
    db.exec('PRAGMA journal_mode = WAL');
  }
  migrate(db);
  return db;
}

export function migrate(db: DatabaseSync): void {
  let applied = false;
  const current = Number(
    (db.prepare('PRAGMA user_version').get() as { user_version?: number } | undefined)
      ?.user_version ?? 0,
  );
  if (migrations.every((migration) => migration.version <= current)) return;

  // A table rebuild (migration 2) cannot run with foreign keys enforced, and the pragma is a
  // no-op inside a transaction, so it is toggled around the whole batch.
  db.exec('PRAGMA foreign_keys = OFF');
  try {
    for (const migration of migrations) {
      if (migration.version <= current) continue;
      db.exec('BEGIN');
      try {
        db.exec(migration.sql);
        db.exec(`PRAGMA user_version = ${migration.version}`);
        db.exec('COMMIT');
        applied = true;
      } catch (error) {
        db.exec('ROLLBACK');
        throw error;
      }
    }
  } finally {
    db.exec('PRAGMA foreign_keys = ON');
  }
  if (applied) db.exec('PRAGMA foreign_key_check');
}
