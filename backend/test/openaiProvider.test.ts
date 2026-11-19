import assert from 'node:assert/strict';
import { test } from 'node:test';
import { OpenAiCompatibleProvider } from '../src/llm/openai.js';
import { ProviderError } from '../src/llm/provider.js';
import { samplePayload } from './helpers.js';
import { buildPrompt } from '../src/domain/prompt.js';

/**
 * The adapter contract (D6): OpenAI-compatible request shape, OpenRouter-only headers, bounded
 * max_tokens, a hard timeout, and typed failures that never leak the key or the prompt.
 */
function fakeFetch(handler: (url: string, init: RequestInit) => Response | Promise<Response>): {
  fetchImpl: typeof fetch;
  calls: Array<{ url: string; init: RequestInit }>;
} {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const fetchImpl = (async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return handler(url, init);
  }) as unknown as typeof fetch;
  return { fetchImpl, calls };
}

const call = {
  prompt: buildPrompt(samplePayload()),
  payload: samplePayload(),
  maxOutputTokens: 800,
};

test('posts to /chat/completions with json_object and the token cap', async () => {
  const { fetchImpl, calls } = fakeFetch(
    () =>
      new Response(
        JSON.stringify({ choices: [{ message: { content: '{"ok":true}' } }], usage: { prompt_tokens: 10, completion_tokens: 5 } }),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ),
  );
  const provider = new OpenAiCompatibleProvider({
    baseUrl: 'https://api.openai.com/v1/',
    apiKey: 'sk-test',
    model: 'gpt-4o-mini',
    timeoutMs: 1_000,
    fetchImpl,
  });

  const result = await provider.generate(call);
  assert.equal(result.text, '{"ok":true}');
  assert.equal(result.promptTokens, 10);
  assert.equal(result.completionTokens, 5);

  const [request] = calls;
  assert.equal(request?.url, 'https://api.openai.com/v1/chat/completions');
  const headers = request?.init.headers as Record<string, string>;
  assert.equal(headers.authorization, 'Bearer sk-test');
  assert.equal(headers['HTTP-Referer'], undefined, 'OpenAI must not receive OpenRouter headers');
  const body = JSON.parse(String(request?.init.body)) as Record<string, unknown>;
  assert.deepEqual(body.response_format, { type: 'json_object' });
  assert.equal(body.max_tokens, 800);
  assert.equal(body.model, 'gpt-4o-mini');
  assert.equal((body.messages as unknown[]).length, 2);
});

test('adds HTTP-Referer and X-Title only for OpenRouter', async () => {
  const { fetchImpl, calls } = fakeFetch(
    () => new Response(JSON.stringify({ choices: [{ message: { content: '{}' } }] }), { status: 200 }),
  );
  const provider = new OpenAiCompatibleProvider({
    baseUrl: 'https://openrouter.ai/api/v1',
    apiKey: 'sk-or-test',
    model: 'openai/gpt-4o-mini',
    timeoutMs: 1_000,
    fetchImpl,
  });
  await provider.generate(call);
  const headers = calls[0]?.init.headers as Record<string, string>;
  assert.equal(headers['HTTP-Referer'], 'https://pam.app');
  assert.equal(headers['X-Title'], 'PAM');
});

test('a non-2xx response is provider_unavailable, without the body', async () => {
  const { fetchImpl } = fakeFetch(
    () => new Response('{"error":"secret-detail"}', { status: 429 }),
  );
  const provider = new OpenAiCompatibleProvider({
    baseUrl: 'https://api.openai.com/v1',
    apiKey: 'sk-test',
    model: 'm',
    timeoutMs: 1_000,
    fetchImpl,
  });
  await assert.rejects(
    () => provider.generate(call),
    (error: unknown) => {
      assert.ok(error instanceof ProviderError);
      assert.equal(error.reason, 'http_429');
      assert.ok(!error.message.includes('secret-detail'));
      assert.ok(!error.message.includes('sk-test'));
      return true;
    },
  );
});

test('a timeout is reported as timeout', async () => {
  const { fetchImpl } = fakeFetch(
    (_url, init) =>
      new Promise<Response>((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => {
          const error = new Error('aborted');
          error.name = 'TimeoutError';
          reject(error);
        });
      }),
  );
  const provider = new OpenAiCompatibleProvider({
    baseUrl: 'https://api.openai.com/v1',
    apiKey: 'sk-test',
    model: 'm',
    timeoutMs: 20,
    fetchImpl,
  });
  await assert.rejects(() => provider.generate(call), (error: unknown) => {
    assert.ok(error instanceof ProviderError);
    assert.equal(error.reason, 'timeout');
    return true;
  });
});

test('an empty completion is rejected', async () => {
  const { fetchImpl } = fakeFetch(
    () => new Response(JSON.stringify({ choices: [{ message: { content: '   ' } }] }), { status: 200 }),
  );
  const provider = new OpenAiCompatibleProvider({
    baseUrl: 'https://api.openai.com/v1',
    apiKey: 'sk-test',
    model: 'm',
    timeoutMs: 1_000,
    fetchImpl,
  });
  await assert.rejects(() => provider.generate(call), (error: unknown) => {
    assert.ok(error instanceof ProviderError);
    assert.equal(error.reason, 'empty_completion');
    return true;
  });
});
