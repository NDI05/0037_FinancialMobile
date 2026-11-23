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

// EnqueueAutoCategoryJob records an eligible transaction at its current revision. Idempotent: the
// UNIQUE(owner, transaction, revision) index means a re-delivered import batch cannot enqueue twice,
// and a job that already reached a terminal state is never resurrected.
func (s *Store) EnqueueAutoCategoryJob(ctx context.Context, ownerID, transactionID string, revision int, merchantKey string, now time.Time) (bool, error) {
	res, err := s.DB.ExecContext(ctx, `INSERT INTO category_jobs
		(id,owner_id,transaction_id,expected_revision,state,created_at,updated_at,merchant_key)
		VALUES(?,?,?,?,'PENDING',?,?,?)
		ON CONFLICT(owner_id,transaction_id,expected_revision) DO NOTHING`,
		NewID("catjob"), ownerID, transactionID, revision, nowString(now), nowString(now), merchantKey)
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

// ListQueuedCategoryJobs returns at most limit PENDING jobs whose merchant keys are all distinct,
// oldest first, so one request never pays twice for the same merchant. Jobs sharing a key with an
// earlier pick stay queued for the next drain instead of being dropped.
func (s *Store) ListQueuedCategoryJobs(ctx context.Context, ownerID string, limit int) ([]CategoryJob, error) {
	rows, err := s.DB.QueryContext(ctx, categoryJobColumns+
		`WHERE owner_id=? AND state='PENDING' ORDER BY created_at LIMIT ?`, ownerID, limit*8)
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
