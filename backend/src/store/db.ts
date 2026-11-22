import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { createClient, type Client } from '@libsql/client';
import { normalizeDatabaseUrl } from '../config.js';
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

/**
 * Migration bookkeeping lives in a real table, not in `PRAGMA user_version`.
 *
 * Turso's server-side parser rejects PRAGMA writes over Hrana
 * (`SQL_PARSE_ERROR: SQL not allowed statement: PRAGMA user_version = 1`), so the old scheme only
 * worked for local files. Reading `PRAGMA user_version` is still allowed and is used once, to adopt
 * a database created by the old scheme.
 */
export const SCHEMA_MIGRATIONS_DDL = `CREATE TABLE IF NOT EXISTS schema_migrations (
  version    INTEGER PRIMARY KEY,
  applied_at TEXT NOT NULL
)`;

export interface MigrateOptions {
  /** Runs `PRAGMA foreign_key_check` afterwards; only needed (and only safe) on local files. */
  readonly checkForeignKeys?: boolean;
  /** Injected for deterministic applied_at values in tests. */
  readonly now?: () => Date;
  /** Records every statement issued, so a test can prove no PRAGMA write is ever sent. */
  readonly onStatement?: (sql: string) => void;
}

/** Filesystem path behind a `file:` URL, or null for a remote database. */
export function localFilePath(url: string): string | null {
  if (!url.startsWith('file:')) return null;
  let path = url.slice('file:'.length).split('?')[0] ?? '';
  if (path.startsWith('//')) path = path.slice(2);
  return path.length > 0 ? path : ':memory:';
}

export async function openDatabase(config: DatabaseConfig): Promise<Client> {
  const url = normalizeDatabaseUrl(config.url);
  const localPath = localFilePath(url);
  if (localPath && localPath !== ':memory:') {
    mkdirSync(dirname(localPath), { recursive: true });
  }
  const client = createClient({
    url,
    ...(config.authToken ? { authToken: config.authToken } : {}),
  });
  try {
    await migrate(client, { checkForeignKeys: localPath !== null });
  } catch (error) {
    client.close();
    throw error;
  }
  return client;
}

/** `PRAGMA user_version` is a read: allowed on Turso, and only needed for legacy adoption. */
async function readLegacyUserVersion(client: Client, onStatement?: (sql: string) => void): Promise<number> {
  const sql = 'PRAGMA user_version';
  onStatement?.(sql);
  try {
    const result = await client.execute(sql);
    return Number(result.rows[0]?.user_version ?? 0);
  } catch {
    // A remote target that refuses the read is not a legacy database: the table is authoritative.
    return 0;
  }
}

async function readAppliedVersions(client: Client, onStatement?: (sql: string) => void): Promise<number[]> {
  const sql = 'SELECT version FROM schema_migrations ORDER BY version';
  onStatement?.(sql);
  const result = await client.execute(sql);
  return result.rows.map((row) => Number(row.version));
}

export async function migrate(client: Client, options: MigrateOptions = {}): Promise<void> {
  const track = options.onStatement;
  const legacyVersion = await readLegacyUserVersion(client, track);

  track?.(SCHEMA_MIGRATIONS_DDL);
  await client.execute(SCHEMA_MIGRATIONS_DDL);

  const applied = await readAppliedVersions(client, track);
  const maxVersion = migrations.reduce((max, migration) => Math.max(max, migration.version), 0);

  // Old format: `user_version` was the source of truth and there was no table. The schema is already
  // there, so record what was applied instead of re-running DDL that would fail on existing tables.
  if (applied.length === 0 && legacyVersion > 0) {
    const now = (options.now ?? (() => new Date()))().toISOString();
    for (let version = 1; version <= Math.min(legacyVersion, maxVersion); version += 1) {
      const sql = 'INSERT OR IGNORE INTO schema_migrations (version, applied_at) VALUES (?, ?)';
      track?.(sql);
      await client.execute({ sql, args: [version, now] });
    }
  }

  const appliedSet = new Set(applied.length === 0 && legacyVersion > 0 ? range(1, Math.min(legacyVersion, maxVersion)) : applied);

  for (const migration of migrations) {
    if (appliedSet.has(migration.version)) continue;
    const now = (options.now ?? (() => new Date()))().toISOString();
    const transaction = await client.transaction('write');
    try {
      // Re-check inside the write transaction: two processes booting at once cannot both apply it.
      const inside = await transaction.execute({
        sql: 'SELECT 1 AS present FROM schema_migrations WHERE version = ?',
        args: [migration.version],
      });
      if (inside.rows.length > 0) {
        await transaction.rollback();
        continue;
      }
      const statements = [
        ...splitStatements(migration.sql),
        ...(migration.extraStatements ?? []),
      ];
      for (const statement of statements) {
        track?.(statement);
        await transaction.execute(statement);
      }
      const record = 'INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)';
      track?.(record);
      await transaction.execute({ sql: record, args: [migration.version, now] });
      await transaction.commit();
    } catch (error) {
      await transaction.rollback();
      throw error;
    }
  }

  if (options.checkForeignKeys) {
    track?.('PRAGMA foreign_key_check');
    const check = await client.execute('PRAGMA foreign_key_check');
    if (check.rows.length > 0) {
      throw new Error(`foreign_key_check failed after migration: ${check.rows.length} row(s)`);
    }
  }
}

function range(from: number, to: number): number[] {
  return Array.from({ length: Math.max(0, to - from + 1) }, (_unused, index) => from + index);
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
