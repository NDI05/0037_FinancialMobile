DROP INDEX IF EXISTS idx_consents_owner_purpose;
ALTER TABLE consents RENAME TO consents_v1;
CREATE TABLE consents (
  id             TEXT PRIMARY KEY,
  owner_id       TEXT NOT NULL,
  purpose        TEXT NOT NULL CHECK (purpose IN ('GMAIL_READ', 'AI_PROCESSING', 'AI_CATEGORIZATION')),
  decision       TEXT NOT NULL CHECK (decision IN ('GRANTED', 'DECLINED', 'WITHDRAWN')),
  notice_version TEXT NOT NULL,
  created_at     TEXT NOT NULL
);
INSERT INTO consents (id, owner_id, purpose, decision, notice_version, created_at)
  SELECT id, owner_id, purpose, decision, notice_version, created_at FROM consents_v1;
DROP TABLE consents_v1;
CREATE INDEX idx_consents_owner_purpose ON consents(owner_id, purpose, created_at);
