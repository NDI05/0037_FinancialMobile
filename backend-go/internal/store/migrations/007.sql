-- R5: bearer receipt for deletion status after sessions are revoked.
ALTER TABLE deletion_requests ADD COLUMN receipt_hash TEXT NOT NULL DEFAULT '';
CREATE INDEX idx_deletion_receipt ON deletion_requests(receipt_hash);
