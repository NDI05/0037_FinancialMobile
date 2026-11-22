package httpapi

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/pam/recap/backend-go/internal/categoryllm"
	"github.com/pam/recap/backend-go/internal/store"
)

// failingCategory returns a fixed error from Classify and counts attempts.
type failingCategory struct {
	mu    sync.Mutex
	calls int
	err   error
}

func (f *failingCategory) ID() string    { return "stub" }
func (f *failingCategory) Model() string { return "stub-model" }
func (f *failingCategory) SplitCandidates(c []categoryllm.Candidate) [][]categoryllm.Candidate {
	if len(c) == 0 {
		return nil
	}
	return [][]categoryllm.Candidate{c}
}
func (f *failingCategory) FitsInputBudget([]categoryllm.Candidate) bool { return true }
func (f *failingCategory) Classify(_ context.Context, candidates []categoryllm.Candidate) (categoryllm.Response, error) {
	f.mu.Lock()
	f.calls++
	f.mu.Unlock()
	if f.err != nil {
		return categoryllm.Response{}, f.err
	}
	out := make([]categoryllm.Result, len(candidates))
	for i, c := range candidates {
		code, review := "uncategorized", true
		if strings.HasPrefix(c.Merchant, "BAKSO") {
			code, review = "food", false
		}
		out[i] = categoryllm.Result{CaseID: "c" + string(rune('0'+i)), CategoryCode: code,
			NeedsReview: review, ReasonCode: "stub"}
	}
	return categoryllm.Response{Results: out, PromptTokens: 320, CompletionTokens: 16, UsageKnown: true}, nil
}
func (f *failingCategory) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

func classifyBody(t *testing.T, h *harness, token, merchant, description, fingerprint string) string {
	t.Helper()
	status, created := h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "source": "MANUAL", "categoryOrigin": "IMPORTED",
		"amountMinor": 16000, "currency": "IDR", "eventAt": "2026-11-19T02:15:20Z",
		"fingerprint": fingerprint, "parserVersion": "livin-v1", "parserConfidence": 0.9,
		"description": description, "merchantLabel": merchant,
	})
	if status != 201 {
		t.Fatalf("create tx=%d %v", status, created)
	}
	return created["id"].(string)
}

func grantConsent(t *testing.T, h *harness, token string) {
	t.Helper()
	if status, _ := h.request("POST", "/v1/consents", token, map[string]any{
		"purpose": "AI_CATEGORIZATION", "decision": "GRANTED", "noticeVersion": "notice-v2"}); status != 201 {
		t.Fatalf("consent=%d", status)
	}
}

// The budget gate must reject the attempt BEFORE the provider is called, and every claimed job
// must still reach a terminal state (FAILED budget_exhausted) instead of being left RUNNING.
func TestBudgetExhaustedBeforeProviderCall(t *testing.T) {
	stub := &failingCategory{}
	h := newCategoryHarnessWithEnv(t, stub, map[string]string{
		"CATEGORY_LLM_DAILY_CEILING_USD": "0.00001",
	})
	h.register("budget@example.test")
	token, _ := h.login("budget@example.test")
	grantConsent(t, h, token)
	txID := classifyBody(t, h, token, "BAKSO UJI", "QRIS", "a"+strings.Repeat("0", 31))
	status, body := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{txID}})
	if status != 429 {
		t.Fatalf("status=%d body=%v", status, body)
	}
	if body["error"] != "budget_exhausted" {
		t.Fatalf("error=%v", body["error"])
	}
	if stub.count() != 0 {
		t.Fatalf("provider called %d times despite exhausted budget", stub.count())
	}
	jobs, _ := body["jobs"].([]any)
	if len(jobs) != 1 {
		t.Fatalf("jobs=%v", body["jobs"])
	}
	job := jobs[0].(map[string]any)
	if job["state"] != "FAILED" || job["errorCode"] != "budget_exhausted" {
		t.Fatalf("job=%v", job)
	}
}

// A classifier that fails transiently must not lose the job: it is durably requeued to PENDING
// (retry_count 1) and the recovery worker runs exactly one more attempt; a second transient
// failure is terminal.
func TestTransientFailureRequeuesOnceThenFails(t *testing.T) {
	stub := &failingCategory{err: categoryllm.ErrProviderTransient}
	h := newCategoryHarness(t, stub)
	h.register("transient@example.test")
	token, _ := h.login("transient@example.test")
	grantConsent(t, h, token)
	txID := classifyBody(t, h, token, "BAKSO UJI", "QRIS", "c"+strings.Repeat("0", 31))
	status, body := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{txID}})
	if status != 200 {
		t.Fatalf("status=%d body=%v", status, body)
	}
	job := body["jobs"].([]any)[0].(map[string]any)
	if job["state"] != "PENDING" {
		t.Fatalf("transient job should be requeued, got %v", job)
	}
	if stub.count() != 1 {
		t.Fatalf("inline attempts=%d want 1", stub.count())
	}
	// The recovery worker claims the PENDING job and retries exactly once.
	worker := NewCategoryWorker(h.api)
	worker.tick()
	if stub.count() != 2 {
		t.Fatalf("after recovery attempts=%d want 2 (one bounded retry)", stub.count())
	}
	_, jobsResp := h.request("GET", "/v1/category-jobs/"+job["id"].(string), token, nil)
	if jobsResp["state"] != "FAILED" || jobsResp["errorCode"] != "provider_transient" {
		t.Fatalf("after second transient failure: %v", jobsResp)
	}
	// A third tick must not attempt again: the job is terminal.
	worker.tick()
	if stub.count() != 2 {
		t.Fatalf("terminal job retried: attempts=%d", stub.count())
	}
}

// A transient failure whose retry succeeds must end CLASSIFIED with the category applied.
func TestTransientRetryRecoversToClassified(t *testing.T) {
	stub := &failingCategory{err: categoryllm.ErrProviderTransient}
	h := newCategoryHarness(t, stub)
	h.register("recover@example.test")
	token, _ := h.login("recover@example.test")
	grantConsent(t, h, token)
	txID := classifyBody(t, h, token, "BAKSO UJI", "QRIS", "d"+strings.Repeat("0", 31))
	_, body := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{txID}})
	jobID := body["jobs"].([]any)[0].(map[string]any)["id"].(string)
	stub.mu.Lock()
	stub.err = nil // provider recovered
	stub.mu.Unlock()
	NewCategoryWorker(h.api).tick()
	_, got := h.request("GET", "/v1/category-jobs/"+jobID, token, nil)
	if got["state"] != "CLASSIFIED" || got["categoryCode"] != "food" {
		t.Fatalf("recovered job=%v", got)
	}
	_, tx := h.request("GET", "/v1/transactions/"+txID, token, nil)
	// DB truth is category_origin=RULE + classifier_origin=LLM; the API surfaces the classifier
	// origin as the client-visible provenance so the app can show "AI".
	if tx["categoryOrigin"] != "LLM" || tx["categoryCode"] != "food" || tx["categoryReason"] != "stub" {
		t.Fatalf("transaction not updated: %v", tx)
	}
}

// A personal/internal transfer must never reach the provider: no budget attempt, no merchant send.
func TestPersonalTransferNeverSentToProvider(t *testing.T) {
	stub := &failingCategory{}
	h := newCategoryHarness(t, stub)
	h.register("transfer@example.test")
	token, _ := h.login("transfer@example.test")
	grantConsent(t, h, token)
	// Internal transfer flag.
	status, created := h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "source": "MANUAL", "categoryOrigin": "IMPORTED", "amountMinor": 50000,
		"currency": "IDR", "eventAt": "2026-11-19T02:15:20Z", "fingerprint": "e" + strings.Repeat("0", 31),
		"description": "TRANSFER KE BUDI", "merchantLabel": "BUDI", "isInternalTransfer": true,
	})
	if status != 201 {
		t.Fatalf("create=%d %v", status, created)
	}
	id := created["id"].(string)
	status, body := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{id}})
	if status != 200 {
		t.Fatalf("status=%d %v", status, body)
	}
	job := body["jobs"].([]any)[0].(map[string]any)
	if job["state"] != "NEEDS_REVIEW" || job["reasonCode"] != "not_eligible_personal_transfer" {
		t.Fatalf("job=%v", job)
	}
	if stub.count() != 0 {
		t.Fatalf("personal transfer sent to provider %d times", stub.count())
	}
	// BI-FAST to an individual (no internal flag) is also transfer-shaped.
	status, created2 := h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "source": "MANUAL", "categoryOrigin": "IMPORTED", "amountMinor": 50000,
		"currency": "IDR", "eventAt": "2026-11-19T02:16:20Z", "fingerprint": "f" + strings.Repeat("1", 31),
		"description": "BI-FAST KE SITI", "merchantLabel": "SITI",
	})
	if status != 201 {
		t.Fatalf("create2=%d %v", status, created2)
	}
	_, body2 := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{created2["id"].(string)}})
	job2 := body2["jobs"].([]any)[0].(map[string]any)
	if job2["state"] != "NEEDS_REVIEW" || job2["reasonCode"] != "not_eligible_personal_transfer" {
		t.Fatalf("bi-fast job=%v", job2)
	}
	if stub.count() != 0 {
		t.Fatalf("BI-FAST counterparty sent to provider %d times", stub.count())
	}
}

// Identical merchant+context in one batch is coalesced into a single provider case, and the
// result is applied to every transaction in the group.
func TestBatchCoalescingSingleProviderCase(t *testing.T) {
	var seen int
	stub := &countingCategory{onClassify: func(c []categoryllm.Candidate) { seen = len(c) }}
	h := newCategoryHarness(t, stub)
	h.register("coalesce@example.test")
	token, _ := h.login("coalesce@example.test")
	grantConsent(t, h, token)
	id1 := classifyBody(t, h, token, "BAKSO UJI", "QRIS", "1"+strings.Repeat("0", 31))
	id2 := classifyBody(t, h, token, "BAKSO UJI", "QRIS", "2"+strings.Repeat("0", 31))
	status, body := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{id1, id2}})
	if status != 200 {
		t.Fatalf("status=%d %v", status, body)
	}
	if seen != 1 {
		t.Fatalf("provider received %d cases for identical merchant+context, want 1", seen)
	}
	jobs := body["jobs"].([]any)
	if len(jobs) != 2 {
		t.Fatalf("jobs=%d", len(jobs))
	}
	for _, j := range jobs {
		m := j.(map[string]any)
		if m["state"] != "CLASSIFIED" {
			t.Fatalf("coalesced job=%v", m)
		}
	}
}

// A duplicate tap while the first request is in flight (or already terminal) must not call the
// provider again: the existing job is replayed.
func TestDuplicateTapDoesNotRecallProvider(t *testing.T) {
	stub := &failingCategory{}
	h := newCategoryHarness(t, stub)
	h.register("dup@example.test")
	token, _ := h.login("dup@example.test")
	grantConsent(t, h, token)
	txID := classifyBody(t, h, token, "BAKSO UJI", "QRIS", "3"+strings.Repeat("0", 31))
	if status, _ := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{txID}}); status != 200 {
		t.Fatal(status)
	}
	if stub.count() != 1 {
		t.Fatalf("first call=%d", stub.count())
	}
	status, body := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{txID}})
	if status != 200 {
		t.Fatalf("second status=%d %v", status, body)
	}
	// The transaction is already categorized, so the re-tap resolves to a terminal
	// not-eligible job rather than a second provider call.
	job := body["jobs"].([]any)[0].(map[string]any)
	if state := job["state"]; state != "NEEDS_REVIEW" && state != "CLASSIFIED" {
		t.Fatalf("second tap job=%v", job)
	}
	if stub.count() != 1 {
		t.Fatalf("duplicate tap called provider again: %d", stub.count())
	}
}

// Withdrawal of consent between the request check and apply must not write a category.
func TestConsentWithdrawnBeforeApplyBlocksWrite(t *testing.T) {
	stub := &failingCategory{}
	h := newCategoryHarness(t, stub)
	h.register("withdraw@example.test")
	token, _ := h.login("withdraw@example.test")
	grantConsent(t, h, token)
	txID := classifyBody(t, h, token, "BAKSO UJI", "QRIS", "4"+strings.Repeat("0", 31))
	// Withdraw consent (latest decision WITHDRAWN) before classification runs.
	if status, _ := h.request("POST", "/v1/consents", token, map[string]any{
		"purpose": "AI_CATEGORIZATION", "decision": "WITHDRAWN", "noticeVersion": "notice-v3"}); status != 201 {
		t.Fatal("withdraw consent")
	}
	status, _ := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{txID}})
	if status != 403 {
		t.Fatalf("withdrawn consent status=%d", status)
	}
	if stub.count() != 0 {
		t.Fatal("provider called after withdrawal")
	}
}

// A stale job (transaction revision moved) must not overwrite the newer state.
func TestStaleRevisionJobFailsWithoutApplying(t *testing.T) {
	stub := &failingCategory{}
	h := newCategoryHarness(t, stub)
	h.register("stale@example.test")
	token, _ := h.login("stale@example.test")
	grantConsent(t, h, token)
	txID := classifyBody(t, h, token, "BAKSO UJI", "QRIS", "5"+strings.Repeat("0", 31))
	_, tx := h.request("GET", "/v1/transactions/"+txID, token, nil)
	// Create a PENDING job at the current revision directly, then move the transaction revision.
	var ownerID string
	if err := h.store.DB.QueryRowContext(context.Background(), `SELECT owner_id FROM transactions WHERE id=?`, txID).Scan(&ownerID); err != nil {
		t.Fatal(err)
	}
	job, err := h.store.CreateCategoryJob(context.Background(), ownerID, txID, int(tx["revision"].(float64)), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := h.store.DB.ExecContext(context.Background(),
		`UPDATE transactions SET revision = revision + 1 WHERE id=?`, txID); err != nil {
		t.Fatal(err)
	}
	// Re-tap claims nothing (job exists in PENDING); recovery finds it stale and fails it.
	NewCategoryWorker(h.api).tick()
	got, err := h.store.GetCategoryJob(context.Background(), ownerID, job.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.State != "FAILED" || got.ErrorCode != "stale_job" {
		t.Fatalf("stale job=%+v", got)
	}
	if stub.count() != 0 {
		t.Fatalf("stale job reached the provider %d times", stub.count())
	}
	_, after := h.request("GET", "/v1/transactions/"+txID, token, nil)
	if after["categoryCode"] == "food" || after["categoryOrigin"] == "LLM" {
		t.Fatalf("stale job applied a category: %v", after)
	}
}

// Owner B must not be able to read or drive owner A's jobs through the API.
func TestOwnerCannotReadForeignJob(t *testing.T) {
	stub := &failingCategory{}
	h := newCategoryHarness(t, stub)
	h.register("owner-a@example.test")
	h.register("owner-b@example.test")
	tokenA, _ := h.login("owner-a@example.test")
	tokenB, _ := h.login("owner-b@example.test")
	grantConsent(t, h, tokenA)
	grantConsent(t, h, tokenB)
	txID := classifyBody(t, h, tokenA, "BAKSO UJI", "QRIS", "6"+strings.Repeat("0", 31))
	_, body := h.request("POST", "/v1/category-jobs", tokenA, map[string]any{"transactionIds": []string{txID}})
	jobID := body["jobs"].([]any)[0].(map[string]any)["id"].(string)
	if status, _ := h.request("GET", "/v1/category-jobs/"+jobID, tokenB, nil); status != 404 {
		t.Fatalf("owner B read owner A job: status=%d", status)
	}
	if status, _ := h.request("GET", "/v1/category-jobs/"+jobID, tokenA, nil); status != 200 {
		t.Fatalf("owner A cannot read own job: status=%d", status)
	}
}

func TestClassifyErrorCodeMapping(t *testing.T) {
	cases := map[error]string{
		categoryllm.ErrInputTooLarge:      "input_too_large",
		categoryllm.ErrTruncated:          "truncated",
		categoryllm.ErrInvalidOutput:      "invalid_output",
		categoryllm.ErrInvalidInput:       "invalid_input",
		categoryllm.ErrProviderTransient:  "provider_transient",
		errors.New("some other failure"):  "provider_error",
	}
	for err, want := range cases {
		if got := classifyErrorCode(err); got != want {
			t.Fatalf("classifyErrorCode(%v)=%s want %s", err, got, want)
		}
	}
}

// countingCategory lets a test observe the provider case count.
type countingCategory struct {
	failingCategory
	onClassify func([]categoryllm.Candidate)
}

func (c *countingCategory) Classify(ctx context.Context, candidates []categoryllm.Candidate) (categoryllm.Response, error) {
	if c.onClassify != nil {
		c.onClassify(candidates)
	}
	return c.failingCategory.Classify(ctx, candidates)
}

func (c *countingCategory) ID() string    { return "stub" }
func (c *countingCategory) Model() string { return "stub-model" }
func (c *countingCategory) SplitCandidates(cs []categoryllm.Candidate) [][]categoryllm.Candidate {
	if len(cs) == 0 {
		return nil
	}
	return [][]categoryllm.Candidate{cs}
}
func (c *countingCategory) FitsInputBudget([]categoryllm.Candidate) bool { return true }

var _ = store.ErrNotFound
// An explicit user retry after a terminal provider failure must be able to run again instead of
// replaying the FAILED job forever, while a NEEDS_REVIEW decision is never re-litigated.
func TestExplicitRetryAfterFailedJob(t *testing.T) {
	stub := &failingCategory{err: categoryllm.ErrProviderTransient}
	h := newCategoryHarness(t, stub)
	h.register("retry@example.test")
	token, _ := h.login("retry@example.test")
	grantConsent(t, h, token)
	txID := classifyBody(t, h, token, "BAKSO UJI", "QRIS", "7"+strings.Repeat("0", 31))
	_, first := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{txID}})
	jobID := first["jobs"].([]any)[0].(map[string]any)["id"].(string)
	NewCategoryWorker(h.api).tick() // retry #1 -> transient again -> FAILED
	_, failed := h.request("GET", "/v1/category-jobs/"+jobID, token, nil)
	if failed["state"] != "FAILED" {
		t.Fatalf("state=%v", failed["state"])
	}
	before := stub.count()
	status, again := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{txID}})
	if status != 200 {
		t.Fatalf("retry status=%d %v", status, again)
	}
	if got := again["jobs"].([]any)[0].(map[string]any); got["state"] != "PENDING" {
		t.Fatalf("retry not re-attempted: %v", got)
	}
	if stub.count() != before+1 {
		t.Fatalf("retry did not reach the provider: %d -> %d", before, stub.count())
	}
	// A recovered provider then completes the job for real.
	stub.mu.Lock()
	stub.err = nil
	stub.mu.Unlock()
	NewCategoryWorker(h.api).tick()
	_, done := h.request("GET", "/v1/category-jobs/"+jobID, token, nil)
	if done["state"] != "CLASSIFIED" {
		t.Fatalf("after recovery: %v", done)
	}
	// Tapping again on a CLASSIFIED job must not call the provider.
	after := stub.count()
	h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds": []string{txID}})
	if stub.count() != after {
		t.Fatalf("classified job re-billed: %d -> %d", after, stub.count())
	}
}

func TestResetFailedJobIsOwnerScoped(t *testing.T) {
	stub := &failingCategory{}
	h := newCategoryHarness(t, stub)
	ctx := context.Background()
	ownerA, txA, _ := setupOwnerRow(t, h, "reset-a@example.test")
	ownerB, _, _ := setupOwnerRow(t, h, "reset-b@example.test")
	job, err := h.store.CreateCategoryJob(ctx, ownerA, txA, 0, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if err := h.store.FinishCategoryJob(ctx, ownerA, job.ID, "FAILED", "", "", "provider_transient", "p", "m", "v", "t", 0, time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := h.store.ResetFailedCategoryJob(ctx, ownerB, job.ID, time.Now()); err == nil {
		t.Fatal("owner B reset owner A's job")
	}
	if _, err := h.store.GetCategoryJob(ctx, ownerB, job.ID); err == nil {
		t.Fatal("owner B read owner A's job")
	}
	if reset, err := h.store.ResetFailedCategoryJob(ctx, ownerA, job.ID, time.Now()); err != nil {
		t.Fatal(err)
	} else if reset.State != "PENDING" || reset.RetryCount != 0 {
		t.Fatalf("reset=%+v", reset)
	}
	// CLASSIFIED and NEEDS_REVIEW jobs are not resettable.
	if err := h.store.FinishCategoryJob(ctx, ownerA, job.ID, "NEEDS_REVIEW", "uncategorized", "unsafe_merchant", "", "p", "m", "v", "t", 0, time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := h.store.ResetFailedCategoryJob(ctx, ownerA, job.ID, time.Now()); err == nil {
		t.Fatal("NEEDS_REVIEW job was reset")
	}
}

// setupOwnerRow creates a real owner plus one uncategorized transaction for store-level tests.
func setupOwnerRow(t *testing.T, h *harness, email string) (owner, txID, categoryID string) {
	t.Helper()
	ctx := context.Background()
	if _, err := h.store.CreateAccount(ctx, email, "Uji",
		store.PasswordDigest{Salt: []byte("salt"), Hash: []byte("hash"), KDF: "scrypt:N=32768,r=8,p=1"},
		time.Date(2026, 11, 22, 12, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	owner = store.OwnerIDForEmail(email)
	catID, err := h.store.CategoryIDForCode(ctx, owner, "food")
	if err != nil {
		t.Fatal(err)
	}
	tx, err := h.store.CreateTransaction(ctx, owner, store.TransactionInput{
		Direction: "DEBIT", Source: "MANUAL", AmountMinor: 16000, Currency: "IDR",
		EventAt: time.Date(2026, 11, 19, 2, 15, 20, 0, time.UTC), Fingerprint: strings.Repeat("9", 32),
		ParserVersion: "livin-v1", ParserConfidence: 0.9, CategoryOrigin: "IMPORTED",
		Description: "QRIS", MerchantLabel: "BAKSO UJI",
	}, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	return owner, tx.ID, catID
}
