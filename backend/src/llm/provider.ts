import type { AggregatePayload } from '../domain/payload.js';
import type { PromptPayload } from '../domain/prompt.js';

/**
 * One interface, two adapters (contract §1, OI-07). The route layer never imports a concrete
 * provider, so tests inject the deterministic fake and production injects OpenAI/OpenRouter.
 */
export interface ProviderCall {
  readonly prompt: PromptPayload;
  readonly payload: AggregatePayload;
  readonly maxOutputTokens: number;
}

export interface ProviderResult {
  readonly text: string;
  readonly promptTokens?: number | null;
  readonly completionTokens?: number | null;
}

export interface AnalysisProvider {
  readonly id: string;
  readonly model: string;
  generate(call: ProviderCall): Promise<ProviderResult>;
}

export class ProviderError extends Error {
  constructor(
    /** Technical reason only: never the request body, the prompt or the API key. */
    readonly reason: string,
    options: { cause?: unknown } = {},
  ) {
    super(`provider_failed:${reason}`, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = 'ProviderError';
  }
}
