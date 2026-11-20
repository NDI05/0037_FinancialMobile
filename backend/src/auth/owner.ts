import { createHash } from 'node:crypto';

/**
 * Stable, non-reversible owner id derived from the account email (FR-02).
 *
 * Deriving it instead of using the email keeps the address out of every table except `users`, and
 * out of every log line. The email is normalised first, so `A@x.test` and `a@x.test` are one owner.
 */
export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

export function ownerIdForEmail(email: string): string {
  return `u_${createHash('sha256')
    .update(`pam-owner:${normalizeEmail(email)}`)
    .digest('hex')
    .slice(0, 32)}`;
}
