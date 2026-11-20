import { randomBytes, scrypt, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';

/**
 * FR-51 password hashing: scrypt (memory-hard KDF), no native dependency.
 *
 * Parameters are fixed by the change record (CR-001): N=2^15, r=8, p=1, 16-byte random salt,
 * 64-byte key. The stored `kdf` string names the parameters so a future bump can verify old rows
 * with their original settings and rehash on next login.
 */
const scryptAsync = promisify(scrypt) as (
  password: string | Buffer,
  salt: Buffer,
  keyLength: number,
  options: { N: number; r: number; p: number; maxmem: number },
) => Promise<Buffer>;

export const SCRYPT_PARAMS = {
  N: 2 ** 15,
  r: 8,
  p: 1,
  keyLength: 64,
  saltBytes: 16,
  /** 128 * N * r bytes is 32 MiB at these settings; leave headroom for the allocation. */
  maxmem: 128 * 1024 * 1024,
} as const;

export const KDF_VERSION = `scrypt:N=${SCRYPT_PARAMS.N},r=${SCRYPT_PARAMS.r},p=${SCRYPT_PARAMS.p}`;

export interface PasswordDigest {
  readonly salt: Buffer;
  readonly hash: Buffer;
  readonly kdf: string;
}

export async function hashPassword(password: string): Promise<PasswordDigest> {
  const salt = randomBytes(SCRYPT_PARAMS.saltBytes);
  const hash = await digest(password, salt);
  return { salt, hash, kdf: KDF_VERSION };
}

export async function verifyPassword(
  password: string,
  stored: { salt: Buffer; hash: Buffer; kdf: string },
): Promise<boolean> {
  const params = parseKdf(stored.kdf);
  if (!params) return false;
  const candidate = await digestWith(password, stored.salt, params);
  if (candidate.length !== stored.hash.length) return false;
  return timingSafeEqual(candidate, stored.hash);
}

/**
 * A fixed digest used when the email is unknown, so a login attempt costs the same scrypt work
 * whether or not the account exists (FR-53: no existence disclosure, including by timing).
 */
const DUMMY_SALT = Buffer.alloc(SCRYPT_PARAMS.saltBytes, 0x5a);
export async function dummyVerify(password: string): Promise<void> {
  await digestWith(password, DUMMY_SALT, {
    N: SCRYPT_PARAMS.N,
    r: SCRYPT_PARAMS.r,
    p: SCRYPT_PARAMS.p,
  });
}

function digest(password: string, salt: Buffer): Promise<Buffer> {
  return digestWith(password, salt, {
    N: SCRYPT_PARAMS.N,
    r: SCRYPT_PARAMS.r,
    p: SCRYPT_PARAMS.p,
  });
}

function digestWith(
  password: string,
  salt: Buffer,
  params: { N: number; r: number; p: number },
): Promise<Buffer> {
  return scryptAsync(password, salt, SCRYPT_PARAMS.keyLength, {
    N: params.N,
    r: params.r,
    p: params.p,
    maxmem: SCRYPT_PARAMS.maxmem,
  });
}

function parseKdf(kdf: string): { N: number; r: number; p: number } | null {
  const match = /^scrypt:N=(\d+),r=(\d+),p=(\d+)$/.exec(kdf);
  if (!match) return null;
  const N = Number(match[1]);
  const r = Number(match[2]);
  const p = Number(match[3]);
  if (!Number.isFinite(N) || !Number.isFinite(r) || !Number.isFinite(p)) return null;
  // Refuse parameters that would let a hostile row force unbounded work.
  if (N < 2 ** 12 || N > 2 ** 20 || r < 1 || r > 16 || p < 1 || p > 4) return null;
  return { N, r, p };
}
