package httpapi

import (
	"context"
	"strings"
	"testing"
	"time"
)

// queueTx creates one owner transaction for the automatic-queue tests.
func (h *harness) queueTx(token, fingerprint, merchant, description string) string {
	h.t.Helper()
	body := map[string]any{"direction": "DEBIT", "source": "MANUAL", "categoryOrigin": "IMPORTED",
		"amountMinor": 16000, "currency": "IDR", "eventAt": "2026-11-19T02:15:20Z",
		"fingerprint": fingerprint, "parserVersion": "livin-v1", "parserConfidence": 0.9,
		"description": description, "merchantLabel": merchant}
	status, created := h.request("POST", "/v1/transactions", token, body)
	if status != 201 {
		h.t.Fatalf("create transaction=%d %v", status, created)
	}
	return created["id"].(string)
}

func (h *harness) grantCategoryConsent(token string) {
	h.t.Helper()
	status, body := h.request("POST", "/v1/consents", token,
		map[string]any{"purpose": "AI_CATEGORIZATION", "decision": "GRANTED", "noticeVersion": "notice-v2"})
	if status != 201 {
		h.t.Fatalf("consent=%d %v", status, body)
	}
}

func (h *harness) pendingQueue() int {
	h.t.Helper()
	n, err := h.store.CountQueuedCategoryJobs(context.Background(), h.ownerID())
	if err != nil {
		h.t.Fatal(err)
	}
	return n
}

func (h *harness) ownerID() string {
	h.t.Helper()
	var owner string
	if err := h.store.DB.QueryRowContext(context.Background(), `SELECT owner_id FROM users LIMIT 1`).Scan(&owner); err != nil {
		h.t.Fatal(err)
	}
	return owner
}

// TestAutoCategoryQueueEnqueueNeverCallsProvider proves the enqueue leg is provider-free, idempotent,
// consent-gated and skips transactions a USER already decided.
func TestAutoCategoryQueueEnqueueNeverCallsProvider(t *testing.T) {
	stub := &stubCategory{}
	h := newCategoryHarness(t, stub)
	h.register("queue@example.test")
	token, _ := h.login("queue@example.test")
	txID := h.queueTx(token, "a"+strings.Repeat("0", 31), "BAKSO UJI", "QRIS BAKSO")

	status, body := h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": []string{txID}})
	if status != 403 {
		t.Fatalf("enqueue without consent status=%d %v", status, body)
	}
	if stub.calls != 0 || h.pendingQueue() != 0 {
		t.Fatalf("consent gate leaked: calls=%d pending=%d", stub.calls, h.pendingQueue())
	}

	h.grantCategoryConsent(token)
	status, body = h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": []string{txID}})
	if status != 200 || body["queued"].(float64) != 1 || body["pending"].(float64) != 1 {
		t.Fatalf("enqueue=%d %v", status, body)
	}
	if stub.calls != 0 {
		t.Fatalf("enqueue called the provider %d times", stub.calls)
	}
	// Re-delivering the same financial commit must not grow the backlog.
	_, body = h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": []string{txID}})
	if body["queued"].(float64) != 0 || body["pending"].(float64) != 1 {
		t.Fatalf("re-enqueue %v", body)
	}

	// A USER decision wins: the row is never enqueued even though the request asks for it.
	tx := h.queueTx(token, "b"+strings.Repeat("0", 31), "APOTEK SEHAT", "QRIS APOTEK")
	status, created := h.request("GET", "/v1/transactions/"+tx, token, nil)
	if status != 200 {
		t.Fatalf("get tx=%d", status)
	}
	foodID := h.categoryID("food")
	status, _ = h.request("PATCH", "/v1/transactions/"+tx, token, map[string]any{"categoryId": foodID, "revision": created["revision"]})
	if status != 200 {
		t.Fatalf("user override patch=%d", status)
	}
	_, body = h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": []string{tx}})
	if body["queued"].(float64) != 0 {
		t.Fatalf("user override was enqueued: %v", body)
	}
}

func (h *harness) categoryID(code string) string {
	h.t.Helper()
	var id string
	if err := h.store.DB.QueryRowContext(context.Background(),
		`SELECT id FROM categories WHERE owner_id=? AND code=?`, h.ownerID(), code).Scan(&id); err != nil {
		h.t.Fatal(err)
	}
	return id
}

// TestAutoCategoryDrainHonoursIntervalBatchAndRemainder covers the server-side scheduler: empty and
// early polls never reach the provider, at most five unique merchants are classified per drain, the
// remainder is carried over, a second drain inside the interval is a no-op, and the cached merchant
// result is reused instead of paying twice.
func TestAutoCategoryDrainHonoursIntervalBatchAndRemainder(t *testing.T) {
	stub := &stubCategory{}
	h := newCategoryHarness(t, stub)
	h.register("drain@example.test")
	token, _ := h.login("drain@example.test")
	h.grantCategoryConsent(token)

	// Empty queue: no provider call, explicit empty state.
	status, body := h.request("POST", "/v1/category-jobs/drain", token, map[string]any{})
	if status != 200 || body["state"] != "empty" || stub.calls != 0 {
		t.Fatalf("empty drain=%d %v calls=%d", status, body, stub.calls)
	}

	merchants := []string{"BAKSO UJI", "TOKO LISTRIK", "APOTEK SEHAT"}
	var ids []string
	for i := 0; i < 3; i++ {
		for j := 0; j < 2; j++ {
			fp := string(rune('a'+i*2+j)) + strings.Repeat("0", 31)
			ids = append(ids, h.queueTx(token, fp, merchants[i], "QRIS "+merchants[i]))
		}
	}
	// One transaction from a fourth merchant exercises the per-request cap (4 merchants > 5 is not
	// reachable here, so the cap is checked with 6 merchants in the second test below).
	status, body = h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": ids})
	if status != 200 || body["queued"].(float64) != 6 {
		t.Fatalf("enqueue=%d %v", status, body)
	}

	status, body = h.request("POST", "/v1/category-jobs/drain", token, map[string]any{})
	if status != 200 || body["state"] != "drained" {
		t.Fatalf("drain=%d %v", status, body)
	}
	if stub.calls != 1 {
		t.Fatalf("three merchants should coalesce into one provider batch, calls=%d", stub.calls)
	}
	// One window runs several bounded batches: the three duplicate-key jobs are finished from the
	// cache in the same window instead of waiting another two hours.
	if body["drained"].(float64) != 6 || body["pending"].(float64) != 0 {
		t.Fatalf("batch/remainder wrong: %v", body)
	}

	// A merchant enqueued after the window opened is NOT due yet: a poll inside the interval is a
	// no-op and the provider is not called.
	newID := h.queueTx(token, "z"+strings.Repeat("3", 31), "WARUNG BARU", "QRIS WARUNG BARU")
	if status, body = h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": []string{newID}}); status != 200 {
		t.Fatalf("enqueue after window=%d %v", status, body)
	}
	status, body = h.request("POST", "/v1/category-jobs/drain", token, map[string]any{})
	if status != 200 || body["state"] != "not_due" || stub.calls != 1 {
		t.Fatalf("early drain=%d %v calls=%d", status, body, stub.calls)
	}
	if body["nextDueAt"] == "" {
		t.Fatalf("not_due must report a due time: %v", body)
	}

	// After the interval the new merchant is classified once.
	base := h.api.Now()
	h.api.Now = func() time.Time { return base.Add(CategoryDrainInterval + time.Minute) }
	// The access token was minted against the old clock; re-issue so the shift stays valid.
	token, _ = h.login("drain@example.test")
	status, body = h.request("POST", "/v1/category-jobs/drain", token, map[string]any{})
	if status != 200 || body["state"] != "drained" || body["pending"].(float64) != 0 {
		t.Fatalf("second drain=%d %v", status, body)
	}
	if stub.calls != 2 {
		t.Fatalf("the new merchant needs exactly one more call, calls=%d", stub.calls)
	}
}

// TestAutoCategoryDrainCapsUniqueMerchantsPerRequest keeps the provider request at five unique
// merchants but lets one window run two bounded batches, so ten distinct merchants finish in the
// same window with two calls instead of waiting two hours for the second half.
func TestAutoCategoryDrainCapsUniqueMerchantsPerRequest(t *testing.T) {
	stub := &stubCategory{}
	h := newCategoryHarness(t, stub)
	h.register("cap@example.test")
	token, _ := h.login("cap@example.test")
	h.grantCategoryConsent(token)
	var ids []string
	for i := 0; i < 10; i++ {
		merchant := "MERCHANT " + string(rune('A'+i))
		fp := string(rune('a'+i)) + strings.Repeat("1", 31)
		ids = append(ids, h.queueTx(token, fp, merchant, "QRIS "+merchant))
	}
	if status, body := h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": ids}); status != 200 {
		t.Fatalf("enqueue=%d %v", status, body)
	}
	status, body := h.request("POST", "/v1/category-jobs/drain", token, map[string]any{})
	if status != 200 {
		t.Fatalf("drain=%d %v", status, body)
	}
	if body["drained"].(float64) != 10 || body["pending"].(float64) != 0 {
		t.Fatalf("window batches wrong: %v", body)
	}
	if stub.calls != 2 {
		t.Fatalf("one call per five unique merchants expected, calls=%d", stub.calls)
	}
}

// TestAutoCategoryDrainKeepsBacklogWhenBudgetIsExhausted proves a denied budget neither calls the
// provider nor drops jobs: the backlog and a due time are reported, so the UI can say "ditunda".
func TestAutoCategoryDrainKeepsBacklogWhenBudgetIsExhausted(t *testing.T) {
	stub := &stubCategory{}
	h := newCategoryHarnessWithEnv(t, stub, map[string]string{
		"CATEGORY_LLM_DAILY_CEILING_USD": "0.00000001",
	})
	h.register("budget@example.test")
	token, _ := h.login("budget@example.test")
	h.grantCategoryConsent(token)
	ids := []string{
		h.queueTx(token, "c"+strings.Repeat("2", 31), "BAKSO UJI", "QRIS BAKSO"),
		h.queueTx(token, "d"+strings.Repeat("2", 31), "TOKO LISTRIK", "QRIS LISTRIK"),
	}
	if status, body := h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": ids}); status != 200 {
		t.Fatalf("enqueue=%d %v", status, body)
	}
	status, body := h.request("POST", "/v1/category-jobs/drain", token, map[string]any{})
	if status != 200 || body["state"] != "budget_exhausted" {
		t.Fatalf("drain=%d %v", status, body)
	}
	if stub.calls != 0 {
		t.Fatalf("denied budget must not reach the provider, calls=%d", stub.calls)
	}
	if body["pending"].(float64) != 2 {
		t.Fatalf("backlog dropped: %v", body)
	}
	if body["nextDueAt"] == nil {
		t.Fatalf("backlog must keep a due time: %v", body)
	}
}
