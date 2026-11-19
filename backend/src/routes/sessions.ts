import { Router, type RequestHandler } from 'express';
import { z } from 'zod';
import type { GoogleVerifier } from '../auth/google.js';
import { ownerIdFromSubject } from '../auth/owner.js';
import { issueSession } from '../auth/session.js';
import { notFound } from '../errors.js';
import { parseBody } from '../http/validate.js';
import type { RouteDeps } from './deps.js';

const sessionRequestSchema = z
  .object({
    idToken: z.string().min(1).max(6_000),
  })
  .strict();

/**
 * POST /v1/sessions (contract §2.1) and DELETE /v1/sessions (§2.2).
 *
 * The token exchange is the only place the Google ID token is seen; it is verified, reduced to a
 * subject, and never stored or logged.
 */
export function sessionsRouter(
  deps: RouteDeps,
  verifier: GoogleVerifier,
  authenticated: RequestHandler,
): Router {
  const router = Router();

  router.post('/v1/sessions', async (request, response) => {
    const body = parseBody(sessionRequestSchema, request.body);
    const identity = await verifier.verify(body.idToken);
    const ownerId = ownerIdFromSubject(identity.sub);
    const now = deps.now();
    deps.store.upsertUser({
      ownerId,
      googleSub: identity.sub,
      displayName: identity.name,
      at: now.toISOString(),
    });
    const session = issueSession(deps.store, {
      ownerId,
      pepper: deps.config.sessionPepper,
      ttlDays: deps.config.sessionTtlDays,
      now,
    });
    response.status(201).json({
      accessToken: session.accessToken,
      expiresAt: session.expiresAt,
      displayName: identity.name,
    });
  });

  router.delete('/v1/sessions', authenticated, (request, response) => {
    const auth = request.auth;
    if (!auth) throw notFound();
    const revoked = deps.store.revokeSession(auth.ownerId, auth.sessionId, deps.now().toISOString());
    if (!revoked) throw notFound('Session not found');
    response.status(204).end();
  });

  return router;
}
