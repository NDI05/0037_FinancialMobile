CREATE TABLE category_jobs (
  id              TEXT PRIMARY KEY,
  owner_id        TEXT NOT NULL,
  transaction_id  TEXT NOT NULL,
  expected_revision INTEGER NOT NULL,
  state           TEXT NOT NULL CHECK (state IN ('PENDING','RUNNING','CLASSIFIED','NEEDS_REVIEW','FAILED','CANCELLED')),
  category_code   TEXT,
  confidence      REAL,
  provider        TEXT,
  model           TEXT,
  prompt_version  TEXT,
  taxonomy_version TEXT,
  reason_code     TEXT,
  error_code      TEXT,
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL,
  UNIQUE(owner_id, transaction_id, expected_revision)
);
CREATE INDEX idx_category_jobs_owner ON category_jobs(owner_id, created_at DESC);

CREATE TABLE category_cache (
  owner_id       TEXT NOT NULL,
  cache_key      TEXT NOT NULL,
  direction      TEXT NOT NULL,
  method         TEXT NOT NULL,
  taxonomy_version TEXT NOT NULL,
  model          TEXT NOT NULL,
  prompt_version TEXT NOT NULL,
  category_code  TEXT NOT NULL,
  needs_review   INTEGER NOT NULL,
  reason_code    TEXT NOT NULL,
  updated_at     TEXT NOT NULL,
  PRIMARY KEY(owner_id, cache_key, taxonomy_version, model, prompt_version)
);

CREATE TABLE category_usage (
  scope      TEXT NOT NULL,
  bucket     TEXT NOT NULL,
  attempts   INTEGER NOT NULL DEFAULT 0,
  reserved_usd REAL NOT NULL DEFAULT 0,
  actual_usd REAL NOT NULL DEFAULT 0,
  PRIMARY KEY(scope, bucket)
);
