import { Router, type RequestHandler } from 'express';
import { z } from 'zod';
import { unauthorized } from '../errors.js';
import { assertLoginAllowed, clearLoginFailures, recordLoginFailure } from '../auth/rateLimit.js';
import { dummyVerify, verifyPassword } from '../auth/password.js';
import { normalizeEmail } from '../auth/owner.js';
import { issueSession } from '../auth/session.js';
import { notFound } from '../errors.js';
import { parseBody } from '../http/validate.js';
import type { RouteDeps } from './deps.js';

const loginSchema = z
  .object({
    email: z.string().trim().max(254).email(),
    password: z.string().min(1).max(200),
  })
  .strict();

/**
 * POST /v1/sessions (FR-51/FR-53) and DELETE /v1/sessions (§2.2).
 *
 * Login answers `401 unauthorized` for both an unknown email and a wrong password, and it spends
 * the same scrypt work in both cases, so neither the response nor its timing discloses whether the
 * account exists. Five failures per email + address class within fifteen minutes answer 429; a
 * successful login clears the counter.
 */
export function sessionsRouter(deps: RouteDeps, authenticated: RequestHandler): Router {
  const router = Router();

  router.post('/v1/sessions', async (request, response) => {
    const body = parseBody(loginSchema, request.body);
    const email = normalizeEmail(body.email);
    const now = deps.now();
    const key = await assertLoginAllowed(deps.store, email, clientAddress(request), now);

    const account = await deps.store.findAccountByEmail(email);
    if (!account) {
      // Same work as a real verify: no timing side channel.
      await dummyVerify(body.password);
      await recordLoginFailure(deps.store, key, now);
      throw unauthorized('Email or password is incorrect');
    }

    const valid = await verifyPassword(body.password, {
      salt: account.salt,
      hash: account.passwordHash,
      kdf: account.kdf,
    });
    if (!valid) {
      await recordLoginFailure(deps.store, key, now);
      throw unauthorized('Email or password is incorrect');
    }

    await clearLoginFailures(deps.store, key);
    await deps.store.touchAccount(account.ownerId, now.toISOString());
    const issued = await issueSession(deps.store, {
      ownerId: account.ownerId,
      pepper: deps.config.sessionPepper,
      ttlDays: deps.config.sessionTtlDays,
      now,
    });
    response.status(201).json({
      accessToken: issued.accessToken,
      expiresAt: issued.expiresAt,
      displayName: account.displayName,
    });
  });

  router.delete('/v1/sessions', authenticated, async (request, response) => {
    const auth = request.auth;
    if (!auth) throw notFound();
    const revoked = await deps.store.revokeSession(auth.ownerId, auth.sessionId, deps.now().toISOString());
    if (!revoked) throw notFound('Session not found');
    response.status(204).end();
  });

  return router;
}

/** Express reports the peer address as `request.ip`; the limiter only keeps a hashed class of it. */
function clientAddress(request: { ip?: string; socket?: { remoteAddress?: string } }): string {
  return request.ip ?? request.socket?.remoteAddress ?? 'unknown';
}
