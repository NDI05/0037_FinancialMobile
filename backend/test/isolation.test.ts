import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { analyze, api, createHarness, grantConsent, login, samplePayload, type Harness } from './helpers.js';

/**
 * FR-02 / NFR-17 / §8 bullet 3: user B can never read or mutate user A's objects, on every
 * resource type. The assertion is a 404 for reads (nothing exists for B) and independence for
 * writes.
 */
let harness: Harness;
let alice: string;
let bob: string;

before(async () => {
  harness = await createHarness();
  alice = await login(harness, 'google-sub-owner-a', 'Alice');
  bob = await login(harness, 'google-sub-owner-b', 'Bob');
  await grantConsent(harness, alice);
  await grantConsent(harness, bob);
});

after(async () => {
  await harness.close();
});

test('summaries are owner-scoped', async () => {
  const put = await api(harness, 'PUT', '/v1/summaries/2026-02', {
    token: alice,
    body: { inputRevision: 3, payload: samplePayload() },
  });
  assert.equal(put.status, 200);

  const bobRead = await api(harness, 'GET', '/v1/summaries/2026-02', { token: bob });
  assert.equal(bobRead.status, 404);
  assert.equal(bobRead.json.code, 'not_found');

  const aliceRead = await api(harness, 'GET', '/v1/summaries/2026-02', { token: alice });
  assert.equal(aliceRead.status, 200);
  assert.equal((aliceRead.json.payload as { grossInflowMinor: number }).grossInflowMinor, 585_000_000);
});

test('analyses are owner-scoped', async () => {
  const created = await analyze(harness, alice, { idempotencyKey: 'iso-a' });
  assert.equal(created.status, 200);

  const bobRead = await api(harness, 'GET', '/v1/analyses/2026-02', { token: bob });
  assert.equal(bobRead.status, 404);

  // Bob's own analysis for the same month is his own row: the FR-40 cache is per owner, so he
  // gets a fresh provider call (and his own stored analysis) rather than Alice's.
  const bobCreated = await analyze(harness, bob, { idempotencyKey: 'iso-b' });
  assert.equal(bobCreated.status, 200);
  assert.equal(bobCreated.json.promptVersion, 'analysis-v1');
  assert.ok(Array.isArray(bobCreated.json.observations));
});

test('budget plans are owner-scoped', async () => {
  const put = await api(harness, 'PUT', '/v1/budget-plans/2026-03', {
    token: alice,
    body: {
      plan: {
        expectedIncomeMinor: 287_500_000,
        plannedSavingsMinor: 28_750_000,
        categoryLimitTotals: [{ categoryCode: 'housing', amountMinor: 95_000_000, transactionCount: 2 }],
      },
    },
  });
  assert.equal(put.status, 200);

  const bobRead = await api(harness, 'GET', '/v1/budget-plans/2026-03', { token: bob });
  assert.equal(bobRead.status, 404);

  const aliceRead = await api(harness, 'GET', '/v1/budget-plans/2026-03', { token: alice });
  assert.equal(aliceRead.status, 200);
  assert.equal(aliceRead.json.plannedSavingsMinor, 28_750_000);
});

test('deletion requests are owner-scoped', async () => {
  const created = await api(harness, 'POST', '/v1/deletion-requests', { token: alice });
  assert.equal(created.status, 202);
  const id = created.json.id as string;

  const bobRead = await api(harness, 'GET', `/v1/deletion-requests/${id}`, { token: bob });
  assert.equal(bobRead.status, 404);

  // Bob's account is unaffected: his analysis still works.
  const bobAnalysis = await analyze(harness, bob, { inputRevision: 4, idempotencyKey: 'iso-b-after' });
  assert.equal(bobAnalysis.status, 200);
});

test('the consent ledger is owner-scoped', async () => {
  const bobLedger = await api(harness, 'GET', '/v1/consents', { token: bob });
  assert.equal(bobLedger.status, 200);
  const purposes = (bobLedger.json.consents as Array<{ purpose: string }>).map((entry) => entry.purpose);
  assert.deepEqual(purposes, ['AI_PROCESSING']);
});
