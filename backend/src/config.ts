import { createHash, randomBytes } from 'node:crypto';
import { z } from 'zod';

/**
 * Environment parsing and validation (contract §1/§5).
 *
 * Two modes matter:
 * - development/test: every secret has a deterministic, clearly-labelled dev default so
 *   `npm start` and `npm test` need zero external services;
 * - production: missing or weak secrets fail fast with an actionable message instead of silently
 *   running with a dev key.
 */

export class ConfigError extends Error {
  constructor(readonly problems: string[]) {
    super(`Invalid configuration:\n- ${problems.join('\n- ')}`);
    this.name = 'ConfigError';
  }
}

const booleanish = z
  .enum(['true', 'false'])
  .transform((value) => value === 'true');

const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().min(0).max(65535).default(8080),
  DATABASE_PATH: z.string().min(1).default('./data/pam.db'),
  LOG_LEVEL: z.enum(['debug', 'info', 'warn', 'error']).default('info'),

  GOOGLE_WEB_CLIENT_ID: z.string().min(1).default('dev-client-id.apps.googleusercontent.com'),
  GOOGLE_JWKS_URL: z.string().url().default('https://www.googleapis.com/oauth2/v3/certs'),
  AUTH_DEV_FAKE_TOKENS: booleanish.default(true),
  SESSION_PEPPER: z.string().default(''),
  SESSION_TTL_DAYS: z.coerce.number().int().min(1).max(365).default(30),

  DATA_ENCRYPTION_KEY: z.string().default(''),
  DATA_KEY_VERSION: z.coerce.number().int().min(1).default(1),

  LLM_PROVIDER: z.enum(['fake', 'openai']).default('fake'),
  LLM_BASE_URL: z.string().url().default('https://api.openai.com/v1'),
  LLM_API_KEY: z.string().default(''),
  LLM_MODEL: z.string().min(1).default('gpt-4o-mini'),
  LLM_MAX_OUTPUT_TOKENS: z.coerce.number().int().min(64).max(4096).default(800),
  LLM_TIMEOUT_MS: z.coerce.number().int().min(1000).max(120_000).default(45_000),
  FAKE_LLM_SCENARIO: z
    .enum(['valid', 'malformed', 'invented', 'injection', 'bare_minor', 'invalid_then_valid'])
    .default('valid'),

  RATE_LIMIT_ANALYSES_PER_HOUR: z.coerce.number().int().min(1).max(10_000).default(10),
  RATE_LIMIT_ANALYSES_PER_DAY: z.coerce.number().int().min(1).max(10_000).default(40),
  DAILY_AI_CEILING_USD: z.coerce.number().min(0).max(10_000).default(1),
  PRICE_PROMPT_PER_1K_USD: z.coerce.number().min(0).default(0.00015),
  PRICE_COMPLETION_PER_1K_USD: z.coerce.number().min(0).default(0.0006),

  INTERNAL_SWEEP_TOKEN: z.string().default(''),
});

export interface AppConfig {
  readonly nodeEnv: 'development' | 'test' | 'production';
  readonly port: number;
  readonly databasePath: string;
  readonly logLevel: 'debug' | 'info' | 'warn' | 'error';
  readonly googleClientId: string;
  readonly googleJwksUrl: string;
  readonly devFakeTokens: boolean;
  readonly sessionPepper: string;
  readonly sessionTtlDays: number;
  readonly dataKey: Buffer;
  readonly dataKeyVersion: number;
  readonly llm: {
    readonly provider: 'fake' | 'openai';
    readonly baseUrl: string;
    readonly apiKey: string;
    readonly model: string;
    readonly maxOutputTokens: number;
    readonly timeoutMs: number;
    readonly fakeScenario:
      | 'valid'
      | 'malformed'
      | 'invented'
      | 'injection'
      | 'bare_minor'
      | 'invalid_then_valid';
  };
  readonly limits: {
    readonly perHour: number;
    readonly perDay: number;
    readonly dailyCeilingUsd: number;
    readonly promptPricePer1k: number;
    readonly completionPricePer1k: number;
  };
  readonly internalSweepToken: string;
  readonly isProduction: boolean;
  /** Non-fatal issues worth logging once at boot (e.g. dev secrets in use). */
  readonly warnings: readonly string[];
}

/** Dev-only deterministic secret, clearly marked so it can never be mistaken for a real one. */
const DEV_PEPPER = 'dev-session-pepper-not-for-production-use';
const DEV_DATA_KEY = createHash('sha256').update('pam-dev-data-key').digest();

function decodeDataKey(raw: string): Buffer | null {
  if (!raw) return null;
  try {
    const decoded = Buffer.from(raw, 'base64');
    return decoded.length === 32 ? decoded : null;
  } catch {
    return null;
  }
}


/**
 * A local OpenAI-compatible runtime (Ollama, LM Studio, llama.cpp) needs no credential. Detecting
 * it by host keeps the free, fully-offline path working without asking the user to invent a key.
 */
export function isLocalLlmHost(baseUrl: string): boolean {
  try {
    // URL.hostname keeps IPv6 brackets (`[::1]`), so strip them before comparing.
    const host = new URL(baseUrl).hostname.replace(/^\[(.*)\]$/, '$1');
    return host === 'localhost' || host === '127.0.0.1' || host === '::1' || host.endsWith('.local');
  } catch {
    return false;
  }
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = schema.safeParse(env);
  if (!parsed.success) {
    const problems = parsed.error.issues.map(
      (issue) => `${issue.path.join('.') || '(root)'}: ${issue.message}`,
    );
    throw new ConfigError(problems);
  }
  const value = parsed.data;
  const isProduction = value.NODE_ENV === 'production';
  const problems: string[] = [];
  const warnings: string[] = [];

  let sessionPepper = value.SESSION_PEPPER;
  let dataKey = decodeDataKey(value.DATA_ENCRYPTION_KEY);

  if (isProduction) {
    if (sessionPepper.length < 32) {
      problems.push(
        'SESSION_PEPPER must be at least 32 characters (generate with: openssl rand -hex 32)',
      );
    }
    if (!dataKey) {
      problems.push(
        'DATA_ENCRYPTION_KEY must be base64 for exactly 32 bytes (generate with: openssl rand -base64 32)',
      );
    }
    if (!value.INTERNAL_SWEEP_TOKEN || value.INTERNAL_SWEEP_TOKEN.length < 16) {
      problems.push(
        'INTERNAL_SWEEP_TOKEN must be at least 16 characters (protects POST /v1/internal/sweep)',
      );
    }
    if (value.AUTH_DEV_FAKE_TOKENS) {
      problems.push('AUTH_DEV_FAKE_TOKENS must be false in production (dev tokens bypass Google)');
    }
    if (value.GOOGLE_WEB_CLIENT_ID.startsWith('dev-')) {
      problems.push(
        'GOOGLE_WEB_CLIENT_ID is still the development placeholder; set the Web client ID from docs/05_REAL_GOOGLE_SETUP.md',
      );
    }
    if (value.LLM_PROVIDER === 'openai' && !value.LLM_API_KEY && !isLocalLlmHost(value.LLM_BASE_URL)) {
      problems.push(
        'LLM_API_KEY is required when LLM_PROVIDER=openai unless LLM_BASE_URL points at a local runtime',
      );
    }
  } else {
    if (!sessionPepper) {
      sessionPepper = DEV_PEPPER;
      warnings.push('SESSION_PEPPER missing: using the development pepper');
    }
    if (!dataKey) {
      dataKey = DEV_DATA_KEY;
      warnings.push('DATA_ENCRYPTION_KEY missing: using the development data key');
    }
    if (!value.INTERNAL_SWEEP_TOKEN) {
      warnings.push('INTERNAL_SWEEP_TOKEN missing: the internal sweep endpoint stays closed');
    }
  }

  if (value.LLM_PROVIDER === 'openai' && !value.LLM_API_KEY && !isLocalLlmHost(value.LLM_BASE_URL)) {
    warnings.push('LLM_PROVIDER=openai without LLM_API_KEY: analysis calls will fail with 502');
  }

  if (problems.length > 0) throw new ConfigError(problems);

  return {
    nodeEnv: value.NODE_ENV,
    port: value.PORT,
    databasePath: value.DATABASE_PATH,
    logLevel: value.LOG_LEVEL,
    googleClientId: value.GOOGLE_WEB_CLIENT_ID,
    googleJwksUrl: value.GOOGLE_JWKS_URL,
    devFakeTokens: value.AUTH_DEV_FAKE_TOKENS,
    sessionPepper,
    sessionTtlDays: value.SESSION_TTL_DAYS,
    dataKey: dataKey as Buffer,
    dataKeyVersion: value.DATA_KEY_VERSION,
    llm: {
      provider: value.LLM_PROVIDER,
      baseUrl: value.LLM_BASE_URL,
      apiKey: value.LLM_API_KEY,
      model: value.LLM_MODEL,
      maxOutputTokens: value.LLM_MAX_OUTPUT_TOKENS,
      timeoutMs: value.LLM_TIMEOUT_MS,
      fakeScenario: value.FAKE_LLM_SCENARIO,
    },
    limits: {
      perHour: value.RATE_LIMIT_ANALYSES_PER_HOUR,
      perDay: value.RATE_LIMIT_ANALYSES_PER_DAY,
      dailyCeilingUsd: value.DAILY_AI_CEILING_USD,
      promptPricePer1k: value.PRICE_PROMPT_PER_1K_USD,
      completionPricePer1k: value.PRICE_COMPLETION_PER_1K_USD,
    },
    internalSweepToken: value.INTERNAL_SWEEP_TOKEN,
    isProduction,
    warnings,
  };
}

/** A fresh, valid base64 key — used by the README and by tests that need a real key. */
export function generateDataKey(): string {
  return randomBytes(32).toString('base64');
}
