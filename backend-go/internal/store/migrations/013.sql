-- Automatic category queue (P3). One row per owner tracks when the next drain is due, so the
-- 2-hour target is enforced server-side: a client that polls more often gets a no-op answer and the
-- provider is never called on an empty or not-yet-due queue.
CREATE TABLE category_queue_state (
  owner_id      TEXT PRIMARY KEY,
  next_drain_at TEXT,
  last_drain_at TEXT,
  last_result   TEXT,
  updated_at    TEXT NOT NULL
);

-- Coalescing key: identical merchant/context rows in one drain share a single provider call. It is
-- a hash of the normalized description, never the raw text (nothing here may leak a counterparty).
ALTER TABLE category_jobs ADD COLUMN merchant_key TEXT NOT NULL DEFAULT '';

-- Draining scans the owner's PENDING jobs oldest-first.
CREATE INDEX IF NOT EXISTS idx_category_jobs_queued ON category_jobs(owner_id, state, created_at);
