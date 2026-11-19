import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { api, createGoogleFixture, createHarness, type GoogleFixture, type Harness } from './helpers.js';

/**
 * Contract §2.1 and §8 bullet 1: every mandatory ID-token check, exercised against real jose
 * verification with a locally generated key set (no network to Google).
 */
let harness: Harness;
let google: GoogleFixture;

before(async () => {
  google = await createGoogleFixture('test-client-id.apps.googleusercontent.com');
  harness = await createHarness({ verifier: google.verifier });
});

after(async () => {
  await harness.close();
});

async function exchange(idToken: string) {
  return api(harness, 'POST', '/v1/sessions', { body: { idToken } });
}

test('a valid Google ID token creates a session', async () => {
  const result = await exchange(await google.sign());
  assert.equal(result.status, 201);
  const body = result.json as { accessToken: string; expiresAt: string; displayName: string | null };
  assert.match(body.accessToken, /^[A-Za-z0-9_-]{40,}$/);
  assert.ok(Date.parse(body.expiresAt) > Date.now());
  assert.equal(body.displayName, 'Alice');
  assert.equal(result.headers.get('x-correlation-id')?.length, 36);
});

test('a wrong audience is rejected', async () => {
  const result = await exchange(await google.sign({ audience: 'someone-else.apps.googleusercontent.com' }));
  assert.equal(result.status, 401);
  assert.equal(result.json.code, 'unauthorized');
  assert.equal(result.json.retryable, false);
});

test('an expired token is rejected', async () => {
  const nowSeconds = Math.floor(Date.now() / 1000);
  const result = await exchange(await google.sign({ issuedAt: nowSeconds - 3600, expiresIn: 60 }));
  assert.equal(result.status, 401);
});

test('a wrong issuer is rejected', async () => {
  const result = await exchange(await google.sign({ issuer: 'https://evil.example.com' }));
  assert.equal(result.status, 401);
});

test('a token signed by another key is rejected', async () => {
  const result = await exchange(await google.sign({ key: (await createGoogleFixture('x')).privateKey }));
  assert.equal(result.status, 401);
});

test('an unknown kid is rejected', async () => {
  const result = await exchange(await google.sign({ kid: 'not-a-key' }));
  assert.equal(result.status, 401);
});

test('an issued-in-the-future token is rejected', async () => {
  const future = Math.floor(Date.now() / 1000) + 3600;
  const result = await exchange(await google.sign({ issuedAt: future, expiresIn: 600 }));
  assert.equal(result.status, 401);
});

test('a missing subject is rejected', async () => {
  const result = await exchange(await google.sign({ subject: '' }));
  assert.equal(result.status, 401);
});

test('a random string is not a token', async () => {
  const result = await exchange('not-a-jwt');
  assert.equal(result.status, 401);
});

test('an invalid body is a 400 with the contract error shape', async () => {
  const result = await api(harness, 'POST', '/v1/sessions', { body: { token: 'x' } });
  assert.equal(result.status, 400);
  assert.equal(result.json.code, 'invalid_request');
  assert.equal(typeof result.json.correlationId, 'string');
});

test('dev tokens are refused when the Google verifier is active', async () => {
  const result = await exchange('dev:google-sub-auth:Alice');
  assert.equal(result.status, 401);
});
