import { badRequest, deletionInProgress, notFound } from '../errors.js';
import type { RouteDeps } from './deps.js';

const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

/** `monthId` is `YYYY-MM` in Asia/Jakarta (contract §4). */
export function readMonthId(raw: string | string[] | undefined): string {
  const value = typeof raw === 'string' ? raw : undefined;
  if (!value || !MONTH_PATTERN.test(value)) {
    throw badRequest('monthId must look like YYYY-MM');
  }
  return value;
}

/**
 * FR-47/§4.3: while a deletion request is PENDING, sync and analysis are refused so the sweep
 * cannot race a new write.
 */
export function assertNoDeletionInProgress(deps: RouteDeps, ownerId: string): void {
  if (deps.store.hasPendingDeletion(ownerId)) throw deletionInProgress();
}

export function requireAuth(request: { auth?: { ownerId: string; sessionId: string } }) {
  if (!request.auth) throw notFound();
  return request.auth;
}
