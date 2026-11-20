import { Router, type RequestHandler } from 'express';
import { z } from 'zod';
import { badRequest, notFound } from '../errors.js';
import { parseBody } from '../http/validate.js';
import { aggregatePayloadSchema } from '../domain/payload.js';
import type { RouteDeps } from './deps.js';
import { assertNoDeletionInProgress, readMonthId } from './guard.js';

const putSummarySchema = z
  .object({
    inputRevision: z.number().int().min(0).max(1_000_000),
    payload: aggregatePayloadSchema,
  })
  .strict();

/**
 * PUT/GET /v1/summaries/{monthId} (FR-33/FR-02). The payload is sealed with AES-256-GCM; only the
 * owner can read it back, and the month in the URL must match the month inside the payload.
 */
export function summariesRouter(deps: RouteDeps, authenticated: RequestHandler): Router {
  const router = Router();

  router.put('/v1/summaries/:monthId', authenticated, async (request, response) => {
    const auth = request.auth!;
    const monthId = readMonthId(request.params.monthId);
    const body = parseBody(putSummarySchema, request.body);
    if (body.payload.monthId !== monthId) {
      throw badRequest('payload.monthId must match the month in the path');
    }
    await assertNoDeletionInProgress(deps, auth.ownerId);
    const record = await deps.store.upsertSummary({
      ownerId: auth.ownerId,
      monthId,
      inputRevision: body.inputRevision,
      payload: body.payload,
      at: deps.now().toISOString(),
    });
    response.status(200).json({
      monthId: record.monthId,
      inputRevision: record.inputRevision,
      coverage: record.coverage,
      updatedAt: record.updatedAt,
    });
  });

  router.get('/v1/summaries/:monthId', authenticated, async (request, response) => {
    const auth = request.auth!;
    const monthId = readMonthId(request.params.monthId);
    const record = await deps.store.getSummary(auth.ownerId, monthId);
    if (!record) throw notFound('No summary for that month');
    response.status(200).json({
      monthId: record.monthId,
      inputRevision: record.inputRevision,
      coverage: record.coverage,
      createdAt: record.createdAt,
      updatedAt: record.updatedAt,
      payload: record.payload,
    });
  });

  return router;
}
