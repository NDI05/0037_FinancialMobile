import { z } from 'zod';

/**
 * The exact response shape of `RemoteAnalysisResponse` in
 * `app/src/main/java/com/pam/recap/data/ai/RemoteAnalysisEngine.kt` (contract §4.1).
 *
 * Every field except `text` is optional there, so the schema keeps `metricKeys` defaulted and the
 * stored form may omit tokens. `categoryCode` and `suggestedAmountMinor` only exist on suggestions.
 */
export const observationSchema = z
  .object({
    text: z.string().min(1).max(600),
    metricKeys: z.array(z.string().min(1).max(64)).default([]),
  })
  .strict();

export const suggestionSchema = z
  .object({
    text: z.string().min(1).max(600),
    metricKeys: z.array(z.string().min(1).max(64)).default([]),
    categoryCode: z.string().min(1).max(32).nullish(),
    suggestedAmountMinor: z.number().int().nullish(),
  })
  .strict();

export const analysisResponseSchema = z
  .object({
    promptVersion: z.string().min(1).max(32),
    observations: z.array(observationSchema).max(12),
    suggestions: z.array(suggestionSchema).max(8),
    limitations: z.array(z.string().min(1).max(400)).max(8),
    promptTokens: z.number().int().min(0).nullish(),
    completionTokens: z.number().int().min(0).nullish(),
  })
  .strict();

export type Observation = z.infer<typeof observationSchema>;
export type Suggestion = z.infer<typeof suggestionSchema>;
export type AnalysisResponse = z.infer<typeof analysisResponseSchema>;
