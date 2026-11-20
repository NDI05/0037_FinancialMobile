import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { test } from 'node:test';
import { createClient } from '@libsql/client';
import { loadConfig } from '../src/config.js';
import { localFilePath, splitStatements } from '../src/store/db.js';
import { createHarness, tempDatabasePath } from './helpers.js';

/**
 * F4/F3: one driver, two URLs. The `file:` mode must be a real, persistent SQLite database reached
 * through @libsql/client — not a second storage path kept around for local runs.
 */
test('a file URL maps to a local path and boots a real database', async () => {
  assert.equal(localFilePath('file:./data/pam.db'), './data/pam.db');
  assert.equal(localFilePath('file:/tmp/pam.db'), '/tmp/pam.db');
  assert.equal(localFilePath('file:///tmp/pam.db'), '/tmp/pam.db');
  assert.equal(localFilePath('libsql://pam-org.turso.io'), null);

  const databasePath = tempDatabasePath();
  const harness = await createHarness({ databasePath });
  try {
    // Migrations ran: the schema is queryable through the same client the app uses.
    const client = createClient({ url: `file:${databasePath}` });
    try {
      const tables = await client.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
      );
      const names = tables.rows.map((row) => String(row.name));
      for (const expected of [
        'users',
        'sessions',
        'consents',
        'summaries',
        'analyses',
        'budget_plans',
        'deletion_requests',
        'login_attempts',
        'usage_counters',
      ]) {
        assert.ok(names.includes(expected), `missing table ${expected}`);
      }
      const version = await client.execute('PRAGMA user_version');
      assert.equal(Number(version.rows[0]?.user_version), 2);
    } finally {
      client.close();
    }

    // The file is a real file on disk, and it survives a reopen.
    const files = readdirSync(dirname(databasePath)).filter((name) => name.includes('pam.db'));
    assert.ok(files.length > 0);
    assert.ok(readFileSync(databasePath).length > 0);
  } finally {
    await harness.close();
  }
});

test('migrations split into statements without cutting a comment in half', () => {
  const statements = splitStatements(`
    -- a comment with a semicolon; inside it
    CREATE TABLE a (id TEXT);
    CREATE TABLE b (id TEXT);
  `);
  assert.equal(statements.length, 2);
  assert.match(statements[0]!, /CREATE TABLE a/);
  assert.match(statements[1]!, /CREATE TABLE b/);
});

test('an unset DATABASE_URL still boots on the local file default', () => {
  const config = loadConfig({ NODE_ENV: 'development' } as NodeJS.ProcessEnv);
  assert.equal(config.database.url, 'file:./data/pam.db');
  assert.equal(config.database.authToken, '');
});

test('a legacy DATABASE_PATH is mapped to a file URL with a deprecation warning', () => {
  const config = loadConfig({
    NODE_ENV: 'development',
    DATABASE_PATH: '/tmp/legacy/pam.db',
  } as NodeJS.ProcessEnv);
  assert.equal(config.database.url, 'file:/tmp/legacy/pam.db');
  assert.ok(config.warnings.some((warning) => warning.includes('DATABASE_PATH is deprecated')));
});

test('an explicit DATABASE_URL wins over a stale DATABASE_PATH', () => {
  const config = loadConfig({
    NODE_ENV: 'development',
    DATABASE_URL: 'file:/tmp/explicit.db',
    DATABASE_PATH: '/tmp/stale/pam.db',
  } as NodeJS.ProcessEnv);
  assert.equal(config.database.url, 'file:/tmp/explicit.db');
  assert.ok(config.warnings.some((warning) => warning.includes('DATABASE_PATH is ignored')));
});

test('a Turso URL without a token fails in production and warns in development', () => {
  const production = {
    NODE_ENV: 'production',
    SESSION_PEPPER: 'x'.repeat(40),
    DATA_ENCRYPTION_KEY: Buffer.alloc(32, 7).toString('base64'),
    INTERNAL_SWEEP_TOKEN: 'y'.repeat(24),
    REGISTRATION_INVITE_CODE: 'invite-for-tests',
    DATABASE_URL: 'libsql://pam-org.turso.io',
  };
  assert.throws(
    () => loadConfig(production as NodeJS.ProcessEnv),
    (error: unknown) => {
      assert.ok(error instanceof Error);
      assert.ok(error.message.includes('DATABASE_AUTH_TOKEN'));
      return true;
    },
  );

  const development = loadConfig({
    NODE_ENV: 'development',
    DATABASE_URL: 'libsql://pam-org.turso.io',
  } as NodeJS.ProcessEnv);
  assert.ok(
    development.warnings.some((warning) => warning.includes('DATABASE_AUTH_TOKEN is empty')),
  );

  const withToken = loadConfig({
    ...production,
    DATABASE_AUTH_TOKEN: 'token',
  } as NodeJS.ProcessEnv);
  assert.equal(withToken.database.authToken, 'token');
});
