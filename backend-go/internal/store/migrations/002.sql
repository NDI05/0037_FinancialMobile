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
