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

// CategoryJob is one durable classification attempt for an owner-scoped transaction at a fixed
// revision. LeaseUntil guards run ownership for recovery; RetryCount bounds transient retries.
type CategoryJob struct {
	ID, OwnerID, TransactionID string
	ExpectedRevision int
	State string
	CategoryCode string
	Confidence float64
	Provider, Model, PromptVersion, TaxonomyVersion, ReasonCode, ErrorCode string
	LeaseUntil *time.Time
	RetryCount int
	CreatedAt, UpdatedAt time.Time
}

func (s *Store) CategoryCacheGet(ctx context.Context, ownerID, key, direction, method, taxonomy, model, prompt string) (string, bool, string, error) {
	var code, reason string
	var review int
	err := s.DB.QueryRowContext(ctx, `SELECT category_code, needs_review, reason_code FROM category_cache
		WHERE owner_id=? AND cache_key=? AND direction=? AND method=? AND taxonomy_version=? AND model=? AND prompt_version=?`,
		ownerID,key,direction,method,taxonomy,model,prompt).Scan(&code,&review,&reason)
	if errors.Is(err, sql.ErrNoRows) { return "",false,"",nil }
	return code, review==1, reason, err
}

func (s *Store) CategoryCachePut(ctx context.Context, ownerID, key, direction, method, taxonomy, model, prompt, code, reason string, review bool, now time.Time) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO category_cache
		(owner_id,cache_key,direction,method,taxonomy_version,model,prompt_version,category_code,needs_review,reason_code,updated_at)
		VALUES (?,?,?,?,?,?,?,?,?,?,?)
		ON CONFLICT(owner_id,cache_key,taxonomy_version,model,prompt_version) DO UPDATE SET
		category_code=excluded.category_code,needs_review=excluded.needs_review,reason_code=excluded.reason_code,updated_at=excluded.updated_at`,
		ownerID,key,direction,method,taxonomy,model,prompt,code,boolInt(review),reason,nowString(now))
	return err
}

func CacheKey(merchant, direction, method string) string {
	sum:=sha256.Sum256([]byte(merchant+"\x1f"+direction+"\x1f"+method)); return hex.EncodeToString(sum[:])
}

// ReserveCategoryCall atomically checks owner/installation attempts plus global daily/monthly
// budgets and returns a durable reservation ID. The reservation is settled by ID, using the
// buckets captured here, so crossing midnight/month-end cannot hide spend.
func (s *Store) ReserveCategoryCall(ctx context.Context, ownerID string, ownerCap, installCap int,
	dailyCap, monthlyCap, reserveUSD float64, now time.Time) (string, bool, error) {
	day,_:=JakartaBuckets(now); month:=day[:7]
	tx,err:=s.DB.BeginTx(ctx,nil); if err!=nil{return "",false,err}; defer func(){_=tx.Rollback()}()
	type bound struct{ scope,bucket string; capAttempts int; capUSD float64 }
	bounds:=[]bound{
		{ownerID,"day:"+day,ownerCap,dailyCap},
		{ownerID,"month:"+month,0,monthlyCap},
		{"*","day:"+day,installCap,dailyCap},
		{"*","month:"+month,0,monthlyCap},
	}
	for _,b:=range bounds {
		var attempts int; var reserved,actual float64
		err:=tx.QueryRowContext(ctx,`SELECT attempts,reserved_usd,actual_usd FROM category_usage WHERE scope=? AND bucket=?`,b.scope,b.bucket).Scan(&attempts,&reserved,&actual)
		if err!=nil && !errors.Is(err,sql.ErrNoRows){return "",false,err}
		if b.capAttempts>0 && attempts>=b.capAttempts{return "",false,nil}
		if b.capUSD>0 && actual+reserved+reserveUSD>b.capUSD+1e-12{return "",false,nil}
	}
	for _,b:=range bounds {
		if _,err:=tx.ExecContext(ctx,`INSERT INTO category_usage(scope,bucket,attempts,reserved_usd,actual_usd) VALUES(?,?,1,?,0)
			ON CONFLICT(scope,bucket) DO UPDATE SET attempts=category_usage.attempts+1,reserved_usd=category_usage.reserved_usd+excluded.reserved_usd`,
			b.scope,b.bucket,reserveUSD);err!=nil{return "",false,err}
	}
	id:=NewID("catres")
	if _,err:=tx.ExecContext(ctx,`INSERT INTO category_reservations
		(id,owner_id,day_bucket,month_bucket,reserve_usd,estimated_usd,actual_usd,usage_unknown,state,created_at)
		VALUES(?,?,?,?,?,?,0,0,'RESERVED',?)`,id,ownerID,day,month,reserveUSD,reserveUSD,nowString(now));err!=nil{return "",false,err}
	if err:=tx.Commit();err!=nil{return "",false,err}
	return id,true,nil
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
	tx,err:=s.DB.BeginTx(ctx,nil); if err!=nil{return err}; defer func(){_=tx.Rollback()}()
	var day,month,state string; var reserve float64
	if err:=tx.QueryRowContext(ctx,`SELECT day_bucket,month_bucket,reserve_usd,state FROM category_reservations WHERE id=? AND owner_id=?`,reservationID,ownerID).Scan(&day,&month,&reserve,&state);err!=nil{return err}
	switch state {
	case "SETTLED", "UNKNOWN":
		// Terminal: replaying must not charge again, and there is nothing to reconcile here.
		return tx.Commit()
	case "RESERVED":
	default:
		return fmt.Errorf("category reservation %s in unexpected state %q", reservationID, state)
	}
	settled:=actualUSD; if !usageKnown { settled=reserve }
	nextState:="SETTLED"; if !usageKnown { nextState="UNKNOWN" }
	res,err:=tx.ExecContext(ctx,`UPDATE category_reservations
		SET estimated_usd=?,actual_usd=?,usage_unknown=?,state=?,settled_at=?
		WHERE id=? AND owner_id=? AND state='RESERVED'`,
		estimatedUSD,settled,boolInt(!usageKnown),nextState,nowString(now),reservationID,ownerID)
	if err!=nil{return err}
	n,_:=res.RowsAffected()
	if n!=1 {
		// Another transaction settled (or terminal) first: exactly-once means we do no cost deltas.
		return tx.Commit()
	}
	for _,b:=range []struct{scope,bucket string}{{ownerID,"day:"+day},{ownerID,"month:"+month},{"*","day:"+day},{"*","month:"+month}} {
		if _,err:=tx.ExecContext(ctx,`UPDATE category_usage SET reserved_usd=MAX(0,reserved_usd-?), actual_usd=actual_usd+? WHERE scope=? AND bucket=?`,reserve,settled,b.scope,b.bucket);err!=nil{return err}
	}
	return tx.Commit()
}

// ReconcileCategoryReservation transitions an UNKNOWN settlement to SETTLED once measured usage
// arrives, applying only the delta between the measured USD and the conservative reserve that was
// already recorded as actual. The UNKNOWN->SETTLED transition is conditional, so replays and
// concurrent reconciliations are exactly-once no-ops after the first winner.
func (s *Store) ReconcileCategoryReservation(ctx context.Context, ownerID, reservationID string,
	measuredUSD float64, now time.Time) error {
	tx,err:=s.DB.BeginTx(ctx,nil); if err!=nil{return err}; defer func(){_=tx.Rollback()}()
	var day,month,state string; var reserve,priorActual float64
	if err:=tx.QueryRowContext(ctx,`SELECT day_bucket,month_bucket,reserve_usd,actual_usd,state FROM category_reservations WHERE id=? AND owner_id=?`,reservationID,ownerID).Scan(&day,&month,&reserve,&priorActual,&state);err!=nil{return err}
	if state=="SETTLED" { return tx.Commit() } // already settled (with or without known usage)
	if state!="UNKNOWN" { return fmt.Errorf("category reservation %s cannot be reconciled in state %q", reservationID, state) }
	res,err:=tx.ExecContext(ctx,`UPDATE category_reservations
		SET estimated_usd=?,actual_usd=?,usage_unknown=0,state='SETTLED',settled_at=?
		WHERE id=? AND owner_id=? AND state='UNKNOWN'`,
		measuredUSD,measuredUSD,nowString(now),reservationID,ownerID)
	if err!=nil{return err}
	n,_:=res.RowsAffected()
	if n!=1 { return tx.Commit() } // a concurrent reconciliation won; do not double-apply the delta
	delta:=measuredUSD-priorActual
	for _,b:=range []struct{scope,bucket string}{{ownerID,"day:"+day},{ownerID,"month:"+month},{"*","day:"+day},{"*","month:"+month}} {
		if _,err:=tx.ExecContext(ctx,`UPDATE category_usage SET actual_usd=MAX(0,actual_usd+?) WHERE scope=? AND bucket=?`,delta,b.scope,b.bucket);err!=nil{return err}
	}
	return tx.Commit()
}

// SweepOrphanCategoryReservations refunds reservations that stayed RESERVED past the lease window
// (the process died between ReserveCategoryCall and settlement). They are settled conservatively as
// UNKNOWN at the reserved amount so spend is never silently lost and reserved_usd stops leaking.
// The conditional RESERVED transition makes this safe against a slow in-flight settlement.
func (s *Store) SweepOrphanCategoryReservations(ctx context.Context, createdBefore time.Time, now time.Time) (int, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT id, owner_id, day_bucket, month_bucket, reserve_usd
		FROM category_reservations WHERE state='RESERVED' AND created_at < ? ORDER BY created_at`, nowString(createdBefore))
	if err != nil { return 0, err }
	type orphan struct{ id, owner, day, month string; reserve float64 }
	var orphans []orphan
	for rows.Next() {
		var o orphan
		if err := rows.Scan(&o.id, &o.owner, &o.day, &o.month, &o.reserve); err != nil { rows.Close(); return 0, err }
		orphans = append(orphans, o)
	}
	rows.Close()
	if err := rows.Err(); err != nil { return 0, err }
	swept := 0
	for _, o := range orphans {
		res, err := s.DB.ExecContext(ctx, `UPDATE category_reservations
			SET estimated_usd=?,actual_usd=?,usage_unknown=1,state='UNKNOWN',settled_at=?
			WHERE id=? AND state='RESERVED'`, o.reserve, o.reserve, nowString(now), o.id)
		if err != nil { return swept, err }
		n, _ := res.RowsAffected()
		if n != 1 { continue } // settled in-flight; not an orphan
		if _, err := s.DB.ExecContext(ctx, `UPDATE category_usage SET reserved_usd=MAX(0,reserved_usd-?), actual_usd=actual_usd+?
			WHERE scope=? AND bucket=?`, o.reserve, o.reserve, o.owner, "day:"+o.day); err != nil { return swept, err }
		if _, err := s.DB.ExecContext(ctx, `UPDATE category_usage SET reserved_usd=MAX(0,reserved_usd-?), actual_usd=actual_usd+?
			WHERE scope=? AND bucket=?`, o.reserve, o.reserve, o.owner, "month:"+o.month); err != nil { return swept, err }
		if _, err := s.DB.ExecContext(ctx, `UPDATE category_usage SET reserved_usd=MAX(0,reserved_usd-?), actual_usd=actual_usd+?
			WHERE scope=? AND bucket=?`, o.reserve, o.reserve, "*", "day:"+o.day); err != nil { return swept, err }
		if _, err := s.DB.ExecContext(ctx, `UPDATE category_usage SET reserved_usd=MAX(0,reserved_usd-?), actual_usd=actual_usd+?
			WHERE scope=? AND bucket=?`, o.reserve, o.reserve, "*", "month:"+o.month); err != nil { return swept, err }
		swept++
	}
	return swept, nil
}

func (s *Store) CreateCategoryJob(ctx context.Context, ownerID, transactionID string, revision int, now time.Time) (CategoryJob,error) {
	id:=NewID("catjob")
	_,err:=s.DB.ExecContext(ctx,`INSERT INTO category_jobs(id,owner_id,transaction_id,expected_revision,state,created_at,updated_at)
		VALUES(?,?,?,?,'PENDING',?,?) ON CONFLICT(owner_id,transaction_id,expected_revision) DO NOTHING`,
		id,ownerID,transactionID,revision,nowString(now),nowString(now)); if err!=nil{return CategoryJob{},err}
	row:=s.DB.QueryRowContext(ctx,categoryJobColumns+`WHERE owner_id=? AND transaction_id=? AND expected_revision=?`,ownerID,transactionID,revision)
	return scanCategoryJob(row)
}

// ClaimCategoryJob atomically moves one PENDING job to RUNNING under a lease. Duplicate taps lose
// the claim and must replay the existing job rather than call the provider again.
func (s *Store) ClaimCategoryJob(ctx context.Context, ownerID, id string, now time.Time, lease time.Duration) (bool,error) {
	res,err:=s.DB.ExecContext(ctx,`UPDATE category_jobs SET state='RUNNING',lease_until=?,updated_at=?
		WHERE owner_id=? AND id=? AND state='PENDING'`,nowString(now.Add(lease)),nowString(now),ownerID,id)
	if err!=nil{return false,err}; n,_:=res.RowsAffected(); return n==1,nil
}

// ClaimCategoryJobsForRecovery reclaims PENDING jobs and RUNNING jobs whose lease expired (the
// worker that owned them died). Each reclaim is a conditional UPDATE, so a live worker with an
// unexpired lease cannot lose its job to this sweep.
func (s *Store) ClaimCategoryJobsForRecovery(ctx context.Context, now time.Time, lease time.Duration, limit int) ([]CategoryJob,error) {
	rows,err:=s.DB.QueryContext(ctx,`SELECT owner_id,id FROM category_jobs
		WHERE state='PENDING' OR (state='RUNNING' AND (lease_until IS NULL OR lease_until < ?))
		ORDER BY created_at LIMIT ?`,nowString(now),limit)
	if err!=nil{return nil,err}
	type ref struct{ owner,id string }
	var refs []ref
	for rows.Next(){ var r ref; if err:=rows.Scan(&r.owner,&r.id);err!=nil{rows.Close();return nil,err}; refs=append(refs,r) }
	rows.Close(); if err:=rows.Err();err!=nil{return nil,err}
	var out []CategoryJob
	for _,r:=range refs {
		res,err:=s.DB.ExecContext(ctx,`UPDATE category_jobs SET state='RUNNING',lease_until=?,updated_at=?
			WHERE owner_id=? AND id=? AND (state='PENDING' OR (state='RUNNING' AND (lease_until IS NULL OR lease_until < ?)))`,
			nowString(now.Add(lease)),nowString(now),r.owner,r.id,nowString(now))
		if err!=nil{return out,err}
		n,_:=res.RowsAffected(); if n!=1{continue}
		job,err:=s.GetCategoryJob(ctx,r.owner,r.id); if err!=nil{return out,err}
		out=append(out,job)
	}
	return out,nil
}

// RequeueCategoryJob returns a RUNNING job to PENDING for a bounded transient retry or a graceful
// shutdown. retry_count increments so the policy can stop after one retry.
func (s *Store) RequeueCategoryJob(ctx context.Context, ownerID, id string, now time.Time) (CategoryJob,error) {
	res,err:=s.DB.ExecContext(ctx,`UPDATE category_jobs SET state='PENDING',lease_until=NULL,retry_count=retry_count+1,updated_at=?
		WHERE owner_id=? AND id=? AND state='RUNNING'`,nowString(now),ownerID,id)
	if err!=nil{return CategoryJob{},err}
	n,_:=res.RowsAffected(); if n!=1{return CategoryJob{},ErrNotFound}
	return s.GetCategoryJob(ctx,ownerID,id)
}

const categoryJobColumns = `SELECT id,owner_id,transaction_id,expected_revision,state,
	COALESCE(category_code,''),COALESCE(confidence,0),COALESCE(provider,''),COALESCE(model,''),
	COALESCE(prompt_version,''),COALESCE(taxonomy_version,''),COALESCE(reason_code,''),
	COALESCE(error_code,''),COALESCE(lease_until,''),COALESCE(retry_count,0),created_at,updated_at
	FROM category_jobs `

func (s *Store) GetCategoryJob(ctx context.Context, ownerID,id string)(CategoryJob,error){
	row:=s.DB.QueryRowContext(ctx,categoryJobColumns+`WHERE owner_id=? AND id=?`,ownerID,id)
	job,err:=scanCategoryJob(row)
	if errors.Is(err,sql.ErrNoRows){return CategoryJob{},ErrNotFound}
	return job,err
}

// ResetFailedCategoryJob makes an explicit user retry possible: a terminal FAILED job at the same
// transaction revision returns to PENDING with a fresh bounded retry budget. CLASSIFIED and
// NEEDS_REVIEW jobs are never reset, so a user-visible review decision is not re-litigated and a
// finished classification cannot be re-billed by tapping the button again.
func (s *Store) ResetFailedCategoryJob(ctx context.Context, ownerID, id string, now time.Time) (CategoryJob, error) {
	res, err := s.DB.ExecContext(ctx, `UPDATE category_jobs
		SET state='PENDING', error_code='', reason_code='', retry_count=0, lease_until=NULL, updated_at=?
		WHERE owner_id=? AND id=? AND state='FAILED'`, nowString(now), ownerID, id)
	if err != nil {
		return CategoryJob{}, fmt.Errorf("reset failed job: %w", err)
	}
	if n, _ := res.RowsAffected(); n != 1 {
		return CategoryJob{}, ErrNotFound
	}
	return s.GetCategoryJob(ctx, ownerID, id)
}

// FinishCategoryJob moves a job to a terminal state and releases its lease. It returns an error
// when the row does not exist, so callers can fail the HTTP response instead of reporting success
// with a RUNNING/PENDING job left behind.
func (s *Store) FinishCategoryJob(ctx context.Context, ownerID,id,state,code,reason,errorCode,provider,model,prompt,taxonomy string,confidence float64,now time.Time) error {
	res,err:=s.DB.ExecContext(ctx,`UPDATE category_jobs SET state=?,category_code=?,confidence=?,provider=?,model=?,prompt_version=?,taxonomy_version=?,reason_code=?,error_code=?,lease_until=NULL,updated_at=?
		WHERE owner_id=? AND id=?`,state,code,confidence,provider,model,prompt,taxonomy,reason,errorCode,nowString(now),ownerID,id)
	if err!=nil{return err}; n,_:=res.RowsAffected(); if n!=1{return ErrNotFound}; return nil
}

func scanCategoryJob(row rowScanner)(CategoryJob,error){
	var j CategoryJob; var created,updated,lease string; var retry int
	if err:=row.Scan(&j.ID,&j.OwnerID,&j.TransactionID,&j.ExpectedRevision,&j.State,&j.CategoryCode,&j.Confidence,&j.Provider,&j.Model,&j.PromptVersion,&j.TaxonomyVersion,&j.ReasonCode,&j.ErrorCode,&lease,&retry,&created,&updated);err!=nil{return CategoryJob{},err}
	if lease!=""{t:=parseTime(lease); j.LeaseUntil=&t}
	j.RetryCount=retry; j.CreatedAt=parseTime(created); j.UpdatedAt=parseTime(updated); return j,nil
}

// ApplyLLMCategory updates only a non-USER row at the expected revision and bumps month revision.
//
// The consent, deletion and revision guards all live inside the single UPDATE statement, so the
// apply is atomic with respect to an intervening consent withdrawal, deletion request, row
// deletion or USER override: the row only changes when all of them still hold at commit time.
// Failures collapse to ErrRevisionConflict; callers classify the specific reason with a read-only
// pre-check for diagnostics, never as the authoritative guard.
func (s *Store) ApplyLLMCategory(ctx context.Context, ownerID, transactionID string, expectedRevision int, categoryID, code, provider, model, prompt, taxonomy, reason string, confidence float64, now time.Time) (Transaction,error) {
	row:=s.DB.QueryRowContext(ctx,`SELECT month_id, category_origin FROM transactions WHERE owner_id=? AND id=? AND deleted_at IS NULL`,ownerID,transactionID)
	var month,origin string; if err:=row.Scan(&month,&origin);err!=nil{return Transaction{},err}
	if origin=="USER" { return Transaction{}, ErrRevisionConflict }
	tx,err:=s.DB.BeginTx(ctx,nil); if err!=nil{return Transaction{},err}; defer func(){_=tx.Rollback()}()
	res,err:=tx.ExecContext(ctx,`UPDATE transactions SET category_id=?,category_origin='RULE',
		classifier_origin='LLM',category_confidence=?,category_rule_version=?,category_reason=?,
		category_taxonomy_version=?,revision=revision+1,updated_at=?
		WHERE owner_id=? AND id=? AND revision=? AND deleted_at IS NULL AND category_origin!='USER'
		  AND NOT EXISTS (SELECT 1 FROM deletion_requests d
		      WHERE d.owner_id = transactions.owner_id AND d.state='PENDING')
		  AND EXISTS (SELECT 1 FROM consents c
		      WHERE c.owner_id = transactions.owner_id AND c.purpose='AI_CATEGORIZATION'
		        AND c.decision='GRANTED'
		        AND c.created_at = (SELECT MAX(c2.created_at) FROM consents c2
		            WHERE c2.owner_id = transactions.owner_id AND c2.purpose='AI_CATEGORIZATION'))`,
		categoryID,confidence,prompt+"|"+model,reason,taxonomy,nowString(now),ownerID,transactionID,expectedRevision)
	if err!=nil{return Transaction{},err}; n,_:=res.RowsAffected(); if n!=1{return Transaction{},ErrRevisionConflict}
	if err:=bumpMonthRevision(ctx,tx,ownerID,month,now);err!=nil{return Transaction{},err}; if err:=tx.Commit();err!=nil{return Transaction{},err}; return s.GetTransaction(ctx,ownerID,transactionID)
}

func (s *Store) CategoryIDForCode(ctx context.Context, ownerID, code string)(string,error){
	var id string; err:=s.DB.QueryRowContext(ctx,`SELECT id FROM categories WHERE owner_id=? AND code=? AND deleted_at IS NULL`,ownerID,code).Scan(&id); if errors.Is(err,sql.ErrNoRows){return "",fmt.Errorf("category code not found")}; return id,err
}