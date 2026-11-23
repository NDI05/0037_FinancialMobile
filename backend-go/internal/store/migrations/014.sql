-- Durable due time per queued job (P3 follow-up). The queue window is a property of the job, not
-- only of an in-memory worker: a PENDING job that a client just enqueued must not be picked up by
-- the recovery worker before its window opens, otherwise every recovery tick would silently skip
-- the 2-hour batching target. 0 means "already due" for rows written before this migration and for
-- an explicit manual retry, which is allowed to run immediately.
ALTER TABLE category_jobs ADD COLUMN due_at_unix INTEGER NOT NULL DEFAULT 0;

-- Recovery scans for jobs whose window has opened; drain scans the same way per owner.
CREATE INDEX IF NOT EXISTS idx_category_jobs_due ON category_jobs(state, due_at_unix);
