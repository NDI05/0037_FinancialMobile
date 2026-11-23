package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// CategoryQueueState is the per-owner drain clock. The 2-hour target lives here instead of in the
// client so "check often, call rarely" is a server guarantee: more frequent polls see the due time
// and return without touching the provider.
type CategoryQueueState struct {
	OwnerID     string
	NextDrainAt *time.Time
	LastDrainAt *time.Time
	LastResult  string
	UpdatedAt   time.Time
}

// EnqueueAutoCategoryJob records an eligible transaction at its current revision. dueAt is durable
// on the row so both the HTTP drain and the backend recovery worker wait for the same window; a
// job enqueued right after a drain therefore cannot be classified by a recovery tick seconds later.
// Idempotent: the UNIQUE(owner, transaction, revision) index means a re-delivered import batch
// cannot enqueue twice, and a job that already reached a terminal state is never resurrected.
func (s *Store) EnqueueAutoCategoryJob(ctx context.Context, ownerID, transactionID string, revision int, merchantKey string, dueAt, now time.Time) (bool, error) {
	res, err := s.DB.ExecContext(ctx, `INSERT INTO category_jobs
		(id,owner_id,transaction_id,expected_revision,state,created_at,updated_at,merchant_key,due_at_unix)
		VALUES(?,?,?,?,'PENDING',?,?,?,?)
		ON CONFLICT(owner_id,transaction_id,expected_revision) DO NOTHING`,
		NewID("catjob"), ownerID, transactionID, revision, nowString(now), nowString(now), merchantKey, dueAt.UnixMilli())
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n == 1, nil
}

// CountQueuedCategoryJobs is the backlog the status panel shows. It counts every PENDING job, not
// just the ones this drain can afford.
func (s *Store) CountQueuedCategoryJobs(ctx context.Context, ownerID string) (int, error) {
	var n int
	err := s.DB.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM category_jobs WHERE owner_id=? AND state='PENDING'`, ownerID).Scan(&n)
	return n, err
}

// ListQueuedCategoryJobs returns at most limit PENDING jobs whose merchant keys are all distinct and
// whose durable due time has been reached, oldest first, so one request never pays twice for the
// same merchant. Jobs sharing a key with an earlier pick stay queued for the next batch instead of
// being dropped.
func (s *Store) ListQueuedCategoryJobs(ctx context.Context, ownerID string, limit int, now time.Time) ([]CategoryJob, error) {
	rows, err := s.DB.QueryContext(ctx, categoryJobColumns+
		`WHERE owner_id=? AND state='PENDING' AND due_at_unix <= ?
		ORDER BY created_at LIMIT ?`, ownerID, now.UnixMilli(), limit*8)
	if err != nil {
		return nil, err
	}
	var candidates []CategoryJob
	for rows.Next() {
		job, err := scanCategoryJob(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		candidates = append(candidates, job)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	keys, err := s.merchantKeysFor(ctx, ownerID, candidates)
	if err != nil {
		return nil, err
	}
	var picked []CategoryJob
	seen := map[string]bool{}
	for _, job := range candidates {
		if len(picked) >= limit {
			break
		}
		key := keys[job.ID]
		if key != "" && seen[key] {
			continue
		}
		seen[key] = true
		picked = append(picked, job)
	}
	return picked, nil
}

// ListAllQueuedCategoryJobs returns PENDING jobs in creation order regardless of merchant or due
// time. It exists for the provider-free cache pass: a transaction whose merchant is already known
// must be categorized at enqueue time instead of waiting for the next window.
func (s *Store) ListAllQueuedCategoryJobs(ctx context.Context, ownerID string, limit int) ([]CategoryJob, error) {
	rows, err := s.DB.QueryContext(ctx, categoryJobColumns+
		`WHERE owner_id=? AND state='PENDING' ORDER BY created_at LIMIT ?`, ownerID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var jobs []CategoryJob
	for rows.Next() {
		job, err := scanCategoryJob(rows)
		if err != nil {
			return nil, err
		}
		jobs = append(jobs, job)
	}
	return jobs, rows.Err()
}

func (s *Store) merchantKeysFor(ctx context.Context, ownerID string, jobs []CategoryJob) (map[string]string, error) {
	out := make(map[string]string, len(jobs))
	for _, job := range jobs {
		var key string
		err := s.DB.QueryRowContext(ctx,
			`SELECT merchant_key FROM category_jobs WHERE owner_id=? AND id=?`, ownerID, job.ID).Scan(&key)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return nil, err
		}
		out[job.ID] = key
	}
	return out, nil
}

// DeferQueuedCategoryJobs pushes every PENDING job of one owner to dueAt. It is the remainder policy:
// work that did not fit a window (batch cap, budget, concurrency) is carried to the next window
// instead of being retried by the recovery tick seconds later.
func (s *Store) DeferQueuedCategoryJobs(ctx context.Context, ownerID string, dueAt time.Time) (int, error) {
	res, err := s.DB.ExecContext(ctx,
		`UPDATE category_jobs SET due_at_unix=? WHERE owner_id=? AND state='PENDING'`,
		dueAt.UnixMilli(), ownerID)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// DueQueueOwners lists owners that have at least one PENDING job whose window opened, least recently
// drained first. The backend scheduler drains these without waiting for the client to poll, and the
// ordering keeps one busy owner from starving the others.
func (s *Store) DueQueueOwners(ctx context.Context, now time.Time, limit int) ([]string, error) {
	rows, err := s.DB.QueryContext(ctx,
		`SELECT j.owner_id
		 FROM category_jobs j
		 LEFT JOIN category_queue_state q ON q.owner_id = j.owner_id
		 WHERE j.state='PENDING' AND j.due_at_unix <= ?
		 GROUP BY j.owner_id
		 ORDER BY COALESCE(q.last_drain_at,'') ASC
		 LIMIT ?`, now.UnixMilli(), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var owners []string
	for rows.Next() {
		var owner string
		if err := rows.Scan(&owner); err != nil {
			return nil, err
		}
		owners = append(owners, owner)
	}
	return owners, rows.Err()
}

// CategoryQueueStateFor reads the drain clock; an owner with no row has never drained.
func (s *Store) CategoryQueueStateFor(ctx context.Context, ownerID string) (CategoryQueueState, error) {
	state := CategoryQueueState{OwnerID: ownerID}
	var next, last string
	var updated string
	err := s.DB.QueryRowContext(ctx,
		`SELECT COALESCE(next_drain_at,''),COALESCE(last_drain_at,''),COALESCE(last_result,''),updated_at
		 FROM category_queue_state WHERE owner_id=?`, ownerID).Scan(&next, &last, &state.LastResult, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return state, nil
	}
	if err != nil {
		return state, err
	}
	if next != "" {
		if parsed, perr := time.Parse(time.RFC3339Nano, next); perr == nil {
			state.NextDrainAt = &parsed
		}
	}
	if last != "" {
		if parsed, perr := time.Parse(time.RFC3339Nano, last); perr == nil {
			state.LastDrainAt = &parsed
		}
	}
	return state, nil
}

// SaveCategoryQueueState records the outcome of one drain. Written even when the drain was a no-op
// so the next poll gets an explicit due time instead of guessing from the client's clock.
func (s *Store) SaveCategoryQueueState(ctx context.Context, ownerID string, next, last *time.Time, result string, now time.Time) error {
	tt := func(v *time.Time) string {
		if v == nil {
			return ""
		}
		return nowString(*v)
	}
	_, err := s.DB.ExecContext(ctx, `INSERT INTO category_queue_state(owner_id,next_drain_at,last_drain_at,last_result,updated_at)
		VALUES(?,?,?,?,?) ON CONFLICT(owner_id) DO UPDATE SET
		next_drain_at=excluded.next_drain_at,last_drain_at=excluded.last_drain_at,
		last_result=excluded.last_result,updated_at=excluded.updated_at`,
		ownerID, tt(next), tt(last), result, nowString(now))
	return err
}
