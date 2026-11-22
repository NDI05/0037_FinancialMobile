package store

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"
)

// regNow is a fixed instant: Jakarta day 2026-11-22, month 2026-11.
var regNow = time.Date(2026, 11, 22, 12, 0, 0, 0, time.UTC)

// near reports whether two money values are equal within float noise.
func near(a, b float64) bool {
	d := a - b
	return d < 1e-9 && d > -1e-9
}

// regOwnerSetup creates a real owner (transactions have an FK to users.owner_id), grants
// AI_CATEGORIZATION consent, a builtin-style category and one uncategorized transaction.
func regOwnerSetup(t *testing.T, s *Store, email string) (owner, txID, categoryID string) {
	t.Helper()
	ctx := context.Background()
	if _, err := s.CreateAccount(ctx, email, "Uji",
		PasswordDigest{Salt: []byte("salt"), Hash: []byte("hash"), KDF: "scrypt:N=32768,r=8,p=1"}, regNow); err != nil {
		t.Fatal(err)
	}
	owner = OwnerIDForEmail(email)
	if _, err := s.AppendConsent(ctx, owner, "AI_CATEGORIZATION", "GRANTED", "notice-v2", regNow); err != nil {
		t.Fatal(err)
	}
	catID, err := s.CategoryIDForCode(ctx, owner, "food")
	if err != nil {
		t.Fatal(err)
	}
	tx, err := s.CreateTransaction(ctx, owner, TransactionInput{
		Direction: "DEBIT", Source: "EMAIL", AmountMinor: 16000, Currency: "IDR",
		EventAt: regNow, Fingerprint: strings.Repeat("f", 32), ParserVersion: "livin-v1",
		ParserConfidence: 0.9, CategoryOrigin: "IMPORTED",
		Description: "QRIS", MerchantLabel: "BAKSO UJI",
	}, regNow)
	if err != nil {
		t.Fatal(err)
	}
	return owner, tx.ID, catID
}

func jakartaDay(now time.Time) string {
	d, _ := JakartaBuckets(now)
	return d
}

func usageRow(t *testing.T, s *Store, scope, bucket string) (attempts int, reserved, actual float64) {
	t.Helper()
	err := s.DB.QueryRowContext(context.Background(),
		`SELECT attempts, reserved_usd, actual_usd FROM category_usage WHERE scope=? AND bucket=?`, scope, bucket).
		Scan(&attempts, &reserved, &actual)
	if err != nil {
		t.Fatalf("usage %s/%s: %v", scope, bucket, err)
	}
	return
}

// claimFence creates a job at the transaction revision and claims it, returning the job whose
// lease token every fenced write must present. A failed write leaves the job RUNNING, so callers
// that only probe guards can reuse one fence for several attempts at the same revision.
func claimFence(t *testing.T, s *Store, owner, txID string, revision int) CategoryJob {
	t.Helper()
	ctx := context.Background()
	job, err := s.CreateCategoryJob(ctx, owner, txID, revision, regNow)
	if err != nil {
		t.Fatal(err)
	}
	token, ok, err := s.ClaimCategoryJob(ctx, owner, job.ID, regNow, CategoryLeaseDuration)
	if err != nil || !ok {
		t.Fatalf("claim ok=%v err=%v state=%s", ok, err, job.State)
	}
	job, err = s.GetCategoryJob(ctx, owner, job.ID)
	if err != nil {
		t.Fatal(err)
	}
	if job.LeaseToken != token || job.LeaseToken == "" {
		t.Fatalf("lease token %q want %q", job.LeaseToken, token)
	}
	return job
}

// Bug A regression: replaying the settlement of an UNKNOWN reservation must not double-charge.
func TestSettleUNKNOWNReplayNoDoubleCharge(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	id, ok, err := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 100, 0.10, 0.06, regNow)
	if err != nil || !ok {
		t.Fatalf("reserve ok=%v err=%v", ok, err)
	}
	if err := s.SettleCategoryReservation(ctx, "owner-a", id, 0.06, 0.06, false, regNow); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		if err := s.SettleCategoryReservation(ctx, "owner-a", id, 0.06, 0.06, false, regNow); err != nil {
			t.Fatal(err)
		}
	}
	_, reserved, actual := usageRow(t, s, "owner-a", "day:"+jakartaDay(regNow))
	if reserved != 0 || !near(actual, 0.06) {
		t.Fatalf("reserved=%v actual=%v (replay must charge 0.06 once)", reserved, actual)
	}
}

func TestSettleKnownReplayNoOp(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	id, ok, _ := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 100, 0.10, 0.06, regNow)
	if !ok {
		t.Fatal("reserve denied")
	}
	if err := s.SettleCategoryReservation(ctx, "owner-a", id, 0.02, 0.02, true, regNow); err != nil {
		t.Fatal(err)
	}
	if err := s.SettleCategoryReservation(ctx, "owner-a", id, 0.02, 0.02, true, regNow); err != nil {
		t.Fatal(err)
	}
	_, reserved, actual := usageRow(t, s, "owner-a", "day:"+jakartaDay(regNow))
	if reserved != 0 || !near(actual, 0.02) {
		t.Fatalf("reserved=%v actual=%v", reserved, actual)
	}
}

func TestSettleConcurrentExactlyOnce(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	id, ok, _ := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 100, 0.10, 0.06, regNow)
	if !ok {
		t.Fatal("reserve denied")
	}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			known := i%2 == 0
			v := 0.06
			if !known {
				v = 0.04
			}
			if err := s.SettleCategoryReservation(ctx, "owner-a", id, v, v, known, regNow); err != nil {
				t.Errorf("settle: %v", err)
			}
		}(i)
	}
	wg.Wait()
	_, reserved, actual := usageRow(t, s, "owner-a", "day:"+jakartaDay(regNow))
	if reserved != 0 {
		t.Fatalf("reserved leak: %v", reserved)
	}
	// Exactly one winner applied its delta; every other settle was a terminal no-op.
	if !near(actual, 0.06) && !near(actual, 0.04) {
		t.Fatalf("concurrent settle double-charged or lost: actual=%v", actual)
	}
}

func TestReconcileUNKNOWNDeltaOnce(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	id, ok, _ := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 100, 0.10, 0.06, regNow)
	if !ok {
		t.Fatal("reserve denied")
	}
	if err := s.SettleCategoryReservation(ctx, "owner-a", id, 0.06, 0.06, false, regNow); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		if err := s.ReconcileCategoryReservation(ctx, "owner-a", id, 0.02, regNow); err != nil {
			t.Fatal(err)
		}
	}
	_, reserved, actual := usageRow(t, s, "owner-a", "day:"+jakartaDay(regNow))
	if reserved != 0 || !near(actual, 0.02) {
		t.Fatalf("reconcile: reserved=%v actual=%v want 0.02", reserved, actual)
	}
}

func TestReconcileDoesNotTouchOtherReservations(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	idA, okA, _ := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 1.0, 0.50, 0.06, regNow)
	idB, okB, _ := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 1.0, 0.50, 0.06, regNow)
	if !okA || !okB {
		t.Fatal("reserve denied")
	}
	if err := s.SettleCategoryReservation(ctx, "owner-a", idA, 0.06, 0.06, false, regNow); err != nil {
		t.Fatal(err)
	}
	if err := s.SettleCategoryReservation(ctx, "owner-a", idB, 0.04, 0.04, true, regNow); err != nil {
		t.Fatal(err)
	}
	if err := s.ReconcileCategoryReservation(ctx, "owner-a", idA, 0.02, regNow); err != nil {
		t.Fatal(err)
	}
	_, reserved, actual := usageRow(t, s, "owner-a", "day:"+jakartaDay(regNow))
	if reserved != 0 || !near(actual, 0.06) {
		t.Fatalf("reserved=%v actual=%v want 0.06 (A reconciled 0.02 + B known 0.04)", reserved, actual)
	}
}

func TestSweepOrphanRefundsReservedExactlyOnce(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	id, ok, _ := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 100, 0.10, 0.06, regNow)
	if !ok {
		t.Fatal("reserve denied")
	}
	// Fake an orphan: created long ago, never settled (process died after reserve).
	if _, err := s.DB.ExecContext(ctx, `UPDATE category_reservations SET created_at=? WHERE id=?`,
		nowString(regNow.Add(-time.Hour)), id); err != nil {
		t.Fatal(err)
	}
	n, err := s.SweepOrphanCategoryReservations(ctx, regNow.Add(-10*time.Minute), regNow)
	if err != nil || n != 1 {
		t.Fatalf("sweep n=%d err=%v", n, err)
	}
	n, err = s.SweepOrphanCategoryReservations(ctx, regNow.Add(-10*time.Minute), regNow)
	if err != nil || n != 0 {
		t.Fatalf("second sweep n=%d err=%v", n, err)
	}
	_, reserved, actual := usageRow(t, s, "owner-a", "day:"+jakartaDay(regNow))
	if reserved != 0 || !near(actual, 0.06) {
		t.Fatalf("sweep refund: reserved=%v actual=%v", reserved, actual)
	}
	var st string
	if err := s.DB.QueryRowContext(ctx, `SELECT state FROM category_reservations WHERE id=?`, id).Scan(&st); err != nil {
		t.Fatal(err)
	}
	if st != "UNKNOWN" {
		t.Fatalf("state=%s want UNKNOWN", st)
	}
}

func TestSweepSkipsInFlightReserved(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	id, ok, _ := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 100, 0.10, 0.06, regNow)
	if !ok {
		t.Fatal("reserve denied")
	}
	if _, err := s.DB.ExecContext(ctx, `UPDATE category_reservations SET created_at=? WHERE id=?`,
		nowString(regNow.Add(-time.Hour)), id); err != nil {
		t.Fatal(err)
	}
	// A slow live settle wins before the sweep's conditional UPDATE: no double refund.
	if err := s.SettleCategoryReservation(ctx, "owner-a", id, 0.02, 0.02, true, regNow); err != nil {
		t.Fatal(err)
	}
	n, _ := s.SweepOrphanCategoryReservations(ctx, regNow.Add(-10*time.Minute), regNow)
	if n != 0 {
		t.Fatalf("sweep claimed a settled reservation: n=%d", n)
	}
	_, reserved, actual := usageRow(t, s, "owner-a", "day:"+jakartaDay(regNow))
	if reserved != 0 || !near(actual, 0.02) {
		t.Fatalf("reserved=%v actual=%v", reserved, actual)
	}
}

func TestLeaseClaimRecoveryAndRequeue(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	owner, txID, _ := regOwnerSetup(t, s, "lease@example.test")
	job, err := s.CreateCategoryJob(ctx, owner, txID, 0, regNow)
	if err != nil {
		t.Fatal(err)
	}
	token, claimed, err := s.ClaimCategoryJob(ctx, owner, job.ID, regNow, CategoryLeaseDuration)
	if err != nil || !claimed || token == "" {
		t.Fatalf("claim ok=%v tok=%q err=%v", claimed, token, err)
	}
	// A fresh lease is not reclaimable by the recovery sweep.
	if jobs, _ := s.ClaimCategoryJobsForRecovery(ctx, regNow.Add(time.Minute), CategoryLeaseDuration, 10); len(jobs) != 0 {
		t.Fatalf("recovery stole a fresh lease: %d jobs", len(jobs))
	}
	// An expired lease is reclaimed and a NEW token is minted; the first token is now stale.
	reclaimed, _ := s.ClaimCategoryJobsForRecovery(ctx, regNow.Add(CategoryLeaseDuration+time.Minute), CategoryLeaseDuration, 10)
	if len(reclaimed) != 1 {
		t.Fatalf("expired lease not reclaimed: %d jobs", len(reclaimed))
	}
	if reclaimed[0].LeaseToken == "" || reclaimed[0].LeaseToken == token {
		t.Fatalf("reclaim did not mint a new lease token: %q vs %q", reclaimed[0].LeaseToken, token)
	}
	if _, err := s.RequeueCategoryJob(ctx, owner, job.ID, token, regNow.Add(CategoryLeaseDuration+2*time.Minute)); err != ErrLeaseLost {
		t.Fatalf("stale token requeued the job: %v", err)
	}
	req, err := s.RequeueCategoryJob(ctx, owner, job.ID, reclaimed[0].LeaseToken, regNow.Add(CategoryLeaseDuration+2*time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if req.State != "PENDING" || req.RetryCount != 1 || req.LeaseUntil != nil {
		t.Fatalf("requeue: state=%s retry=%d lease=%v", req.State, req.RetryCount, req.LeaseUntil)
	}
	if jobs, _ := s.ClaimCategoryJobsForRecovery(ctx, regNow.Add(CategoryLeaseDuration+3*time.Minute), CategoryLeaseDuration, 10); len(jobs) != 1 {
		t.Fatalf("pending not recovered: %d jobs", len(jobs))
	}
}

func TestApplyLLMCategoryDistinctProvenanceValues(t *testing.T) {
	// Bug B regression: category_reason and category_taxonomy_version must receive their own
	// values (they were swapped). rule_version = prompt|model, classifier_origin LLM, origin RULE.
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	owner, txID, catID := regOwnerSetup(t, s, "provenance@example.test")
	before, err := s.GetTransaction(ctx, owner, txID)
	if err != nil {
		t.Fatal(err)
	}
	fence := claimFence(t, s, owner, txID, before.Revision)
	applied, err := s.ApplyLLMCategory(ctx, owner, txID, before.Revision, fence.ID, fence.LeaseToken, catID, "food",
		"azure-openai-compatible", "gpt-oss-120b", "category-prompt/1", "builtin-categories/1",
		"merchant_food", 0.97, regNow)
	if err != nil {
		t.Fatal(err)
	}
	if applied.CategoryOrigin != "RULE" || applied.ClassifierOrigin != "LLM" {
		t.Fatalf("origins: %s/%s", applied.CategoryOrigin, applied.ClassifierOrigin)
	}
	if applied.CategoryReason != "merchant_food" {
		t.Fatalf("category_reason=%q want merchant_food", applied.CategoryReason)
	}
	if applied.CategoryTaxonomyVersion != "builtin-categories/1" {
		t.Fatalf("category_taxonomy_version=%q", applied.CategoryTaxonomyVersion)
	}
	if applied.CategoryRuleVersion != "category-prompt/1|gpt-oss-120b" {
		t.Fatalf("category_rule_version=%q", applied.CategoryRuleVersion)
	}
	if applied.CategoryConfidence != 0.97 || applied.Revision != before.Revision+1 {
		t.Fatalf("confidence=%v revision=%d", applied.CategoryConfidence, applied.Revision)
	}
}

func TestApplyLLMCategoryGuards(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	owner, txID, catID := regOwnerSetup(t, s, "guards@example.test")

	// USER origin is never overwritten by the LLM.
	tx, err := s.GetTransaction(ctx, owner, txID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.DB.ExecContext(ctx, `UPDATE transactions SET category_origin='USER' WHERE id=?`, txID); err != nil {
		t.Fatal(err)
	}
	fence := claimFence(t, s, owner, txID, tx.Revision)
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, tx.Revision, fence.ID, fence.LeaseToken, catID, "food", "p", "m",
		"category-prompt/1", "builtin-categories/1", "r", 0.9, regNow); err != ErrRevisionConflict {
		t.Fatalf("USER origin overwrite: err=%v", err)
	}
	if _, err := s.DB.ExecContext(ctx, `UPDATE transactions SET category_origin='IMPORTED' WHERE id=?`, txID); err != nil {
		t.Fatal(err)
	}

	// Stale revision is refused.
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, tx.Revision+99, fence.ID, fence.LeaseToken, catID, "food", "p", "m",
		"category-prompt/1", "builtin-categories/1", "r", 0.9, regNow); err != ErrRevisionConflict {
		t.Fatalf("stale revision: err=%v", err)
	}

	// Consent revocation is enforced inside the UPDATE, not by a racy pre-check.
	if _, err := s.AppendConsent(ctx, owner, "AI_CATEGORIZATION", "WITHDRAWN", "notice-v3", regNow.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	after, _ := s.GetTransaction(ctx, owner, txID)
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, after.Revision, fence.ID, fence.LeaseToken, catID, "food", "p", "m",
		"category-prompt/1", "builtin-categories/1", "r", 0.9, regNow); err != ErrRevisionConflict {
		t.Fatalf("revoked consent: err=%v", err)
	}

	// A pending deletion blocks the apply even with a fresh GRANTED consent.
	if _, err := s.AppendConsent(ctx, owner, "AI_CATEGORIZATION", "GRANTED", "notice-v4", regNow.Add(2*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.DB.ExecContext(ctx, `INSERT INTO deletion_requests
		(id, owner_id, state, sla_days, requested_at, created_at)
		VALUES ('del-1', ?, 'PENDING', 30, ?, ?)`, owner, nowString(regNow), nowString(regNow)); err != nil {
		t.Fatal(err)
	}
	after2, _ := s.GetTransaction(ctx, owner, txID)
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, after2.Revision, fence.ID, fence.LeaseToken, catID, "food", "p", "m",
		"category-prompt/1", "builtin-categories/1", "r", 0.9, regNow); err != ErrRevisionConflict {
		t.Fatalf("pending deletion: err=%v", err)
	}
}

func TestCategoryOwnerIsolation(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	ownerA, txA, _ := regOwnerSetup(t, s, "a@example.test")
	ownerB, txB, _ := regOwnerSetup(t, s, "b@example.test")
	_ = txB
	job, err := s.CreateCategoryJob(ctx, ownerA, txA, 0, regNow)
	if err != nil {
		t.Fatal(err)
	}
	if token, ok, _ := s.ClaimCategoryJob(ctx, ownerB, job.ID, regNow, CategoryLeaseDuration); ok || token != "" {
		t.Fatal("owner B claimed A's job")
	}
	if _, err := s.RequeueCategoryJob(ctx, ownerB, job.ID, "tok", regNow); err == nil {
		t.Fatal("owner B requeued A's job")
	}
	if err := s.FinishCategoryJob(ctx, ownerB, job.ID, "tok", "FAILED", "", "", "", "p", "m", "v", "t", 0, regNow); err == nil {
		t.Fatal("owner B finished A's job")
	}
	if _, err := s.GetCategoryJob(ctx, ownerB, job.ID); err == nil {
		t.Fatal("owner B read A's job")
	}
	jobs, _ := s.ClaimCategoryJobsForRecovery(ctx, regNow.Add(time.Hour), CategoryLeaseDuration, 10)
	if len(jobs) != 1 || jobs[0].OwnerID != ownerA {
		t.Fatalf("recovery returned %d jobs", len(jobs))
	}
	if err := s.SettleCategoryReservation(ctx, ownerB, "nonexistent", 0.01, 0.01, true, regNow); err == nil {
		t.Fatal("owner B settled a foreign reservation")
	}
	// The recovery claim above holds a live lease for owner A; cache writes are fenced on it.
	fence := jobs[0]
	if err := s.CategoryCachePut(ctx, ownerA, fence.ID, fence.LeaseToken, "k", "DEBIT", "QRIS",
		"builtin-categories/1", "m", "category-prompt/1", "caseid/1", "food", "r", false, regNow.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if code, _, _, _ := s.CategoryCacheGet(ctx, ownerB, "k", "DEBIT", "QRIS", "builtin-categories/1", "m", "category-prompt/1", "caseid/1"); code != "" {
		t.Fatalf("owner B read A's cache: code=%q", code)
	}
	if code, review, _, _ := s.CategoryCacheGet(ctx, ownerA, "k", "DEBIT", "QRIS", "builtin-categories/1", "m", "category-prompt/1", "caseid/1"); code != "food" || review {
		t.Fatalf("owner A cache miss: code=%q review=%v", code, review)
	}
}
func TestUpdateTransactionClearsClassifierProvenance(t *testing.T) {
	// A user category edit (USER override) must fully replace classifier provenance: classifier
	// origin, confidence, reason, rule and taxonomy versions are cleared so no stale "LLM"
	// label survives the override (found during real UAT: PATCH kept origin=LLM after the edit).
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	owner, txID, catID := regOwnerSetup(t, s, "override@example.test")

	before, err := s.GetTransaction(ctx, owner, txID)
	if err != nil {
		t.Fatal(err)
	}
	fence := claimFence(t, s, owner, txID, before.Revision)
	applied, err := s.ApplyLLMCategory(ctx, owner, txID, before.Revision, fence.ID, fence.LeaseToken, catID, "food",
		"azure-openai-compatible", "gpt-oss-120b", "category-prompt/1", "builtin-categories/1",
		"merchant_food", 0.97, regNow)
	if err != nil {
		t.Fatal(err)
	}
	if applied.ClassifierOrigin != "LLM" || applied.CategoryReason == "" {
		t.Fatalf("precondition: origin=%q reason=%q", applied.ClassifierOrigin, applied.CategoryReason)
	}

	otherID, err := s.CategoryIDForCode(ctx, owner, "transport")
	if err != nil {
		t.Fatal(err)
	}

	edited, err := s.UpdateTransaction(ctx, owner, txID, TransactionPatch{
		CategoryID: &otherID, Revision: applied.Revision,
	}, regNow.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if edited.CategoryID != otherID {
		t.Fatalf("category_id=%q want %q", edited.CategoryID, otherID)
	}
	if edited.CategoryOrigin != "USER" {
		t.Fatalf("origin=%q want USER", edited.CategoryOrigin)
	}
	if edited.ClassifierOrigin != "" {
		t.Fatalf("classifier_origin=%q want empty", edited.ClassifierOrigin)
	}
	if edited.CategoryReason != "" || edited.CategoryRuleVersion != "" || edited.CategoryTaxonomyVersion != "" {
		t.Fatalf("stale provenance: reason=%q rule=%q tax=%q", edited.CategoryReason,
			edited.CategoryRuleVersion, edited.CategoryTaxonomyVersion)
	}
	if edited.CategoryConfidence != 0 {
		t.Fatalf("confidence=%v want 0", edited.CategoryConfidence)
	}
	if edited.Revision != applied.Revision+1 {
		t.Fatalf("revision=%d want %d", edited.Revision, applied.Revision+1)
	}

	// The LLM must refuse to re-touch a USER override.
	fence1 := claimFence(t, s, owner, txID, edited.Revision)
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, edited.Revision, fence1.ID, fence1.LeaseToken, catID, "food",
		"azure-openai-compatible", "gpt-oss-120b", "category-prompt/1", "builtin-categories/1",
		"merchant_food", 0.97, regNow.Add(2*time.Minute)); err != ErrRevisionConflict {
		t.Fatalf("re-apply over USER override: err=%v", err)
	}

	// A note-only edit must NOT wipe classifier provenance that is still current.
	tx2, err := s.CreateTransaction(ctx, owner, TransactionInput{
		Direction: "DEBIT", Source: "EMAIL", AmountMinor: 22000, Currency: "IDR",
		EventAt: regNow, Fingerprint: strings.Repeat("c", 32), ParserVersion: "livin-v1",
		ParserConfidence: 0.9, CategoryOrigin: "IMPORTED",
		Description: "QRIS", MerchantLabel: "KOPI UJI",
	}, regNow)
	if err != nil {
		t.Fatal(err)
	}
	fence2 := claimFence(t, s, owner, tx2.ID, tx2.Revision)
	applied2, err := s.ApplyLLMCategory(ctx, owner, tx2.ID, tx2.Revision, fence2.ID, fence2.LeaseToken, catID, "food",
		"azure-openai-compatible", "gpt-oss-120b", "category-prompt/1", "builtin-categories/1",
		"merchant_food", 0.97, regNow.Add(2*time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if applied2.ClassifierOrigin != "LLM" {
		t.Fatalf("reapply origin=%q want LLM", applied2.ClassifierOrigin)
	}
	note := "catatan"
	noted, err := s.UpdateTransaction(ctx, owner, tx2.ID, TransactionPatch{
		Note: &note, Revision: applied2.Revision,
	}, regNow.Add(3*time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if noted.ClassifierOrigin != "LLM" || noted.CategoryReason != "merchant_food" {
		t.Fatalf("note-only edit cleared provenance: origin=%q reason=%q",
			noted.ClassifierOrigin, noted.CategoryReason)
	}
}

// Finding 2 regression: the orphan sweep is one transaction per reservation. A failure while
// adjusting any counter must roll the conditional RESERVED -> UNKNOWN transition back too, so the
// reservation is still RESERVED and every counter is untouched; after the fault is removed the
// same sweep applies the whole thing exactly once.
func TestSweepOrphanIsAtomicUnderCounterFailure(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	id, ok, err := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 1.0, 1.0, 0.10, regNow)
	if err != nil || !ok {
		t.Fatalf("reserve ok=%v err=%v", ok, err)
	}
	if _, err := s.DB.ExecContext(ctx, `UPDATE category_reservations SET created_at=? WHERE id=?`,
		nowString(regNow.Add(-time.Hour)), id); err != nil {
		t.Fatal(err)
	}
	// Fail the two global (installation) counter updates, i.e. after the owner deltas were already
	// executed inside the transaction.
	if _, err := s.DB.ExecContext(ctx, `CREATE TRIGGER fail_install_counter BEFORE UPDATE ON category_usage
		WHEN NEW.scope='*'
		BEGIN SELECT RAISE(ABORT, 'injected counter failure'); END`); err != nil {
		t.Fatal(err)
	}
	swept, err := s.SweepOrphanCategoryReservations(ctx, regNow.Add(-10*time.Minute), regNow)
	if err == nil {
		t.Fatal("sweep reported success despite the injected counter failure")
	}
	if swept != 0 {
		t.Fatalf("swept=%d after rollback", swept)
	}
	var state string
	if err := s.DB.QueryRowContext(ctx, `SELECT state FROM category_reservations WHERE id=?`, id).Scan(&state); err != nil {
		t.Fatal(err)
	}
	if state != "RESERVED" {
		t.Fatalf("state=%s: reservation transition was not rolled back", state)
	}
	for _, b := range []struct{ scope, bucket string }{
		{"owner-a", "day:" + jakartaDay(regNow)},
		{"owner-a", "month:" + jakartaDay(regNow)[:7]},
		{"*", "day:" + jakartaDay(regNow)},
	} {
		_, reserved, actual := usageRow(t, s, b.scope, b.bucket)
		if !near(reserved, 0.10) || !near(actual, 0) {
			t.Fatalf("%s/%s partial counters: reserved=%v actual=%v", b.scope, b.bucket, reserved, actual)
		}
	}
	// Remove the fault: the very same orphan is now refunded exactly once.
	if _, err := s.DB.ExecContext(ctx, `DROP TRIGGER fail_install_counter`); err != nil {
		t.Fatal(err)
	}
	if n, err := s.SweepOrphanCategoryReservations(ctx, regNow.Add(-10*time.Minute), regNow); err != nil || n != 1 {
		t.Fatalf("replay sweep n=%d err=%v", n, err)
	}
	if n, err := s.SweepOrphanCategoryReservations(ctx, regNow.Add(-10*time.Minute), regNow); err != nil || n != 0 {
		t.Fatalf("third sweep n=%d err=%v (double charge)", n, err)
	}
	for _, b := range []struct{ scope, bucket string }{
		{"owner-a", "day:" + jakartaDay(regNow)},
		{"owner-a", "month:" + jakartaDay(regNow)[:7]},
		{"*", "day:" + jakartaDay(regNow)},
		{"*", "month:" + jakartaDay(regNow)[:7]},
	} {
		_, reserved, actual := usageRow(t, s, b.scope, b.bucket)
		if !near(reserved, 0) || !near(actual, 0.10) {
			t.Fatalf("%s/%s after replay: reserved=%v actual=%v want actual 0.10 once", b.scope, b.bucket, reserved, actual)
		}
	}
}

// Finding 2 regression: a concurrent settlement and orphan sweep of the same reservation must
// charge the cost exactly once. The conditional transition is the only arbiter.
func TestSweepOrphanConcurrentWithSettlement(t *testing.T) {
	for i := 0; i < 8; i++ {
		s, _ := openCategoryStore(t)
		ctx := context.Background()
		id, ok, err := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 1.0, 1.0, 0.10, regNow)
		if err != nil || !ok {
			t.Fatalf("reserve ok=%v err=%v", ok, err)
		}
		if _, err := s.DB.ExecContext(ctx, `UPDATE category_reservations SET created_at=? WHERE id=?`,
			nowString(regNow.Add(-time.Hour)), id); err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			_, _ = s.SweepOrphanCategoryReservations(ctx, regNow.Add(-10*time.Minute), regNow)
		}()
		go func() {
			defer wg.Done()
			_ = s.SettleCategoryReservation(ctx, "owner-a", id, 0.06, 0.06, true, regNow)
		}()
		wg.Wait()
		var state string
		if err := s.DB.QueryRowContext(ctx, `SELECT state FROM category_reservations WHERE id=?`, id).Scan(&state); err != nil {
			t.Fatal(err)
		}
		want := 0.10 // sweep won: conservative reserved cost
		if state == "SETTLED" {
			want = 0.06 // settlement won: measured cost
		} else if state != "UNKNOWN" {
			t.Fatalf("run %d: state=%s", i, state)
		}
		_, reserved, actual := usageRow(t, s, "owner-a", "day:"+jakartaDay(regNow))
		if !near(reserved, 0) || !near(actual, want) {
			t.Fatalf("run %d (%s): reserved=%v actual=%v want exactly one %v charge", i, state, reserved, actual, want)
		}
	}
}

// Finding 3 regression: every write is fenced by the lease token issued at claim time. After a
// recovery worker reclaims the lease, the old worker cannot finish, requeue, apply a category or
// write a cache entry for that job, and it cannot overwrite the terminal state B wrote.
func TestLeaseFencingRejectsStaleWorker(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	owner, txID, catID := regOwnerSetup(t, s, "fencing@example.test")
	job, err := s.CreateCategoryJob(ctx, owner, txID, 0, regNow)
	if err != nil {
		t.Fatal(err)
	}
	tokenA, ok, err := s.ClaimCategoryJob(ctx, owner, job.ID, regNow, CategoryLeaseDuration)
	if err != nil || !ok {
		t.Fatalf("claim A ok=%v err=%v", ok, err)
	}
	reclaimed, err := s.ClaimCategoryJobsForRecovery(ctx, regNow.Add(CategoryLeaseDuration+time.Minute), CategoryLeaseDuration, 10)
	if err != nil || len(reclaimed) != 1 {
		t.Fatalf("recovery len=%d err=%v", len(reclaimed), err)
	}
	tokenB := reclaimed[0].LeaseToken
	if tokenB == "" || tokenB == tokenA {
		t.Fatalf("recovery token %q must differ from %q", tokenB, tokenA)
	}
	now := regNow.Add(CategoryLeaseDuration + 2*time.Minute)
	before, err := s.GetTransaction(ctx, owner, txID)
	if err != nil {
		t.Fatal(err)
	}
	// A's writes are all refused.
	if err := s.FinishCategoryJob(ctx, owner, job.ID, tokenA, "CLASSIFIED", "food", "r", "", "p", "m", "v", "t", 0.9, now); err != ErrLeaseLost {
		t.Fatalf("stale finish err=%v", err)
	}
	if _, err := s.RequeueCategoryJob(ctx, owner, job.ID, tokenA, now); err != ErrLeaseLost {
		t.Fatalf("stale requeue err=%v", err)
	}
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, before.Revision, job.ID, tokenA, catID, "food",
		"p", "m", "category-prompt/1", "builtin-categories/1", "r", 0.9, now); err != ErrLeaseLost {
		t.Fatalf("stale apply err=%v", err)
	}
	if err := s.CategoryCachePut(ctx, owner, job.ID, tokenA, storeKey("BAKSO UJI"), "DEBIT", "QRIS",
		"builtin-categories/1", "m", "category-prompt/1", "caseid/1", "food", "r", false, now); err != nil {
		t.Fatalf("stale cache put should be a silent no-op: %v", err)
	}
	// Nothing A tried changed the row, the transaction or the cache.
	mid, err := s.GetCategoryJob(ctx, owner, job.ID)
	if err != nil {
		t.Fatal(err)
	}
	if mid.State != "RUNNING" || mid.LeaseToken != tokenB || mid.RetryCount != 0 || mid.CategoryCode != "" {
		t.Fatalf("stale worker changed job: %+v", mid)
	}
	midTx, _ := s.GetTransaction(ctx, owner, txID)
	if midTx.Revision != before.Revision || midTx.CategoryID != "" || midTx.CategoryOrigin == "RULE" {
		t.Fatalf("stale worker changed transaction: %+v", midTx)
	}
	if code, _, _, _ := s.CategoryCacheGet(ctx, owner, storeKey("BAKSO UJI"), "DEBIT", "QRIS",
		"builtin-categories/1", "m", "category-prompt/1", "caseid/1"); code != "" {
		t.Fatalf("stale worker wrote cache: %q", code)
	}
	// B applies and finishes legitimately.
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, before.Revision, job.ID, tokenB, catID, "food",
		"p", "m", "category-prompt/1", "builtin-categories/1", "r", 0.9, now); err != nil {
		t.Fatalf("owner apply: %v", err)
	}
	if err := s.FinishCategoryJob(ctx, owner, job.ID, tokenB, "CLASSIFIED", "food", "r", "", "p", "m", "v", "t", 0.9, now); err != nil {
		t.Fatalf("owner finish: %v", err)
	}
	// A returns even later: the terminal state and the already-applied category stay B's.
	if err := s.FinishCategoryJob(ctx, owner, job.ID, tokenA, "FAILED", "", "", "provider_error", "p", "m", "v", "t", 0, now); err != ErrLeaseLost {
		t.Fatalf("late finish err=%v", err)
	}
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, before.Revision+1, job.ID, tokenA, catID, "transport",
		"p", "m", "category-prompt/1", "builtin-categories/1", "r", 0.9, now); err != ErrLeaseLost {
		t.Fatalf("late apply err=%v", err)
	}
	final, _ := s.GetCategoryJob(ctx, owner, job.ID)
	if final.State != "CLASSIFIED" || final.CategoryCode != "food" {
		t.Fatalf("late worker overwrote terminal state: %+v", final)
	}
	finalTx, _ := s.GetTransaction(ctx, owner, txID)
	if finalTx.CategoryCode != "food" || finalTx.Revision != before.Revision+1 {
		t.Fatalf("late worker changed transaction: %+v", finalTx)
	}
}

func storeKey(merchant string) string { return CacheKey(merchant, "DEBIT", "QRIS") }
