package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"
)

// CategoryLeaseDuration is how long one RUNNING claim owns a job before another worker may
// recover it. It is longer than one provider attempt (20s timeout + retry + apply) so a live
// worker never loses its job to the recovery sweep; a dead worker is recovered after expiry.
const CategoryLeaseDuration = 5 * time.Minute

// ErrLeaseLost means the caller no longer owns the job: the lease expired and a recovery worker
// reclaimed it (minting a new token). The write was refused, not applied partially.
var ErrLeaseLost = errors.New("category job lease lost")

// CategoryJob is one durable classification attempt for an owner-scoped transaction at a fixed
// revision. LeaseUntil guards run ownership for recovery; RetryCount bounds transient retries.
type CategoryJob struct {
	ID, OwnerID, TransactionID                                             string
	ExpectedRevision                                                       int
	State                                                                  string
	CategoryCode                                                           string
	Confidence                                                             float64
	Provider, Model, PromptVersion, TaxonomyVersion, ReasonCode, ErrorCode string
	// LeaseToken is minted on every claim/reclaim. Writers pass it back so only the holder at that
	// moment may finish, requeue, apply a category or store a provider result for the job.
	LeaseToken           string
	LeaseUntil           *time.Time
	RetryCount           int
	CreatedAt, UpdatedAt time.Time
}

// CategoryCacheGet only trusts entries written under the current attribution mapping version.
// Rows from an older mapping are treated as a miss (never deleted), so a stale positional match can
// never be replayed onto a transaction while real data and USER choices are untouched.
func (s *Store) CategoryCacheGet(ctx context.Context, ownerID, key, direction, method, taxonomy, model, prompt, mappingVersion string) (string, bool, string, error) {
	var code, reason string
	var review int
	err := s.DB.QueryRowContext(ctx, `SELECT category_code, needs_review, reason_code FROM category_cache
		WHERE owner_id=? AND cache_key=? AND direction=? AND method=? AND taxonomy_version=? AND model=? AND prompt_version=? AND mapping_version=?`,
		ownerID, key, direction, method, taxonomy, model, prompt, mappingVersion).Scan(&code, &review, &reason)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, "", nil
	}
	return code, review == 1, reason, err
}

// CategoryCachePut stores a provider result only while the writer still holds the job lease. A
// worker whose lease expired skips the write (no error): the recovered owner will classify and
// store the same merchant result under its own lease.
func (s *Store) CategoryCachePut(ctx context.Context, ownerID, jobID, leaseToken, key, direction, method, taxonomy, model, prompt, mappingVersion, code, reason string, review bool, now time.Time) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	held, err := s.leaseHeld(ctx, tx, ownerID, jobID, leaseToken, now)
	if err != nil {
		return err
	}
	if !held {
		return tx.Commit()
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO category_cache
		(owner_id,cache_key,direction,method,taxonomy_version,model,prompt_version,mapping_version,category_code,needs_review,reason_code,updated_at)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
		ON CONFLICT(owner_id,cache_key,taxonomy_version,model,prompt_version) DO UPDATE SET
		mapping_version=excluded.mapping_version,category_code=excluded.category_code,
		needs_review=excluded.needs_review,reason_code=excluded.reason_code,updated_at=excluded.updated_at`,
		ownerID, key, direction, method, taxonomy, model, prompt, mappingVersion, code, boolInt(review), reason, nowString(now)); err != nil {
		return err
	}
	return tx.Commit()
}

// rowQueryer is satisfied by both *sql.DB and *sql.Tx, so a lease check can run standalone or
// inside the same transaction as the write it guards.
type rowQueryer interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// leaseHeld reports whether jobID is RUNNING with exactly leaseToken and an unexpired lease,
// using the same numeric expiry the recovery sweep uses.
func (s *Store) leaseHeld(ctx context.Context, q rowQueryer, ownerID, jobID, leaseToken string, now time.Time) (bool, error) {
	var one int
	err := q.QueryRowContext(ctx, `SELECT 1 FROM category_jobs
		WHERE owner_id=? AND id=? AND state='RUNNING' AND lease_token=? AND lease_until_unix > ?`,
		ownerID, jobID, leaseToken, now.UnixMilli()).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

func CacheKey(merchant, direction, method string) string {
	sum := sha256.Sum256([]byte(merchant + "\x1f" + direction + "\x1f" + method))
	return hex.EncodeToString(sum[:])
}

// ReserveCategoryCall atomically checks owner/installation attempts plus global daily/monthly
// budgets and returns a durable reservation ID. The reservation is settled by ID, using the
// buckets captured here, so crossing midnight/month-end cannot hide spend.
func (s *Store) ReserveCategoryCall(ctx context.Context, ownerID string, ownerCap, installCap int,
	dailyCap, monthlyCap, reserveUSD float64, now time.Time) (string, bool, error) {
	day, _ := JakartaBuckets(now)
	month := day[:7]
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return "", false, err
	}
	defer func() { _ = tx.Rollback() }()
	type bound struct {
		scope, bucket string
		capAttempts   int
		capUSD        float64
	}
	bounds := []bound{
		{ownerID, "day:" + day, ownerCap, dailyCap},
		{ownerID, "month:" + month, 0, monthlyCap},
		{"*", "day:" + day, installCap, dailyCap},
		{"*", "month:" + month, 0, monthlyCap},
	}
	for _, b := range bounds {
		var attempts int
		var reserved, actual float64
		err := tx.QueryRowContext(ctx, `SELECT attempts,reserved_usd,actual_usd FROM category_usage WHERE scope=? AND bucket=?`, b.scope, b.bucket).Scan(&attempts, &reserved, &actual)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return "", false, err
		}
		if b.capAttempts > 0 && attempts >= b.capAttempts {
			return "", false, nil
		}
		if b.capUSD > 0 && actual+reserved+reserveUSD > b.capUSD+1e-12 {
			return "", false, nil
		}
	}
	for _, b := range bounds {
		if _, err := tx.ExecContext(ctx, `INSERT INTO category_usage(scope,bucket,attempts,reserved_usd,actual_usd) VALUES(?,?,1,?,0)
			ON CONFLICT(scope,bucket) DO UPDATE SET attempts=category_usage.attempts+1,reserved_usd=category_usage.reserved_usd+excluded.reserved_usd`,
			b.scope, b.bucket, reserveUSD); err != nil {
			return "", false, err
		}
	}
	id := NewID("catres")
	if _, err := tx.ExecContext(ctx, `INSERT INTO category_reservations
		(id,owner_id,day_bucket,month_bucket,reserve_usd,estimated_usd,actual_usd,usage_unknown,state,created_at)
		VALUES(?,?,?,?,?,?,0,0,'RESERVED',?)`, id, ownerID, day, month, reserveUSD, reserveUSD, nowString(now)); err != nil {
		return "", false, err
	}
	if err := tx.Commit(); err != nil {
		return "", false, err
	}
	return id, true, nil
}

// SettleCategoryReservation replaces the conservative reservation with measured/estimated cost.
// Unknown outcomes keep conservative cost and mark usage_unknown.
//
// Settlement is idempotent and exactly-once:
//   - terminal reservations (SETTLED or UNKNOWN) are never charged a second time, so replaying a
//     settlement with the same reservation ID after an UNKNOWN outcome cannot double-record cost;
//   - the state transition RESERVED -> terminal is conditional, so concurrent settlements of the
//     same reservation apply the cost deltas exactly once; the loser is a no-op.
func (s *Store) SettleCategoryReservation(ctx context.Context, ownerID, reservationID string,
	estimatedUSD, actualUSD float64, usageKnown bool, now time.Time) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	var day, month, state string
	var reserve float64
	if err := tx.QueryRowContext(ctx, `SELECT day_bucket,month_bucket,reserve_usd,state FROM category_reservations WHERE id=? AND owner_id=?`, reservationID, ownerID).Scan(&day, &month, &reserve, &state); err != nil {
		return err
	}
	switch state {
	case "SETTLED", "UNKNOWN":
		// Terminal: replaying must not charge again, and there is nothing to reconcile here.
		return tx.Commit()
	case "RESERVED":
	default:
		return fmt.Errorf("category reservation %s in unexpected state %q", reservationID, state)
	}
	settled := actualUSD
	if !usageKnown {
		settled = reserve
	}
	nextState := "SETTLED"
	if !usageKnown {
		nextState = "UNKNOWN"
	}
	res, err := tx.ExecContext(ctx, `UPDATE category_reservations
		SET estimated_usd=?,actual_usd=?,usage_unknown=?,state=?,settled_at=?
		WHERE id=? AND owner_id=? AND state='RESERVED'`,
		estimatedUSD, settled, boolInt(!usageKnown), nextState, nowString(now), reservationID, ownerID)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n != 1 {
		// Another transaction settled (or terminal) first: exactly-once means we do no cost deltas.
		return tx.Commit()
	}
	for _, b := range []struct{ scope, bucket string }{{ownerID, "day:" + day}, {ownerID, "month:" + month}, {"*", "day:" + day}, {"*", "month:" + month}} {
		if _, err := tx.ExecContext(ctx, `UPDATE category_usage SET reserved_usd=MAX(0,reserved_usd-?), actual_usd=actual_usd+? WHERE scope=? AND bucket=?`, reserve, settled, b.scope, b.bucket); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// ReconcileCategoryReservation transitions an UNKNOWN settlement to SETTLED once measured usage
// arrives, applying only the delta between the measured USD and the conservative reserve that was
// already recorded as actual. The UNKNOWN->SETTLED transition is conditional, so replays and
// concurrent reconciliations are exactly-once no-ops after the first winner.
func (s *Store) ReconcileCategoryReservation(ctx context.Context, ownerID, reservationID string,
	measuredUSD float64, now time.Time) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	var day, month, state string
	var reserve, priorActual float64
	if err := tx.QueryRowContext(ctx, `SELECT day_bucket,month_bucket,reserve_usd,actual_usd,state FROM category_reservations WHERE id=? AND owner_id=?`, reservationID, ownerID).Scan(&day, &month, &reserve, &priorActual, &state); err != nil {
		return err
	}
	if state == "SETTLED" {
		return tx.Commit()
	} // already settled (with or without known usage)
	if state != "UNKNOWN" {
		return fmt.Errorf("category reservation %s cannot be reconciled in state %q", reservationID, state)
	}
	res, err := tx.ExecContext(ctx, `UPDATE category_reservations
		SET estimated_usd=?,actual_usd=?,usage_unknown=0,state='SETTLED',settled_at=?
		WHERE id=? AND owner_id=? AND state='UNKNOWN'`,
		measuredUSD, measuredUSD, nowString(now), reservationID, ownerID)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n != 1 {
		return tx.Commit()
	} // a concurrent reconciliation won; do not double-apply the delta
	delta := measuredUSD - priorActual
	for _, b := range []struct{ scope, bucket string }{{ownerID, "day:" + day}, {ownerID, "month:" + month}, {"*", "day:" + day}, {"*", "month:" + month}} {
		if _, err := tx.ExecContext(ctx, `UPDATE category_usage SET actual_usd=MAX(0,actual_usd+?) WHERE scope=? AND bucket=?`, delta, b.scope, b.bucket); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// SweepOrphanCategoryReservations refunds reservations that stayed RESERVED past the lease window
// (the process died between ReserveCategoryCall and settlement). They are settled conservatively as
// UNKNOWN at the reserved amount so spend is never silently lost and reserved_usd stops leaking.
//
// Each orphan is refunded in ONE transaction: the conditional RESERVED -> UNKNOWN transition and
// all four owner/installation day/month counter deltas either commit together or not at all. A
// crash or injected failure before COMMIT leaves the reservation RESERVED with every counter
// untouched, so the next sweep (or a manual reconciliation) retries the same work; there is no
// state where the reservation is terminal but some counters were only partially adjusted.
func (s *Store) SweepOrphanCategoryReservations(ctx context.Context, createdBefore time.Time, now time.Time) (int, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT id, owner_id, day_bucket, month_bucket, reserve_usd
		FROM category_reservations WHERE state='RESERVED' AND created_at < ? ORDER BY created_at`, nowString(createdBefore))
	if err != nil {
		return 0, err
	}
	type orphan struct {
		id, owner, day, month string
		reserve               float64
	}
	var orphans []orphan
	for rows.Next() {
		var o orphan
		if err := rows.Scan(&o.id, &o.owner, &o.day, &o.month, &o.reserve); err != nil {
			rows.Close()
			return 0, err
		}
		orphans = append(orphans, o)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	swept := 0
	for _, o := range orphans {
		done, err := s.sweepOrphanReservation(ctx, o.id, o.owner, o.day, o.month, o.reserve, now)
		if err != nil {
			return swept, err
		}
		if done {
			swept++
		}
	}
	return swept, nil
}

// sweepOrphanReservation performs the conditional transition and every counter delta atomically.
func (s *Store) sweepOrphanReservation(ctx context.Context, id, owner, day, month string, reserve float64, now time.Time) (bool, error) {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer func() { _ = tx.Rollback() }()
	res, err := tx.ExecContext(ctx, `UPDATE category_reservations
		SET estimated_usd=?,actual_usd=?,usage_unknown=1,state='UNKNOWN',settled_at=?
		WHERE id=? AND state='RESERVED'`, reserve, reserve, nowString(now), id)
	if err != nil {
		return false, err
	}
	if n, _ := res.RowsAffected(); n != 1 {
		// Settled (or reconciled) in-flight: not an orphan. Nothing to adjust.
		return false, tx.Commit()
	}
	for _, b := range []struct{ scope, bucket string }{{owner, "day:" + day}, {owner, "month:" + month}, {"*", "day:" + day}, {"*", "month:" + month}} {
		if _, err := tx.ExecContext(ctx, `UPDATE category_usage SET reserved_usd=MAX(0,reserved_usd-?), actual_usd=actual_usd+?
			WHERE scope=? AND bucket=?`, reserve, reserve, b.scope, b.bucket); err != nil {
			return false, err
		}
	}
	if err := tx.Commit(); err != nil {
		return false, err
	}
	return true, nil
}

func (s *Store) CreateCategoryJob(ctx context.Context, ownerID, transactionID string, revision int, now time.Time) (CategoryJob, error) {
	id := NewID("catjob")
	_, err := s.DB.ExecContext(ctx, `INSERT INTO category_jobs(id,owner_id,transaction_id,expected_revision,state,created_at,updated_at)
		VALUES(?,?,?,?,'PENDING',?,?) ON CONFLICT(owner_id,transaction_id,expected_revision) DO NOTHING`,
		id, ownerID, transactionID, revision, nowString(now), nowString(now))
	if err != nil {
		return CategoryJob{}, err
	}
	row := s.DB.QueryRowContext(ctx, categoryJobColumns+`WHERE owner_id=? AND transaction_id=? AND expected_revision=?`, ownerID, transactionID, revision)
	return scanCategoryJob(row)
}

// ClaimCategoryJob atomically moves one PENDING job to RUNNING under a fresh lease token. Duplicate
// taps lose the claim and must replay the existing job rather than call the provider again. The
// returned token is the caller's only proof of ownership for every later write.
func (s *Store) ClaimCategoryJob(ctx context.Context, ownerID, id string, now time.Time, lease time.Duration) (string, bool, error) {
	token := NewID("catlease")
	res, err := s.DB.ExecContext(ctx, `UPDATE category_jobs SET state='RUNNING',lease_token=?,lease_until=?,lease_until_unix=?,updated_at=?
		WHERE owner_id=? AND id=? AND state='PENDING'`,
		token, nowString(now.Add(lease)), now.Add(lease).UnixMilli(), nowString(now), ownerID, id)
	if err != nil {
		return "", false, err
	}
	if n, _ := res.RowsAffected(); n != 1 {
		return "", false, nil
	}
	return token, true, nil
}

// ClaimCategoryJobsForRecovery reclaims due PENDING jobs and RUNNING jobs whose lease expired (the
// worker that owned them died). A PENDING job whose durable due time is still in the future is NOT
// reclaimed: the queue window belongs to the row, so a recovery tick cannot shortcut the batching
// target. Each reclaim is a conditional UPDATE, so a live worker with an unexpired lease cannot lose
// its job to this sweep.
func (s *Store) ClaimCategoryJobsForRecovery(ctx context.Context, now time.Time, lease time.Duration, limit int) ([]CategoryJob, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT owner_id,id FROM category_jobs
		WHERE (state='PENDING' AND due_at_unix <= ?)
		   OR (state='RUNNING' AND (lease_until_unix IS NULL OR lease_until_unix <= ?))
		ORDER BY created_at LIMIT ?`, now.UnixMilli(), now.UnixMilli(), limit)
	if err != nil {
		return nil, err
	}
	type ref struct{ owner, id string }
	var refs []ref
	for rows.Next() {
		var r ref
		if err := rows.Scan(&r.owner, &r.id); err != nil {
			rows.Close()
			return nil, err
		}
		refs = append(refs, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	var out []CategoryJob
	for _, r := range refs {
		token := NewID("catlease")
		res, err := s.DB.ExecContext(ctx, `UPDATE category_jobs SET state='RUNNING',lease_token=?,lease_until=?,lease_until_unix=?,updated_at=?
			WHERE owner_id=? AND id=? AND ((state='PENDING' AND due_at_unix <= ?)
			   OR (state='RUNNING' AND (lease_until_unix IS NULL OR lease_until_unix <= ?)))`,
			token, nowString(now.Add(lease)), now.Add(lease).UnixMilli(), nowString(now), r.owner, r.id, now.UnixMilli(), now.UnixMilli())
		if err != nil {
			return out, err
		}
		n, _ := res.RowsAffected()
		if n != 1 {
			continue
		}
		job, err := s.GetCategoryJob(ctx, r.owner, r.id)
		if err != nil {
			return out, err
		}
		out = append(out, job)
	}
	return out, nil
}

// RequeueCategoryJob returns a RUNNING job to PENDING for a bounded transient retry or a graceful
// shutdown. retry_count increments so the policy can stop after one retry.
func (s *Store) RequeueCategoryJob(ctx context.Context, ownerID, id, leaseToken string, now time.Time) (CategoryJob, error) {
	res, err := s.DB.ExecContext(ctx, `UPDATE category_jobs SET state='PENDING',lease_token='',lease_until=NULL,lease_until_unix=NULL,retry_count=retry_count+1,updated_at=?
		WHERE owner_id=? AND id=? AND state='RUNNING' AND lease_token=? AND lease_until_unix > ?`,
		nowString(now), ownerID, id, leaseToken, now.UnixMilli())
	if err != nil {
		return CategoryJob{}, err
	}
	n, _ := res.RowsAffected()
	if n != 1 {
		return CategoryJob{}, s.jobWriteRefusal(ctx, ownerID, id)
	}
	return s.GetCategoryJob(ctx, ownerID, id)
}

// jobWriteRefusal distinguishes "the row is gone / not mine" from "a recovery worker owns it now".
func (s *Store) jobWriteRefusal(ctx context.Context, ownerID, id string) error {
	var one int
	err := s.DB.QueryRowContext(ctx, `SELECT 1 FROM category_jobs WHERE owner_id=? AND id=?`, ownerID, id).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	return ErrLeaseLost
}

const categoryJobColumns = `SELECT id,owner_id,transaction_id,expected_revision,state,
	COALESCE(category_code,''),COALESCE(confidence,0),COALESCE(provider,''),COALESCE(model,''),
	COALESCE(prompt_version,''),COALESCE(taxonomy_version,''),COALESCE(reason_code,''),
	COALESCE(error_code,''),COALESCE(lease_token,''),COALESCE(lease_until,''),COALESCE(retry_count,0),created_at,updated_at
	FROM category_jobs `

func (s *Store) GetCategoryJob(ctx context.Context, ownerID, id string) (CategoryJob, error) {
	row := s.DB.QueryRowContext(ctx, categoryJobColumns+`WHERE owner_id=? AND id=?`, ownerID, id)
	job, err := scanCategoryJob(row)
	if errors.Is(err, sql.ErrNoRows) {
		return CategoryJob{}, ErrNotFound
	}
	return job, err
}

// ResetFailedCategoryJob makes an explicit user retry possible: a terminal FAILED job at the same
// transaction revision returns to PENDING with a fresh bounded retry budget. CLASSIFIED and
// NEEDS_REVIEW jobs are never reset, so a user-visible review decision is not re-litigated and a
// finished classification cannot be re-billed by tapping the button again.
func (s *Store) ResetFailedCategoryJob(ctx context.Context, ownerID, id string, now time.Time) (CategoryJob, error) {
	res, err := s.DB.ExecContext(ctx, `UPDATE category_jobs
		SET state='PENDING', error_code='', reason_code='', retry_count=0, lease_token='', lease_until=NULL, lease_until_unix=NULL, updated_at=?
		WHERE owner_id=? AND id=? AND state='FAILED'`, nowString(now), ownerID, id)
	if err != nil {
		return CategoryJob{}, fmt.Errorf("reset failed job: %w", err)
	}
	if n, _ := res.RowsAffected(); n != 1 {
		return CategoryJob{}, ErrNotFound
	}
	return s.GetCategoryJob(ctx, ownerID, id)
}

// FinishCategoryJob moves a job to a terminal state only while the caller still holds the lease it
// was granted. Lease loss returns ErrLeaseLost and the row is left untouched, so a stale worker can
// never overwrite the terminal state written by the worker that reclaimed it.
func (s *Store) FinishCategoryJob(ctx context.Context, ownerID, id, leaseToken, state, code, reason, errorCode, provider, model, prompt, taxonomy string, confidence float64, now time.Time) error {
	res, err := s.DB.ExecContext(ctx, `UPDATE category_jobs SET state=?,category_code=?,confidence=?,provider=?,model=?,prompt_version=?,taxonomy_version=?,reason_code=?,error_code=?,lease_until=NULL,lease_until_unix=NULL,lease_token='',updated_at=?
		WHERE owner_id=? AND id=? AND state='RUNNING' AND lease_token=? AND lease_until_unix > ?`,
		state, code, confidence, provider, model, prompt, taxonomy, reason, errorCode, nowString(now), ownerID, id, leaseToken, now.UnixMilli())
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n != 1 {
		return s.jobWriteRefusal(ctx, ownerID, id)
	}
	return nil
}

func scanCategoryJob(row rowScanner) (CategoryJob, error) {
	var j CategoryJob
	var created, updated, lease string
	var retry int
	if err := row.Scan(&j.ID, &j.OwnerID, &j.TransactionID, &j.ExpectedRevision, &j.State, &j.CategoryCode, &j.Confidence, &j.Provider, &j.Model, &j.PromptVersion, &j.TaxonomyVersion, &j.ReasonCode, &j.ErrorCode, &j.LeaseToken, &lease, &retry, &created, &updated); err != nil {
		return CategoryJob{}, err
	}
	if lease != "" {
		t := parseTime(lease)
		j.LeaseUntil = &t
	}
	j.RetryCount = retry
	j.CreatedAt = parseTime(created)
	j.UpdatedAt = parseTime(updated)
	return j, nil
}

// ApplyLLMCategory updates only a non-USER row at the expected revision and bumps month revision.
//
// The consent, deletion and revision guards all live inside the single UPDATE statement, so the
// apply is atomic with respect to an intervening consent withdrawal, deletion request, row
// deletion or USER override: the row only changes when all of them still hold at commit time.
// Failures collapse to ErrRevisionConflict; callers classify the specific reason with a read-only
// pre-check for diagnostics, never as the authoritative guard.
func (s *Store) ApplyLLMCategory(ctx context.Context, ownerID, transactionID string, expectedRevision int, jobID, leaseToken, categoryID, code, provider, model, prompt, taxonomy, reason string, confidence float64, now time.Time) (Transaction, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT month_id, category_origin FROM transactions WHERE owner_id=? AND id=? AND deleted_at IS NULL`, ownerID, transactionID)
	var month, origin string
	if err := row.Scan(&month, &origin); err != nil {
		return Transaction{}, err
	}
	if origin == "USER" {
		return Transaction{}, ErrRevisionConflict
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return Transaction{}, err
	}
	defer func() { _ = tx.Rollback() }()
	res, err := tx.ExecContext(ctx, `UPDATE transactions SET category_id=?,category_origin='RULE',
		classifier_origin='LLM',category_confidence=?,category_rule_version=?,category_reason=?,
		category_taxonomy_version=?,revision=revision+1,updated_at=?
		WHERE owner_id=? AND id=? AND revision=? AND deleted_at IS NULL AND category_origin!='USER'
		  AND NOT EXISTS (SELECT 1 FROM deletion_requests d
		      WHERE d.owner_id = transactions.owner_id AND d.state='PENDING')
		  AND EXISTS (SELECT 1 FROM consents c
		      WHERE c.owner_id = transactions.owner_id AND c.purpose='AI_CATEGORIZATION'
		        AND c.decision='GRANTED'
		        AND c.created_at = (SELECT MAX(c2.created_at) FROM consents c2
		            WHERE c2.owner_id = transactions.owner_id AND c2.purpose='AI_CATEGORIZATION'))
		  AND EXISTS (SELECT 1 FROM category_jobs j
		      WHERE j.owner_id = transactions.owner_id AND j.id=? AND j.state='RUNNING'
		        AND j.lease_token=? AND j.lease_until_unix > ?)`,
		categoryID, confidence, prompt+"|"+model, reason, taxonomy, nowString(now), ownerID, transactionID, expectedRevision, jobID, leaseToken, now.UnixMilli())
	if err != nil {
		return Transaction{}, err
	}
	n, _ := res.RowsAffected()
	if n != 1 {
		if held, herr := s.leaseHeld(ctx, tx, ownerID, jobID, leaseToken, now); herr == nil && !held {
			return Transaction{}, ErrLeaseLost
		}
		return Transaction{}, ErrRevisionConflict
	}
	if err := bumpMonthRevision(ctx, tx, ownerID, month, now); err != nil {
		return Transaction{}, err
	}
	if err := tx.Commit(); err != nil {
		return Transaction{}, err
	}
	return s.GetTransaction(ctx, ownerID, transactionID)
}

func (s *Store) CategoryIDForCode(ctx context.Context, ownerID, code string) (string, error) {
	var id string
	err := s.DB.QueryRowContext(ctx, `SELECT id FROM categories WHERE owner_id=? AND code=? AND deleted_at IS NULL`, ownerID, code).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", fmt.Errorf("category code not found")
	}
	return id, err
}
