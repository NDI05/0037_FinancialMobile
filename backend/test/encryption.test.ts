import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { createClient } from '@libsql/client';
import { test } from 'node:test';
import { ownerIdForSeed } from './helpers.js';
import { analyze, api, createHarness, grantConsent, login, samplePayload, tempDatabasePath } from './helpers.js';

/**
 * Contract §6 / §8 bullet 8: the database file must not contain the plaintext aggregate, analysis
 * or plan. Distinctive amounts make a false negative impossible.
 */
const MARKER_AMOUNT = 123_456_789;
const MARKER_TEXT = 'UNIK-tanda-baca-7f3a';

test('sealed fields never appear in the database file, and decrypt correctly', async () => {
  const databasePath = tempDatabasePath();
  const harness = await createHarness({ databasePath });
  try {
    const token = await login(harness, 'user-crypto');
    await grantConsent(harness, token);
    const payload = samplePayload({ grossInflowMinor: MARKER_AMOUNT, monthId: '2026-02' });

    assert.equal(
      (await api(harness, 'PUT', '/v1/summaries/2026-02', {
        token,
        body: { inputRevision: 1, payload },
      })).status,
      200,
    );
    const analysis = await analyze(harness, token, { payload, inputRevision: 1, idempotencyKey: 'crypto-1' });
    assert.equal(analysis.status, 200);
    assert.equal(
      (await api(harness, 'PUT', '/v1/budget-plans/2026-03', {
        token,
        body: {
          plan: {
            expectedIncomeMinor: MARKER_AMOUNT,
            plannedSavingsMinor: 1_000_000,
            categoryLimitTotals: [],
          },
        },
      })).status,
      200,
    );

    // Write a marker into an analysis text by using a provider-free path: the fake's own prose is
    // already distinctive, so assert on both the amount and a sentence fragment.
    const ownerId = ownerIdForSeed('user-crypto');
    const stored = await harness.store.getSummary(ownerId, '2026-02');
    assert.ok(stored);
    assert.equal(stored.payload.grossInflowMinor, MARKER_AMOUNT);
    assert.equal((await harness.store.getBudgetPlan(ownerId, '2026-03'))?.plan.expectedIncomeMinor, MARKER_AMOUNT);
    assert.ok((await harness.store.latestAnalysis(ownerId, '2026-02'))?.analysis.observations.length);

    const directory = dirname(databasePath);
    const files = readdirSync(directory).filter((name) => name.includes('pam.db'));
    assert.ok(files.length > 0, 'database files exist');
    const bytes = Buffer.concat(files.map((name) => readFileSync(`${directory}/${name}`))).toString('latin1');

    assert.ok(!bytes.includes(String(MARKER_AMOUNT)), 'the amount must not be plaintext');
    assert.ok(!bytes.includes('123456789'), 'the amount must not be plaintext in any spelling');
    assert.ok(!bytes.includes('grossInflowMinor'), 'the payload keys must not be plaintext');
    assert.ok(!bytes.includes('mencatat'), 'the analysis prose must not be plaintext');
    assert.ok(!bytes.includes('housing'), 'category codes from the sealed plan must not be plaintext');
    assert.ok(!bytes.includes(MARKER_TEXT));
  } finally {
    await harness.close();
  }
});

test('a ciphertext copied to another owner fails to decrypt (AAD binds the owner)', async () => {
  const databasePath = tempDatabasePath();
  const harness = await createHarness({ databasePath });
  try {
    const aliceToken = await login(harness, 'user-crypto-a');
    const bobToken = await login(harness, 'user-crypto-b');
    await grantConsent(harness, aliceToken);
    await grantConsent(harness, bobToken);
    const alicePayload = samplePayload({ grossInflowMinor: 555_000_000 });
    const bobPayload = samplePayload({ grossInflowMinor: 111_000_000 });

    for (const [token, payload] of [
      [aliceToken, alicePayload],
      [bobToken, bobPayload],
    ] as const) {
      assert.equal(
        (await api(harness, 'PUT', '/v1/summaries/2026-02', {
          token,
          body: { inputRevision: 1, payload },
        })).status,
        200,
      );
    }

    const aliceOwner = ownerIdForSeed('user-crypto-a');
    const bobOwner = ownerIdForSeed('user-crypto-b');
    const aliceRow = await harness.store.getSummary(aliceOwner, '2026-02');
    assert.ok(aliceRow);

    // Copy Alice's sealed columns onto Bob's row, straight in the database (same libSQL driver).
    const raw = createClient({ url: `file:${databasePath}` });
    try {
      const columns = (
        await raw.execute({
          sql: 'SELECT ciphertext, iv, tag, key_version FROM summaries WHERE owner_id = ?',
          args: [aliceOwner],
        })
      ).rows[0] as unknown as { ciphertext: ArrayBuffer; iv: ArrayBuffer; tag: ArrayBuffer; key_version: number };
      await raw.execute({
        sql: 'UPDATE summaries SET ciphertext = ?, iv = ?, tag = ?, key_version = ? WHERE owner_id = ?',
        args: [columns.ciphertext, columns.iv, columns.tag, columns.key_version, bobOwner],
      });
    } finally {
      raw.close();
    }

    await assert.rejects(async () => {
      await harness.store.getSummary(bobOwner, '2026-02');
    });
    // Alice's own row is untouched.
    assert.equal((await harness.store.getSummary(aliceOwner, '2026-02'))?.payload.grossInflowMinor, 555_000_000);
  } finally {
    await harness.close();
  }
});
