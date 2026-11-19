import { ProviderError, type AnalysisProvider, type ProviderCall, type ProviderResult } from './provider.js';

export interface OpenAiCompatibleOptions {
  readonly baseUrl: string;
  readonly apiKey: string;
  readonly model: string;
  readonly timeoutMs: number;
  /** Injection point for tests; production uses global fetch. */
  readonly fetchImpl?: typeof fetch;
}

interface ChatCompletion {
  choices?: Array<{ message?: { content?: string | null } }>;
  usage?: { prompt_tokens?: number; completion_tokens?: number };
}

/**
 * One adapter for OpenAI and OpenRouter (contract §1, OI-07).
 *
 * `response_format: json_object` and a hard `max_tokens` keep the answer parseable and bounded.
 * Only a technical reason is thrown — never the prompt, the key or the response body.
 */
export class OpenAiCompatibleProvider implements AnalysisProvider {
  readonly id = 'openai-compatible';
  readonly model: string;
  private readonly fetchImpl: typeof fetch;

  constructor(private readonly options: OpenAiCompatibleOptions) {
    this.model = options.model;
    this.fetchImpl = options.fetchImpl ?? fetch;
  }

  async generate(call: ProviderCall): Promise<ProviderResult> {
    const url = `${this.options.baseUrl.replace(/\/+$/, '')}/chat/completions`;
    const headers: Record<string, string> = {
      'content-type': 'application/json',
      authorization: `Bearer ${this.options.apiKey}`,
    };
    if (this.options.baseUrl.includes('openrouter')) {
      headers['HTTP-Referer'] = 'https://pam.app';
      headers['X-Title'] = 'PAM';
    }

    let response: Response;
    try {
      response = await this.fetchImpl(url, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          model: this.options.model,
          messages: [
            { role: 'system', content: call.prompt.system },
            { role: 'user', content: call.prompt.user },
          ],
          response_format: { type: 'json_object' },
          max_tokens: call.maxOutputTokens,
          temperature: 0.2,
        }),
        signal: AbortSignal.timeout(this.options.timeoutMs),
      });
    } catch (error) {
      const reason =
        error instanceof Error && error.name === 'TimeoutError' ? 'timeout' : 'network_error';
      throw new ProviderError(reason, { cause: error });
    }

    if (!response.ok) {
      throw new ProviderError(`http_${response.status}`);
    }

    let body: ChatCompletion;
    try {
      body = (await response.json()) as ChatCompletion;
    } catch (error) {
      throw new ProviderError('invalid_json', { cause: error });
    }

    const text = body.choices?.[0]?.message?.content;
    if (typeof text !== 'string' || text.trim() === '') {
      throw new ProviderError('empty_completion');
    }

    return {
      text,
      promptTokens: body.usage?.prompt_tokens ?? null,
      completionTokens: body.usage?.completion_tokens ?? null,
    };
  }
}
