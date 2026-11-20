import assert from 'node:assert/strict';
import { test } from 'node:test';
import { FakeProvider } from '../src/llm/fake.js';
import { analyze, api, createHarness, grantConsent, login } from './helpers.js';

/**
 * FR-50 / contract §5 / §8 bullet 7: limits are enforced **before** the provider call, so a
 * limited request costs nothing.
 */

test('the hourly limit rejects the third analysis without calling the provider', async () => {
  const provider = new FakeProvider('valid');
  const harness = await createHarness({
    provider,
    env: { RATE_LIMIT_ANALYSES_PER_HOUR: '2', RATE_LIMIT_ANALYSES_PER_DAY: '40' },
  });
  try {
    const token = await login(harness, 'google-sub-hourly');
    await grantConsent(harness, token);

    assert.equal((await analyze(harness, token, { inputRevision: 1, idempotencyKey: 'h1' })).status, 200);
    assert.equal((await analyze(harness, token, { inputRevision: 2, idempotencyKey: 'h2' })).status, 200);
    const limited = await analyze(harness, token, { inputRevision: 3, idempotencyKey: 'h3' });

    assert.equal(limited.status, 429);
    assert.equal(limited.json.code, 'rate_limited');
    assert.equal(limited.json.retryable, true);
    assert.equal(provider.calls, 2, 'the provider is never called for a limited request');
  } finally {
    await harness.close();
  }
});

test('the daily limit rejects a second analysis when the limit is one', async () => {
  const provider = new FakeProvider('valid');
  const harness = await createHarness({
    provider,
    env: { RATE_LIMIT_ANALYSES_PER_HOUR: '10', RATE_LIMIT_ANALYSES_PER_DAY: '1' },
  });
  try {
    const token = await login(harness, 'google-sub-daily');
    await grantConsent(harness, token);
    assert.equal((await analyze(harness, token, { inputRevision: 1, idempotencyKey: 'd1' })).status, 200);
    const limited = await analyze(harness, token, { inputRevision: 2, idempotencyKey: 'd2' });
    assert.equal(limited.status, 429);
    assert.equal(limited.json.code, 'rate_limited');
    assert.equal(provider.calls, 1);
  } finally {
    await harness.close();
  }
});

test('the global daily ceiling rejects before any provider call', async () => {
  const provider = new FakeProvider('valid');
  const harness = await createHarness({ provider, env: { DAILY_AI_CEILING_USD: '0' } });
  try {
    const token = await login(harness, 'google-sub-ceiling');
    await grantConsent(harness, token);
    const limited = await analyze(harness, token, { idempotencyKey: 'c1' });
    assert.equal(limited.status, 429);
    assert.equal(limited.json.code, 'daily_ceiling_reached');
    assert.equal(provider.calls, 0);
  } finally {
    await harness.close();
  }
});

test('quota is checked before the cache, and GET still serves the stored analysis', async () => {
  // Contract §4.1 orders the steps: quota (4) then cache (5). A limited user therefore cannot
  // create a new analysis call at all — but FR-40 reading is served by GET, which is not limited.
  const provider = new FakeProvider('valid');
  const harness = await createHarness({
    provider,
    env: { RATE_LIMIT_ANALYSES_PER_HOUR: '1' },
  });
  try {
    const token = await login(harness, 'google-sub-cache-quota');
    await grantConsent(harness, token);
    assert.equal((await analyze(harness, token, { inputRevision: 1, idempotencyKey: 'q1' })).status, 200);

    const limited = await analyze(harness, token, { inputRevision: 1, idempotencyKey: 'q2' });
    assert.equal(limited.status, 429);
    assert.equal(limited.json.code, 'rate_limited');
    assert.equal(provider.calls, 1);

    const read = await api(harness, 'GET', '/v1/analyses/2026-02', { token });
    assert.equal(read.status, 200);
    assert.equal(read.json.promptVersion, 'analysis-v2');
  } finally {
    await harness.close();
  }
});
