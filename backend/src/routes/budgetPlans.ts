import { Router, type RequestHandler } from 'express';
import { z } from 'zod';
import { notFound } from '../errors.js';
import { parseBody } from '../http/validate.js';
import { planSummarySchema } from '../domain/payload.js';
import { assertNoDeletionInProgress, readMonthId } from './guard.js';
import type { RouteDeps } from './deps.js';

const putPlanSchema = z.object({ plan: planSummarySchema }).strict();

/** PUT/GET /v1/budget-plans/{monthId} (FR-42…FR-45). Stored sealed, like summaries. */
export function budgetPlansRouter(deps: RouteDeps, authenticated: RequestHandler): Router {
  const router = Router();

  router.put('/v1/budget-plans/:monthId', authenticated, (request, response) => {
    const auth = request.auth!;
    const monthId = readMonthId(request.params.monthId);
    const body = parseBody(putPlanSchema, request.body);
    assertNoDeletionInProgress(deps, auth.ownerId);
    const record = deps.store.upsertBudgetPlan({
      ownerId: auth.ownerId,
      monthId,
      plan: body.plan,
      at: deps.now().toISOString(),
    });
    response.status(200).json({
      monthId: record.monthId,
      expectedIncomeMinor: record.plan.expectedIncomeMinor,
      plannedSavingsMinor: record.plan.plannedSavingsMinor,
      categoryLimitTotals: record.plan.categoryLimitTotals,
      updatedAt: record.updatedAt,
    });
  });

  router.get('/v1/budget-plans/:monthId', authenticated, (request, response) => {
    const auth = request.auth!;
    const monthId = readMonthId(request.params.monthId);
    const record = deps.store.getBudgetPlan(auth.ownerId, monthId);
    if (!record) throw notFound('No budget plan for that month');
    response.status(200).json({
      monthId: record.monthId,
      expectedIncomeMinor: record.plan.expectedIncomeMinor,
      plannedSavingsMinor: record.plan.plannedSavingsMinor,
      categoryLimitTotals: record.plan.categoryLimitTotals,
      createdAt: record.createdAt,
      updatedAt: record.updatedAt,
    });
  });

  return router;
}
