ALTER TABLE transactions ADD COLUMN classifier_origin TEXT NOT NULL DEFAULT '';
ALTER TABLE transactions ADD COLUMN category_confidence REAL;
ALTER TABLE transactions ADD COLUMN category_rule_version TEXT;
ALTER TABLE transactions ADD COLUMN category_reason TEXT;
ALTER TABLE transactions ADD COLUMN category_taxonomy_version TEXT;

CREATE TABLE category_reservations (
  id            TEXT PRIMARY KEY,
  owner_id      TEXT NOT NULL,
  day_bucket    TEXT NOT NULL,
  month_bucket  TEXT NOT NULL,
  reserve_usd   REAL NOT NULL,
  estimated_usd REAL NOT NULL DEFAULT 0,
  actual_usd    REAL NOT NULL DEFAULT 0,
  usage_unknown INTEGER NOT NULL DEFAULT 0,
  state         TEXT NOT NULL CHECK (state IN ('RESERVED','SETTLED','UNKNOWN')),
  created_at    TEXT NOT NULL,
  settled_at    TEXT
);
CREATE INDEX idx_category_reservations_owner ON category_reservations(owner_id, created_at DESC);
