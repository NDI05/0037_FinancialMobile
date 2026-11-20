import type { AppConfig } from '../config.js';
import { dailyCeilingReached, rateLimited } from '../errors.js';
import { jakartaBuckets, type Store } from '../store/store.js';

/**
 * FR-50 / contract §5.
 *
 * Two steps, deliberately:
 * 1. `assertWithinQuota` is a cheap read used in the contract's step order (quota before cache), so
 *    a rate-limited user still gets 429 even for a request that would have been served from cache.
 * 2. `reserveAnalysis` runs inside one write transaction and is what actually admits the provider
 *    call. Read-then-increment cannot interleave, so N concurrent requests against a limit of M
 *    admit exactly M (F4).
 */
export const GLOBAL_SCOPE = '*';

export interface QuotaSnapshot {
  readonly hourUsed: number;
  readonly dayUsed: number;
  readonly globalCostUsd: number;
}

export async function quotaSnapshot(store: Store, ownerId: string, now: Date): Promise<QuotaSnapshot> {
  const buckets = jakartaBuckets(now);
  const hour = await store.getUsage(ownerId, `hour:${buckets.hour}`);
  const day = await store.getUsage(ownerId, `day:${buckets.day}`);
  const global = await store.getUsage(GLOBAL_SCOPE, `day:${buckets.day}`);
  return { hourUsed: hour.analyses, dayUsed: day.analyses, globalCostUsd: global.costUsd };
}

export async function assertWithinQuota(
  store: Store,
  config: AppConfig,
  ownerId: string,
  now: Date,
): Promise<void> {
  const snapshot = await quotaSnapshot(store, ownerId, now);
  if (snapshot.hourUsed >= config.limits.perHour) {
    throw rateLimited(
      `Hourly analysis limit reached (${config.limits.perHour} per hour). Try again later.`,
    );
  }
  if (snapshot.dayUsed >= config.limits.perDay) {
    throw rateLimited(
      `Daily analysis limit reached (${config.limits.perDay} per day). Try again tomorrow.`,
    );
  }
  if (snapshot.globalCostUsd >= config.limits.dailyCeilingUsd) {
    throw dailyCeilingReached();
  }
}

/** Atomically admits one analysis. Throws the same 429s as the read-only check. */
export async function reserveAnalysis(
  store: Store,
  config: AppConfig,
  ownerId: string,
  now: Date,
): Promise<void> {
  const reservation = await store.tryReserveAnalysis(ownerId, now, config.limits);
  if (reservation.ok) return;
  if (reservation.reason === 'daily_ceiling_reached') throw dailyCeilingReached();
  throw rateLimited(
    `Analysis limit reached (${config.limits.perHour} per hour, ${config.limits.perDay} per day).`,
  );
}

export function costUsdFor(
  config: AppConfig,
  tokens: { readonly promptTokens: number; readonly completionTokens: number },
): number {
  return (
    (tokens.promptTokens / 1000) * config.limits.promptPricePer1k +
    (tokens.completionTokens / 1000) * config.limits.completionPricePer1k
  );
}

/** Records tokens and cost after the provider call (the analysis itself was reserved earlier). */
export async function recordUsage(
  store: Store,
  config: AppConfig,
  ownerId: string,
  now: Date,
  tokens: { readonly promptTokens: number; readonly completionTokens: number },
): Promise<void> {
  await store.addUsage(ownerId, now, {
    promptTokens: tokens.promptTokens,
    completionTokens: tokens.completionTokens,
    costUsd: costUsdFor(config, tokens),
  });
}
