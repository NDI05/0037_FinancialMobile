import { createHash, timingSafeEqual } from 'node:crypto';
import { Router } from 'express';
import { z } from 'zod';
import { conflict, forbidden } from '../errors.js';
import { hashPassword } from '../auth/password.js';
import { normalizeEmail, ownerIdForEmail } from '../auth/owner.js';
import { parseBody } from '../http/validate.js';
import type { RouteDeps } from './deps.js';

export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 200;

const registerSchema = z
  .object({
    email: z.string().trim().max(254).email(),
    password: z.string().min(PASSWORD_MIN_LENGTH).max(PASSWORD_MAX_LENGTH),
    displayName: z.string().trim().min(1).max(80).optional(),
    inviteCode: z.string().min(1).max(200),
  })
  .strict();

/**
 * POST /v1/accounts (FR-51): registration is invite-only while the service is a personal demo.
 * The invite code is compared in constant time; a duplicate email is a 409 without revealing the
 * account, and the password is only ever stored as a scrypt digest.
 */
export function accountsRouter(deps: RouteDeps): Router {
  const router = Router();

  router.post('/v1/accounts', async (request, response) => {
    const body = parseBody(registerSchema, request.body);
    if (!timingSafeEquals(body.inviteCode, deps.config.registrationInviteCode)) {
      throw forbidden('invalid_invite');
    }

    const email = normalizeEmail(body.email);
    const digest = await hashPassword(body.password);
    const now = deps.now().toISOString();
    const account = await deps.store.createAccount({
      ownerId: ownerIdForEmail(email),
      email,
      displayName: body.displayName?.trim() || null,
      salt: digest.salt,
      passwordHash: digest.hash,
      kdf: digest.kdf,
      at: now,
    });
    if (!account) throw conflict('Email is already registered');

    response.status(201).json({
      ownerId: account.ownerId,
      email: account.email,
      displayName: account.displayName,
    });
  });

  return router;
}

/**
 * Constant-time comparison so the invite code cannot be probed a character at a time. Both sides
 * are hashed first, so the comparison length reveals nothing either.
 */
function timingSafeEquals(left: string, right: string): boolean {
  const a = createHash('sha256').update(left).digest();
  const b = createHash('sha256').update(right).digest();
  return timingSafeEqual(a, b);
}
