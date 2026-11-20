import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { hashPassword, verifyPassword } from '../src/auth/password.js';
import {
  api,
  emailFor,
  register,
  TEST_PASSWORD,
  createHarness,
  type Harness,
} from './helpers.js';

/**
 * FR-51/FR-53 and CR-001: local accounts replace Google ID tokens. Registration is invite-only,
 * the password is a scrypt digest, login never distinguishes an unknown email from a wrong
 * password, and five failures per window answer 429 until a success clears them.
 */
let harness: Harness;

before(async () => {
  harness = await createHarness();
});

after(async () => {
  await harness.close();
});

test('registration creates an account without echoing the password or hash', async () => {
  const email = emailFor('register-ok');
  const result = await register(harness, email, TEST_PASSWORD, 'Andhika');

  assert.equal(result.status, 201);
  const body = result.json as Record<string, unknown>;
  assert.equal(body.email, email);
  assert.equal(body.displayName, 'Andhika');
  assert.match(String(body.ownerId), /^u_[0-9a-f]{32}$/);
  assert.equal(body.password, undefined);
  assert.equal(body.passwordHash, undefined);
  assert.equal(body.salt, undefined);
  assert.equal(body.idToken, undefined);
});

test('a duplicate email is a 409', async () => {
  const email = emailFor('register-dup');
  assert.equal((await register(harness, email)).status, 201);
  const duplicate = await register(harness, email);
  assert.equal(duplicate.status, 409);
  assert.equal(duplicate.json.code, 'conflict');
});

test('a bad invite code is a 403 invalid_invite', async () => {
  const result = await register(harness, emailFor('register-invite'), TEST_PASSWORD, 'X', 'wrong-code');
  assert.equal(result.status, 403);
  assert.match(String(result.json.message), /invalid_invite/);
});

test('a missing invite code is a 400 from the schema', async () => {
  const result = await api(harness, 'POST', '/v1/accounts', {
    body: { email: emailFor('register-no-invite'), password: TEST_PASSWORD },
  });
  assert.equal(result.status, 400);
  assert.equal(result.json.code, 'invalid_request');
});

test('a short password and an invalid email are rejected', async () => {
  const shortPassword = await register(harness, emailFor('register-short'), 'short');
  assert.equal(shortPassword.status, 400);
  const badEmail = await register(harness, 'not-an-email');
  assert.equal(badEmail.status, 400);
});

test('login returns an opaque token for the right password', async () => {
  const email = emailFor('login-ok');
  await register(harness, email);
  const result = await api(harness, 'POST', '/v1/sessions', {
    body: { email, password: TEST_PASSWORD },
  });
  assert.equal(result.status, 201);
  assert.match(String(result.json.accessToken), /^[A-Za-z0-9_-]{40,}$/);
  assert.ok(Date.parse(String(result.json.expiresAt)) > Date.now());

  const me = await api(harness, 'GET', '/v1/me', { token: result.json.accessToken as string });
  assert.equal(me.status, 200);
});

test('an unknown email and a wrong password answer identically', async () => {
  const knownEmail = emailFor('login-wrong');
  await register(harness, knownEmail);

  const wrongPassword = await api(harness, 'POST', '/v1/sessions', {
    body: { email: knownEmail, password: 'definitely-not-it' },
  });
  const unknownEmail = await api(harness, 'POST', '/v1/sessions', {
    body: { email: emailFor('login-does-not-exist'), password: 'definitely-not-it' },
  });

  assert.equal(wrongPassword.status, 401);
  assert.equal(unknownEmail.status, 401);
  assert.equal(wrongPassword.json.code, unknownEmail.json.code);
  assert.equal(wrongPassword.json.message, unknownEmail.json.message);
  assert.equal(wrongPassword.json.retryable, unknownEmail.json.retryable);
});

test('five failed attempts answer 429 and a success clears the counter', async () => {
  const email = emailFor('login-limit');
  await register(harness, email);

  for (let attempt = 0; attempt < 4; attempt += 1) {
    const failed = await api(harness, 'POST', '/v1/sessions', {
      body: { email, password: 'wrong-password' },
    });
    assert.equal(failed.status, 401, `attempt ${attempt}`);
  }

  // A success resets, so four more failures are still allowed.
  const success = await api(harness, 'POST', '/v1/sessions', {
    body: { email, password: TEST_PASSWORD },
  });
  assert.equal(success.status, 201);
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const failed = await api(harness, 'POST', '/v1/sessions', {
      body: { email, password: 'wrong-password' },
    });
    assert.equal(failed.status, 401, `post-reset attempt ${attempt}`);
  }

  // The fifth failure reaches the ceiling; the next attempt is throttled.
  const fifth = await api(harness, 'POST', '/v1/sessions', {
    body: { email, password: 'wrong-password' },
  });
  assert.equal(fifth.status, 401);
  const throttled = await api(harness, 'POST', '/v1/sessions', {
    body: { email, password: TEST_PASSWORD },
  });
  assert.equal(throttled.status, 429);
  assert.equal(throttled.json.code, 'rate_limited');
  assert.equal(throttled.json.retryable, true);
});

test('sign out revokes the token immediately', async () => {
  const email = emailFor('login-logout');
  await register(harness, email);
  const session = await api(harness, 'POST', '/v1/sessions', {
    body: { email, password: TEST_PASSWORD },
  });
  const token = session.json.accessToken as string;
  assert.equal((await api(harness, 'GET', '/v1/me', { token })).status, 200);
  assert.equal((await api(harness, 'DELETE', '/v1/sessions', { token })).status, 204);
  assert.equal((await api(harness, 'GET', '/v1/me', { token })).status, 401);
});

test('the stored digest is scrypt with a random salt and verifies correctly', async () => {
  const email = emailFor('scrypt');
  await register(harness, email, TEST_PASSWORD);
  const account = harness.store.findAccountByEmail(email);
  assert.ok(account);
  assert.match(account.kdf, /^scrypt:N=32768,r=8,p=1$/);
  assert.equal(account.salt.length, 16);
  assert.equal(account.passwordHash.length, 64);
  assert.notEqual(account.passwordHash.toString('base64'), TEST_PASSWORD);

  assert.equal(
    await verifyPassword(TEST_PASSWORD, {
      salt: account.salt,
      hash: account.passwordHash,
      kdf: account.kdf,
    }),
    true,
  );
  assert.equal(
    await verifyPassword('something-else', {
      salt: account.salt,
      hash: account.passwordHash,
      kdf: account.kdf,
    }),
    false,
  );

  const first = await hashPassword(TEST_PASSWORD);
  const second = await hashPassword(TEST_PASSWORD);
  assert.notEqual(first.salt.toString('hex'), second.salt.toString('hex'));
  assert.notEqual(first.hash.toString('hex'), second.hash.toString('hex'));
});

test('no email, password or hash reaches the log', async () => {
  const email = emailFor('log-scan');
  await register(harness, email, TEST_PASSWORD, 'Secret Person');
  await api(harness, 'POST', '/v1/sessions', { body: { email, password: TEST_PASSWORD } });
  await api(harness, 'POST', '/v1/sessions', { body: { email, password: 'wrong-password' } });

  const raw = harness.logs.join('\n');
  for (const secret of [email, TEST_PASSWORD, 'wrong-password', 'Secret Person', 'password']) {
    assert.ok(!raw.includes(secret), `log must not contain ${secret}`);
  }
  assert.ok(!raw.toLowerCase().includes('salt'));
});
