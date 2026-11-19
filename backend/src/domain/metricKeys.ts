/**
 * Mirrors `MetricKeys` in the Android domain (Analysis.kt). A provider may cite only these keys;
 * `category.<CODE>` covers every category the app can produce.
 */
export const METRIC_KEYS = {
  grossInflow: 'summary.grossInflow',
  grossOutflow: 'summary.grossOutflow',
  netCashFlow: 'summary.netCashFlow',
  classifiedIncome: 'summary.classifiedIncome',
  classifiedExpense: 'summary.classifiedExpense',
  uncategorizedCount: 'summary.uncategorizedCount',
  manualEntryCount: 'summary.manualEntryCount',
  nonIdrExcludedCount: 'summary.nonIdrExcludedCount',
  transactionCount: 'summary.transactionCount',
  coverage: 'summary.coverage',
} as const;

export const CATEGORY_CODES = [
  'income',
  'housing',
  'food',
  'transport',
  'utilities',
  'shopping',
  'health',
  'education',
  'other_expense',
  'transfer',
  'uncategorized',
] as const;

export type CategoryCode = (typeof CATEGORY_CODES)[number];

export function categoryKey(code: string): string {
  return `category.${code}`;
}

export const ALL_METRIC_KEYS: ReadonlySet<string> = new Set<string>([
  ...Object.values(METRIC_KEYS),
  ...CATEGORY_CODES.map(categoryKey),
]);

export function isKnownMetricKey(key: string): boolean {
  return ALL_METRIC_KEYS.has(key);
}
