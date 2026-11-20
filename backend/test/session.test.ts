import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { hashAccessToken } from '../src/auth/session.js';
import {
  api,
  createHarness,
  emailFor,
  grantConsent,
  login,
  register,
  samplePayload,
  TEST_PASSWORD,
  type Harness,
} from './helpers.js';

/** Contract §2.2 / §8 bullet 2: mint, use, revoke, and refuse anything else. */
let harness: Harness;

before(async () => {
  harness = await createHarness();
});

after(async () => {
  await harness.close();
});

test('the session token authenticates /v1/me', async () => {
  const token = await login(harness);
  const result = await api(harness, 'GET', '/v1/me', { token });
  assert.equal(result.status, 200);
  assert.equal(result.json.displayName, 'Alice');
  assert.equal(result.json.status.AI_PROCESSING, null);
});

test('sign out revokes immediately (FR-05)', async () => {
  const token = await login(harness, 'user-revoke');
  assert.equal((await api(harness, 'GET', '/v1/me', { token })).status, 200);

  const revoked = await api(harness, 'DELETE', '/v1/sessions', { token });
  assert.equal(revoked.status, 204);

  const afterwards = await api(harness, 'GET', '/v1/me', { token });
  assert.equal(afterwards.status, 401);
  assert.equal(afterwards.json.code, 'unauthorized');
});

test('a random bearer token is rejected', async () => {
  const result = await api(harness, 'GET', '/v1/me', { token: 'a'.repeat(43) });
  assert.equal(result.status, 401);
});

test('a missing bearer token is rejected', async () => {
  const result = await api(harness, 'GET', '/v1/me');
  assert.equal(result.status, 401);
});

test('an expired session is rejected', async () => {
  const email = emailFor('user-expired');
  await register(harness, email, TEST_PASSWORD, 'Expired');
  const ownerId = (await harness.store.findAccountByEmail(email))!.ownerId;
  const rawToken = 'expired-token-value';
  await harness.store.createSession({
    ownerId,
    tokenHash: hashAccessToken(rawToken, harness.config.sessionPepper),
    createdAt: new Date(Date.now() - 2 * 86_400_000).toISOString(),
    expiresAt: new Date(Date.now() - 86_400_000).toISOString(),
  });
  const result = await api(harness, 'GET', '/v1/me', { token: rawToken });
  assert.equal(result.status, 401);
});

test('the raw token is never stored', async () => {
  const token = await login(harness, 'user-storage');
  const rows = await harness.store.findSessionByHash(hashAccessToken(token, harness.config.sessionPepper));
  assert.ok(rows, 'session row exists for the hash');
  // There is no API that returns a token hash; prove the token itself is not a column value.
  const stored = JSON.stringify(rows);
  assert.ok(!stored.includes(token));
});

test('a token from one user does not authenticate another', async () => {
  const alice = await login(harness, 'user-a');
  const bob = await login(harness, 'user-b');
  assert.notEqual(alice, bob);
  await grantConsent(harness, alice);
  const aliceMe = await api(harness, 'GET', '/v1/me', { token: alice });
  const bobMe = await api(harness, 'GET', '/v1/me', { token: bob });
  assert.equal(aliceMe.json.status.AI_PROCESSING, 'GRANTED');
  assert.equal(bobMe.json.status.AI_PROCESSING, null);
  // And a summary written by Alice is invisible to Bob (FR-02).
  await api(harness, 'PUT', '/v1/summaries/2026-02', {
    token: alice,
    body: { inputRevision: 1, payload: samplePayload() },
  });
  const bobRead = await api(harness, 'GET', '/v1/summaries/2026-02', { token: bob });
  assert.equal(bobRead.status, 404);
});
