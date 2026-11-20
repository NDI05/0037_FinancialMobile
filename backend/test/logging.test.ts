import assert from 'node:assert/strict';
import { test } from 'node:test';
import { analyze, api, createHarness, grantConsent, login, samplePayload } from './helpers.js';

/**
 * NFR-18 / contract §7 / §8 bullet 11: JSON lines with a fixed field set, a hashed owner reference,
 * and no payload, amount, category code, email or token anywhere in the output.
 */
test('request logs are structured and contain no PII', async () => {
  const harness = await createHarness();
  try {
    const token = await login(harness, 'user-logging', 'Andhika Pratama');
    await grantConsent(harness, token);
    const created = await analyze(harness, token, { idempotencyKey: 'log-1' });
    assert.equal(created.status, 200);
    const errorResult = await api(harness, 'GET', '/v1/analyses/2026-09', { token });
    assert.equal(errorResult.status, 404);

    const lines = harness.logs.map((line) => JSON.parse(line) as Record<string, unknown>);
    const requests = lines.filter((line) => typeof line.method === 'string');
    assert.ok(requests.length >= 4);
    for (const entry of requests) {
      assert.deepEqual(
        Object.keys(entry).sort(),
        ['correlationId', 'level', 'method', 'ms', 'ownerRef', 'path', 'retry', 'status', 'ts'],
      );
      assert.match(String(entry.correlationId), /^[0-9a-f-]{36}$/);
      assert.equal(typeof entry.ms, 'number');
      assert.equal(typeof entry.status, 'number');
      assert.equal(typeof entry.retry, 'number');
      if (entry.ownerRef !== null) assert.match(String(entry.ownerRef), /^u_[0-9a-f]{10}$/);
    }

    const analysisLog = requests.find(
      (entry) => entry.method === 'POST' && entry.path === '/v1/analyses',
    );
    assert.ok(analysisLog);
    assert.equal(analysisLog.status, 200);
    assert.equal(analysisLog.retry, 0);

    const raw = harness.logs.join('\n');
    const forbidden = [
      token,
      'dev:user-logging',
      'Andhika Pratama',
      '585000000',
      'Rp',
      'housing',
      'grossInflow',
      'mencatat',
      'amazon',
      'idempotency-key',
      'log-1',
    ];
    for (const needle of forbidden) {
      assert.ok(!raw.includes(needle), `log output must not contain ${needle}`);
    }
    assert.ok(!raw.toLowerCase().includes('subject'), 'no email subjects');
  } finally {
    await harness.close();
  }
});

test('an error response carries the same correlationId as its log line', async () => {
  const harness = await createHarness();
  try {
    const token = await login(harness, 'user-correlation');
    const response = await api(harness, 'GET', '/v1/analyses/2026-04', { token });
    assert.equal(response.status, 404);
    const correlationId = response.json.correlationId as string;
    assert.equal(response.headers.get('x-correlation-id'), correlationId);
    const logged = harness.logs
      .map((line) => JSON.parse(line) as Record<string, unknown>)
      .some((entry) => entry.correlationId === correlationId && entry.status === 404);
    assert.ok(logged, 'the failing request is logged with the same correlationId');
  } finally {
    await harness.close();
  }
});
