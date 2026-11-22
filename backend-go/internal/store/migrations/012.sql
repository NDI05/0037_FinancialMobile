-- Lease fencing: every claim/reclaim mints a new token. Finishing, requeueing, applying a
-- category and writing provider results are conditional on the token the caller was granted, so a
-- worker whose lease expired can never write after ownership moved to a recovery worker.
ALTER TABLE category_jobs ADD COLUMN lease_token TEXT NOT NULL DEFAULT '';

-- Cache attribution version: entries written while provider results were matched by array
-- position are never read again (they are treated as a miss and re-derived), but they are not
-- deleted and never touch a USER-selected category.
ALTER TABLE category_cache ADD COLUMN mapping_version TEXT NOT NULL DEFAULT '';

-- Lease expiry is compared numerically (unix millis) so mixed/absent fractional-second text
-- formats can never make an expired lease look live or a live lease look expired.
ALTER TABLE category_jobs ADD COLUMN lease_until_unix INTEGER;
CREATE INDEX IF NOT EXISTS idx_category_jobs_lease_unix ON category_jobs(state, lease_until_unix);
