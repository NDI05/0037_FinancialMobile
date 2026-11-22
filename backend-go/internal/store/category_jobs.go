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

type CategoryJob struct {
	ID, OwnerID, TransactionID string
	ExpectedRevision int
	State string
	CategoryCode string
	Confidence float64
	Provider, Model, PromptVersion, TaxonomyVersion, ReasonCode, ErrorCode string
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
func (s *Store) SettleCategoryReservation(ctx context.Context, ownerID, reservationID string,
	estimatedUSD, actualUSD float64, usageKnown bool, now time.Time) error {
	tx,err:=s.DB.BeginTx(ctx,nil); if err!=nil{return err}; defer func(){_=tx.Rollback()}()
	var day,month,state string; var reserve float64
	if err:=tx.QueryRowContext(ctx,`SELECT day_bucket,month_bucket,reserve_usd,state FROM category_reservations WHERE id=? AND owner_id=?`,reservationID,ownerID).Scan(&day,&month,&reserve,&state);err!=nil{return err}
	if state=="SETTLED"{return nil}
	settled:=actualUSD; if !usageKnown { settled=reserve }
	for _,b:=range []struct{scope,bucket string}{{ownerID,"day:"+day},{ownerID,"month:"+month},{"*","day:"+day},{"*","month:"+month}} {
		if _,err:=tx.ExecContext(ctx,`UPDATE category_usage SET reserved_usd=MAX(0,reserved_usd-?), actual_usd=actual_usd+? WHERE scope=? AND bucket=?`,reserve,settled,b.scope,b.bucket);err!=nil{return err}
	}
	nextState:="SETTLED"; if !usageKnown { nextState="UNKNOWN" }
	if _,err:=tx.ExecContext(ctx,`UPDATE category_reservations SET estimated_usd=?,actual_usd=?,usage_unknown=?,state=?,settled_at=? WHERE id=?`,
		estimatedUSD,settled,boolInt(!usageKnown),nextState,nowString(now),reservationID);err!=nil{return err}
	return tx.Commit()
}

func (s *Store) CreateCategoryJob(ctx context.Context, ownerID, transactionID string, revision int, now time.Time) (CategoryJob,error) {
	id:=NewID("catjob")
	_,err:=s.DB.ExecContext(ctx,`INSERT INTO category_jobs(id,owner_id,transaction_id,expected_revision,state,created_at,updated_at)
		VALUES(?,?,?,?,'PENDING',?,?) ON CONFLICT(owner_id,transaction_id,expected_revision) DO NOTHING`,
		id,ownerID,transactionID,revision,nowString(now),nowString(now)); if err!=nil{return CategoryJob{},err}
	row:=s.DB.QueryRowContext(ctx,`SELECT id,owner_id,transaction_id,expected_revision,state,COALESCE(category_code,''),COALESCE(confidence,0),COALESCE(provider,''),COALESCE(model,''),COALESCE(prompt_version,''),COALESCE(taxonomy_version,''),COALESCE(reason_code,''),COALESCE(error_code,''),created_at,updated_at
		FROM category_jobs WHERE owner_id=? AND transaction_id=? AND expected_revision=?`,ownerID,transactionID,revision)
	return scanCategoryJob(row)
}

// ClaimCategoryJob atomically moves one PENDING job to RUNNING. Duplicate taps lose the claim and
// must replay the existing job rather than call the provider again.
func (s *Store) ClaimCategoryJob(ctx context.Context, ownerID,id string, now time.Time) (bool,error) {
	res,err:=s.DB.ExecContext(ctx,`UPDATE category_jobs SET state='RUNNING',updated_at=? WHERE owner_id=? AND id=? AND state='PENDING'`,nowString(now),ownerID,id)
	if err!=nil{return false,err}; n,_:=res.RowsAffected(); return n==1,nil
}

func (s *Store) GetCategoryJob(ctx context.Context, ownerID,id string)(CategoryJob,error){
	row:=s.DB.QueryRowContext(ctx,`SELECT id,owner_id,transaction_id,expected_revision,state,COALESCE(category_code,''),COALESCE(confidence,0),COALESCE(provider,''),COALESCE(model,''),COALESCE(prompt_version,''),COALESCE(taxonomy_version,''),COALESCE(reason_code,''),COALESCE(error_code,''),created_at,updated_at FROM category_jobs WHERE owner_id=? AND id=?`,ownerID,id)
	return scanCategoryJob(row)
}

func (s *Store) FinishCategoryJob(ctx context.Context, ownerID,id,state,code,reason,errorCode,provider,model,prompt,taxonomy string,confidence float64,now time.Time) error {
	res,err:=s.DB.ExecContext(ctx,`UPDATE category_jobs SET state=?,category_code=?,confidence=?,provider=?,model=?,prompt_version=?,taxonomy_version=?,reason_code=?,error_code=?,updated_at=? WHERE owner_id=? AND id=?`,state,code,confidence,provider,model,prompt,taxonomy,reason,errorCode,nowString(now),ownerID,id); if err!=nil{return err}; n,_:=res.RowsAffected(); if n!=1{return ErrNotFound}; return nil
}

func scanCategoryJob(row rowScanner)(CategoryJob,error){
	var j CategoryJob; var created,updated string
	if err:=row.Scan(&j.ID,&j.OwnerID,&j.TransactionID,&j.ExpectedRevision,&j.State,&j.CategoryCode,&j.Confidence,&j.Provider,&j.Model,&j.PromptVersion,&j.TaxonomyVersion,&j.ReasonCode,&j.ErrorCode,&created,&updated);err!=nil{return CategoryJob{},err}
	j.CreatedAt=parseTime(created); j.UpdatedAt=parseTime(updated); return j,nil
}

// ApplyLLMCategory updates only a non-USER row at the expected revision and bumps month revision.
func (s *Store) ApplyLLMCategory(ctx context.Context, ownerID, transactionID string, expectedRevision int, categoryID, code, provider, model, prompt, taxonomy, reason string, confidence float64, now time.Time) (Transaction,error) {
	row:=s.DB.QueryRowContext(ctx,`SELECT month_id, category_origin FROM transactions WHERE owner_id=? AND id=? AND deleted_at IS NULL`,ownerID,transactionID)
	var month,origin string; if err:=row.Scan(&month,&origin);err!=nil{return Transaction{},err}
	if origin=="USER" { return Transaction{}, ErrRevisionConflict }
	tx,err:=s.DB.BeginTx(ctx,nil); if err!=nil{return Transaction{},err}; defer func(){_=tx.Rollback()}()
	res,err:=tx.ExecContext(ctx,`UPDATE transactions SET category_id=?,category_origin='RULE',
		classifier_origin='LLM',category_confidence=?,category_rule_version=?,category_reason=?,
		category_taxonomy_version=?,revision=revision+1,updated_at=?
		WHERE owner_id=? AND id=? AND revision=? AND deleted_at IS NULL AND category_origin!='USER'`,
		categoryID,confidence,prompt+"|"+model,taxonomy,reason,nowString(now),ownerID,transactionID,expectedRevision)
	if err!=nil{return Transaction{},err}; n,_:=res.RowsAffected(); if n!=1{return Transaction{},ErrRevisionConflict}
	if err:=bumpMonthRevision(ctx,tx,ownerID,month,now);err!=nil{return Transaction{},err}; if err:=tx.Commit();err!=nil{return Transaction{},err}; return s.GetTransaction(ctx,ownerID,transactionID)
}

func (s *Store) CategoryIDForCode(ctx context.Context, ownerID, code string)(string,error){
	var id string; err:=s.DB.QueryRowContext(ctx,`SELECT id FROM categories WHERE owner_id=? AND code=? AND deleted_at IS NULL`,ownerID,code).Scan(&id); if errors.Is(err,sql.ErrNoRows){return "",fmt.Errorf("category code not found")}; return id,err
}
