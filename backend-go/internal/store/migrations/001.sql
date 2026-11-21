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
