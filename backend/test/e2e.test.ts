import assert from 'node:assert/strict';
import { test } from 'node:test';
import { FakeProvider } from '../src/llm/fake.js';
import { api, analyze, createHarness, login, samplePayload } from './helpers.js';

/**
 * Contract §8 happy path, in the order the Android app drives it:
 * session → consent → summary → analysis → cached second call → plan → deletion.
 */
test('end-to-end: session, consent, summary, analysis, cache, plan, deletion', async () => {
  const provider = new FakeProvider('valid');
  const harness = await createHarness({ provider, env: { INTERNAL_SWEEP_TOKEN: 'e2e-sweep-token' } });
  try {
    const health = await api(harness, 'GET', '/healthz');
    assert.equal(health.status, 200);
    assert.equal(health.json.status, 'ok');

    const session = await api(harness, 'POST', '/v1/sessions', {
      body: { idToken: 'dev:google-sub-e2e:Andhika' },
    });
    assert.equal(session.status, 201);
    const token = session.json.accessToken as string;
    assert.equal(session.json.displayName, 'Andhika');

    const consent = await api(harness, 'POST', '/v1/consents', {
      token,
      body: { purpose: 'AI_PROCESSING', decision: 'GRANTED', noticeVersion: 'v1' },
    });
    assert.equal(consent.status, 201);

    const payload = samplePayload();
    const summary = await api(harness, 'PUT', '/v1/summaries/2026-02', {
      token,
      body: { inputRevision: 7, payload },
    });
    assert.equal(summary.status, 200);
    assert.equal(summary.json.inputRevision, 7);

    const first = await analyze(harness, token, { inputRevision: 7, idempotencyKey: 'e2e-1' });
    assert.equal(first.status, 200);
    assert.equal(provider.calls, 1);

    const cached = await analyze(harness, token, { inputRevision: 7, idempotencyKey: 'e2e-2' });
    assert.equal(cached.status, 200);
    assert.equal(provider.calls, 1, 'the second call is served from the FR-40 cache');
    assert.deepEqual(cached.json, first.json);

    const stored = await api(harness, 'GET', '/v1/analyses/2026-02', { token });
    assert.equal(stored.status, 200);
    assert.deepEqual(stored.json, first.json);

    const plan = await api(harness, 'PUT', '/v1/budget-plans/2026-03', {
      token,
      body: {
        plan: {
          expectedIncomeMinor: 287_500_000,
          plannedSavingsMinor: 28_750_000,
          categoryLimitTotals: [
            { categoryCode: 'housing', amountMinor: 95_000_000, transactionCount: 2 },
          ],
        },
      },
    });
    assert.equal(plan.status, 200);
    const readPlan = await api(harness, 'GET', '/v1/budget-plans/2026-03', { token });
    assert.equal(readPlan.status, 200);
    assert.equal(readPlan.json.expectedIncomeMinor, 287_500_000);

    const me = await api(harness, 'GET', '/v1/me', { token });
    assert.equal(me.status, 200);
    assert.equal(me.json.status.AI_PROCESSING, 'GRANTED');
    assert.ok(me.json.lastAnalysisAt);

    const deletion = await api(harness, 'POST', '/v1/deletion-requests', { token });
    assert.equal(deletion.status, 202);

    const blocked = await analyze(harness, token, { inputRevision: 8, idempotencyKey: 'e2e-3' });
    assert.equal(blocked.status, 409);
    assert.equal(blocked.json.code, 'deletion_in_progress');

    const sweep = await api(harness, 'POST', '/v1/internal/sweep', {
      headers: { 'x-internal-token': 'e2e-sweep-token' },
    });
    assert.equal(sweep.status, 200);
    assert.ok(sweep.json.completed >= 1);

    assert.equal((await api(harness, 'GET', '/v1/analyses/2026-02', { token })).status, 404);
    const finished = await api(harness, 'GET', `/v1/deletion-requests/${deletion.json.id}`, { token });
    assert.equal(finished.json.state, 'COMPLETED');

    // No log line may carry request content: the final assertion of the happy path.
    const raw = harness.logs.join('\n');
    assert.ok(!raw.includes(token));
    assert.ok(!raw.includes('585000000'));
    assert.ok(!raw.includes('Rp'));
  } finally {
    await harness.close();
  }
});
