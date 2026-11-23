-- S4 attachment metadata. The file itself never leaves the device; the server stores only the
-- descriptive fields needed to re-attach it to a transaction on another install.
--
-- The v1 table only allowed four MIME types and required a device-local file id, and it had no
-- content hash or storage path. Rebuild it so HEIC is accepted (common on Android), the device
-- fields are optional, and a sha256 + storage_ref make the metadata deduplicable.
CREATE TABLE attachments_v2 (
  id                    TEXT PRIMARY KEY,
  owner_id              TEXT NOT NULL REFERENCES users(owner_id) ON DELETE CASCADE,
  transaction_id        TEXT NOT NULL,
  local_file_id         TEXT NOT NULL DEFAULT '',
  device_ref            TEXT NOT NULL DEFAULT '',
  mime                  TEXT NOT NULL
    CHECK (mime IN ('application/pdf', 'image/jpeg', 'image/png', 'image/webp', 'image/heic')),
  size_bytes            INTEGER NOT NULL
    CHECK (size_bytes > 0 AND size_bytes <= 10485760),
  sha256                TEXT NOT NULL
    CHECK (length(sha256) = 64 AND sha256 = lower(sha256)),
  -- A device-relative path marker, never an absolute path or a URI.
  storage_ref           TEXT NOT NULL
    CHECK (length(storage_ref) > 0 AND storage_ref NOT LIKE '/%' AND instr(storage_ref, '..') = 0
           AND instr(storage_ref, char(92)) = 0 AND instr(storage_ref, '://') = 0),
  sensitive_ciphertext  BLOB NOT NULL,
  sensitive_iv          BLOB NOT NULL,
  sensitive_tag         BLOB NOT NULL,
  key_version           INTEGER NOT NULL,
  revision              INTEGER NOT NULL DEFAULT 0,
  created_at_ms         INTEGER NOT NULL,
  created_at            TEXT NOT NULL,
  updated_at            TEXT NOT NULL,
  deleted_at            TEXT,
  UNIQUE (owner_id, id),
  UNIQUE (owner_id, transaction_id, storage_ref),
  FOREIGN KEY (owner_id, transaction_id) REFERENCES transactions(owner_id, id)
    ON DELETE CASCADE
);

-- Existing rows carried no hash/path. Keep them readable with a deterministic placeholder hash
-- and a legacy-* marker rather than dropping user metadata during the upgrade.
INSERT INTO attachments_v2 (id, owner_id, transaction_id, local_file_id, device_ref, mime,
  size_bytes, sha256, storage_ref, sensitive_ciphertext, sensitive_iv, sensitive_tag,
  key_version, revision, created_at_ms, created_at, updated_at, deleted_at)
SELECT id, owner_id, transaction_id, local_file_id, device_ref, mime, size_bytes,
  printf('%064d', 0), 'legacy-' || id, sensitive_ciphertext, sensitive_iv, sensitive_tag,
  key_version, revision, CAST(strftime('%s', created_at) AS INTEGER) * 1000,
  created_at, updated_at, deleted_at
FROM attachments;

DROP TABLE attachments;
ALTER TABLE attachments_v2 RENAME TO attachments;

CREATE INDEX idx_attachments_owner_transaction
  ON attachments(owner_id, transaction_id, deleted_at);
CREATE INDEX idx_attachments_owner_created
  ON attachments(owner_id, created_at DESC, id DESC);
