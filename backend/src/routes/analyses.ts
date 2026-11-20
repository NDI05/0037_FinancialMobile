import { createHash } from 'node:crypto';
import { Router, type RequestHandler } from 'express';
import { z } from 'zod';
import {
  badRequest,
  conflict,
  consentRequired,
  deletionInProgress,
  invalidAnalysisOutput,
  notFound,
  providerUnavailable,
} from '../errors.js';
import { assertWithinQuota, recordUsage, reserveAnalysis } from '../domain/limits.js';
import { aggregatePayloadSchema, findDisallowedFields } from '../domain/payload.js';
import { PROMPT_VERSION, buildPrompt } from '../domain/prompt.js';
import { validateAnalysis } from '../domain/validateAnalysis.js';
import { parseBody } from '../http/validate.js';
import { ProviderError } from '../llm/provider.js';
import type { AnalysisRecord } from '../store/store.js';
import { assertNoDeletionInProgress, readMonthId } from './guard.js';
import type { RouteDeps } from './deps.js';

const analysisRequestSchema = z
  .object({
    monthId: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/),
    inputRevision: z.number().int().min(0).max(1_000_000),
    payload: aggregatePayloadSchema,
  })
  .strict();

/** The externally visible shape; identical to `RemoteAnalysisResponse` (contract §4.1). */
export function analysisToResponse(record: AnalysisRecord): Record<string, unknown> {
  return {
    promptVersion: record.analysis.promptVersion,
    observations: record.analysis.observations.map((observation) => ({
      text: observation.text,
      metricKeys: observation.metricKeys,
    })),
    suggestions: record.analysis.suggestions.map((suggestion) => ({
      text: suggestion.text,
      metricKeys: suggestion.metricKeys,
      ...(suggestion.categoryCode ? { categoryCode: suggestion.categoryCode } : {}),
      ...(suggestion.suggestedAmountMinor !== null && suggestion.suggestedAmountMinor !== undefined
        ? { suggestedAmountMinor: suggestion.suggestedAmountMinor }
        : {}),
    })),
    limitations: record.analysis.limitations,
    promptTokens: record.analysis.promptTokens ?? record.promptTokens,
    completionTokens: record.analysis.completionTokens ?? record.completionTokens,
  };
}

/** Tolerates a fenced block: some providers wrap JSON despite `response_format`. */
function parseProviderJson(text: string): unknown {
  const trimmed = text.trim();
  try {
    return JSON.parse(trimmed);
  } catch {
    const start = trimmed.indexOf('{');
    const end = trimmed.lastIndexOf('}');
    if (start >= 0 && end > start) {
      try {
        return JSON.parse(trimmed.slice(start, end + 1));
      } catch {
        return null;
      }
    }
    return null;
  }
}

/**
 * POST /v1/analyses — the core endpoint (contract §4.1).
 *
 * The order is deliberate: identity, deletion, consent, allowlist, quota, cache, provider,
 * validation, store. A limited or unauthorised request never reaches the provider.
 */
export function analysesRouter(deps: RouteDeps, authenticated: RequestHandler): Router {
  const router = Router();

  router.post('/v1/analyses', authenticated, async (request, response) => {
    const auth = request.auth!;
    const body = parseBody(analysisRequestSchema, request.body);
    if (body.payload.monthId !== body.monthId) {
      throw badRequest('payload.monthId must match monthId');
    }

    const idempotencyKey = request.header('idempotency-key')?.trim();
    if (!idempotencyKey) throw badRequest('Idempotency-Key header is required');
    if (idempotencyKey.length > 200) throw badRequest('Idempotency-Key is too long');

    await assertNoDeletionInProgress(deps, auth.ownerId);

    const consent = await deps.store.latestConsent(auth.ownerId, 'AI_PROCESSING');
    if (!consent || consent.decision !== 'GRANTED') throw consentRequired();

    const disallowed = [...new Set(findDisallowedFields(body.payload))];
    if (disallowed.length > 0) {
      throw badRequest(
        `payload contains fields that may never leave the device: ${disallowed.slice(0, 3).join(', ')}`,
      );
    }

    const requestHash = createHash('sha256')
      .update(JSON.stringify({ monthId: body.monthId, inputRevision: body.inputRevision, payload: body.payload }))
      .digest('hex');

    const replay = await deps.store.findAnalysisByIdempotencyKey(auth.ownerId, idempotencyKey);
    if (replay) {
      if (replay.requestHash !== requestHash) {
        throw conflict('This Idempotency-Key was already used with a different body');
      }
      response.status(200).json(analysisToResponse(replay.analysis));
      return;
    }

    const now = deps.now();
    await assertWithinQuota(deps.store, deps.config, auth.ownerId, now);

    // The summary is the anchor for the analysis; creating it here keeps the reference intact even
    // when the client only posts the payload with the analysis request.
    let summary = await deps.store.getSummary(auth.ownerId, body.monthId);
    if (!summary || summary.inputRevision !== body.inputRevision) {
      summary = await deps.store.upsertSummary({
        ownerId: auth.ownerId,
        monthId: body.monthId,
        inputRevision: body.inputRevision,
        payload: body.payload,
        at: now.toISOString(),
      });
    }

    const cached = await deps.store.findAnalysisByCacheKey({
      ownerId: auth.ownerId,
      monthId: body.monthId,
      inputRevision: body.inputRevision,
      promptVersion: PROMPT_VERSION,
      provider: deps.provider.id,
      model: deps.provider.model,
    });
    if (cached) {
      response.status(200).json(analysisToResponse(cached));
      return;
    }

    // F4: the provider call is admitted by an atomic reservation, so concurrent requests cannot
    // both pass a read-only quota check. Throws 429 when the limit is already reached.
    await reserveAnalysis(deps.store, deps.config, auth.ownerId, now);

    const usage = { promptTokens: 0, completionTokens: 0 };
    let issues: string[] = [];
    let analysis: AnalysisRecord['analysis'] | null = null;
    let regenerationCount = 0;

    for (let attempt = 0; attempt < 2; attempt += 1) {
      if (attempt > 0) {
        regenerationCount = 1;
        request.retryCount = 1;
      }
      const prompt = buildPrompt(body.payload, { previousIssues: issues });
      let output;
      try {
        output = await deps.provider.generate({
          prompt,
          payload: body.payload,
          maxOutputTokens: deps.config.llm.maxOutputTokens,
        });
      } catch (error) {
        const reason = error instanceof ProviderError ? error.reason : 'unknown';
        deps.logger.error('provider_failed', { correlationId: request.correlationId, reason });
        throw providerUnavailable();
      }

      const attemptTokens = {
        promptTokens: output.promptTokens ?? 0,
        completionTokens: output.completionTokens ?? 0,
      };
      usage.promptTokens += attemptTokens.promptTokens;
      usage.completionTokens += attemptTokens.completionTokens;

      const validation = validateAnalysis(parseProviderJson(output.text), body.payload, {
        expectedPromptVersion: PROMPT_VERSION,
      });
      if (validation.ok) {
        analysis = {
          ...validation.analysis,
          promptTokens: attemptTokens.promptTokens,
          completionTokens: attemptTokens.completionTokens,
        };
        break;
      }
      issues = [...validation.issues];
    }

    // The provider was called; the tokens are spent even when the output is rejected.
    await recordUsage(deps.store, deps.config, auth.ownerId, now, usage);

    if (!analysis) {
      deps.logger.warn('analysis_output_rejected', {
        correlationId: request.correlationId,
        issues: issues.join(','),
      });
      throw invalidAnalysisOutput();
    }

    const record = await deps.store.insertAnalysisUnlessDeletionPending({
      ownerId: auth.ownerId,
      monthId: body.monthId,
      summaryId: summary.id,
      inputRevision: body.inputRevision,
      consentId: consent.id,
      provider: deps.provider.id,
      model: deps.provider.model,
      promptVersion: PROMPT_VERSION,
      analysis,
      issues: [],
      regenerationCount,
      idempotencyKey,
      requestHash,
      at: now.toISOString(),
    });

    if (!record) {
      // A deletion request became PENDING while the provider was working (contract §4.3).
      throw deletionInProgress();
    }

    response.status(200).json(analysisToResponse(record));
  });

  router.get('/v1/analyses/:monthId', authenticated, async (request, response) => {
    const auth = request.auth!;
    const monthId = readMonthId(request.params.monthId);
    const record = await deps.store.latestAnalysis(auth.ownerId, monthId);
    if (!record) throw notFound('No analysis for that month');
    if (!record) {
      // A deletion request became PENDING while the provider was working (contract §4.3).
      throw deletionInProgress();
    }

    response.status(200).json(analysisToResponse(record));
  });

  return router;
}
