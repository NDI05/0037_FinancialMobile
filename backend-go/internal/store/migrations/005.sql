-- Analyses must be append-only across consent lifecycle changes. The legacy cache UNIQUE constraint
-- prevented a new analysis for the same month/revision after a withdrawal + regrant. Rebuild the
-- table without the cache uniqueness, keeping the idempotency uniqueness and all historical rows.
CREATE TABLE analyses_new (
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
  snapshot_hash      TEXT NOT NULL DEFAULT '',
  created_at         TEXT NOT NULL,
  UNIQUE (owner_id, idempotency_key)
);
INSERT INTO analyses_new
  (id, owner_id, month_id, summary_id, input_revision, consent_id, provider, model,
   prompt_version, ciphertext, iv, tag, key_version, status, validation_issues,
   regeneration_count, prompt_tokens, completion_tokens, idempotency_key, request_hash,
   snapshot_hash, created_at)
SELECT id, owner_id, month_id, summary_id, input_revision, consent_id, provider, model,
   prompt_version, ciphertext, iv, tag, key_version, status, validation_issues,
   regeneration_count, prompt_tokens, completion_tokens, idempotency_key, request_hash,
   snapshot_hash, created_at
FROM analyses;
DROP TABLE analyses;
ALTER TABLE analyses_new RENAME TO analyses;
CREATE INDEX idx_analyses_owner_month ON analyses(owner_id, month_id, created_at);
CREATE INDEX idx_analyses_cache ON analyses(
  owner_id, month_id, input_revision, prompt_version, provider, model, consent_id, snapshot_hash
);
