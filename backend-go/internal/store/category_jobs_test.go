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
	if claimed, err := s.ClaimCategoryJob(ctx, owner, job.ID, regNow, CategoryLeaseDuration); err != nil || !claimed {
		t.Fatalf("claim ok=%v err=%v", claimed, err)
	}
	// A fresh lease is not reclaimable by the recovery sweep.
	if jobs, _ := s.ClaimCategoryJobsForRecovery(ctx, regNow.Add(time.Minute), CategoryLeaseDuration, 10); len(jobs) != 0 {
		t.Fatalf("recovery stole a fresh lease: %d jobs", len(jobs))
	}
	// An expired lease is reclaimed.
	if jobs, _ := s.ClaimCategoryJobsForRecovery(ctx, regNow.Add(CategoryLeaseDuration+time.Minute), CategoryLeaseDuration, 10); len(jobs) != 1 {
		t.Fatalf("expired lease not reclaimed: %d jobs", len(jobs))
	}
	req, err := s.RequeueCategoryJob(ctx, owner, job.ID, regNow.Add(CategoryLeaseDuration+2*time.Minute))
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
	applied, err := s.ApplyLLMCategory(ctx, owner, txID, before.Revision, catID, "food",
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
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, tx.Revision, catID, "food", "p", "m",
		"category-prompt/1", "builtin-categories/1", "r", 0.9, regNow); err != ErrRevisionConflict {
		t.Fatalf("USER origin overwrite: err=%v", err)
	}
	if _, err := s.DB.ExecContext(ctx, `UPDATE transactions SET category_origin='IMPORTED' WHERE id=?`, txID); err != nil {
		t.Fatal(err)
	}

	// Stale revision is refused.
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, tx.Revision+99, catID, "food", "p", "m",
		"category-prompt/1", "builtin-categories/1", "r", 0.9, regNow); err != ErrRevisionConflict {
		t.Fatalf("stale revision: err=%v", err)
	}

	// Consent revocation is enforced inside the UPDATE, not by a racy pre-check.
	if _, err := s.AppendConsent(ctx, owner, "AI_CATEGORIZATION", "WITHDRAWN", "notice-v3", regNow.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	after, _ := s.GetTransaction(ctx, owner, txID)
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, after.Revision, catID, "food", "p", "m",
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
	if _, err := s.ApplyLLMCategory(ctx, owner, txID, after2.Revision, catID, "food", "p", "m",
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
	if ok, _ := s.ClaimCategoryJob(ctx, ownerB, job.ID, regNow, CategoryLeaseDuration); ok {
		t.Fatal("owner B claimed A's job")
	}
	if _, err := s.RequeueCategoryJob(ctx, ownerB, job.ID, regNow); err == nil {
		t.Fatal("owner B requeued A's job")
	}
	if err := s.FinishCategoryJob(ctx, ownerB, job.ID, "FAILED", "", "", "", "p", "m", "v", "t", 0, regNow); err == nil {
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
	if err := s.CategoryCachePut(ctx, ownerA, "k", "DEBIT", "QRIS", "builtin-categories/1", "m", "category-prompt/1", "food", "r", false, regNow); err != nil {
		t.Fatal(err)
	}
	if code, _, _, _ := s.CategoryCacheGet(ctx, ownerB, "k", "DEBIT", "QRIS", "builtin-categories/1", "m", "category-prompt/1"); code != "" {
		t.Fatalf("owner B read A's cache: code=%q", code)
	}
	if code, review, _, _ := s.CategoryCacheGet(ctx, ownerA, "k", "DEBIT", "QRIS", "builtin-categories/1", "m", "category-prompt/1"); code != "food" || review {
		t.Fatalf("owner A cache miss: code=%q review=%v", code, review)
	}
}