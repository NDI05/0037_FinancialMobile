import { createHash } from 'node:crypto';
import { rateLimited } from '../errors.js';
import type { Store } from '../store/store.js';

/**
 * FR-53 login throttling: five failed attempts per email + address class within fifteen minutes,
 * then 429. A successful login clears the counter. The key is hashed, so neither the email nor the
 * client address is stored or logged (§7).
 */
export const LOGIN_WINDOW_MS = 15 * 60 * 1000;
export const LOGIN_MAX_FAILURES = 5;

/** IPv4 /24 and IPv6 /64: close enough to "one network" without storing the address. */
export function addressClass(rawAddress: string | undefined): string {
  const address = (rawAddress ?? 'unknown').replace(/^::ffff:/, '');
  if (address.includes(':')) {
    return `${address.split(':').slice(0, 4).join(':')}::/64`;
  }
  const octets = address.split('.');
  return octets.length === 4 ? `${octets.slice(0, 3).join('.')}.0/24` : 'unknown';
}

export function attemptKey(email: string, rawAddress: string | undefined): string {
  return createHash('sha256')
    .update(`${email.trim().toLowerCase()}|${addressClass(rawAddress)}`)
    .digest('hex');
}

export async function assertLoginAllowed(
  store: Store,
  email: string,
  rawAddress: string | undefined,
  now: Date,
): Promise<string> {
  const key = attemptKey(email, rawAddress);
  const state = await store.loginFailureState(key, now, LOGIN_WINDOW_MS);
  if (state.failedCount >= LOGIN_MAX_FAILURES) {
    throw rateLimited('Too many failed sign-in attempts. Try again in a few minutes.');
  }
  return key;
}

export async function recordLoginFailure(store: Store, key: string, now: Date): Promise<void> {
  await store.registerLoginFailure(key, now, LOGIN_WINDOW_MS);
}

export async function clearLoginFailures(store: Store, key: string): Promise<void> {
  await store.deleteLoginFailures(key);
}
