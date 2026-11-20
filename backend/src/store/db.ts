import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { createClient, type Client } from '@libsql/client';
import { migrations } from './migrations.js';

/**
 * One driver, two URLs (CR-001 / Fase 4):
 *
 * - `file:./data/pam.db` (default) — local SQLite through libSQL's embedded mode, no account;
 * - `libsql://<db>-<org>.turso.io` + `DATABASE_AUTH_TOKEN` — the managed Turso database.
 *
 * Both go through `@libsql/client`, so there is exactly one storage path: the same SQL, the same
 * transactions and the same tests run against either target.
 */
export interface DatabaseConfig {
  readonly url: string;
  readonly authToken: string;
}

/** Filesystem path behind a `file:` URL, or null for a remote database. */
export function localFilePath(url: string): string | null {
  if (!url.startsWith('file:')) return null;
  let path = url.slice('file:'.length).split('?')[0] ?? '';
  if (path.startsWith('//')) path = path.slice(2);
  return path.length > 0 ? path : ':memory:';
}

export async function openDatabase(config: DatabaseConfig): Promise<Client> {
  const localPath = localFilePath(config.url);
  if (localPath && localPath !== ':memory:') {
    mkdirSync(dirname(localPath), { recursive: true });
  }
  const client = createClient({
    url: config.url,
    ...(config.authToken ? { authToken: config.authToken } : {}),
  });
  await migrate(client);
  return client;
}

export async function migrate(client: Client): Promise<void> {
  const versionResult = await client.execute('PRAGMA user_version');
  const current = Number(versionResult.rows[0]?.user_version ?? 0);
  if (migrations.every((migration) => migration.version <= current)) return;

  // A table rebuild (migration 2) cannot run with foreign keys enforced.
  await client.execute('PRAGMA foreign_keys = OFF');
  try {
    for (const migration of migrations) {
      if (migration.version <= current) continue;
      const transaction = await client.transaction('write');
      try {
        for (const statement of splitStatements(migration.sql)) {
          await transaction.execute(statement);
        }
        await transaction.execute(`PRAGMA user_version = ${migration.version}`);
        await transaction.commit();
      } catch (error) {
        await transaction.rollback();
        throw error;
      }
    }
  } finally {
    await client.execute('PRAGMA foreign_keys = ON');
  }
  const check = await client.execute('PRAGMA foreign_key_check');
  if (check.rows.length > 0) {
    throw new Error(`foreign_key_check failed after migration: ${check.rows.length} row(s)`);
  }
}

/**
 * The migrations are plain DDL without triggers or function bodies, so splitting on `;` is exact
 * enough to feed them one statement at a time inside a transaction.
 */
export function splitStatements(sql: string): string[] {
  // Strip `--` comments *before* splitting: a comment may contain a semicolon (the migration
  // narrative does), and splitting first would cut the statement in half.
  const withoutComments = sql
    .split('\n')
    .map((line) => {
      const index = line.indexOf('--');
      return index >= 0 ? line.slice(0, index) : line;
    })
    .join('\n');
  return withoutComments
    .split(';')
    .map((statement) => statement.trim())
    .filter((statement) => statement.length > 0);
}
