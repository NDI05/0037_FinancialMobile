import type { DatabaseSync } from 'node:sqlite';
import { aadFor, openJson, sealJson } from '../crypto.js';
import type { AnalysisResponse } from '../domain/analysis.js';
import type { AggregatePayload, PlanSummary } from '../domain/payload.js';

/**
 * All persistence (contract §6).
 *
 * Rules enforced here rather than in routes, because a missed `owner_id` predicate is the failure
 * mode that matters most (FR-02/NFR-17):
 * - every read and write takes `ownerId` as its first argument and filters on it;
 * - sealed fields (summary payload, analysis, budget plan) are encrypted per row with an AAD that
 *   includes the owner, so a copied ciphertext cannot be opened as another owner's data;
 * - no method returns a row without knowing whose row it is.
 */

export type SealedPurpose = 'summary' | 'analysis' | 'budget_plan';

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

export interface SessionRecord {
  readonly id: string;
  readonly ownerId: string;
  readonly createdAt: string;
  readonly expiresAt: string;
  readonly revokedAt: string | null;
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

interface SealedColumns {
  readonly ciphertext: Uint8Array;
  readonly iv: Uint8Array;
  readonly tag: Uint8Array;
  readonly key_version: number;
}

export interface StoreOptions {
  readonly db: DatabaseSync;
  readonly dataKey: Buffer;
  readonly keyVersion: number;
}

/** Jakarta day/hour buckets for rate windows (Asia/Jakarta is UTC+7, no DST). */
export function jakartaBuckets(now: Date): { readonly day: string; readonly hour: string } {
  const shifted = new Date(now.getTime() + 7 * 60 * 60 * 1000).toISOString();
  return { day: shifted.slice(0, 10), hour: shifted.slice(0, 13) };
}

export class Store {
  private readonly db: DatabaseSync;
  private readonly dataKey: Buffer;
  private readonly keyVersion: number;

  constructor(options: StoreOptions) {
    this.db = options.db;
    this.dataKey = options.dataKey;
    this.keyVersion = options.keyVersion;
  }

  close(): void {
    this.db.close();
  }

  // ---------------------------------------------------------------------------------------------
  // Users & sessions
  // ---------------------------------------------------------------------------------------------

  /** FR-51: creates a PAM account. Returns null when the email is already registered. */
  createAccount(input: {
    ownerId: string;
    email: string;
    displayName: string | null;
    salt: Buffer;
    passwordHash: Buffer;
    kdf: string;
    at: string;
  }): AccountRecord | null {
    if (this.findAccountByEmail(input.email)) return null;
    try {
      this.db
        .prepare(
          `INSERT INTO users (
             owner_id, email, display_name, salt, password_hash, kdf, created_at, last_seen_at
           ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .run(
          input.ownerId,
          input.email,
          input.displayName,
          input.salt,
          input.passwordHash,
          input.kdf,
          input.at,
          input.at,
        );
    } catch {
      // A concurrent insert hit the unique index first: still a duplicate, never a 500.
      return null;
    }
    return this.findAccountByEmail(input.email);
  }

  findAccountByEmail(email: string): AccountRecord | null {
    const row = this.db
      .prepare(
        `SELECT owner_id, email, display_name, salt, password_hash, kdf, created_at
         FROM users WHERE email = ?`,
      )
      .get(email) as
      | {
          owner_id: string;
          email: string;
          display_name: string | null;
          salt: Uint8Array;
          password_hash: Uint8Array;
          kdf: string;
          created_at: string;
        }
      | undefined;
    if (!row) return null;
    return {
      ownerId: row.owner_id,
      email: row.email,
      displayName: row.display_name,
      salt: Buffer.from(row.salt),
      passwordHash: Buffer.from(row.password_hash),
      kdf: row.kdf,
      createdAt: row.created_at,
    };
  }

  getUser(ownerId: string): { displayName: string | null; email: string | null } | null {
    const row = this.db
      .prepare('SELECT display_name, email FROM users WHERE owner_id = ?')
      .get(ownerId) as { display_name: string | null; email: string | null } | undefined;
    return row ? { displayName: row.display_name, email: row.email } : null;
  }

  touchAccount(ownerId: string, at: string): void {
    this.db.prepare('UPDATE users SET last_seen_at = ? WHERE owner_id = ?').run(at, ownerId);
  }

  // ---------------------------------------------------------------------------------------------
  // Login throttling (FR-53). The key is already a hash; nothing identifying is stored here.
  // ---------------------------------------------------------------------------------------------

  loginFailureState(attemptKey: string, now: Date, windowMs: number): LoginFailureState {
    const row = this.db
      .prepare('SELECT failed_count, window_start FROM login_attempts WHERE attempt_key = ?')
      .get(attemptKey) as { failed_count: number; window_start: string } | undefined;
    if (!row) return { failedCount: 0, windowStartMs: now.getTime() };
    const windowStartMs = Date.parse(row.window_start);
    if (!Number.isFinite(windowStartMs) || now.getTime() - windowStartMs >= windowMs) {
      // An expired window starts over rather than carrying old failures forward.
      return { failedCount: 0, windowStartMs: now.getTime() };
    }
    return { failedCount: row.failed_count, windowStartMs };
  }

  registerLoginFailure(attemptKey: string, now: Date, windowMs: number): number {
    const state = this.loginFailureState(attemptKey, now, windowMs);
    const next = state.failedCount + 1;
    const windowStart = new Date(state.failedCount === 0 ? now.getTime() : state.windowStartMs);
    this.db
      .prepare(
        `INSERT INTO login_attempts (attempt_key, failed_count, window_start, updated_at)
         VALUES (?, ?, ?, ?)
         ON CONFLICT(attempt_key) DO UPDATE SET
           failed_count = excluded.failed_count,
           window_start = excluded.window_start,
           updated_at = excluded.updated_at`,
      )
      .run(attemptKey, next, windowStart.toISOString(), now.toISOString());
    return next;
  }

  deleteLoginFailures(attemptKey: string): void {
    this.db.prepare('DELETE FROM login_attempts WHERE attempt_key = ?').run(attemptKey);
  }

  createSession(input: {
    ownerId: string;
    tokenHash: string;
    createdAt: string;
    expiresAt: string;
  }): string {
    const id = crypto.randomUUID();
    this.db
      .prepare(
        `INSERT INTO sessions (id, owner_id, token_hash, created_at, expires_at)
         VALUES (?, ?, ?, ?, ?)`,
      )
      .run(id, input.ownerId, input.tokenHash, input.createdAt, input.expiresAt);
    return id;
  }

  findSessionByHash(tokenHash: string): SessionRecord | null {
    const row = this.db
      .prepare(
        `SELECT id, owner_id, created_at, expires_at, revoked_at
         FROM sessions WHERE token_hash = ?`,
      )
      .get(tokenHash) as
      | { id: string; owner_id: string; created_at: string; expires_at: string; revoked_at: string | null }
      | undefined;
    if (!row) return null;
    return {
      id: row.id,
      ownerId: row.owner_id,
      createdAt: row.created_at,
      expiresAt: row.expires_at,
      revokedAt: row.revoked_at,
    };
  }

  findSession(ownerId: string, sessionId: string): { id: string; revokedAt: string | null } | null {
    const row = this.db
      .prepare('SELECT id, revoked_at FROM sessions WHERE owner_id = ? AND id = ?')
      .get(ownerId, sessionId) as { id: string; revoked_at: string | null } | undefined;
    return row ? { id: row.id, revokedAt: row.revoked_at } : null;
  }

  revokeSession(ownerId: string, sessionId: string, at: string): boolean {
    const result = this.db
      .prepare('UPDATE sessions SET revoked_at = ? WHERE owner_id = ? AND id = ? AND revoked_at IS NULL')
      .run(at, ownerId, sessionId);
    return Number(result.changes) > 0;
  }

  // ---------------------------------------------------------------------------------------------
  // Consents (append-only ledger, FR-03/FR-49)
  // ---------------------------------------------------------------------------------------------

  appendConsent(input: {
    ownerId: string;
    purpose: ConsentRecord['purpose'];
    decision: ConsentRecord['decision'];
    noticeVersion: string;
    at: string;
  }): ConsentRecord {
    const id = crypto.randomUUID();
    this.db
      .prepare(
        `INSERT INTO consents (id, owner_id, purpose, decision, notice_version, created_at)
         VALUES (?, ?, ?, ?, ?, ?)`,
      )
      .run(id, input.ownerId, input.purpose, input.decision, input.noticeVersion, input.at);
    return {
      id,
      purpose: input.purpose,
      decision: input.decision,
      noticeVersion: input.noticeVersion,
      createdAt: input.at,
    };
  }

  latestConsent(ownerId: string, purpose: ConsentRecord['purpose']): ConsentRecord | null {
    const row = this.db
      .prepare(
        `SELECT id, purpose, decision, notice_version, created_at
         FROM consents WHERE owner_id = ? AND purpose = ?
         ORDER BY created_at DESC, rowid DESC LIMIT 1`,
      )
      .get(ownerId, purpose) as
      | { id: string; purpose: ConsentRecord['purpose']; decision: ConsentRecord['decision']; notice_version: string; created_at: string }
      | undefined;
    return row
      ? {
          id: row.id,
          purpose: row.purpose,
          decision: row.decision,
          noticeVersion: row.notice_version,
          createdAt: row.created_at,
        }
      : null;
  }

  listConsents(ownerId: string, limit = 100): ConsentRecord[] {
    const rows = this.db
      .prepare(
        `SELECT id, purpose, decision, notice_version, created_at
         FROM consents WHERE owner_id = ?
         ORDER BY created_at DESC, rowid DESC LIMIT ?`,
      )
      .all(ownerId, limit) as Array<{
      id: string;
      purpose: ConsentRecord['purpose'];
      decision: ConsentRecord['decision'];
      notice_version: string;
      created_at: string;
    }>;
    return rows.map((row) => ({
      id: row.id,
      purpose: row.purpose,
      decision: row.decision,
      noticeVersion: row.notice_version,
      createdAt: row.created_at,
    }));
  }

  // ---------------------------------------------------------------------------------------------
  // Summaries (encrypted aggregate payload)
  // ---------------------------------------------------------------------------------------------

  upsertSummary(input: {
    ownerId: string;
    monthId: string;
    inputRevision: number;
    payload: AggregatePayload;
    at: string;
  }): SummaryRecord {
    const sealed = sealJson(
      input.payload,
      this.dataKey,
      this.keyVersion,
      aadFor(input.ownerId, 'summary', this.keyVersion),
    );
    const existing = this.getSummary(input.ownerId, input.monthId);
    const id = existing?.id ?? crypto.randomUUID();
    this.db
      .prepare(
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
      )
      .run(
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

  getSummary(ownerId: string, monthId: string): SummaryRecord | null {
    const row = this.db
      .prepare(
        `SELECT id, month_id, input_revision, coverage, ciphertext, iv, tag, key_version,
                created_at, updated_at
         FROM summaries WHERE owner_id = ? AND month_id = ?`,
      )
      .get(ownerId, monthId) as
      | ({ id: string; month_id: string; input_revision: number; coverage: string; created_at: string; updated_at: string } & SealedColumns)
      | undefined;
    if (!row) return null;
    const payload = openJson<AggregatePayload>(
      {
        ciphertext: Buffer.from(row.ciphertext),
        iv: Buffer.from(row.iv),
        tag: Buffer.from(row.tag),
        keyVersion: row.key_version,
      },
      this.dataKey,
      aadFor(ownerId, 'summary', row.key_version),
    );
    return {
      id: row.id,
      monthId: row.month_id,
      inputRevision: row.input_revision,
      coverage: row.coverage,
      payload,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Analyses
  // ---------------------------------------------------------------------------------------------

  insertAnalysis(input: {
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
  }): AnalysisRecord {
    const sealed = sealJson(
      input.analysis,
      this.dataKey,
      this.keyVersion,
      aadFor(input.ownerId, 'analysis', this.keyVersion),
    );
    const id = crypto.randomUUID();
    this.db
      .prepare(
        `INSERT INTO analyses (
           id, owner_id, month_id, summary_id, input_revision, consent_id,
           provider, model, prompt_version, ciphertext, iv, tag, key_version,
           status, validation_issues, regeneration_count, prompt_tokens, completion_tokens,
           idempotency_key, request_hash, created_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
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
      );
    const stored = this.getAnalysisById(input.ownerId, id);
    if (!stored) throw new Error('analysis row vanished after insert');
    return stored;
  }

  findAnalysisByCacheKey(input: {
    ownerId: string;
    monthId: string;
    inputRevision: number;
    promptVersion: string;
    provider: string;
    model: string;
  }): AnalysisRecord | null {
    const row = this.db
      .prepare(
        `SELECT id FROM analyses
         WHERE owner_id = ? AND month_id = ? AND input_revision = ?
           AND prompt_version = ? AND provider = ? AND model = ?
         LIMIT 1`,
      )
      .get(
        input.ownerId,
        input.monthId,
        input.inputRevision,
        input.promptVersion,
        input.provider,
        input.model,
      ) as { id: string } | undefined;
    return row ? this.getAnalysisById(input.ownerId, row.id) : null;
  }

  findAnalysisByIdempotencyKey(
    ownerId: string,
    idempotencyKey: string,
  ): { analysis: AnalysisRecord; requestHash: string } | null {
    const row = this.db
      .prepare('SELECT id, request_hash FROM analyses WHERE owner_id = ? AND idempotency_key = ?')
      .get(ownerId, idempotencyKey) as { id: string; request_hash: string } | undefined;
    if (!row) return null;
    const analysis = this.getAnalysisById(ownerId, row.id);
    return analysis ? { analysis, requestHash: row.request_hash } : null;
  }

  latestAnalysis(ownerId: string, monthId: string): AnalysisRecord | null {
    const row = this.db
      .prepare(
        `SELECT id FROM analyses WHERE owner_id = ? AND month_id = ?
         ORDER BY created_at DESC, rowid DESC LIMIT 1`,
      )
      .get(ownerId, monthId) as { id: string } | undefined;
    return row ? this.getAnalysisById(ownerId, row.id) : null;
  }

  getAnalysisById(ownerId: string, id: string): AnalysisRecord | null {
    const row = this.db
      .prepare(
        `SELECT id, month_id, summary_id, input_revision, consent_id, provider, model,
                prompt_version, ciphertext, iv, tag, key_version, status, validation_issues,
                regeneration_count, prompt_tokens, completion_tokens, created_at
         FROM analyses WHERE owner_id = ? AND id = ?`,
      )
      .get(ownerId, id) as
      | ({
          id: string;
          month_id: string;
          summary_id: string;
          input_revision: number;
          consent_id: string | null;
          provider: string;
          model: string;
          prompt_version: string;
          status: string;
          validation_issues: string;
          regeneration_count: number;
          prompt_tokens: number | null;
          completion_tokens: number | null;
          created_at: string;
        } & SealedColumns)
      | undefined;
    if (!row) return null;
    const analysis = openJson<AnalysisResponse>(
      {
        ciphertext: Buffer.from(row.ciphertext),
        iv: Buffer.from(row.iv),
        tag: Buffer.from(row.tag),
        keyVersion: row.key_version,
      },
      this.dataKey,
      aadFor(ownerId, 'analysis', row.key_version),
    );
    return {
      id: row.id,
      monthId: row.month_id,
      summaryId: row.summary_id,
      inputRevision: row.input_revision,
      consentId: row.consent_id,
      provider: row.provider,
      model: row.model,
      promptVersion: row.prompt_version,
      analysis,
      status: row.status,
      issues: JSON.parse(row.validation_issues) as string[],
      regenerationCount: row.regeneration_count,
      promptTokens: row.prompt_tokens,
      completionTokens: row.completion_tokens,
      createdAt: row.created_at,
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Budget plans
  // ---------------------------------------------------------------------------------------------

  upsertBudgetPlan(input: {
    ownerId: string;
    monthId: string;
    plan: PlanSummary;
    at: string;
  }): BudgetPlanRecord {
    const sealed = sealJson(
      input.plan,
      this.dataKey,
      this.keyVersion,
      aadFor(input.ownerId, 'budget_plan', this.keyVersion),
    );
    const existing = this.getBudgetPlan(input.ownerId, input.monthId);
    const id = existing?.id ?? crypto.randomUUID();
    this.db
      .prepare(
        `INSERT INTO budget_plans (
           id, owner_id, month_id, ciphertext, iv, tag, key_version, created_at, updated_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(owner_id, month_id) DO UPDATE SET
           ciphertext = excluded.ciphertext,
           iv = excluded.iv,
           tag = excluded.tag,
           key_version = excluded.key_version,
           updated_at = excluded.updated_at`,
      )
      .run(
        id,
        input.ownerId,
        input.monthId,
        sealed.ciphertext,
        sealed.iv,
        sealed.tag,
        sealed.keyVersion,
        existing?.createdAt ?? input.at,
        input.at,
      );
    return {
      id,
      monthId: input.monthId,
      plan: input.plan,
      createdAt: existing?.createdAt ?? input.at,
      updatedAt: input.at,
    };
  }

  getBudgetPlan(ownerId: string, monthId: string): BudgetPlanRecord | null {
    const row = this.db
      .prepare(
        `SELECT id, month_id, ciphertext, iv, tag, key_version, created_at, updated_at
         FROM budget_plans WHERE owner_id = ? AND month_id = ?`,
      )
      .get(ownerId, monthId) as
      | ({ id: string; month_id: string; created_at: string; updated_at: string } & SealedColumns)
      | undefined;
    if (!row) return null;
    const plan = openJson<PlanSummary>(
      {
        ciphertext: Buffer.from(row.ciphertext),
        iv: Buffer.from(row.iv),
        tag: Buffer.from(row.tag),
        keyVersion: row.key_version,
      },
      this.dataKey,
      aadFor(ownerId, 'budget_plan', row.key_version),
    );
    return {
      id: row.id,
      monthId: row.month_id,
      plan,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Deletion (FR-47/FR-48)
  // ---------------------------------------------------------------------------------------------

  createDeletionRequest(input: {
    ownerId: string;
    slaDays: number;
    at: string;
  }): DeletionRequestRecord {
    const id = crypto.randomUUID();
    this.db
      .prepare(
        `INSERT INTO deletion_requests (id, owner_id, state, sla_days, requested_at, created_at)
         VALUES (?, ?, 'PENDING', ?, ?, ?)`,
      )
      .run(id, input.ownerId, input.slaDays, input.at, input.at);
    return {
      id,
      state: 'PENDING',
      slaDays: input.slaDays,
      requestedAt: input.at,
      completedAt: null,
      aggregatesDeleted: 0,
    };
  }

  getDeletionRequest(ownerId: string, id: string): DeletionRequestRecord | null {
    const row = this.db
      .prepare(
        `SELECT id, state, sla_days, requested_at, completed_at, aggregates_deleted
         FROM deletion_requests WHERE owner_id = ? AND id = ?`,
      )
      .get(ownerId, id) as
      | { id: string; state: DeletionState; sla_days: number; requested_at: string; completed_at: string | null; aggregates_deleted: number }
      | undefined;
    return row
      ? {
          id: row.id,
          state: row.state,
          slaDays: row.sla_days,
          requestedAt: row.requested_at,
          completedAt: row.completed_at,
          aggregatesDeleted: row.aggregates_deleted,
        }
      : null;
  }

  hasPendingDeletion(ownerId: string): boolean {
    const row = this.db
      .prepare("SELECT 1 AS present FROM deletion_requests WHERE owner_id = ? AND state = 'PENDING' LIMIT 1")
      .get(ownerId) as { present: number } | undefined;
    return row !== undefined;
  }

  pendingDeletionForOwner(ownerId: string): DeletionRequestRecord | null {
    const row = this.db
      .prepare(
        `SELECT id FROM deletion_requests WHERE owner_id = ? AND state = 'PENDING'
         ORDER BY requested_at DESC LIMIT 1`,
      )
      .get(ownerId) as { id: string } | undefined;
    return row ? this.getDeletionRequest(ownerId, row.id) : null;
  }

  pendingDeletionOwnerIds(): string[] {
    const rows = this.db
      .prepare("SELECT DISTINCT owner_id FROM deletion_requests WHERE state = 'PENDING'")
      .all() as Array<{ owner_id: string }>;
    return rows.map((row) => row.owner_id);
  }

  /** Deletes every active aggregate for one owner; keeps the request row for FR-48 reporting. */
  deleteAggregatesForOwner(ownerId: string): number {
    let deleted = 0;
    for (const table of ['summaries', 'analyses', 'budget_plans'] as const) {
      const result = this.db.prepare(`DELETE FROM ${table} WHERE owner_id = ?`).run(ownerId);
      deleted += Number(result.changes);
    }
    return deleted;
  }

  completeDeletionRequest(id: string, at: string, aggregatesDeleted: number): void {
    this.db
      .prepare(
        `UPDATE deletion_requests
         SET state = 'COMPLETED', completed_at = ?, aggregates_deleted = ?
         WHERE id = ? AND state = 'PENDING'`,
      )
      .run(at, aggregatesDeleted, id);
  }

  /** Runs the sweep: one transaction per owner, so a crash never half-deletes. */
  sweepDeletions(at: string): number {
    let completed = 0;
    for (const ownerId of this.pendingDeletionOwnerIds()) {
      const pending = this.db
        .prepare(
          "SELECT id FROM deletion_requests WHERE owner_id = ? AND state = 'PENDING' ORDER BY requested_at",
        )
        .all(ownerId) as Array<{ id: string }>;
      this.db.exec('BEGIN');
      try {
        const deleted = this.deleteAggregatesForOwner(ownerId);
        for (const request of pending) {
          this.completeDeletionRequest(request.id, at, deleted);
          completed += 1;
        }
        this.db.exec('COMMIT');
      } catch (error) {
        this.db.exec('ROLLBACK');
        throw error;
      }
    }
    return completed;
  }

  // ---------------------------------------------------------------------------------------------
  // Usage counters (FR-50)
  // ---------------------------------------------------------------------------------------------

  bumpUsage(scope: string, bucket: string, delta: UsageDelta): void {
    this.db
      .prepare(
        `INSERT INTO usage_counters (scope, bucket, analyses, tokens_prompt, tokens_completion, cost_usd)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(scope, bucket) DO UPDATE SET
           analyses = usage_counters.analyses + excluded.analyses,
           tokens_prompt = usage_counters.tokens_prompt + excluded.tokens_prompt,
           tokens_completion = usage_counters.tokens_completion + excluded.tokens_completion,
           cost_usd = usage_counters.cost_usd + excluded.cost_usd`,
      )
      .run(
        scope,
        bucket,
        delta.analyses ?? 0,
        delta.tokensPrompt ?? 0,
        delta.tokensCompletion ?? 0,
        delta.costUsd ?? 0,
      );
  }

  getUsage(scope: string, bucket: string): UsageRecord {
    const row = this.db
      .prepare(
        'SELECT analyses, tokens_prompt, tokens_completion, cost_usd FROM usage_counters WHERE scope = ? AND bucket = ?',
      )
      .get(scope, bucket) as
      | { analyses: number; tokens_prompt: number; tokens_completion: number; cost_usd: number }
      | undefined;
    return row
      ? {
          analyses: row.analyses,
          tokensPrompt: row.tokens_prompt,
          tokensCompletion: row.tokens_completion,
          costUsd: row.cost_usd,
        }
      : { analyses: 0, tokensPrompt: 0, tokensCompletion: 0, costUsd: 0 };
  }

  /** Last activity timestamps for `/v1/me` (FR-49). */
  activity(ownerId: string): { lastSummaryAt: string | null; lastAnalysisAt: string | null } {
    const summary = this.db
      .prepare('SELECT MAX(updated_at) AS at FROM summaries WHERE owner_id = ?')
      .get(ownerId) as { at: string | null } | undefined;
    const analysis = this.db
      .prepare('SELECT MAX(created_at) AS at FROM analyses WHERE owner_id = ?')
      .get(ownerId) as { at: string | null } | undefined;
    return { lastSummaryAt: summary?.at ?? null, lastAnalysisAt: analysis?.at ?? null };
  }
}
