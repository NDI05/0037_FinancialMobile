import { Router, type RequestHandler } from 'express';
import { notFound } from '../errors.js';
import type { RouteDeps } from './deps.js';

export const DELETION_SLA_DAYS = 7;

/**
 * FR-47/FR-48. Creating a request is enough to block sync and analysis immediately (§4.3); the
 * sweep (boot or POST /v1/internal/sweep) removes the aggregates, and the request row keeps its
 * state and timestamps for reporting.
 */
export function deletionRouter(deps: RouteDeps, authenticated: RequestHandler): Router {
  const router = Router();

  router.post('/v1/deletion-requests', authenticated, async (request, response) => {
    const auth = request.auth!;
    const now = deps.now().toISOString();
    const pendingOwner = await deps.store.hasPendingDeletion(auth.ownerId);
    const record = pendingOwner
      ? await deps.store.pendingDeletionForOwner(auth.ownerId)
      : await deps.store.createDeletionRequest({
          ownerId: auth.ownerId,
          slaDays: DELETION_SLA_DAYS,
          at: now,
        });
    if (!record) throw notFound();
    response.status(202).json({ id: record.id, state: record.state, slaDays: record.slaDays });
  });

  router.get('/v1/deletion-requests/:id', authenticated, async (request, response) => {
    const auth = request.auth!;
    const record = await deps.store.getDeletionRequest(auth.ownerId, String(request.params.id ?? ''));
    if (!record) throw notFound('Deletion request not found');
    response.status(200).json({
      id: record.id,
      state: record.state,
      slaDays: record.slaDays,
      requestedAt: record.requestedAt,
      completedAt: record.completedAt,
      aggregatesDeleted: record.aggregatesDeleted,
    });
  });

  return router;
}
