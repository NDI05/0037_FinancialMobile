-- Merchant-label repair identity (P0: Livin QR "Penerima" vs Merchant PAN).
--
-- The reparsed business name changes the dedupe fingerprint of an already-imported row, so the
-- server must be able to recognize the *same notification* after the repair. The device sends
-- the opaque Gmail reference UIDVALIDITY:UID; it is sealed like every other sensitive value and
-- a keyed hash (not the reference itself) carries the per-owner uniqueness that a future re-import
-- will collide with instead of inserting a second row.
--
-- All columns are additive and nullable: rows imported before this migration simply have no
-- source reference until the repair pass adopts them.
ALTER TABLE transactions ADD COLUMN source_ref_ciphertext BLOB;
ALTER TABLE transactions ADD COLUMN source_ref_iv BLOB;
ALTER TABLE transactions ADD COLUMN source_ref_tag BLOB;
ALTER TABLE transactions ADD COLUMN source_ref_key_version INTEGER;
ALTER TABLE transactions ADD COLUMN source_ref_hash TEXT;

CREATE UNIQUE INDEX idx_transactions_owner_source_ref
  ON transactions(owner_id, source_ref_hash)
  WHERE source_ref_hash IS NOT NULL;
