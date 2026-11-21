-- C1 hardening: HTTP idempotency, canonical analysis snapshot identity and usage accounting.
CREATE TABLE http_idempotency (
  id           TEXT PRIMARY KEY,
  owner_id     TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
  operation    TEXT NOT NULL,
  idem_key     TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  result_id    TEXT,
  created_at   TEXT NOT NULL,
  UNIQUE (owner_id, operation, idem_key)
);
CREATE INDEX idx_http_idempotency_result ON http_idempotency(owner_id, result_id);

ALTER TABLE analyses ADD COLUMN snapshot_hash TEXT NOT NULL DEFAULT '';
CREATE INDEX idx_analyses_snapshot
  ON analyses(owner_id, month_id, input_revision, snapshot_hash);

ALTER TABLE usage_counters ADD COLUMN provider_calls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE usage_counters ADD COLUMN usage_unknown INTEGER NOT NULL DEFAULT 0;
