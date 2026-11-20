import { Router, type RequestHandler } from 'express';
import { z } from 'zod';
import { parseBody } from '../http/validate.js';
import type { RouteDeps } from './deps.js';

const consentSchema = z
  .object({
    purpose: z.enum(['GMAIL_READ', 'AI_PROCESSING']),
    decision: z.enum(['GRANTED', 'DECLINED', 'WITHDRAWN']),
    noticeVersion: z.string().min(1).max(32),
  })
  .strict();

/**
 * POST /v1/consents (FR-03) and GET /v1/consents (FR-49). Append-only: a withdrawal is a new row,
 * never an update, and the analysis gate reads the latest row per purpose (FR-04/FR-41).
 */
export function consentsRouter(deps: RouteDeps, authenticated: RequestHandler): Router {
  const router = Router();

  router.post('/v1/consents', authenticated, async (request, response) => {
    const auth = request.auth!;
    const body = parseBody(consentSchema, request.body);
    const record = await deps.store.appendConsent({
      ownerId: auth.ownerId,
      purpose: body.purpose,
      decision: body.decision,
      noticeVersion: body.noticeVersion,
      at: deps.now().toISOString(),
    });
    response.status(201).json({
      id: record.id,
      purpose: record.purpose,
      decision: record.decision,
      noticeVersion: record.noticeVersion,
      decidedAt: record.createdAt,
    });
  });

  router.get('/v1/consents', authenticated, async (request, response) => {
    const auth = request.auth!;
    const consents = await deps.store.listConsents(auth.ownerId);
    response.status(200).json({
      consents: consents.map((consent) => ({
        id: consent.id,
        purpose: consent.purpose,
        decision: consent.decision,
        noticeVersion: consent.noticeVersion,
        decidedAt: consent.createdAt,
      })),
    });
  });

  return router;
}
