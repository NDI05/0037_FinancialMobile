import assert from 'node:assert/strict';
import { test } from 'node:test';
import { FakeProvider } from '../src/llm/fake.js';
import { jakartaBuckets } from '../src/store/store.js';
import { analyze, api, createHarness, grantConsent, login, samplePayload } from './helpers.js';

/**
 * F3 on the new async driver: the two places where a check and a write could interleave are
 * atomic.
 */
test('concurrent analyses against a limit of 3 admit exactly 3', async () => {
  const provider = new FakeProvider('valid');
  const harness = await createHarness({
    provider,
    env: {
      RATE_LIMIT_ANALYSES_PER_HOUR: '3',
      RATE_LIMIT_ANALYSES_PER_DAY: '40',
    },
  });
  try {
    const token = await login(harness, 'user-quota-race');
    await grantConsent(harness, token);

    const results = await Promise.all(
      Array.from({ length: 6 }, (_unused, index) =>
        analyze(harness, token, {
          inputRevision: index + 1,
          idempotencyKey: `race-${index}`,
        }),
      ),
    );
    const statuses = results.map((result) => result.status).sort();
    assert.deepEqual(statuses, [200, 200, 200, 429, 429, 429]);
    assert.equal(provider.calls, 3, 'the provider is called once per admitted analysis');

    // The counters agree with what was admitted: exactly the three reservations.
    const owner = (await harness.store.findAccountByEmail('user-quota-race@example.test'))!.ownerId;
    const { hour } = jakartaBuckets(new Date());
    assert.equal((await harness.store.getUsage(owner, `hour:${hour}`)).analyses, 3);
  } finally {
    await harness.close();
  }
});

test('a pending deletion blocks concurrent analyses and the sweep removes the aggregates', async () => {
  const provider = new FakeProvider('valid');
  const harness = await createHarness({ provider });
  try {
    const token = await login(harness, 'user-deletion-race');
    await grantConsent(harness, token);
    assert.equal(
      (await api(harness, 'PUT', '/v1/summaries/2026-02', {
        token,
        body: { inputRevision: 1, payload: samplePayload() },
      })).status,
      200,
    );
    assert.equal((await analyze(harness, token, { idempotencyKey: 'before' })).status, 200);
    const providerCallsBefore = provider.calls;

    const deletion = await api(harness, 'POST', '/v1/deletion-requests', { token });
    assert.equal(deletion.status, 202);

    const blocked = await Promise.all([
      analyze(harness, token, { inputRevision: 2, idempotencyKey: 'during-1' }),
      analyze(harness, token, { inputRevision: 3, idempotencyKey: 'during-2' }),
    ]);
    for (const result of blocked) {
      assert.equal(result.status, 409);
      assert.equal(result.json.code, 'deletion_in_progress');
    }
    assert.equal(provider.calls, providerCallsBefore, 'no provider call while deletion is pending');

    // Run the same sweep job the boot path runs (the harness has no internal token configured).
    const completed = await harness.store.sweepDeletions(new Date().toISOString());
    assert.ok(completed >= 1);

    assert.equal((await api(harness, 'GET', '/v1/analyses/2026-02', { token })).status, 404);
    assert.equal((await api(harness, 'GET', '/v1/summaries/2026-02', { token })).status, 404);
  } finally {
    await harness.close();
  }
});
