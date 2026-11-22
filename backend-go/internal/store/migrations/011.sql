-- Durable category jobs: lease for run ownership and bounded retry counter.
ALTER TABLE category_jobs ADD COLUMN lease_until TEXT;
ALTER TABLE category_jobs ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_category_jobs_recovery ON category_jobs(state, lease_until);