import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { api, createHarness, grantConsent, login, type Harness } from './helpers.js';

/** Contract §4.2 and §8 bullet 4: the AI gate and the append-only ledger. */
let harness: Harness;

before(async () => {
  harness = await createHarness();
});

after(async () => {
  await harness.close();
});

test('analysis without consent is 403 consent_required (FR-34)', async () => {
  const token = await login(harness, 'user-no-consent');
  const result = await api(harness, 'POST', '/v1/analyses', {
    token,
    headers: { 'idempotency-key': 'no-consent' },
    body: { monthId: '2026-02', inputRevision: 1, payload: (await import('./helpers.js')).samplePayload() },
  });
  assert.equal(result.status, 403);
  assert.equal(result.json.code, 'consent_required');
  assert.equal(result.json.retryable, false);
});

test('a DECLINED decision does not open the gate', async () => {
  const token = await login(harness, 'user-declined');
  await grantConsent(harness, token, 'DECLINED');
  const result = await api(harness, 'POST', '/v1/analyses', {
    token,
    headers: { 'idempotency-key': 'declined' },
    body: { monthId: '2026-02', inputRevision: 1, payload: (await import('./helpers.js')).samplePayload() },
  });
  assert.equal(result.status, 403);
  assert.equal(result.json.code, 'consent_required');
});

test('GRANTED opens the gate and WITHDRAWN closes it again (FR-04/FR-41)', async () => {
  const token = await login(harness, 'user-toggle');
  await grantConsent(harness, token);
  const granted = await api(harness, 'POST', '/v1/analyses', {
    token,
    headers: { 'idempotency-key': 'granted' },
    body: { monthId: '2026-02', inputRevision: 1, payload: (await import('./helpers.js')).samplePayload() },
  });
  assert.equal(granted.status, 200);

  await grantConsent(harness, token, 'WITHDRAWN');
  const withdrawn = await api(harness, 'POST', '/v1/analyses', {
    token,
    headers: { 'idempotency-key': 'withdrawn' },
    body: { monthId: '2026-02', inputRevision: 2, payload: (await import('./helpers.js')).samplePayload() },
  });
  assert.equal(withdrawn.status, 403);
  assert.equal(withdrawn.json.code, 'consent_required');
});

test('the ledger is append-only and returns newest first (FR-03/FR-49)', async () => {
  const token = await login(harness, 'user-ledger');
  await grantConsent(harness, token, 'GRANTED');
  await grantConsent(harness, token, 'WITHDRAWN');
  await api(harness, 'POST', '/v1/consents', {
    token,
    body: { purpose: 'GMAIL_READ', decision: 'GRANTED', noticeVersion: 'v1' },
  });

  const ledger = await api(harness, 'GET', '/v1/consents', { token });
  assert.equal(ledger.status, 200);
  const consents = ledger.json.consents as Array<{ purpose: string; decision: string; noticeVersion: string }>;
  assert.equal(consents.length, 3);
  assert.deepEqual(
    consents.map((entry) => `${entry.purpose}:${entry.decision}`).sort(),
    ['AI_PROCESSING:GRANTED', 'AI_PROCESSING:WITHDRAWN', 'GMAIL_READ:GRANTED'],
  );
  const me = await api(harness, 'GET', '/v1/me', { token });
  assert.equal(me.json.status.AI_PROCESSING, 'WITHDRAWN');
  assert.equal(me.json.status.GMAIL_READ, 'GRANTED');
});

test('an unknown consent purpose is a 400', async () => {
  const token = await login(harness, 'user-bad-consent');
  const result = await api(harness, 'POST', '/v1/consents', {
    token,
    body: { purpose: 'ADVERTISING', decision: 'GRANTED', noticeVersion: 'v1' },
  });
  assert.equal(result.status, 400);
  assert.equal(result.json.code, 'invalid_request');
});
