import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ConfigError, isLocalLlmHost, loadConfig } from '../src/config.js';

/**
 * F2: the free local path is first-class. A local runtime needs no credential at all; a remote one
 * still must not start in production without a key.
 */
const productionBase = {
  NODE_ENV: 'production',
  SESSION_PEPPER: 'x'.repeat(40),
  DATA_ENCRYPTION_KEY: Buffer.alloc(32, 7).toString('base64'),
  INTERNAL_SWEEP_TOKEN: 'y'.repeat(24),
  REGISTRATION_INVITE_CODE: 'invite-for-tests',
  LLM_PROVIDER: 'openai',
} as const;

test('isLocalLlmHost recognises local runtimes', () => {
  for (const url of [
    'http://localhost:11434/v1',
    'http://127.0.0.1:11434/v1',
    'http://[::1]:11434/v1',
    'http://ollama.local:11434/v1',
    'http://studio.lan.local/v1',
  ]) {
    assert.equal(isLocalLlmHost(url), true, url);
  }
});

test('isLocalLlmHost rejects remote hosts and malformed URLs', () => {
  for (const url of [
    'https://api.openai.com/v1',
    'https://api.groq.com/openai/v1',
    'https://openrouter.ai/api/v1',
    'http://10.0.0.5:11434/v1',
    'not-a-url',
    '',
  ]) {
    assert.equal(isLocalLlmHost(url), false, url);
  }
});

test('production accepts an empty key for a local runtime, without complaint', () => {
  const config = loadConfig({
    ...productionBase,
    LLM_BASE_URL: 'http://127.0.0.1:11434/v1',
    LLM_API_KEY: '',
  } as NodeJS.ProcessEnv);
  assert.equal(config.llm.provider, 'openai');
  assert.equal(config.llm.apiKey, '');
  assert.equal(
    config.warnings.some((warning) => warning.includes('LLM_API_KEY')),
    false,
  );
});

test('production still refuses a remote provider with no key', () => {
  assert.throws(
    () =>
      loadConfig({
        ...productionBase,
        LLM_BASE_URL: 'https://api.groq.com/openai/v1',
        LLM_API_KEY: '',
      } as NodeJS.ProcessEnv),
    (error: unknown) => {
      assert.ok(error instanceof ConfigError);
      assert.ok(error.problems.some((problem) => problem.includes('LLM_API_KEY')));
      return true;
    },
  );
});

test('development warns for a remote provider without a key', () => {
  const config = loadConfig({
    NODE_ENV: 'development',
    LLM_PROVIDER: 'openai',
    LLM_BASE_URL: 'https://openrouter.ai/api/v1',
    LLM_API_KEY: '',
  } as NodeJS.ProcessEnv);
  assert.ok(config.warnings.some((warning) => warning.includes('LLM_API_KEY')));
});

test('development stays quiet for a local provider without a key', () => {
  const config = loadConfig({
    NODE_ENV: 'development',
    LLM_PROVIDER: 'openai',
    LLM_BASE_URL: 'http://localhost:11434/v1',
    LLM_API_KEY: '',
  } as NodeJS.ProcessEnv);
  assert.equal(
    config.warnings.some((warning) => warning.includes('LLM_API_KEY')),
    false,
  );
});

test('the fake provider is still the default', () => {
  const config = loadConfig({ NODE_ENV: 'development' } as NodeJS.ProcessEnv);
  assert.equal(config.llm.provider, 'fake');
});
