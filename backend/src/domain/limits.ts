import type { AppConfig } from '../config.js';
import { dailyCeilingReached, rateLimited } from '../errors.js';
import { jakartaBuckets, type Store } from '../store/store.js';

/**
 * FR-50 / contract §5. Checked **before** the provider is called, so a limited user never costs
 * money. The global ceiling is a single `*` counter for the Jakarta day.
 */
export const GLOBAL_SCOPE = '*';

export interface QuotaSnapshot {
  readonly hourUsed: number;
  readonly dayUsed: number;
  readonly globalCostUsd: number;
}

export function quotaSnapshot(store: Store, ownerId: string, now: Date): QuotaSnapshot {
  const buckets = jakartaBuckets(now);
  const hour = store.getUsage(ownerId, `hour:${buckets.hour}`);
  const day = store.getUsage(ownerId, `day:${buckets.day}`);
  const global = store.getUsage(GLOBAL_SCOPE, `day:${buckets.day}`);
  return { hourUsed: hour.analyses, dayUsed: day.analyses, globalCostUsd: global.costUsd };
}

export function assertWithinQuota(
  store: Store,
  config: AppConfig,
  ownerId: string,
  now: Date,
): void {
  const snapshot = quotaSnapshot(store, ownerId, now);
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

export function costUsdFor(
  config: AppConfig,
  tokens: { readonly promptTokens: number; readonly completionTokens: number },
): number {
  return (
    (tokens.promptTokens / 1000) * config.limits.promptPricePer1k +
    (tokens.completionTokens / 1000) * config.limits.completionPricePer1k
  );
}

/** Records one analysis (even a failed one: the provider was called and it costs money). */
export function recordUsage(
  store: Store,
  config: AppConfig,
  ownerId: string,
  now: Date,
  tokens: { readonly promptTokens: number; readonly completionTokens: number },
): void {
  const buckets = jakartaBuckets(now);
  const cost = costUsdFor(config, tokens);
  const delta = {
    analyses: 1,
    tokensPrompt: tokens.promptTokens,
    tokensCompletion: tokens.completionTokens,
    costUsd: cost,
  };
  store.bumpUsage(ownerId, `hour:${buckets.hour}`, delta);
  store.bumpUsage(ownerId, `day:${buckets.day}`, delta);
  store.bumpUsage(GLOBAL_SCOPE, `day:${buckets.day}`, {
    tokensPrompt: tokens.promptTokens,
    tokensCompletion: tokens.completionTokens,
    costUsd: cost,
  });
}
