import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';

/**
 * Field-level AES-256-GCM (contract §6, NFR-16).
 *
 * Each field gets a fresh 12-byte IV. The additional authenticated data binds the ciphertext to the
 * owner, the purpose and the key version, so a row copied from another owner (or another table)
 * fails to decrypt instead of silently returning someone else's numbers.
 */

export const IV_BYTES = 12;
export const TAG_BYTES = 16;

export interface SealedField {
  readonly ciphertext: Buffer;
  readonly iv: Buffer;
  readonly tag: Buffer;
  readonly keyVersion: number;
}

export function sealJson(
  value: unknown,
  key: Buffer,
  keyVersion: number,
  aad: string,
): SealedField {
  const iv = randomBytes(IV_BYTES);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(aad, 'utf8'));
  const ciphertext = Buffer.concat([
    cipher.update(JSON.stringify(value), 'utf8'),
    cipher.final(),
  ]);
  return { ciphertext, iv, tag: cipher.getAuthTag(), keyVersion };
}

export function openJson<T>(
  field: SealedField,
  key: Buffer,
  aad: string,
): T {
  const decipher = createDecipheriv('aes-256-gcm', key, field.iv);
  decipher.setAAD(Buffer.from(aad, 'utf8'));
  decipher.setAuthTag(field.tag);
  const plaintext = Buffer.concat([
    decipher.update(field.ciphertext),
    decipher.final(),
  ]).toString('utf8');
  return JSON.parse(plaintext) as T;
}

/** AAD that pins a sealed field to one owner and one purpose. */
export function aadFor(ownerId: string, purpose: string, keyVersion: number): string {
  return `${purpose}|${ownerId}|v${keyVersion}`;
}
