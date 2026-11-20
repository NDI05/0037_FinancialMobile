import assert from 'node:assert/strict';
import { test } from 'node:test';
import { formatMinorDigits } from '../src/domain/money.js';
import { FakeProvider } from '../src/llm/fake.js';
import { analyze, createHarness, grantConsent, login, samplePayload } from './helpers.js';

/**
 * Contract §4.1 steps 5–8 and §8 bullets 5/6: valid output, the three rejection classes, the
 * one-regeneration budget, the FR-40 cache and idempotency.
 */
async function harnessWith(scenario: ConstructorParameters<typeof FakeProvider>[0]) {
  const provider = new FakeProvider(scenario);
  const harness = await createHarness({ provider });
  const token = await login(harness, `google-sub-${scenario}`);
  await grantConsent(harness, token);
  return { harness, token, provider };
}

test('a valid provider answer is accepted and matches the client shape', async () => {
  const { harness, token, provider } = await harnessWith('valid');
  try {
    const result = await analyze(harness, token);
    assert.equal(result.status, 200);
    const body = result.json as Record<string, unknown>;
    assert.deepEqual(Object.keys(body).sort(), [
      'completionTokens',
      'limitations',
      'observations',
      'promptTokens',
      'promptVersion',
      'suggestions',
    ]);
    assert.equal(body.promptVersion, 'analysis-v2');
    const observations = body.observations as Array<{ text: string; metricKeys: string[] }>;
    assert.ok(observations.length > 0);
    for (const observation of observations) {
      assert.ok(observation.metricKeys.length > 0);
    }
    assert.ok(Array.isArray(body.limitations) && (body.limitations as string[]).length > 0);
    // F1: money is quoted in rupiah display form, never as a bare minor value.
    const texts = observations.map((observation) => observation.text);
    assert.ok(texts.some((text) => /Rp[0-9]/.test(text)), 'at least one rupiah figure');
    for (const minor of [585_000_000, 287_065_000, 297_935_000]) {
      assert.ok(!texts.join(' ').includes(formatMinorDigits(minor)));
    }
    assert.equal(provider.calls, 1);
  } finally {
    await harness.close();
  }
});

test('the ambiguous bare minor output from a real model is rejected', async () => {
  const { harness, token, provider } = await harnessWith('bare_minor');
  try {
    const result = await analyze(harness, token);
    assert.equal(result.status, 422);
    assert.equal(result.json.code, 'invalid_analysis_output');
    assert.equal(provider.calls, 2, 'one regeneration, then rejection');
  } finally {
    await harness.close();
  }
});

test('malformed JSON is rejected after exactly one regeneration', async () => {
  const { harness, token, provider } = await harnessWith('malformed');
  try {
    const result = await analyze(harness, token);
    assert.equal(result.status, 422);
    assert.equal(result.json.code, 'invalid_analysis_output');
    assert.equal(provider.calls, 2, 'initial attempt + one regeneration');
  } finally {
    await harness.close();
  }
});

test('an invented rupiah amount is rejected', async () => {
  const { harness, token, provider } = await harnessWith('invented');
  try {
    const result = await analyze(harness, token);
    assert.equal(result.status, 422);
    assert.equal(provider.calls, 2);
  } finally {
    await harness.close();
  }
});

test('prompt-injection prose is rejected', async () => {
  const { harness, token, provider } = await harnessWith('injection');
  try {
    const result = await analyze(harness, token);
    assert.equal(result.status, 422);
    assert.equal(provider.calls, 2);
  } finally {
    await harness.close();
  }
});

test('a bad first answer followed by a good one is accepted', async () => {
  const { harness, token, provider } = await harnessWith('invalid_then_valid');
  try {
    const result = await analyze(harness, token);
    assert.equal(result.status, 200);
    assert.equal(provider.calls, 2);
    const retried = harness.logs
      .map((line) => JSON.parse(line))
      .filter((entry) => entry.method === 'POST' && entry.path === '/v1/analyses');
    assert.equal(retried.at(-1)?.retry, 1, 'the retry count is observable');
  } finally {
    await harness.close();
  }
});

test('the same revision is served from cache without calling the provider (FR-40)', async () => {
  const { harness, token, provider } = await harnessWith('valid');
  try {
    const first = await analyze(harness, token, { idempotencyKey: 'cache-1' });
    assert.equal(first.status, 200);
    assert.equal(provider.calls, 1);

    const second = await analyze(harness, token, { idempotencyKey: 'cache-2' });
    assert.equal(second.status, 200);
    assert.equal(provider.calls, 1, 'cache hit must not call the provider');
    assert.deepEqual(second.json, first.json);
  } finally {
    await harness.close();
  }
});

test('a new input revision calls the provider again', async () => {
  const { harness, token, provider } = await harnessWith('valid');
  try {
    await analyze(harness, token, { inputRevision: 1, idempotencyKey: 'rev-1' });
    assert.equal(provider.calls, 1);
    const second = await analyze(harness, token, { inputRevision: 2, idempotencyKey: 'rev-2' });
    assert.equal(second.status, 200);
    assert.equal(provider.calls, 2);
  } finally {
    await harness.close();
  }
});

test('a repeated Idempotency-Key returns the stored answer', async () => {
  const { harness, token, provider } = await harnessWith('valid');
  try {
    const first = await analyze(harness, token, { idempotencyKey: 'idem-1' });
    const replay = await analyze(harness, token, { idempotencyKey: 'idem-1' });
    assert.equal(replay.status, 200);
    assert.deepEqual(replay.json, first.json);
    assert.equal(provider.calls, 1);
  } finally {
    await harness.close();
  }
});

test('the same Idempotency-Key with a different body is 409 conflict', async () => {
  const { harness, token } = await harnessWith('valid');
  try {
    await analyze(harness, token, { idempotencyKey: 'idem-shared' });
    const changed = await analyze(harness, token, {
      idempotencyKey: 'idem-shared',
      inputRevision: 9,
      payload: samplePayload({ transactionCount: 19 }),
    });
    assert.equal(changed.status, 409);
    assert.equal(changed.json.code, 'conflict');
  } finally {
    await harness.close();
  }
});

test('a missing Idempotency-Key is a 400', async () => {
  const { harness, token } = await harnessWith('valid');
  try {
    const result = await apiNoIdempotency(harness, token);
    assert.equal(result.status, 400);
    assert.match(result.json.message, /Idempotency-Key/);
  } finally {
    await harness.close();
  }
});

test('GET /v1/analyses/{monthId} returns the stored analysis', async () => {
  const { harness, token } = await harnessWith('valid');
  try {
    await analyze(harness, token);
    const result = await (await import('./helpers.js')).api(harness, 'GET', '/v1/analyses/2026-02', { token });
    assert.equal(result.status, 200);
    assert.equal(result.json.promptVersion, 'analysis-v2');
    const missing = await (await import('./helpers.js')).api(harness, 'GET', '/v1/analyses/2026-09', { token });
    assert.equal(missing.status, 404);
    assert.equal(missing.json.code, 'not_found');
  } finally {
    await harness.close();
  }
});

async function apiNoIdempotency(
  harness: Parameters<typeof analyze>[0],
  token: string,
) {
  const { api } = await import('./helpers.js');
  return api(harness, 'POST', '/v1/analyses', {
    token,
    body: { monthId: '2026-02', inputRevision: 1, payload: samplePayload() },
  });
}
