import { createHash, randomBytes } from 'node:crypto';
import type { NextFunction, Request, Response } from 'express';
import { unauthorized } from '../errors.js';
import type { Logger } from '../logger.js';
import type { Store } from '../store/store.js';

/**
 * Opaque session tokens (contract §2.1/§2.2): 32 random bytes, base64url, and only
 * `sha256(token + SESSION_PEPPER)` is stored. Signing out revokes the row, so the next request
 * fails immediately (FR-05).
 */
export interface IssuedSession {
  readonly accessToken: string;
  readonly expiresAt: string;
}

export function hashAccessToken(accessToken: string, pepper: string): string {
  return createHash('sha256').update(accessToken).update(pepper).digest('hex');
}

export function mintAccessToken(): string {
  return randomBytes(32).toString('base64url');
}

export function issueSession(
  store: Store,
  options: { ownerId: string; pepper: string; ttlDays: number; now: Date },
): IssuedSession {
  const accessToken = mintAccessToken();
  const expiresAt = new Date(options.now.getTime() + options.ttlDays * 24 * 60 * 60 * 1000);
  store.createSession({
    ownerId: options.ownerId,
    tokenHash: hashAccessToken(accessToken, options.pepper),
    createdAt: options.now.toISOString(),
    expiresAt: expiresAt.toISOString(),
  });
  return { accessToken, expiresAt: expiresAt.toISOString() };
}

export function readBearerToken(request: Request): string | null {
  const header = request.header('authorization');
  if (!header) return null;
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match?.[1]?.trim() || null;
}

/**
 * Resolves the bearer token to `ownerId` and attaches it to the request. Anything else is a 401,
 * with no distinction between "unknown token" and "wrong token" (no oracle).
 */
export function requireSession(store: Store, pepper: string, logger: Logger) {
  return (request: Request, _response: Response, next: NextFunction): void => {
    const token = readBearerToken(request);
    if (!token) {
      next(unauthorized());
      return;
    }
    const session = store.findSessionByHash(hashAccessToken(token, pepper));
    if (!session) {
      next(unauthorized());
      return;
    }
    if (session.revokedAt !== null) {
      logger.warn('session_revoked_used', { correlationId: request.correlationId });
      next(unauthorized());
      return;
    }
    if (Date.parse(session.expiresAt) <= Date.now()) {
      next(unauthorized('Access token has expired'));
      return;
    }
    request.auth = { ownerId: session.ownerId, sessionId: session.id };
    next();
  };
}
