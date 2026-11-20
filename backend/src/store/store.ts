import type { Client, InArgs, Row } from '@libsql/client';
import { aadFor, openJson, sealJson } from '../crypto.js';
import type { AnalysisResponse } from '../domain/analysis.js';
import type { AggregatePayload, PlanSummary } from '../domain/payload.js';

/**
 * All persistence (contract §6), on libSQL so local `file:` and Turso share one code path.
 *
 * Rules enforced here rather than in routes, because a missed `owner_id` predicate is the failure
 * mode that matters most (FR-02/NFR-17):
 * - every read and write takes `ownerId` as its first argument and filters on it;
 * - sealed fields (summary payload, analysis, budget plan) are encrypted per row with an AAD that
 *   includes the owner, so a copied ciphertext cannot be opened as another owner's data;
 * - the two places where a check and a write must not interleave — the deletion block and the quota
 *   reservation — run inside an explicit `client.transaction('write')`.
 *
 * Every method is async now (libSQL is async even in embedded mode).
 */

export type SealedPurpose = 'summary' | 'analysis' | 'budget_plan';

/** Anything that can execute a statement: the client or a transaction. */
interface Executor {
  execute(statement: { sql: string; args?: InArgs } | string): Promise<{ rows: Row[]; rowsAffected: number }>;
}

export interface SessionRecord {
  readonly id: string;
  readonly ownerId: string;
  readonly createdAt: string;
  readonly expiresAt: string;
  readonly revokedAt: string | null;
}

export interface AccountRecord {
  readonly ownerId: string;
  readonly email: string;
  readonly displayName: string | null;
  readonly salt: Buffer;
  readonly passwordHash: Buffer;
  readonly kdf: string;
  readonly createdAt: string;
}

export interface LoginFailureState {
  readonly failedCount: number;
  readonly windowStartMs: number;
}

export interface ConsentRecord {
  readonly id: string;
  readonly purpose: 'GMAIL_READ' | 'AI_PROCESSING';
  readonly decision: 'GRANTED' | 'DECLINED' | 'WITHDRAWN';
  readonly noticeVersion: string;
  readonly createdAt: string;
}

export interface SummaryRecord {
  readonly id: string;
  readonly monthId: string;
  readonly inputRevision: number;
  readonly coverage: string;
  readonly payload: AggregatePayload;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface AnalysisRecord {
  readonly id: string;
  readonly monthId: string;
  readonly summaryId: string;
  readonly inputRevision: number;
  readonly consentId: string | null;
  readonly provider: string;
  readonly model: string;
  readonly promptVersion: string;
  readonly analysis: AnalysisResponse;
  readonly status: string;
  readonly issues: readonly string[];
  readonly regenerationCount: number;
  readonly promptTokens: number | null;
  readonly completionTokens: number | null;
  readonly createdAt: string;
}

export interface BudgetPlanRecord {
  readonly id: string;
  readonly monthId: string;
  readonly plan: PlanSummary;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export type DeletionState = 'PENDING' | 'COMPLETED';

export interface DeletionRequestRecord {
  readonly id: string;
  readonly state: DeletionState;
  readonly slaDays: number;
  readonly requestedAt: string;
  readonly completedAt: string | null;
  readonly aggregatesDeleted: number;
}

export interface UsageRecord {
  readonly analyses: number;
  readonly tokensPrompt: number;
  readonly tokensCompletion: number;
  readonly costUsd: number;
}

export interface UsageDelta {
  readonly analyses?: number;
  readonly tokensPrompt?: number;
  readonly tokensCompletion?: number;
  readonly costUsd?: number;
}

export interface QuotaLimits {
  readonly perHour: number;
  readonly perDay: number;
  readonly dailyCeilingUsd: number;
}

export type QuotaReservation =
  | { readonly ok: true }
  | { readonly ok: false; readonly reason: 'rate_limited' | 'daily_ceiling_reached' };

interface SealedColumns {
  readonly ciphertext: ArrayBuffer | Uint8Array;
  readonly iv: ArrayBuffer | Uint8Array;
  readonly tag: ArrayBuffer | Uint8Array;
  readonly key_version: number;
}

export interface StoreOptions {
  readonly client: Client;
  readonly dataKey: Buffer;
  readonly keyVersion: number;
}

/** Jakarta day/hour buckets for rate windows (Asia/Jakarta is UTC+7, no DST). */
export function jakartaBuckets(now: Date): { readonly day: string; readonly hour: string } {
  const shifted = new Date(now.getTime() + 7 * 60 * 60 * 1000).toISOString();
  return { day: shifted.slice(0, 10), hour: shifted.slice(0, 13) };
}

/** `297935000` → number, preserving 64-bit safety for the amounts we store. */
function numeric(value: unknown): number {
  if (typeof value === 'number') return value;
  if (typeof value === 'bigint') return Number(value);
  return Number(value ?? 0);
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : String(value ?? '');
}

function blob(value: unknown): Buffer {
  if (value instanceof Uint8Array) return Buffer.from(value);
  if (value instanceof ArrayBuffer) return Buffer.from(value);
  if (typeof value === 'string') return Buffer.from(value, 'utf8');
  return Buffer.alloc(0);
}

export class Store {
  private readonly client: Client;
  private readonly dataKey: Buffer;
  private readonly keyVersion: number;

  constructor(options: StoreOptions) {
    this.client = options.client;
    this.dataKey = options.dataKey;
    this.keyVersion = options.keyVersion;
  }

  async close(): Promise<void> {
    this.client.close();
  }

  // ---------------------------------------------------------------------------------------------
  // Users (PAM accounts, FR-51)
  // ---------------------------------------------------------------------------------------------

  async createAccount(input: {
    ownerId: string;
    email: string;
    displayName: string | null;
    salt: Buffer;
    passwordHash: Buffer;
    kdf: string;
    at: string;
  }): Promise<AccountRecord | null> {
    if (await this.findAccountByEmail(input.email)) return null;
    try {
      await this.run(
        `INSERT INTO users (
           owner_id, email, display_name, salt, password_hash, kdf, created_at, last_seen_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          input.ownerId,
          input.email,
          input.displayName,
          input.salt,
          input.passwordHash,
          input.kdf,
          input.at,
          input.at,
        ],
      );
    } catch {
      // The unique index caught a concurrent registration: still a duplicate, never a 500.
      return null;
    }
    return this.findAccountByEmail(input.email);
  }

  async findAccountByEmail(email: string): Promise<AccountRecord | null> {
    const row = await this.first('SELECT * FROM users WHERE email = ?', [email]);
    if (!row) return null;
    return {
      ownerId: text(row.owner_id),
      email: text(row.email),
      displayName: row.display_name === null ? null : text(row.display_name),
      salt: blob(row.salt),
      passwordHash: blob(row.password_hash),
      kdf: text(row.kdf),
      createdAt: text(row.created_at),
    };
  }

  async getUser(ownerId: string): Promise<{ displayName: string | null; email: string | null } | null> {
    const row = await this.first('SELECT display_name, email FROM users WHERE owner_id = ?', [ownerId]);
    if (!row) return null;
    return {
      displayName: row.display_name === null ? null : text(row.display_name),
      email: row.email === null ? null : text(row.email),
    };
  }

  async touchAccount(ownerId: string, at: string): Promise<void> {
    await this.run('UPDATE users SET last_seen_at = ? WHERE owner_id = ?', [at, ownerId]);
  }

  // ---------------------------------------------------------------------------------------------
  // Login throttling (FR-53). The key is already a hash; nothing identifying is stored here.
  // ---------------------------------------------------------------------------------------------

  async loginFailureState(attemptKey: string, now: Date, windowMs: number): Promise<LoginFailureState> {
    const row = await this.first(
      'SELECT failed_count, window_start FROM login_attempts WHERE attempt_key = ?',
      [attemptKey],
    );
    if (!row) return { failedCount: 0, windowStartMs: now.getTime() };
    const windowStartMs = Date.parse(text(row.window_start));
    if (!Number.isFinite(windowStartMs) || now.getTime() - windowStartMs >= windowMs) {
      // An expired window starts over rather than carrying old failures forward.
      return { failedCount: 0, windowStartMs: now.getTime() };
    }
    return { failedCount: numeric(row.failed_count), windowStartMs };
  }

  async registerLoginFailure(attemptKey: string, now: Date, windowMs: number): Promise<number> {
    const state = await this.loginFailureState(attemptKey, now, windowMs);
    const next = state.failedCount + 1;
    const windowStart = new Date(state.failedCount === 0 ? now.getTime() : state.windowStartMs);
    await this.run(
      `INSERT INTO login_attempts (attempt_key, failed_count, window_start, updated_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT(attempt_key) DO UPDATE SET
         failed_count = excluded.failed_count,
         window_start = excluded.window_start,
         updated_at = excluded.updated_at`,
      [attemptKey, next, windowStart.toISOString(), now.toISOString()],
    );
    return next;
  }

  async deleteLoginFailures(attemptKey: string): Promise<void> {
    await this.run('DELETE FROM login_attempts WHERE attempt_key = ?', [attemptKey]);
  }

  // ---------------------------------------------------------------------------------------------
  // Sessions
  // ---------------------------------------------------------------------------------------------

  async createSession(input: {
    ownerId: string;
    tokenHash: string;
    createdAt: string;
    expiresAt: string;
  }): Promise<string> {
    const id = crypto.randomUUID();
    await this.run(
      `INSERT INTO sessions (id, owner_id, token_hash, created_at, expires_at)
       VALUES (?, ?, ?, ?, ?)`,
      [id, input.ownerId, input.tokenHash, input.createdAt, input.expiresAt],
    );
    return id;
  }

  async findSessionByHash(tokenHash: string): Promise<SessionRecord | null> {
    const row = await this.first('SELECT * FROM sessions WHERE token_hash = ?', [tokenHash]);
    if (!row) return null;
    return {
      id: text(row.id),
      ownerId: text(row.owner_id),
      createdAt: text(row.created_at),
      expiresAt: text(row.expires_at),
      revokedAt: row.revoked_at === null ? null : text(row.revoked_at),
    };
  }

  async findSession(ownerId: string, sessionId: string): Promise<{ id: string; revokedAt: string | null } | null> {
    const row = await this.first('SELECT id, revoked_at FROM sessions WHERE owner_id = ? AND id = ?', [
      ownerId,
      sessionId,
    ]);
    if (!row) return null;
    return { id: text(row.id), revokedAt: row.revoked_at === null ? null : text(row.revoked_at) };
  }

  async revokeSession(ownerId: string, sessionId: string, at: string): Promise<boolean> {
    const changes = await this.run(
      'UPDATE sessions SET revoked_at = ? WHERE owner_id = ? AND id = ? AND revoked_at IS NULL',
      [at, ownerId, sessionId],
    );
    return changes > 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Consents (append-only ledger, FR-03/FR-49)
  // ---------------------------------------------------------------------------------------------

  async appendConsent(input: {
    ownerId: string;
    purpose: ConsentRecord['purpose'];
    decision: ConsentRecord['decision'];
    noticeVersion: string;
    at: string;
  }): Promise<ConsentRecord> {
    const id = crypto.randomUUID();
    await this.run(
      `INSERT INTO consents (id, owner_id, purpose, decision, notice_version, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
      [id, input.ownerId, input.purpose, input.decision, input.noticeVersion, input.at],
    );
    return {
      id,
      purpose: input.purpose,
      decision: input.decision,
      noticeVersion: input.noticeVersion,
      createdAt: input.at,
    };
  }

  async latestConsent(ownerId: string, purpose: ConsentRecord['purpose']): Promise<ConsentRecord | null> {
    const row = await this.first(
      `SELECT id, purpose, decision, notice_version, created_at
       FROM consents WHERE owner_id = ? AND purpose = ?
       ORDER BY created_at DESC, rowid DESC LIMIT 1`,
      [ownerId, purpose],
    );
    return row ? consentOf(row) : null;
  }

  async listConsents(ownerId: string, limit = 100): Promise<ConsentRecord[]> {
    const rows = await this.rows(
      `SELECT id, purpose, decision, notice_version, created_at
       FROM consents WHERE owner_id = ?
       ORDER BY created_at DESC, rowid DESC LIMIT ?`,
      [ownerId, limit],
    );
    return rows.map(consentOf);
  }

  // ---------------------------------------------------------------------------------------------
  // Summaries (encrypted aggregate payload)
  // ---------------------------------------------------------------------------------------------

  async upsertSummary(input: {
    ownerId: string;
    monthId: string;
    inputRevision: number;
    payload: AggregatePayload;
    at: string;
  }): Promise<SummaryRecord> {
    const aad = aadFor(input.ownerId, 'summary', this.keyVersion);
    const sealed = sealJson(input.payload, this.dataKey, this.keyVersion, aad);
    const existing = await this.getSummary(input.ownerId, input.monthId);
    const id = existing?.id ?? crypto.randomUUID();
    await this.run(
      `INSERT INTO summaries (
         id, owner_id, month_id, input_revision, coverage,
         ciphertext, iv, tag, key_version, created_at, updated_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(owner_id, month_id) DO UPDATE SET
         input_revision = excluded.input_revision,
         coverage = excluded.coverage,
         ciphertext = excluded.ciphertext,
         iv = excluded.iv,
         tag = excluded.tag,
         key_version = excluded.key_version,
         updated_at = excluded.updated_at`,
      [
        id,
        input.ownerId,
        input.monthId,
        input.inputRevision,
        input.payload.coverage,
        sealed.ciphertext,
        sealed.iv,
        sealed.tag,
        sealed.keyVersion,
        existing?.createdAt ?? input.at,
        input.at,
      ],
    );
    return {
      id,
      monthId: input.monthId,
      inputRevision: input.inputRevision,
      coverage: input.payload.coverage,
      payload: input.payload,
      createdAt: existing?.createdAt ?? input.at,
      updatedAt: input.at,
    };
  }

  async getSummary(ownerId: string, monthId: string): Promise<SummaryRecord | null> {
    const row = await this.first(
      `SELECT * FROM summaries WHERE owner_id = ? AND month_id = ?`,
      [ownerId, monthId],
    );
    if (!row) return null;
    const payload = this.open<AggregatePayload>(row, ownerId, 'summary');
    return {
      id: text(row.id),
      monthId: text(row.month_id),
      inputRevision: numeric(row.input_revision),
      coverage: text(row.coverage),
      payload,
      createdAt: text(row.created_at),
      updatedAt: text(row.updated_at),
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Analyses
  // ---------------------------------------------------------------------------------------------

  /**
   * Inserts the analysis only if no deletion request became PENDING while the provider was working.
   *
   * The check and the insert share one write transaction: a deletion POST that lands during the
   * (slow) provider call cannot be overtaken by an analysis write (contract §4.3). Returns null when
   * the write was refused, which the route turns into `409 deletion_in_progress`.
   */
  async insertAnalysisUnlessDeletionPending(input: {
    ownerId: string;
    monthId: string;
    summaryId: string;
    inputRevision: number;
    consentId: string | null;
    provider: string;
    model: string;
    promptVersion: string;
    analysis: AnalysisResponse;
    issues: readonly string[];
    regenerationCount: number;
    idempotencyKey: string;
    requestHash: string;
    at: string;
  }): Promise<AnalysisRecord | null> {
    const aad = aadFor(input.ownerId, 'analysis', this.keyVersion);
    const sealed = sealJson(input.analysis, this.dataKey, this.keyVersion, aad);
    const id = crypto.randomUUID();

    const transaction = await this.client.transaction('write');
    try {
      const pending = await transaction.execute({
        sql: "SELECT 1 AS present FROM deletion_requests WHERE owner_id = ? AND state = 'PENDING' LIMIT 1",
        args: [input.ownerId],
      });
      if (pending.rows.length > 0) {
        await transaction.rollback();
        return null;
      }
      await transaction.execute({
        sql: `INSERT INTO analyses (
           id, owner_id, month_id, summary_id, input_revision, consent_id,
           provider, model, prompt_version, ciphertext, iv, tag, key_version,
           status, validation_issues, regeneration_count, prompt_tokens, completion_tokens,
           idempotency_key, request_hash, created_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        args: [
          id,
          input.ownerId,
          input.monthId,
          input.summaryId,
          input.inputRevision,
          input.consentId,
          input.provider,
          input.model,
          input.promptVersion,
          sealed.ciphertext,
          sealed.iv,
          sealed.tag,
          sealed.keyVersion,
          'AVAILABLE',
          JSON.stringify(input.issues),
          input.regenerationCount,
          input.analysis.promptTokens ?? null,
          input.analysis.completionTokens ?? null,
          input.idempotencyKey,
          input.requestHash,
          input.at,
        ],
      });
      await transaction.commit();
    } catch (error) {
      await transaction.rollback();
      throw error;
    }

    const stored = await this.getAnalysisById(input.ownerId, id);
    if (!stored) throw new Error('analysis row vanished after insert');
    return stored;
  }

  async findAnalysisByCacheKey(input: {
    ownerId: string;
    monthId: string;
    inputRevision: number;
    promptVersion: string;
    provider: string;
    model: string;
  }): Promise<AnalysisRecord | null> {
    const row = await this.first(
      `SELECT id FROM analyses
       WHERE owner_id = ? AND month_id = ? AND input_revision = ?
         AND prompt_version = ? AND provider = ? AND model = ?
       LIMIT 1`,
      [
        input.ownerId,
        input.monthId,
        input.inputRevision,
        input.promptVersion,
        input.provider,
        input.model,
      ],
    );
    return row ? this.getAnalysisById(input.ownerId, text(row.id)) : null;
  }

  async findAnalysisByIdempotencyKey(
    ownerId: string,
    idempotencyKey: string,
  ): Promise<{ analysis: AnalysisRecord; requestHash: string } | null> {
    const row = await this.first(
      'SELECT id, request_hash FROM analyses WHERE owner_id = ? AND idempotency_key = ?',
      [ownerId, idempotencyKey],
    );
    if (!row) return null;
    const analysis = await this.getAnalysisById(ownerId, text(row.id));
    return analysis ? { analysis, requestHash: text(row.request_hash) } : null;
  }

  async latestAnalysis(ownerId: string, monthId: string): Promise<AnalysisRecord | null> {
    const row = await this.first(
      `SELECT id FROM analyses WHERE owner_id = ? AND month_id = ?
       ORDER BY created_at DESC, rowid DESC LIMIT 1`,
      [ownerId, monthId],
    );
    return row ? this.getAnalysisById(ownerId, text(row.id)) : null;
  }

  async getAnalysisById(ownerId: string, id: string): Promise<AnalysisRecord | null> {
    const row = await this.first('SELECT * FROM analyses WHERE owner_id = ? AND id = ?', [ownerId, id]);
    if (!row) return null;
    const analysis = this.open<AnalysisResponse>(row, ownerId, 'analysis');
    return {
      id: text(row.id),
      monthId: text(row.month_id),
      summaryId: text(row.summary_id),
      inputRevision: numeric(row.input_revision),
      consentId: row.consent_id === null ? null : text(row.consent_id),
      provider: text(row.provider),
      model: text(row.model),
      promptVersion: text(row.prompt_version),
      analysis,
      status: text(row.status),
      issues: JSON.parse(text(row.validation_issues)) as string[],
      regenerationCount: numeric(row.regeneration_count),
      promptTokens: row.prompt_tokens === null ? null : numeric(row.prompt_tokens),
      completionTokens: row.completion_tokens === null ? null : numeric(row.completion_tokens),
      createdAt: text(row.created_at),
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Budget plans
  // ---------------------------------------------------------------------------------------------

  async upsertBudgetPlan(input: {
    ownerId: string;
    monthId: string;
    plan: PlanSummary;
    at: string;
  }): Promise<BudgetPlanRecord> {
    const aad = aadFor(input.ownerId, 'budget_plan', this.keyVersion);
    const sealed = sealJson(input.plan, this.dataKey, this.keyVersion, aad);
    const existing = await this.getBudgetPlan(input.ownerId, input.monthId);
    const id = existing?.id ?? crypto.randomUUID();
    await this.run(
      `INSERT INTO budget_plans (
         id, owner_id, month_id, ciphertext, iv, tag, key_version, created_at, updated_at
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(owner_id, month_id) DO UPDATE SET
         ciphertext = excluded.ciphertext,
         iv = excluded.iv,
         tag = excluded.tag,
         key_version = excluded.key_version,
         updated_at = excluded.updated_at`,
      [
        id,
        input.ownerId,
        input.monthId,
        sealed.ciphertext,
        sealed.iv,
        sealed.tag,
        sealed.keyVersion,
        existing?.createdAt ?? input.at,
        input.at,
      ],
    );
    return {
      id,
      monthId: input.monthId,
      plan: input.plan,
      createdAt: existing?.createdAt ?? input.at,
      updatedAt: input.at,
    };
  }

  async getBudgetPlan(ownerId: string, monthId: string): Promise<BudgetPlanRecord | null> {
    const row = await this.first('SELECT * FROM budget_plans WHERE owner_id = ? AND month_id = ?', [
      ownerId,
      monthId,
    ]);
    if (!row) return null;
    return {
      id: text(row.id),
      monthId: text(row.month_id),
      plan: this.open<PlanSummary>(row, ownerId, 'budget_plan'),
      createdAt: text(row.created_at),
      updatedAt: text(row.updated_at),
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Deletion (FR-47/FR-48)
  // ---------------------------------------------------------------------------------------------

  async createDeletionRequest(input: {
    ownerId: string;
    slaDays: number;
    at: string;
  }): Promise<DeletionRequestRecord> {
    const id = crypto.randomUUID();
    await this.run(
      `INSERT INTO deletion_requests (id, owner_id, state, sla_days, requested_at, created_at)
       VALUES (?, ?, 'PENDING', ?, ?, ?)`,
      [id, input.ownerId, input.slaDays, input.at, input.at],
    );
    return {
      id,
      state: 'PENDING',
      slaDays: input.slaDays,
      requestedAt: input.at,
      completedAt: null,
      aggregatesDeleted: 0,
    };
  }

  async getDeletionRequest(ownerId: string, id: string): Promise<DeletionRequestRecord | null> {
    const row = await this.first(
      'SELECT * FROM deletion_requests WHERE owner_id = ? AND id = ?',
      [ownerId, id],
    );
    return row ? deletionOf(row) : null;
  }

  async hasPendingDeletion(ownerId: string): Promise<boolean> {
    const row = await this.first(
      "SELECT 1 AS present FROM deletion_requests WHERE owner_id = ? AND state = 'PENDING' LIMIT 1",
      [ownerId],
    );
    return row !== undefined;
  }

  async pendingDeletionForOwner(ownerId: string): Promise<DeletionRequestRecord | null> {
    const row = await this.first(
      `SELECT id FROM deletion_requests WHERE owner_id = ? AND state = 'PENDING'
       ORDER BY requested_at DESC LIMIT 1`,
      [ownerId],
    );
    return row ? this.getDeletionRequest(ownerId, text(row.id)) : null;
  }

  async pendingDeletionOwnerIds(): Promise<string[]> {
    const rows = await this.rows("SELECT DISTINCT owner_id FROM deletion_requests WHERE state = 'PENDING'");
    return rows.map((row) => text(row.owner_id));
  }

  /**
   * One transaction per owner: the aggregate deletes and the request completion are atomic, so a
   * crash can never leave a request half-applied.
   */
  async sweepDeletions(at: string): Promise<number> {
    let completed = 0;
    for (const ownerId of await this.pendingDeletionOwnerIds()) {
      const pendingRows = await this.rows(
        "SELECT id FROM deletion_requests WHERE owner_id = ? AND state = 'PENDING' ORDER BY requested_at",
        [ownerId],
      );
      const transaction = await this.client.transaction('write');
      try {
        let deleted = 0;
        for (const table of ['summaries', 'analyses', 'budget_plans'] as const) {
          const result = await transaction.execute({
            sql: `DELETE FROM ${table} WHERE owner_id = ?`,
            args: [ownerId],
          });
          deleted += Number(result.rowsAffected ?? 0);
        }
        for (const row of pendingRows) {
          await transaction.execute({
            sql: `UPDATE deletion_requests
                  SET state = 'COMPLETED', completed_at = ?, aggregates_deleted = ?
                  WHERE id = ? AND state = 'PENDING'`,
            args: [at, deleted, text(row.id)],
          });
          completed += 1;
        }
        await transaction.commit();
      } catch (error) {
        await transaction.rollback();
        throw error;
      }
    }
    return completed;
  }

  // ---------------------------------------------------------------------------------------------
  // Usage counters (FR-50)
  // ---------------------------------------------------------------------------------------------

  /**
   * Reserves one analysis against the per-user hour/day limits and the global cost ceiling.
   *
   * Read-then-increment happens in a single write transaction, so N concurrent requests against a
   * limit of M admit exactly M. Cache hits never come here: they are served before the reservation,
   * which is why a rate-limited user can still read a stored analysis through GET.
   */
  async tryReserveAnalysis(ownerId: string, now: Date, limits: QuotaLimits): Promise<QuotaReservation> {
    const buckets = jakartaBuckets(now);
    const transaction = await this.client.transaction('write');
    try {
      const hour = await usageIn(transaction, ownerId, `hour:${buckets.hour}`);
      const day = await usageIn(transaction, ownerId, `day:${buckets.day}`);
      const global = await usageIn(transaction, GLOBAL_SCOPE, `day:${buckets.day}`);
      if (hour.analyses >= limits.perHour) {
        await transaction.rollback();
        return { ok: false, reason: 'rate_limited' };
      }
      if (day.analyses >= limits.perDay) {
        await transaction.rollback();
        return { ok: false, reason: 'rate_limited' };
      }
      if (global.costUsd >= limits.dailyCeilingUsd) {
        await transaction.rollback();
        return { ok: false, reason: 'daily_ceiling_reached' };
      }
      for (const [scope, bucket] of [
        [ownerId, `hour:${buckets.hour}`],
        [ownerId, `day:${buckets.day}`],
      ] as const) {
        await bumpUsageOn(transaction, scope, bucket, { analyses: 1 });
      }
      await transaction.commit();
      return { ok: true };
    } catch (error) {
      await transaction.rollback();
      throw error;
    }
  }

  /** Adds tokens/cost after the provider call; the analysis itself was reserved earlier. */
  async addUsage(
    ownerId: string,
    now: Date,
    tokens: { promptTokens: number; completionTokens: number; costUsd: number },
  ): Promise<void> {
    const buckets = jakartaBuckets(now);
    const delta = {
      tokensPrompt: tokens.promptTokens,
      tokensCompletion: tokens.completionTokens,
      costUsd: tokens.costUsd,
    };
    const transaction = await this.client.transaction('write');
    try {
      await bumpUsageOn(transaction, ownerId, `hour:${buckets.hour}`, delta);
      await bumpUsageOn(transaction, ownerId, `day:${buckets.day}`, delta);
      await bumpUsageOn(transaction, GLOBAL_SCOPE, `day:${buckets.day}`, delta);
      await transaction.commit();
    } catch (error) {
      await transaction.rollback();
      throw error;
    }
  }

  async getUsage(scope: string, bucket: string): Promise<UsageRecord> {
    return usageIn(this.client, scope, bucket);
  }

  /** Last activity timestamps for `/v1/me` (FR-49). */
  async activity(ownerId: string): Promise<{ lastSummaryAt: string | null; lastAnalysisAt: string | null }> {
    const summary = await this.first('SELECT MAX(updated_at) AS at FROM summaries WHERE owner_id = ?', [ownerId]);
    const analysis = await this.first('SELECT MAX(created_at) AS at FROM analyses WHERE owner_id = ?', [ownerId]);
    return {
      lastSummaryAt: summary?.at === null || summary?.at === undefined ? null : text(summary.at),
      lastAnalysisAt: analysis?.at === null || analysis?.at === undefined ? null : text(analysis.at),
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------------------------

  private async run(sql: string, args: InArgs = []): Promise<number> {
    const result = await this.client.execute({ sql, args });
    return Number(result.rowsAffected ?? 0);
  }

  private async first(sql: string, args: InArgs = []): Promise<Row | undefined> {
    const result = await this.client.execute({ sql, args });
    return result.rows[0];
  }

  private async rows(sql: string, args: InArgs = []): Promise<Row[]> {
    const result = await this.client.execute({ sql, args });
    return result.rows;
  }

  private open<T>(row: Row, ownerId: string, purpose: SealedPurpose): T {
    return openJson<T>(
      {
        ciphertext: blob(row.ciphertext),
        iv: blob(row.iv),
        tag: blob(row.tag),
        keyVersion: numeric(row.key_version),
      },
      this.dataKey,
      aadFor(ownerId, purpose, numeric(row.key_version)),
    );
  }
}

/** The global bucket for the daily cost ceiling (one row, `scope = '*'`). */
export const GLOBAL_SCOPE = '*';

async function usageIn(db: Executor, scope: string, bucket: string): Promise<UsageRecord> {
  const result = await db.execute({
    sql: 'SELECT analyses, tokens_prompt, tokens_completion, cost_usd FROM usage_counters WHERE scope = ? AND bucket = ?',
    args: [scope, bucket],
  });
  const row = result.rows[0];
  return row
    ? {
        analyses: numeric(row.analyses),
        tokensPrompt: numeric(row.tokens_prompt),
        tokensCompletion: numeric(row.tokens_completion),
        costUsd: numeric(row.cost_usd),
      }
    : { analyses: 0, tokensPrompt: 0, tokensCompletion: 0, costUsd: 0 };
}

async function bumpUsageOn(
  db: Executor,
  scope: string,
  bucket: string,
  delta: UsageDelta,
): Promise<void> {
  await db.execute({
    sql: `INSERT INTO usage_counters (scope, bucket, analyses, tokens_prompt, tokens_completion, cost_usd)
          VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT(scope, bucket) DO UPDATE SET
            analyses = usage_counters.analyses + excluded.analyses,
            tokens_prompt = usage_counters.tokens_prompt + excluded.tokens_prompt,
            tokens_completion = usage_counters.tokens_completion + excluded.tokens_completion,
            cost_usd = usage_counters.cost_usd + excluded.cost_usd`,
    args: [
      scope,
      bucket,
      delta.analyses ?? 0,
      delta.tokensPrompt ?? 0,
      delta.tokensCompletion ?? 0,
      delta.costUsd ?? 0,
    ],
  });
}

function consentOf(row: Row): ConsentRecord {
  return {
    id: text(row.id),
    purpose: text(row.purpose) as ConsentRecord['purpose'],
    decision: text(row.decision) as ConsentRecord['decision'],
    noticeVersion: text(row.notice_version),
    createdAt: text(row.created_at),
  };
}

function deletionOf(row: Row): DeletionRequestRecord {
  return {
    id: text(row.id),
    state: text(row.state) as DeletionState,
    slaDays: numeric(row.sla_days),
    requestedAt: text(row.requested_at),
    completedAt: row.completed_at === null ? null : text(row.completed_at),
    aggregatesDeleted: numeric(row.aggregates_deleted),
  };
}
