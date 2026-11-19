import { createHash } from 'node:crypto';

/**
 * Stable, non-reversible owner id derived from the Google subject.
 *
 * Deriving it (instead of storing the raw `sub` as the key) means no table, log line or response
 * ever contains the Google identifier, while a returning user still resolves to the same rows.
 */
export function ownerIdFromSubject(sub: string): string {
  return `u_${createHash('sha256').update(`pam-owner:${sub}`).digest('hex').slice(0, 32)}`;
}
