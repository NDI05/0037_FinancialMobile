import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { verifyPassword } from '../src/auth/password.js';
import { aadFor, openJson } from '../src/crypto.js';
import { ownerIdForEmail } from '../src/auth/owner.js';

/**
 * Cross-language proof: the Go backend must read Node credentials/ciphertext and Node must read
 * Go's. Fixtures live in fixtures/interop and contain only synthetic values and the dev key.
 */

const fixtures = '../fixtures/interop';

test('Node verifies the Go scrypt password fixture', async () => {
  const fixture = JSON.parse(readFileSync(`${fixtures}/go-password.json`, 'utf8'));
  const ok = await verifyPassword(fixture.password, {
    salt: Buffer.from(fixture.saltB64, 'base64'),
    hash: Buffer.from(fixture.hashB64, 'base64'),
    kdf: fixture.kdf,
  });
  assert.equal(ok, true);
  assert.equal(fixture.ownerId, ownerIdForEmail(fixture.email));
});

test('Node opens the Go AES-GCM fixture and AAD still binds the owner', () => {
  const fixture = JSON.parse(readFileSync(`${fixtures}/go-sealed.json`, 'utf8'));
  const key = createHash('sha256').update('pam-dev-data-key').digest();
  const sealed = {
    ciphertext: Buffer.from(fixture.ciphertextB64, 'base64'),
    iv: Buffer.from(fixture.ivB64, 'base64'),
    tag: Buffer.from(fixture.tagB64, 'base64'),
    keyVersion: fixture.keyVersion,
  };
  const value = openJson<Record<string, unknown>>(sealed, key,
    aadFor(fixture.ownerId, fixture.purpose, fixture.keyVersion));
  assert.equal(value.marker, 'go-sealed');
  assert.equal(value.grossInflowMinor, 500000000);
  assert.throws(() => openJson(sealed, key, aadFor('u_other', fixture.purpose, fixture.keyVersion)));
});
