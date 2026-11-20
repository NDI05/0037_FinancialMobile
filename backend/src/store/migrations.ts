/**
 * Schema for contract §6. Migrations are applied in order inside one transaction and tracked with
 * `PRAGMA user_version`, so boot is idempotent.
 *
 * Every transactional table carries `owner_id`; there is no join that could cross owners without an
 * explicit `owner_id = ?` predicate.
 */
export interface Migration {
  readonly version: number;
  readonly sql: string;
}

export const migrations: readonly Migration[] = [
  {
    version: 1,
    sql: /* sql */ `
      CREATE TABLE users (
        owner_id     TEXT PRIMARY KEY,
        google_sub   TEXT NOT NULL UNIQUE,
        display_name TEXT,
        created_at   TEXT NOT NULL,
        last_seen_at TEXT NOT NULL
      );

      CREATE TABLE sessions (
        id         TEXT PRIMARY KEY,
        owner_id   TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
        token_hash TEXT NOT NULL UNIQUE,
        created_at TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        revoked_at TEXT
      );
      CREATE INDEX idx_sessions_owner ON sessions(owner_id);

      CREATE TABLE consents (
        id             TEXT PRIMARY KEY,
        owner_id       TEXT NOT NULL,
        purpose        TEXT NOT NULL CHECK (purpose IN ('GMAIL_READ', 'AI_PROCESSING')),
        decision       TEXT NOT NULL CHECK (decision IN ('GRANTED', 'DECLINED', 'WITHDRAWN')),
        notice_version TEXT NOT NULL,
        created_at     TEXT NOT NULL
      );
      CREATE INDEX idx_consents_owner_purpose ON consents(owner_id, purpose, created_at);

      CREATE TABLE summaries (
        id             TEXT PRIMARY KEY,
        owner_id       TEXT NOT NULL,
        month_id       TEXT NOT NULL,
        input_revision INTEGER NOT NULL,
        coverage       TEXT NOT NULL,
        ciphertext     BLOB NOT NULL,
        iv             BLOB NOT NULL,
        tag            BLOB NOT NULL,
        key_version    INTEGER NOT NULL,
        created_at     TEXT NOT NULL,
        updated_at     TEXT NOT NULL,
        UNIQUE (owner_id, month_id)
      );

      CREATE TABLE analyses (
        id                 TEXT PRIMARY KEY,
        owner_id           TEXT NOT NULL,
        month_id           TEXT NOT NULL,
        summary_id         TEXT NOT NULL,
        input_revision     INTEGER NOT NULL,
        consent_id         TEXT,
        provider           TEXT NOT NULL,
        model              TEXT NOT NULL,
        prompt_version     TEXT NOT NULL,
        ciphertext         BLOB NOT NULL,
        iv                 BLOB NOT NULL,
        tag                BLOB NOT NULL,
        key_version        INTEGER NOT NULL,
        status             TEXT NOT NULL,
        validation_issues  TEXT NOT NULL DEFAULT '[]',
        regeneration_count INTEGER NOT NULL DEFAULT 0,
        prompt_tokens      INTEGER,
        completion_tokens  INTEGER,
        idempotency_key    TEXT NOT NULL,
        request_hash       TEXT NOT NULL,
        created_at         TEXT NOT NULL,
        UNIQUE (owner_id, month_id, input_revision, prompt_version, provider, model),
        UNIQUE (owner_id, idempotency_key)
      );
      CREATE INDEX idx_analyses_owner_month ON analyses(owner_id, month_id, created_at);
      CREATE INDEX idx_analyses_cache ON analyses(
        owner_id, month_id, input_revision, prompt_version, provider, model
      );

      CREATE TABLE budget_plans (
        id          TEXT PRIMARY KEY,
        owner_id    TEXT NOT NULL,
        month_id    TEXT NOT NULL,
        ciphertext  BLOB NOT NULL,
        iv          BLOB NOT NULL,
        tag         BLOB NOT NULL,
        key_version INTEGER NOT NULL,
        created_at  TEXT NOT NULL,
        updated_at  TEXT NOT NULL,
        UNIQUE (owner_id, month_id)
      );

      CREATE TABLE deletion_requests (
        id                TEXT PRIMARY KEY,
        owner_id          TEXT NOT NULL,
        state             TEXT NOT NULL CHECK (state IN ('PENDING', 'COMPLETED')),
        sla_days          INTEGER NOT NULL,
        requested_at      TEXT NOT NULL,
        completed_at      TEXT,
        aggregates_deleted INTEGER NOT NULL DEFAULT 0,
        created_at        TEXT NOT NULL
      );
      CREATE INDEX idx_deletion_owner_state ON deletion_requests(owner_id, state);

      CREATE TABLE usage_counters (
        scope             TEXT NOT NULL,
        bucket            TEXT NOT NULL,
        analyses          INTEGER NOT NULL DEFAULT 0,
        tokens_prompt     INTEGER NOT NULL DEFAULT 0,
        tokens_completion INTEGER NOT NULL DEFAULT 0,
        cost_usd          REAL NOT NULL DEFAULT 0,
        PRIMARY KEY (scope, bucket)
      );
    `,
  },
  {
    version: 2,
    sql: /* sql */ `
      -- CR-001 / D2: Google Sign-In is removed; identity becomes a PAM account (email + password).
      -- Sessions from the Google era cannot be verified any more, so they are dropped with the old
      -- users table (aggregate rows keep their owner_id and stay readable to nobody).
      DROP TABLE IF EXISTS sessions;
      DROP TABLE IF EXISTS users;

      CREATE TABLE users (
        owner_id      TEXT PRIMARY KEY,
        email         TEXT NOT NULL UNIQUE,
        display_name  TEXT,
        salt          BLOB NOT NULL,
        password_hash BLOB NOT NULL,
        kdf           TEXT NOT NULL,
        created_at    TEXT NOT NULL,
        last_seen_at  TEXT NOT NULL
      );

      CREATE TABLE sessions (
        id         TEXT PRIMARY KEY,
        owner_id   TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
        token_hash TEXT NOT NULL UNIQUE,
        created_at TEXT NOT NULL,
        expires_at TEXT NOT NULL,
        revoked_at TEXT
      );
      CREATE INDEX idx_sessions_owner ON sessions(owner_id);

      CREATE TABLE login_attempts (
        attempt_key  TEXT PRIMARY KEY,
        failed_count INTEGER NOT NULL,
        window_start TEXT NOT NULL,
        updated_at   TEXT NOT NULL
      );
    `,
  },
];
