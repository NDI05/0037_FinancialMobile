import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ownerIdForSeed } from './helpers.js';
import { analyze, api, createHarness, grantConsent, login, samplePayload } from './helpers.js';

/**
 * FR-47/FR-48 and contract §4.3: a PENDING request blocks sync and analysis immediately, the sweep
 * removes the aggregates, and the request row keeps its state and timestamps for reporting.
 */
async function seeded() {
  const harness = await createHarness({ env: { INTERNAL_SWEEP_TOKEN: 'sweep-token-for-tests' } });
  const token = await login(harness, 'user-delete');
  await grantConsent(harness, token);
  assert.equal(
    (await api(harness, 'PUT', '/v1/summaries/2026-02', {
      token,
      body: { inputRevision: 1, payload: samplePayload() },
    })).status,
    200,
  );
  assert.equal((await analyze(harness, token, { idempotencyKey: 'del-1' })).status, 200);
  assert.equal(
    (await api(harness, 'PUT', '/v1/budget-plans/2026-03', {
      token,
      body: { plan: { expectedIncomeMinor: 1, plannedSavingsMinor: 0, categoryLimitTotals: [] } },
    })).status,
    200,
  );
  return { harness, token };
}

test('a pending deletion blocks analysis and summary writes with 409', async () => {
  const { harness, token } = await seeded();
  try {
    const created = await api(harness, 'POST', '/v1/deletion-requests', { token });
    assert.equal(created.status, 202);
    assert.equal(created.json.state, 'PENDING');
    assert.equal(created.json.slaDays, 7);
    assert.equal(typeof created.json.id, 'string');

    const blockedAnalysis = await analyze(harness, token, { inputRevision: 2, idempotencyKey: 'del-2' });
    assert.equal(blockedAnalysis.status, 409);
    assert.equal(blockedAnalysis.json.code, 'deletion_in_progress');

    const blockedSummary = await api(harness, 'PUT', '/v1/summaries/2026-02', {
      token,
      body: { inputRevision: 2, payload: samplePayload({ transactionCount: 19 }) },
    });
    assert.equal(blockedSummary.status, 409);
    assert.equal(blockedSummary.json.code, 'deletion_in_progress');
  } finally {
    await harness.close();
  }
});

test('the sweep requires the internal token and then completes the request', async () => {
  const { harness, token } = await seeded();
  try {
    const request = await api(harness, 'POST', '/v1/deletion-requests', { token });
    const requestId = request.json.id as string;

    const unauthorized = await api(harness, 'POST', '/v1/internal/sweep', {
      headers: { 'x-internal-token': 'wrong-token' },
    });
    assert.equal(unauthorized.status, 401);
    assert.equal(unauthorized.json.code, 'unauthorized');

    const missing = await api(harness, 'POST', '/v1/internal/sweep');
    assert.equal(missing.status, 401);

    const swept = await api(harness, 'POST', '/v1/internal/sweep', {
      headers: { 'x-internal-token': 'sweep-token-for-tests' },
    });
    assert.equal(swept.status, 200);
    assert.ok(swept.json.completed >= 1);

    const after = await api(harness, 'GET', `/v1/deletion-requests/${requestId}`, { token });
    assert.equal(after.status, 200);
    assert.equal(after.json.state, 'COMPLETED');
    assert.ok(after.json.completedAt);
    assert.ok(after.json.aggregatesDeleted >= 3);

    assert.equal((await api(harness, 'GET', '/v1/summaries/2026-02', { token })).status, 404);
    assert.equal((await api(harness, 'GET', '/v1/analyses/2026-02', { token })).status, 404);
    assert.equal((await api(harness, 'GET', '/v1/budget-plans/2026-03', { token })).status, 404);

    // Analysis works again once the request is completed.
    const unblocked = await analyze(harness, token, { inputRevision: 5, idempotencyKey: 'del-3' });
    assert.equal(unblocked.status, 200);
  } finally {
    await harness.close();
  }
});

test('the sweep also runs on boot', async () => {
  const { harness, token } = await seeded();
  try {
    await api(harness, 'POST', '/v1/deletion-requests', { token });
    const ownerId = ownerIdForSeed('user-delete');
    assert.equal(harness.store.hasPendingDeletion(ownerId), true);

    // `buildServer` runs exactly this on boot.
    const completed = harness.store.sweepDeletions(new Date().toISOString());
    assert.equal(completed, 1);
    assert.equal(harness.store.hasPendingDeletion(ownerId), false);
    assert.equal(harness.store.getSummary(ownerId, '2026-02'), null);
  } finally {
    await harness.close();
  }
});

test('the sweep endpoint is closed when no internal token is configured', async () => {
  const harness = await createHarness();
  try {
    const result = await api(harness, 'POST', '/v1/internal/sweep', {
      headers: { 'x-internal-token': 'anything' },
    });
    assert.equal(result.status, 404);
  } finally {
    await harness.close();
  }
});
