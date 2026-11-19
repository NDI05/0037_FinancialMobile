import { Router, type RequestHandler } from 'express';
import type { RouteDeps } from './deps.js';

/**
 * GET /v1/me — FR-49: the current consent status per purpose plus the most recent activity, so the
 * app can show state without asking for a token it does not keep.
 */
export function meRouter(deps: RouteDeps, authenticated: RequestHandler): Router {
  const router = Router();

  router.get('/v1/me', authenticated, (request, response) => {
    const auth = request.auth!;
    const user = deps.store.getUser(auth.ownerId);
    const activity = deps.store.activity(auth.ownerId);
    const consents = deps.store.listConsents(auth.ownerId, 50);
    const latestByPurpose = new Map<string, (typeof consents)[number]>();
    for (const consent of consents) {
      if (!latestByPurpose.has(consent.purpose)) latestByPurpose.set(consent.purpose, consent);
    }
    response.status(200).json({
      displayName: user?.displayName ?? null,
      consents: consents.map((consent) => ({
        id: consent.id,
        purpose: consent.purpose,
        decision: consent.decision,
        noticeVersion: consent.noticeVersion,
        decidedAt: consent.createdAt,
      })),
      status: {
        GMAIL_READ: latestByPurpose.get('GMAIL_READ')?.decision ?? null,
        AI_PROCESSING: latestByPurpose.get('AI_PROCESSING')?.decision ?? null,
      },
      lastSummaryAt: activity.lastSummaryAt,
      lastAnalysisAt: activity.lastAnalysisAt,
      deletionInProgress: deps.store.hasPendingDeletion(auth.ownerId),
    });
  });

  return router;
}
