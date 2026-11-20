import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createClient } from '@libsql/client';
import { openDatabase, migrate } from '../src/store/db.js';
import { migrations } from '../src/store/migrations.js';
import { tempDatabasePath } from './helpers.js';

/**
 * Fase 4 fix: migration state lives in `schema_migrations`, because Turso rejects
 * `PRAGMA user_version = N` as a write over Hrana. These tests are the regression guard.
 */

async function appliedVersions(databasePath: string): Promise<number[]> {
  const client = createClient({ url: `file:${databasePath}` });
  try {
    const result = await client.execute('SELECT version FROM schema_migrations ORDER BY version');
    return result.rows.map((row) => Number(row.version));
  } finally {
    client.close();
  }
}

test('migrations are tracked in schema_migrations and a second boot is a no-op', async () => {
  const databasePath = tempDatabasePath();
  const first = await openDatabase({ url: `file:${databasePath}`, authToken: '' });
  first.close();

  assert.deepEqual(await appliedVersions(databasePath), [1, 2]);

  // Second boot: nothing is re-applied and no row is duplicated.
  const second = await openDatabase({ url: `file:${databasePath}`, authToken: '' });
  second.close();
  assert.deepEqual(await appliedVersions(databasePath), [1, 2]);

  const raw = createClient({ url: `file:${databasePath}` });
  try {
    const count = await raw.execute('SELECT COUNT(*) AS total FROM schema_migrations');
    assert.equal(Number(count.rows[0]?.total), 2);
  } finally {
    raw.close();
  }
});

test('a database created by the old PRAGMA scheme is adopted without re-running DDL', async () => {
  const databasePath = tempDatabasePath();
  const created = await openDatabase({ url: `file:${databasePath}`, authToken: '' });
  created.close();

  // Turn it into an old-format database: data in place, no schema_migrations, user_version = 2.
  const legacy = createClient({ url: `file:${databasePath}` });
  try {
    await legacy.execute({
      sql: `INSERT INTO users (owner_id, email, display_name, salt, password_hash, kdf, created_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      args: ['u_legacy', 'legacy@example.test', 'Legacy', Buffer.from([1, 2]), Buffer.from([3, 4]), 'scrypt:x', '2026-01-01T00:00:00.000Z', '2026-01-01T00:00:00.000Z'],
    });
    await legacy.execute('DROP TABLE schema_migrations');
    await legacy.execute('PRAGMA user_version = 2');
  } finally {
    legacy.close();
  }

  // Boot on the old format: it must record 1..2 and leave the rows alone.
  const upgraded = await openDatabase({ url: `file:${databasePath}`, authToken: '' });
  upgraded.close();

  assert.deepEqual(await appliedVersions(databasePath), [1, 2]);

  const raw = createClient({ url: `file:${databasePath}` });
  try {
    const user = await raw.execute('SELECT email FROM users WHERE owner_id = ?', ['u_legacy']);
    assert.equal(user.rows[0]?.email, 'legacy@example.test', 'no data loss');
    // Tables were not recreated: the adopt path re-ran nothing.
    const tables = await raw.execute("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'");
    assert.equal(tables.rows.length, 1);
  } finally {
    raw.close();
  }
});

test('no statement the migration path issues is a PRAGMA write', async () => {
  const statements: string[] = [];
  const databasePath = tempDatabasePath();
  const client = createClient({ url: `file:${databasePath}` });
  try {
    await migrate(client, { onStatement: (sql) => statements.push(sql.trim()) });
  } finally {
    client.close();
  }

  assert.ok(statements.length > 0, 'statements were captured');
  const pragmaWrites = statements.filter((sql) => /^PRAGMA\s+[A-Za-z_]+\s*=/i.test(sql));
  assert.deepEqual(pragmaWrites, [], `migration issued a PRAGMA write: ${pragmaWrites.join(' | ')}`);
  const forbidden = statements.filter((sql) => /^PRAGMA\s+(foreign_keys|journal_mode|synchronous)\b/i.test(sql));
  assert.deepEqual(forbidden, [], 'no connection pragmas are issued');
  // Reading user_version once (legacy adoption) is allowed.
  assert.ok(statements.filter((sql) => /^PRAGMA\s+user_version\s*$/i.test(sql)).length <= 1);
});

test('every migration is recorded exactly once, with an applied_at timestamp', async () => {
  const databasePath = tempDatabasePath();
  const client = await openDatabase({ url: `file:${databasePath}`, authToken: '' });
  client.close();

  const raw = createClient({ url: `file:${databasePath}` });
  try {
    const rows = await raw.execute('SELECT version, applied_at FROM schema_migrations ORDER BY version');
    assert.equal(rows.rows.length, migrations.length);
    for (const row of rows.rows) {
      assert.ok(Number(row.version) >= 1);
      assert.match(String(row.applied_at), /^\d{4}-\d{2}-\d{2}T/);
    }
  } finally {
    raw.close();
  }
});
