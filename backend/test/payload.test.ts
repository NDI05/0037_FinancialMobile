import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { api, createHarness, grantConsent, login, samplePayload, type Harness } from './helpers.js';

/**
 * Contract §4.1 step 3 / §8 bullet 5: the aggregate allowlist is enforced twice — zod strict
 * objects and an explicit key walk — so an attempt to smuggle email fields is a 400 that names the
 * field.
 */
let harness: Harness;
let token: string;

before(async () => {
  harness = await createHarness();
  token = await login(harness, 'google-sub-payload');
  await grantConsent(harness, token);
});

after(async () => {
  await harness.close();
});

async function send(payload: unknown) {
  return api(harness, 'POST', '/v1/analyses', {
    token,
    headers: { 'idempotency-key': `payload-${Math.random()}` },
    body: { monthId: '2026-02', inputRevision: 5, payload },
  });
}

for (const field of ['subject', 'body', 'messageId', 'token', 'pan', 'merchantName', 'note']) {
  test(`a payload carrying "${field}" is rejected`, async () => {
    const payload = { ...samplePayload(), [field]: 'forbidden-value' } as unknown;
    const result = await send(payload);
    assert.equal(result.status, 400);
    assert.equal(result.json.code, 'invalid_request');
    assert.match(result.json.message, new RegExp(field));
  });
}

test('a forbidden field nested inside categoryTotals is rejected', async () => {
  const payload = samplePayload();
  const nested = {
    ...payload,
    categoryTotals: payload.categoryTotals.map((total, index) =>
      index === 0 ? { ...total, merchantName: 'Indomaret' } : total,
    ),
  };
  const result = await send(nested);
  assert.equal(result.status, 400);
  assert.match(result.json.message, /merchantName/);
});

test('a payload whose monthId disagrees with the request is rejected', async () => {
  const result = await send(samplePayload({ monthId: '2026-01' }));
  assert.equal(result.status, 400);
});

test('an unknown schemaVersion is rejected', async () => {
  const result = await send({ ...samplePayload(), schemaVersion: 2 });
  assert.equal(result.status, 400);
});

test('a negative transaction count is rejected', async () => {
  const result = await send({ ...samplePayload(), transactionCount: -1 });
  assert.equal(result.status, 400);
});

test('the allowlist mirrors the Kotlin AiAggregatePayload.ALLOWED_FIELDS', async () => {
  const { ALLOWED_FIELDS } = await import('../src/domain/payload.js');
  const expected = [
    'schemaVersion', 'monthId', 'coverage', 'grossInflowMinor', 'grossOutflowMinor',
    'netCashFlowMinor', 'classifiedIncomeMinor', 'classifiedExpenseMinor', 'transactionCount',
    'uncategorizedCount', 'manualEntryCount', 'nonIdrExcludedCount', 'categoryTotals',
    'priorMonth', 'plan', 'categoryCode', 'amountMinor', 'limitMinor', 'expenseMinor',
    'incomeMinor', 'expectedIncomeMinor', 'plannedSavingsMinor', 'categoryLimitTotals',
  ];
  assert.deepEqual([...ALLOWED_FIELDS].sort(), expected.sort());
});
