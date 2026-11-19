import { once } from 'node:events';
import type { AddressInfo } from 'node:net';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { Express } from 'express';
import { createLocalJWKSet, generateKeyPair, exportJWK, SignJWT, type JWK } from 'jose';
import { createApp } from '../src/app.js';
import { createVerifierForConfig, createGoogleVerifier, type GoogleVerifier } from '../src/auth/google.js';
import { loadConfig, type AppConfig } from '../src/config.js';
import type { AggregatePayload } from '../src/domain/payload.js';
import { FakeProvider, type FakeScenario } from '../src/llm/fake.js';
import type { AnalysisProvider } from '../src/llm/provider.js';
import { createJsonLogger } from '../src/logger.js';
import { openDatabase } from '../src/store/db.js';
import { Store } from '../src/store/store.js';

/**
 * Test harness: the whole HTTP surface over an in-memory SQLite and the deterministic provider.
 * No network, no real Google key, no real LLM.
 */
export interface Harness {
  readonly app: Express;
  readonly baseUrl: string;
  readonly store: Store;
  readonly config: AppConfig;
  readonly provider: AnalysisProvider;
  readonly logs: string[];
  close(): Promise<void>;
}

export interface HarnessOptions {
  readonly provider?: AnalysisProvider;
  readonly verifier?: GoogleVerifier;
  readonly env?: Record<string, string>;
  readonly databasePath?: string;
  readonly now?: () => Date;
}

export function tempDatabasePath(): string {
  return join(mkdtempSync(join(tmpdir(), 'pam-backend-')), 'pam.db');
}

export async function createHarness(options: HarnessOptions = {}): Promise<Harness> {
  const config = loadConfig({
    NODE_ENV: 'test',
    DATABASE_PATH: options.databasePath ?? ':memory:',
    GOOGLE_WEB_CLIENT_ID: 'test-client-id.apps.googleusercontent.com',
    ...options.env,
  } as NodeJS.ProcessEnv);

  const db = openDatabase(config.databasePath);
  const store = new Store({ db, dataKey: config.dataKey, keyVersion: config.dataKeyVersion });
  const logs: string[] = [];
  const logger = createJsonLogger({ level: 'debug', sink: (line) => logs.push(line) });
  const provider = options.provider ?? new FakeProvider('valid');
  const verifier =
    options.verifier ??
    createVerifierForConfig({
      clientId: config.googleClientId,
      jwksUrl: config.googleJwksUrl,
      devFakeTokens: true,
      nodeEnv: 'development',
    });

  const app = createApp({
    config,
    store,
    logger,
    provider,
    verifier,
    now: options.now ?? (() => new Date()),
  });

  const server = app.listen(0);
  await once(server, 'listening');
  const address = server.address() as AddressInfo;

  return {
    app,
    baseUrl: `http://127.0.0.1:${address.port}`,
    store,
    config,
    provider,
    logs,
    async close() {
      await new Promise<void>((resolve) => server.close(() => resolve()));
      store.close();
    },
  };
}

export interface ApiResult {
  readonly status: number;
  readonly json: any;
  readonly text: string;
  readonly headers: Headers;
}

export async function api(
  harness: Harness,
  method: string,
  path: string,
  options: { token?: string; body?: unknown; headers?: Record<string, string> } = {},
): Promise<ApiResult> {
  const headers: Record<string, string> = { ...options.headers };
  if (options.token) headers.authorization = `Bearer ${options.token}`;
  if (options.body !== undefined) headers['content-type'] = 'application/json';
  const response = await fetch(`${harness.baseUrl}${path}`, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  const text = await response.text();
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: response.status, json, text, headers: response.headers };
}

export async function login(
  harness: Harness,
  sub = 'google-sub-alice',
  name = 'Alice',
): Promise<string> {
  const result = await api(harness, 'POST', '/v1/sessions', {
    body: { idToken: `dev:${sub}:${name}` },
  });
  if (result.status !== 201) {
    throw new Error(`login failed: ${result.status} ${result.text}`);
  }
  return (result.json as { accessToken: string }).accessToken;
}

export async function grantConsent(
  harness: Harness,
  token: string,
  decision: 'GRANTED' | 'DECLINED' | 'WITHDRAWN' = 'GRANTED',
  purpose: 'AI_PROCESSING' | 'GMAIL_READ' = 'AI_PROCESSING',
): Promise<void> {
  const result = await api(harness, 'POST', '/v1/consents', {
    token,
    body: { purpose, decision, noticeVersion: 'v1' },
  });
  if (result.status !== 201) throw new Error(`consent failed: ${result.status} ${result.text}`);
}

/** February 2026 demo numbers, in minor units (Rp × 100). */
export function samplePayload(overrides: Partial<AggregatePayload> = {}): AggregatePayload {
  return {
    schemaVersion: 1,
    monthId: '2026-02',
    coverage: 'COMPLETE',
    grossInflowMinor: 585_000_000,
    grossOutflowMinor: 287_065_000,
    netCashFlowMinor: 297_935_000,
    classifiedIncomeMinor: 575_000_000,
    classifiedExpenseMinor: 249_565_000,
    transactionCount: 18,
    uncategorizedCount: 1,
    manualEntryCount: 2,
    nonIdrExcludedCount: 1,
    categoryTotals: [
      { categoryCode: 'housing', amountMinor: 90_000_000, transactionCount: 2 },
      { categoryCode: 'food', amountMinor: 25_625_000, transactionCount: 4 },
      { categoryCode: 'transport', amountMinor: 8_550_000, transactionCount: 3 },
      { categoryCode: 'utilities', amountMinor: 40_000_000, transactionCount: 2 },
      { categoryCode: 'shopping', amountMinor: 55_775_000, transactionCount: 2 },
      { categoryCode: 'health', amountMinor: 8_990_000, transactionCount: 1 },
      { categoryCode: 'education', amountMinor: 18_900_000, transactionCount: 1 },
    ],
    priorMonth: { monthId: '2026-01', expenseMinor: 180_000_000, incomeMinor: 0 },
    plan: null,
    ...overrides,
  };
}

export async function analyze(
  harness: Harness,
  token: string,
  options: {
    monthId?: string;
    inputRevision?: number;
    payload?: AggregatePayload;
    idempotencyKey?: string;
  } = {},
): Promise<ApiResult> {
  const monthId = options.monthId ?? '2026-02';
  return api(harness, 'POST', '/v1/analyses', {
    token,
    headers: { 'idempotency-key': options.idempotencyKey ?? `key-${Math.random()}` },
    body: {
      monthId,
      inputRevision: options.inputRevision ?? 7,
      payload: options.payload ?? samplePayload({ monthId }),
    },
  });
}

// -------------------------------------------------------------------------------------------
// Google token fixtures for the auth tests
// -------------------------------------------------------------------------------------------

export interface GoogleFixture {
  readonly privateKey: Awaited<ReturnType<typeof generateKeyPair>>['privateKey'];
  readonly verifier: GoogleVerifier;
  readonly clientId: string;
  sign(options?: {
    kid?: string;
    issuer?: string;
    audience?: string;
    subject?: string;
    issuedAt?: number;
    expiresIn?: number;
    key?: Awaited<ReturnType<typeof generateKeyPair>>['privateKey'];
  }): Promise<string>;
}

export async function createGoogleFixture(clientId: string): Promise<GoogleFixture> {
  const { privateKey, publicKey } = await generateKeyPair('RS256');
  const { privateKey: otherKey } = await generateKeyPair('RS256');
  const jwk = (await exportJWK(publicKey)) as JWK;
  jwk.kid = 'test-key';
  jwk.alg = 'RS256';
  jwk.use = 'sig';
  const jwks = createLocalJWKSet({ keys: [jwk] });
  const verifier = createGoogleVerifier({ clientId, jwks });

  return {
    privateKey,
    verifier,
    clientId,
    async sign(options = {}) {
      const nowSeconds = Math.floor(Date.now() / 1000);
      const issuedAt = options.issuedAt ?? nowSeconds;
      const expiresIn = options.expiresIn ?? 600;
      return new SignJWT({ name: 'Alice' })
        .setProtectedHeader({ alg: 'RS256', kid: options.kid ?? 'test-key' })
        .setIssuer(options.issuer ?? 'https://accounts.google.com')
        .setAudience(options.audience ?? clientId)
        .setSubject(options.subject ?? 'google-sub-auth')
        .setIssuedAt(issuedAt)
        .setExpirationTime(issuedAt + expiresIn)
        .sign(options.key ?? privateKey);
    },
  };
}

export { FakeProvider };
export type { FakeScenario };
