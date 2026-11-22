-- CR-002 / v0.4: server-side finance data, JWT sessions, roles and attachment metadata.
      -- Additive over v0.3: every legacy row keeps its owner and stays readable after the upgrade.

      ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'USER'
        CHECK (role IN ('USER', 'ADMIN'));
      ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'));
      -- Incremented on a role/status change so a stale access token can be rejected even before
      -- the session row is revoked.
      ALTER TABLE users ADD COLUMN auth_version INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE users ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
      ALTER TABLE users ADD COLUMN updated_at TEXT;
      UPDATE users SET updated_at = created_at WHERE updated_at IS NULL;
      CREATE INDEX idx_users_role_status ON users(role, status);

      -- The per-account HMAC key is server-held and sealed with the data key. The client fetches it
      -- after login and keeps it in the Keystore, so a reinstall or a second device derives the
      -- same import fingerprint instead of duplicating rows. Rotation requires a re-fingerprint
      -- migration; the key is destroyed with the account.
      CREATE TABLE account_keys (
        owner_id              TEXT PRIMARY KEY REFERENCES users(owner_id) ON DELETE CASCADE,
        key_version           INTEGER NOT NULL,
        fingerprint_ciphertext BLOB NOT NULL,
        fingerprint_iv        BLOB NOT NULL,
        fingerprint_tag       BLOB NOT NULL,
        created_at            TEXT NOT NULL,
        rotated_at            TEXT
      );

      -- Short-lived JWT access tokens reference a row here by session id (sid). The legacy
      -- opaque 'sessions' table is left untouched until C1 cuts over and drops it.
      CREATE TABLE auth_sessions (
        id             TEXT PRIMARY KEY,
        owner_id       TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
        family_id      TEXT NOT NULL,
        created_at     TEXT NOT NULL,
        last_seen_at   TEXT NOT NULL,
        expires_at     TEXT NOT NULL,
        revoked_at     TEXT,
        revoked_reason TEXT
      );
      CREATE INDEX idx_auth_sessions_owner ON auth_sessions(owner_id, revoked_at);
      CREATE INDEX idx_auth_sessions_family ON auth_sessions(family_id);

      -- Only sha256(token + pepper) is stored. Rotation writes the replacement and revokes the
      -- previous row in one transaction; a reused rotated token invalidates the whole family.
      CREATE TABLE refresh_tokens (
        id             TEXT PRIMARY KEY,
        owner_id       TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
        session_id     TEXT NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
        family_id      TEXT NOT NULL,
        token_hash     TEXT NOT NULL UNIQUE,
        issued_at      TEXT NOT NULL,
        expires_at     TEXT NOT NULL,
        revoked_at     TEXT,
        revoked_reason TEXT,
        replaced_by_id TEXT REFERENCES refresh_tokens(id)
      );
      CREATE INDEX idx_refresh_tokens_owner_family ON refresh_tokens(owner_id, family_id);
      CREATE INDEX idx_refresh_tokens_session ON refresh_tokens(session_id);

      -- Four owner-scoped entities with per-entity dynamic attributes (D.5/FR-61).
      -- Labels and attributes are AES-GCM sealed; kind, code and timestamps stay queryable.
      CREATE TABLE categories (
        id                    TEXT PRIMARY KEY,
        owner_id              TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
        code                  TEXT NOT NULL,
        kind                  TEXT NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE', 'TRANSFER', 'UNCATEGORIZED')),
        label_ciphertext      BLOB NOT NULL,
        label_iv              BLOB NOT NULL,
        label_tag             BLOB NOT NULL,
        color                 TEXT,
        is_builtin            INTEGER NOT NULL DEFAULT 0 CHECK (is_builtin IN (0, 1)),
        attributes_ciphertext BLOB,
        attributes_iv         BLOB,
        attributes_tag        BLOB,
        key_version           INTEGER NOT NULL,
        revision              INTEGER NOT NULL DEFAULT 0,
        created_at            TEXT NOT NULL,
        updated_at            TEXT NOT NULL,
        deleted_at            TEXT,
        UNIQUE (owner_id, code),
        UNIQUE (owner_id, id)
      );
      CREATE INDEX idx_categories_owner_active ON categories(owner_id, deleted_at, kind);

      -- Server-side transaction rows (FR-60). Amounts are integer minor units and capped below
      -- Number.MAX_SAFE_INTEGER; merchant/note/description/attributes are sealed in one JSON blob.
      CREATE TABLE transactions (
        id                    TEXT PRIMARY KEY,
        owner_id              TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
        category_id           TEXT,
        direction             TEXT NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
        source                TEXT NOT NULL CHECK (source IN ('EMAIL', 'MANUAL')),
        amount_minor          INTEGER NOT NULL
          CHECK (amount_minor > 0 AND amount_minor <= 1000000000000000),
        currency              TEXT NOT NULL DEFAULT 'IDR' CHECK (currency = 'IDR'),
        event_at_ms           INTEGER NOT NULL CHECK (event_at_ms > 0),
        month_id              TEXT NOT NULL CHECK (length(month_id) = 7),
        status                TEXT NOT NULL DEFAULT 'POSTED' CHECK (status = 'POSTED'),
        fingerprint           TEXT NOT NULL CHECK (length(fingerprint) = 32),
        parser_version        TEXT,
        parser_confidence     REAL,
        category_origin       TEXT NOT NULL DEFAULT 'RULE'
          CHECK (category_origin IN ('IMPORTED', 'RULE', 'USER')),
        sensitive_ciphertext  BLOB NOT NULL,
        sensitive_iv          BLOB NOT NULL,
        sensitive_tag         BLOB NOT NULL,
        key_version           INTEGER NOT NULL,
        is_internal_transfer  INTEGER NOT NULL DEFAULT 0 CHECK (is_internal_transfer IN (0, 1)),
        linked_refund_of      TEXT,
        revision              INTEGER NOT NULL DEFAULT 0,
        created_at            TEXT NOT NULL,
        updated_at            TEXT NOT NULL,
        deleted_at            TEXT,
        UNIQUE (owner_id, fingerprint),
        UNIQUE (owner_id, id),
        FOREIGN KEY (owner_id, category_id) REFERENCES categories(owner_id, id),
        FOREIGN KEY (owner_id, linked_refund_of) REFERENCES transactions(owner_id, id)
      );
      CREATE INDEX idx_transactions_owner_month
        ON transactions(owner_id, month_id, deleted_at, event_at_ms DESC, id);
      CREATE INDEX idx_transactions_owner_category
        ON transactions(owner_id, category_id, event_at_ms DESC);

      -- One revision per owner/month; every write bumps it and dependents compare it.
      CREATE TABLE month_revisions (
        owner_id   TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
        month_id   TEXT NOT NULL,
        revision   INTEGER NOT NULL DEFAULT 0,
        updated_at TEXT NOT NULL,
        PRIMARY KEY (owner_id, month_id)
      );

      ALTER TABLE budget_plans ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
      -- Canonical v0.4 plan arithmetic lives in integer minor columns; the legacy sealed payload
      -- remains for v0.3 rows and now carries the dynamic attributes of a v0.4 plan.
      ALTER TABLE budget_plans ADD COLUMN expected_income_minor INTEGER NOT NULL DEFAULT 0
        CHECK (expected_income_minor >= 0 AND expected_income_minor <= 1000000000000000);
      ALTER TABLE budget_plans ADD COLUMN planned_savings_minor INTEGER NOT NULL DEFAULT 0
        CHECK (planned_savings_minor >= 0 AND planned_savings_minor <= 1000000000000000);
      CREATE UNIQUE INDEX ux_budget_plans_owner_id ON budget_plans(owner_id, id);

      CREATE TABLE budget_limits (
        owner_id     TEXT NOT NULL,
        plan_id      TEXT NOT NULL,
        category_id  TEXT NOT NULL,
        amount_minor INTEGER NOT NULL
          CHECK (amount_minor >= 0 AND amount_minor <= 1000000000000000),
        created_at   TEXT NOT NULL,
        updated_at   TEXT NOT NULL,
        PRIMARY KEY (owner_id, plan_id, category_id),
        FOREIGN KEY (owner_id, plan_id) REFERENCES budget_plans(owner_id, id) ON DELETE CASCADE,
        FOREIGN KEY (owner_id, category_id) REFERENCES categories(owner_id, id)
      );

      -- Metadata only: the file never leaves the device. device_ref is an opaque install id,
      -- so another device can show "file not available here" without guessing the path.
      CREATE TABLE attachments (
        id                    TEXT PRIMARY KEY,
        owner_id              TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
        transaction_id        TEXT NOT NULL,
        local_file_id         TEXT NOT NULL,
        device_ref            TEXT NOT NULL,
        mime                  TEXT NOT NULL
          CHECK (mime IN ('image/jpeg', 'image/png', 'image/webp', 'application/pdf')),
        size_bytes            INTEGER NOT NULL
          CHECK (size_bytes > 0 AND size_bytes <= 10485760),
        sensitive_ciphertext  BLOB NOT NULL,
        sensitive_iv          BLOB NOT NULL,
        sensitive_tag         BLOB NOT NULL,
        key_version           INTEGER NOT NULL,
        revision              INTEGER NOT NULL DEFAULT 0,
        created_at            TEXT NOT NULL,
        updated_at            TEXT NOT NULL,
        deleted_at            TEXT,
        UNIQUE (owner_id, id),
        UNIQUE (owner_id, transaction_id, local_file_id),
        FOREIGN KEY (owner_id, transaction_id) REFERENCES transactions(owner_id, id)
          ON DELETE CASCADE
      );
      CREATE INDEX idx_attachments_owner_transaction
        ON attachments(owner_id, transaction_id, deleted_at);

      CREATE TABLE import_jobs (
        id              TEXT PRIMARY KEY,
        owner_id        TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
        month_id        TEXT NOT NULL,
        state           TEXT NOT NULL
          CHECK (state IN ('CREATED', 'RUNNING', 'COMPLETE', 'PARTIAL', 'FAILED', 'EMPTY', 'PERMISSION_REQUIRED')),
        template_version TEXT NOT NULL,
        pages_processed INTEGER NOT NULL DEFAULT 0,
        pages_expected  INTEGER,
        matched_count   INTEGER NOT NULL DEFAULT 0,
        imported_count  INTEGER NOT NULL DEFAULT 0,
        duplicate_count INTEGER NOT NULL DEFAULT 0,
        skipped_count   INTEGER NOT NULL DEFAULT 0,
        unresolved_count INTEGER NOT NULL DEFAULT 0,
        counters_json   TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(counters_json)),
        started_at      TEXT NOT NULL,
        finished_at     TEXT,
        error_code      TEXT,
        UNIQUE (owner_id, id)
      );
      CREATE INDEX idx_import_jobs_owner_month ON import_jobs(owner_id, month_id, started_at DESC);

      -- A batch key makes a retried page return the stored counts instead of inserting twice.
      CREATE TABLE import_batches (
        id           TEXT PRIMARY KEY,
        owner_id     TEXT NOT NULL,
        job_id       TEXT NOT NULL,
        batch_key    TEXT NOT NULL,
        request_hash TEXT NOT NULL,
        counts_json  TEXT NOT NULL CHECK (json_valid(counts_json)),
        created_at   TEXT NOT NULL,
        UNIQUE (owner_id, job_id, batch_key),
        FOREIGN KEY (owner_id, job_id) REFERENCES import_jobs(owner_id, id) ON DELETE CASCADE
      );

      -- Reason codes only; no subject/body/merchant is ever stored (FR-15).
      CREATE TABLE import_skipped (
        id               TEXT PRIMARY KEY,
        owner_id         TEXT NOT NULL,
        job_id           TEXT NOT NULL,
        reason_code      TEXT NOT NULL,
        page_ordinal     INTEGER NOT NULL,
        candidate_ordinal INTEGER NOT NULL,
        created_at       TEXT NOT NULL,
        FOREIGN KEY (owner_id, job_id) REFERENCES import_jobs(owner_id, id) ON DELETE CASCADE
      );
      CREATE INDEX idx_import_skipped_job ON import_skipped(owner_id, job_id);

      -- Admin provisioning: a one-time token bound to one email and one role. The public invite
      -- code still creates USER only; an ADMIN account requires this controlled path.
      CREATE TABLE admin_invitations (
        id              TEXT PRIMARY KEY,
        created_by      TEXT NOT NULL REFERENCES users(owner_id),
        email           TEXT NOT NULL,
        role            TEXT NOT NULL CHECK (role IN ('USER', 'ADMIN')),
        token_hash      TEXT NOT NULL UNIQUE,
        expires_at      TEXT NOT NULL,
        used_at         TEXT,
        created_at      TEXT NOT NULL
      );
      CREATE INDEX idx_admin_invitations_email ON admin_invitations(email, used_at);

      CREATE TABLE admin_audit (
        id              TEXT PRIMARY KEY,
        actor_owner_id  TEXT NOT NULL REFERENCES users(owner_id),
        target_owner_id TEXT NOT NULL,
        action          TEXT NOT NULL,
        metadata_json   TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(metadata_json)),
        created_at      TEXT NOT NULL
      );
      CREATE INDEX idx_admin_audit_target ON admin_audit(target_owner_id, created_at DESC);
