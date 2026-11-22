import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createClient, type Client } from '@libsql/client';
import { openDatabase } from '../src/store/db.js';
import { tempDatabasePath } from './helpers.js';

/**
 * Package B acceptance for the v0.4 libSQL schema. These tests run on an isolated local `file:`
 * database only; the Turso Cloud run is a separate, explicitly NOT RUN acceptance until test
 * credentials exist.
 *
 * What must hold before C1 can build on the schema:
 * - legacy v0.3 rows survive the additive migration (covered in migrations.test.ts);
 * - an owner cannot point a row at another owner's category/budget/plan (composite foreign key);
 * - a fingerprint is unique per owner, while two owners may share one;
 * - amounts stay in safe integer minor units and JSON columns are valid JSON text;
 * - soft delete removes rows from active totals but keeps them for retention;
 * - the last active ADMIN cannot be demoted or deleted;
 * - refresh rotation and family revocation are expressible in one transaction;
 * - list queries can use the owner/month index.
 */

const NOW = '2026-11-21T12:00:00.000Z';

async function withDatabase(run: (client: Client) => Promise<void>): Promise<void> {
  const databasePath = tempDatabasePath();
  const client = await openDatabase({ url: `file:${databasePath}`, authToken: '' });
  try {
    await run(client);
  } finally {
    client.close();
  }
}

async function insertUser(client: Client, ownerId: string, role: 'USER' | 'ADMIN' = 'USER'): Promise<void> {
  const email = `${ownerId}@example.test`;
  await client.execute({
    sql: `INSERT INTO users
            (owner_id, email, display_name, salt, password_hash, kdf, created_at, last_seen_at, role, status, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)`,
    args: [ownerId, email, ownerId, Buffer.from([1]), Buffer.from([2]), 'scrypt:test', NOW, NOW, role, NOW],
  });
}

async function insertCategory(client: Client, ownerId: string, id: string, code: string): Promise<void> {
  await client.execute({
    sql: `INSERT INTO categories
            (id, owner_id, code, kind, label_ciphertext, label_iv, label_tag, key_version,
             revision, created_at, updated_at)
          VALUES (?, ?, ?, 'EXPENSE', ?, ?, ?, 1, 0, ?, ?)`,
    args: [id, ownerId, code, Buffer.from([1]), Buffer.from([2]), Buffer.from([3]), NOW, NOW],
  });
}

interface TransactionInput {
  readonly id: string;
  readonly ownerId: string;
  readonly categoryId: string | null;
  readonly amountMinor?: number;
  readonly fingerprint?: string;
  readonly eventAtMs?: number;
  readonly deletedAt?: string | null;
}

async function insertTransaction(client: Client, input: TransactionInput): Promise<void> {
  await client.execute({
    sql: `INSERT INTO transactions
            (id, owner_id, category_id, direction, source, amount_minor, currency, event_at_ms,
             month_id, fingerprint, category_origin, sensitive_ciphertext, sensitive_iv,
             sensitive_tag, key_version, revision, created_at, updated_at, deleted_at)
          VALUES (?, ?, ?, 'DEBIT', 'MANUAL', ?, 'IDR', ?, '2026-11', ?, 'USER', ?, ?, ?, 1, 0, ?, ?, ?)`,
    args: [
      input.id,
      input.ownerId,
      input.categoryId,
      input.amountMinor ?? 100_000,
      input.eventAtMs ?? Date.parse('2026-11-10T03:00:00.000Z'),
      input.fingerprint ?? input.id.padEnd(32, '0').slice(0, 32),
      Buffer.from([1]),
      Buffer.from([2]),
      Buffer.from([3]),
      NOW,
      NOW,
      input.deletedAt ?? null,
    ],
  });
}

async function rejects(promise: Promise<unknown>, pattern: RegExp): Promise<void> {
  await assert.rejects(promise, (error: unknown) => {
    assert.match(String((error as Error).message), pattern);
    return true;
  });
}

test('owner-scoped foreign keys reject a cross-owner category', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_a');
    await insertUser(client, 'u_b');
    await insertCategory(client, 'u_a', 'cat_a', 'FOOD');
    await insertCategory(client, 'u_b', 'cat_b', 'FOOD');

    // Same owner: allowed.
    await insertTransaction(client, { id: 'tx_a_ok', ownerId: 'u_a', categoryId: 'cat_a' });

    // u_a may not attach u_b's category, even though the id exists.
    await rejects(
      insertTransaction(client, { id: 'tx_cross', ownerId: 'u_a', categoryId: 'cat_b' }),
      /FOREIGN KEY constraint failed/i,
    );
  });
});

test('a fingerprint is unique per owner, not globally', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_a');
    await insertUser(client, 'u_b');
    await insertCategory(client, 'u_a', 'cat_a', 'FOOD');
    await insertCategory(client, 'u_b', 'cat_b', 'FOOD');
    const fingerprint = 'f'.repeat(32);

    await insertTransaction(client, { id: 'tx_a', ownerId: 'u_a', categoryId: 'cat_a', fingerprint });
    await insertTransaction(client, { id: 'tx_b', ownerId: 'u_b', categoryId: 'cat_b', fingerprint });
    await rejects(
      insertTransaction(client, { id: 'tx_a_dupe', ownerId: 'u_a', categoryId: 'cat_a', fingerprint }),
      /UNIQUE constraint failed/i,
    );
  });
});

test('amounts stay in safe integer minor units and JSON columns are validated', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_a');
    await insertCategory(client, 'u_a', 'cat_a', 'FOOD');

    for (const amount of [0, -1, 1_000_000_000_000_001]) {
      await rejects(
        insertTransaction(client, { id: `tx_bad_${amount}`, ownerId: 'u_a', categoryId: 'cat_a', amountMinor: amount }),
        /CHECK constraint failed/i,
      );
    }
    await insertTransaction(client, {
      id: 'tx_max',
      ownerId: 'u_a',
      categoryId: 'cat_a',
      amountMinor: 1_000_000_000_000_000,
    });

    await rejects(
      client.execute({
        sql: `INSERT INTO import_jobs
                (id, owner_id, month_id, state, template_version, counters_json, started_at)
              VALUES ('job_bad', 'u_a', '2026-11', 'RUNNING', 'v1', 'not-json', ?)`,
        args: [NOW],
      }),
      /CHECK constraint failed/i,
    );
    await client.execute({
      sql: `INSERT INTO import_jobs
              (id, owner_id, month_id, state, template_version, counters_json, started_at)
            VALUES ('job_ok', 'u_a', '2026-11', 'RUNNING', 'v1', '{"pages":1}', ?)`,
      args: [NOW],
    });
  });
});

test('soft delete removes a row from active totals but keeps it for retention', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_a');
    await insertCategory(client, 'u_a', 'cat_a', 'FOOD');
    await insertTransaction(client, { id: 'tx_1', ownerId: 'u_a', categoryId: 'cat_a', amountMinor: 100_000 });
    await insertTransaction(client, { id: 'tx_2', ownerId: 'u_a', categoryId: 'cat_a', amountMinor: 200_000 });

    const activeBefore = await client.execute(
      `SELECT COALESCE(SUM(amount_minor), 0) AS total FROM transactions
        WHERE owner_id = ? AND month_id = ? AND deleted_at IS NULL`,
      ['u_a', '2026-11'],
    );
    assert.equal(Number(activeBefore.rows[0]?.total), 300_000);

    await client.execute({
      sql: 'UPDATE transactions SET deleted_at = ?, revision = revision + 1 WHERE owner_id = ? AND id = ?',
      args: [NOW, 'u_a', 'tx_1'],
    });

    const activeAfter = await client.execute(
      `SELECT COALESCE(SUM(amount_minor), 0) AS total FROM transactions
        WHERE owner_id = ? AND month_id = ? AND deleted_at IS NULL`,
      ['u_a', '2026-11'],
    );
    assert.equal(Number(activeAfter.rows[0]?.total), 200_000);
    const retained = await client.execute(
      'SELECT COUNT(*) AS total FROM transactions WHERE owner_id = ? AND deleted_at IS NOT NULL',
      ['u_a'],
    );
    assert.equal(Number(retained.rows[0]?.total), 1);
  });
});

test('category reassignment and soft delete commit together', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_a');
    await insertCategory(client, 'u_a', 'cat_old', 'FOOD');
    await insertCategory(client, 'u_a', 'cat_new', 'SHOPPING');
    await insertTransaction(client, { id: 'tx_1', ownerId: 'u_a', categoryId: 'cat_old' });

    const transaction = await client.transaction('write');
    try {
      await transaction.execute({
        sql: 'UPDATE transactions SET category_id = ?, revision = revision + 1 WHERE owner_id = ? AND id = ?',
        args: ['cat_new', 'u_a', 'tx_1'],
      });
      await transaction.execute({
        sql: 'UPDATE categories SET deleted_at = ?, revision = revision + 1 WHERE owner_id = ? AND id = ?',
        args: [NOW, 'u_a', 'cat_old'],
      });
      await transaction.commit();
    } catch (error) {
      await transaction.rollback();
      throw error;
    }

    const row = await client.execute('SELECT category_id FROM transactions WHERE id = ?', ['tx_1']);
    assert.equal(row.rows[0]?.category_id, 'cat_new');
    const old = await client.execute('SELECT deleted_at FROM categories WHERE id = ?', ['cat_old']);
    assert.equal(old.rows[0]?.deleted_at, NOW);
  });
});

test('the last active admin cannot be demoted or deleted', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_admin', 'ADMIN');
    await insertUser(client, 'u_user');

    await rejects(
      client.execute("UPDATE users SET role = 'USER' WHERE owner_id = 'u_admin'"),
      /last_active_admin/,
    );
    await rejects(
      client.execute("DELETE FROM users WHERE owner_id = 'u_admin'"),
      /last_active_admin/,
    );

    await insertUser(client, 'u_admin_2', 'ADMIN');
    await client.execute("UPDATE users SET role = 'USER' WHERE owner_id = 'u_admin'");
    const demoted = await client.execute('SELECT role FROM users WHERE owner_id = ?', ['u_admin']);
    assert.equal(demoted.rows[0]?.role, 'USER');

    // u_admin_2 is now the only active admin.
    await rejects(client.execute("DELETE FROM users WHERE owner_id = 'u_admin_2'"), /last_active_admin/);
  });
});

test('refresh rotation and family revocation are single-transaction operations', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_a');
    await client.execute({
      sql: `INSERT INTO auth_sessions (id, owner_id, family_id, created_at, last_seen_at, expires_at)
            VALUES ('sess_1', 'u_a', 'fam_1', ?, ?, '2026-12-21T12:00:00.000Z')`,
      args: [NOW, NOW],
    });
    await client.execute({
      sql: `INSERT INTO refresh_tokens
              (id, owner_id, session_id, family_id, token_hash, issued_at, expires_at)
            VALUES ('rt_1', 'u_a', 'sess_1', 'fam_1', 'hash_1', ?, '2026-12-21T12:00:00.000Z')`,
      args: [NOW],
    });

    const rotation = await client.transaction('write');
    try {
      // Insert the replacement first: replaced_by_id is a foreign key, so linking the old row
      // before the new one exists would fail. Both statements are still one atomic transaction.
      await rotation.execute({
        sql: `INSERT INTO refresh_tokens
                (id, owner_id, session_id, family_id, token_hash, issued_at, expires_at)
              VALUES ('rt_2', 'u_a', 'sess_1', 'fam_1', 'hash_2', ?, '2026-12-21T12:00:00.000Z')`,
        args: [NOW],
      });
      await rotation.execute({
        sql: `UPDATE refresh_tokens
                 SET revoked_at = ?, revoked_reason = 'rotated', replaced_by_id = 'rt_2'
               WHERE id = 'rt_1' AND revoked_at IS NULL`,
        args: [NOW],
      });
      await rotation.commit();
    } catch (error) {
      await rotation.rollback();
      throw error;
    }

    const state = await client.execute(
      "SELECT id, revoked_at, replaced_by_id FROM refresh_tokens WHERE family_id = 'fam_1' ORDER BY id",
    );
    assert.equal(state.rows.length, 2);
    assert.equal(state.rows[0]?.revoked_at, NOW);
    assert.equal(state.rows[0]?.replaced_by_id, 'rt_2');
    assert.equal(state.rows[1]?.revoked_at, null);

    // A replayed old token revokes the whole family instead of minting a third token.
    await client.execute({
      sql: `UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, ?), revoked_reason = 'reuse_detected'
            WHERE family_id = 'fam_1' AND revoked_at IS NULL`,
      args: [NOW],
    });
    const revoked = await client.execute(
      "SELECT COUNT(*) AS total FROM refresh_tokens WHERE family_id = 'fam_1' AND revoked_at IS NULL",
    );
    assert.equal(Number(revoked.rows[0]?.total), 0);
  });
});

test('list pagination uses the owner/month index and is stable', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_a');
    await insertCategory(client, 'u_a', 'cat_a', 'FOOD');
    await insertTransaction(client, { id: 'tx_1', ownerId: 'u_a', categoryId: 'cat_a', eventAtMs: 1_000 });
    await insertTransaction(client, { id: 'tx_2', ownerId: 'u_a', categoryId: 'cat_a', eventAtMs: 2_000 });
    await insertTransaction(client, { id: 'tx_3', ownerId: 'u_a', categoryId: 'cat_a', eventAtMs: 3_000 });

    const plan = await client.execute(
      `EXPLAIN QUERY PLAN
       SELECT id FROM transactions
        WHERE owner_id = 'u_a' AND month_id = '2026-11' AND deleted_at IS NULL
        ORDER BY event_at_ms DESC, id DESC LIMIT 2`,
    );
    const detail = plan.rows.map((row) => String(row.detail)).join(' | ');
    assert.match(detail, /idx_transactions_owner_month/);

    const page = await client.execute(
      `SELECT id FROM transactions
        WHERE owner_id = 'u_a' AND month_id = '2026-11' AND deleted_at IS NULL
        ORDER BY event_at_ms DESC, id DESC LIMIT 2`,
    );
    assert.deepEqual(page.rows.map((row) => String(row.id)), ['tx_3', 'tx_2']);
  });
});

test('a v0.3 budget plan accepts limits and defaults to revision 0', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_a');
    await insertCategory(client, 'u_a', 'cat_a', 'FOOD');
    await client.execute({
      sql: `INSERT INTO budget_plans
              (id, owner_id, month_id, ciphertext, iv, tag, key_version, created_at, updated_at)
            VALUES ('plan_1', 'u_a', '2026-11', ?, ?, ?, 1, ?, ?)`,
      args: [Buffer.from([1]), Buffer.from([2]), Buffer.from([3]), NOW, NOW],
    });
    const plan = await client.execute("SELECT revision FROM budget_plans WHERE id = 'plan_1'");
    assert.equal(Number(plan.rows[0]?.revision), 0);

    await client.execute({
      sql: `INSERT INTO budget_limits
              (owner_id, plan_id, category_id, amount_minor, created_at, updated_at)
            VALUES ('u_a', 'plan_1', 'cat_a', 500000, ?, ?)`,
      args: [NOW, NOW],
    });
    await rejects(
      client.execute({
        sql: `INSERT INTO budget_limits
                (owner_id, plan_id, category_id, amount_minor, created_at, updated_at)
              VALUES ('u_b', 'plan_1', 'cat_a', 1, ?, ?)`,
        args: [NOW, NOW],
      }),
      /FOREIGN KEY constraint failed/i,
    );
  });
});

test('admin invitations are one-time, role-constrained and unique', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_admin', 'ADMIN');
    await client.execute({
      sql: `INSERT INTO admin_invitations
              (id, created_by, email, role, token_hash, expires_at, created_at)
            VALUES ('inv_1', 'u_admin', 'new@example.test', 'ADMIN', 'hash_inv', '2026-12-01T00:00:00.000Z', ?)`,
      args: [NOW],
    });
    await rejects(
      client.execute({
        sql: `INSERT INTO admin_invitations
                (id, created_by, email, role, token_hash, expires_at, created_at)
              VALUES ('inv_2', 'u_admin', 'other@example.test', 'ADMIN', 'hash_inv', '2026-12-01T00:00:00.000Z', ?)`,
        args: [NOW],
      }),
      /UNIQUE constraint failed/i,
    );
    await rejects(
      client.execute({
        sql: `INSERT INTO admin_invitations
                (id, created_by, email, role, token_hash, expires_at, created_at)
              VALUES ('inv_3', 'u_admin', 'super@example.test', 'SUPER', 'hash_super', '2026-12-01T00:00:00.000Z', ?)`,
        args: [NOW],
      }),
      /CHECK constraint failed/i,
    );
  });
});

test('the fingerprint key is one sealed row per owner and users start at revision 0', async () => {
  await withDatabase(async (client) => {
    await insertUser(client, 'u_a');
    const user = await client.execute('SELECT revision, role, status FROM users WHERE owner_id = ?', ['u_a']);
    assert.equal(Number(user.rows[0]?.revision), 0);
    assert.equal(user.rows[0]?.role, 'USER');
    assert.equal(user.rows[0]?.status, 'ACTIVE');

    await client.execute({
      sql: `INSERT INTO account_keys
              (owner_id, key_version, fingerprint_ciphertext, fingerprint_iv, fingerprint_tag, created_at)
            VALUES ('u_a', 1, ?, ?, ?, ?)`,
      args: [Buffer.from([1]), Buffer.from([2]), Buffer.from([3]), NOW],
    });
    await rejects(
      client.execute({
        sql: `INSERT INTO account_keys
                (owner_id, key_version, fingerprint_ciphertext, fingerprint_iv, fingerprint_tag, created_at)
              VALUES ('u_a', 1, ?, ?, ?, ?)`,
        args: [Buffer.from([4]), Buffer.from([5]), Buffer.from([6]), NOW],
      }),
      /UNIQUE constraint failed/i,
    );
    await rejects(
      client.execute({
        sql: `INSERT INTO account_keys
                (owner_id, key_version, fingerprint_ciphertext, fingerprint_iv, fingerprint_tag, created_at)
              VALUES ('u_missing', 1, ?, ?, ?, ?)`,
        args: [Buffer.from([4]), Buffer.from([5]), Buffer.from([6]), NOW],
      }),
      /FOREIGN KEY constraint failed/i,
    );
  });
});
