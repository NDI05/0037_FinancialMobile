import { z } from 'zod';

/**
 * FR-35 / NFR-19: the only shape that may be accepted for analysis.
 *
 * Two independent guards, because this is the single most important privacy boundary:
 * 1. zod strict objects reject unknown keys at every level;
 * 2. `assertAllowedFields` walks the raw body and reports any key that is not in the Kotlin
 *    `AiAggregatePayload.ALLOWED_FIELDS` set — so `subject`, `body`, `messageId`, `token` or `pan`
 *    produce a 400 that names the field, not a silently ignored extra property.
 */

export const ALLOWED_FIELDS: ReadonlySet<string> = new Set([
  'schemaVersion',
  'monthId',
  'coverage',
  'grossInflowMinor',
  'grossOutflowMinor',
  'netCashFlowMinor',
  'classifiedIncomeMinor',
  'classifiedExpenseMinor',
  'transactionCount',
  'uncategorizedCount',
  'manualEntryCount',
  'nonIdrExcludedCount',
  'categoryTotals',
  'priorMonth',
  'plan',
  'categoryCode',
  'amountMinor',
  'limitMinor',
  'expenseMinor',
  'incomeMinor',
  'expectedIncomeMinor',
  'plannedSavingsMinor',
  'categoryLimitTotals',
]);

const minor = z.number().int().min(-1_000_000_000_000).max(1_000_000_000_000);
const count = z.number().int().min(0).max(1_000_000);

export const categoryTotalSchema = z
  .object({
    categoryCode: z.string().min(2).max(32),
    amountMinor: minor,
    transactionCount: count,
  })
  .strict();

export const planSummarySchema = z
  .object({
    expectedIncomeMinor: minor,
    plannedSavingsMinor: minor,
    categoryLimitTotals: z.array(categoryTotalSchema).max(20),
  })
  .strict();

export const aggregatePayloadSchema = z
  .object({
    schemaVersion: z.literal(1),
    monthId: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/),
    coverage: z.enum(['COMPLETE', 'PARTIAL', 'UNKNOWN']),
    grossInflowMinor: minor,
    grossOutflowMinor: minor,
    netCashFlowMinor: minor,
    classifiedIncomeMinor: minor,
    classifiedExpenseMinor: minor,
    transactionCount: count,
    uncategorizedCount: count,
    manualEntryCount: count,
    nonIdrExcludedCount: count,
    categoryTotals: z.array(categoryTotalSchema).max(50),
    priorMonth: z
      .object({
        monthId: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/),
        expenseMinor: minor,
        incomeMinor: minor,
      })
      .strict()
      .nullish(),
    plan: planSummarySchema.nullish(),
  })
  .strict();

export type AggregatePayload = z.infer<typeof aggregatePayloadSchema>;
export type CategoryTotal = z.infer<typeof categoryTotalSchema>;
export type PlanSummary = z.infer<typeof planSummarySchema>;

/** Returns every disallowed key path found in the raw body (empty when the payload is clean). */
export function findDisallowedFields(value: unknown, path = ''): string[] {
  if (value === null || typeof value !== 'object') return [];
  const entries = Array.isArray(value)
    ? value.map((item, index) => [`${path}[${index}]`, item] as const)
    : Object.entries(value as Record<string, unknown>);
  const found: string[] = [];
  for (const [key, child] of entries) {
    if (!Array.isArray(value) && !ALLOWED_FIELDS.has(key)) {
      found.push(path ? `${path}.${key}` : key);
    }
    found.push(...findDisallowedFields(child, Array.isArray(value) ? path : path ? `${path}.${key}` : key));
  }
  return found;
}

/** Every amount a provider is allowed to mention, in minor units (mirrors AnalysisValidator). */
export function allowedAmounts(payload: AggregatePayload, suggested: readonly (number | null | undefined)[] = []): Set<number> {
  const allowed = new Set<number>([0]);
  const add = (value: number | null | undefined): void => {
    if (typeof value === 'number' && Number.isFinite(value)) allowed.add(Math.abs(Math.trunc(value)));
  };
  add(payload.grossInflowMinor);
  add(payload.grossOutflowMinor);
  add(payload.netCashFlowMinor);
  add(payload.classifiedIncomeMinor);
  add(payload.classifiedExpenseMinor);
  for (const total of payload.categoryTotals) add(total.amountMinor);
  if (payload.priorMonth) {
    add(payload.priorMonth.expenseMinor);
    add(payload.priorMonth.incomeMinor);
  }
  if (payload.plan) {
    add(payload.plan.expectedIncomeMinor);
    add(payload.plan.plannedSavingsMinor);
    for (const total of payload.plan.categoryLimitTotals) add(total.amountMinor);
  }
  for (const value of suggested) add(value ?? null);
  return allowed;
}
