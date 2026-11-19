import { createHash, timingSafeEqual } from 'node:crypto';
import { Router } from 'express';
import { notFound, unauthorized } from '../errors.js';
import type { RouteDeps } from './deps.js';

function safeEqual(left: string, right: string): boolean {
  const a = createHash('sha256').update(left).digest();
  const b = createHash('sha256').update(right).digest();
  return timingSafeEqual(a, b);
}

/**
 * POST /v1/internal/sweep (contract §4.3/D9).
 *
 * Closed by default: with no INTERNAL_SWEEP_TOKEN configured the endpoint answers 404 instead of
 * running an unauthenticated destructive operation.
 */
export function internalRouter(deps: RouteDeps): Router {
  const router = Router();

  router.post('/v1/internal/sweep', (request, response) => {
    const expected = deps.config.internalSweepToken;
    if (!expected) throw notFound();
    const supplied = request.header('x-internal-token') ?? '';
    if (!supplied || !safeEqual(supplied, expected)) throw unauthorized('Invalid internal token');
    const completed = deps.store.sweepDeletions(deps.now().toISOString());
    response.status(200).json({ completed });
  });

  return router;
}
