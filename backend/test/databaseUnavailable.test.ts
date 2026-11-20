import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createClient, type Client } from '@libsql/client';
import { isDatabaseUnavailableError } from '../src/errors.js';
import { api, createHarness, login } from './helpers.js';

/**
 * Fase 4 amendment (FR-56): a storage outage answers 503 database_unavailable, retryable, with the
 * same correlationId as the log — never a bare 500.
 */
test('a connectivity failure is classified as unavailable', () => {
  assert.equal(isDatabaseUnavailableError(new TypeError('fetch failed')), true);
  const dns = new TypeError('fetch failed');
  (dns as unknown as { cause: Error }).cause = new Error('getaddrinfo ENOTFOUND x.invalid');
  assert.equal(isDatabaseUnavailableError(dns), true);

  const serverError = Object.assign(new Error('SERVER_ERROR: Server returned HTTP status 400'), {
    code: 'SERVER_ERROR',
  });
  assert.equal(isDatabaseUnavailableError(serverError), true);

  // SQL problems are not availability problems.
  const sqlError = Object.assign(new Error('SQLITE_ERROR: no such table'), { code: 'SQLITE_ERROR' });
  assert.equal(isDatabaseUnavailableError(sqlError), false);
  const parseError = Object.assign(new Error('SQL_PARSE_ERROR: SQL not allowed statement'), {
    code: 'SQL_PARSE_ERROR',
  });
  assert.equal(isDatabaseUnavailableError(parseError), false);
  assert.equal(isDatabaseUnavailableError(new Error('boom')), false);
});

test('an unreachable database answers 503 database_unavailable', async () => {
  const harness = await createHarness();
  const token = await login(harness, 'user-db-down');
  const original = (harness.store as unknown as { client: Client }).client;
  try {
    // Simulate Turso going away mid-flight: the store keeps running, its transport does not.
    const unreachable: Client = createClient({ url: 'libsql://127.0.0.1:1', authToken: 'x' });
    const store = harness.store as unknown as { client: Client };
    store.client = unreachable;

    const response = await api(harness, 'GET', '/v1/me', { token });
    assert.equal(response.status, 503);
    assert.equal(response.json.code, 'database_unavailable');
    assert.equal(response.json.retryable, true);
    assert.equal(typeof response.json.correlationId, 'string');
    assert.ok(Number(response.json.correlationId.length) > 0);
  } finally {
    (harness.store as unknown as { client: Client }).client = original;
    await harness.close();
  }
});
