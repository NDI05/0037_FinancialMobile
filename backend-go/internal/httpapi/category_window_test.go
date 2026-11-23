package httpapi

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"
)

// TestCategoryWindowIsNotSkippedByRecovery proves the durable due time on each job: a merchant
// enqueued right after a drain is still PENDING when the recovery worker ticks, and the provider is
// not called until the window opens.
func TestCategoryWindowIsNotSkippedByRecovery(t *testing.T) {
	stub := &stubCategory{}
	h := newCategoryHarness(t, stub)
	h.register("window@example.test")
	token, _ := h.login("window@example.test")
	h.grantCategoryConsent(token)

	first := h.queueTx(token, "a"+strings.Repeat("4", 31), "BAKSO UJI", "QRIS BAKSO UJI")
	if status, body := h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": []string{first}}); status != 200 {
		t.Fatalf("enqueue=%d %v", status, body)
	}
	if status, body := h.request("POST", "/v1/category-jobs/drain", token, map[string]any{}); status != 200 || body["state"] != "drained" {
		t.Fatalf("first drain=%d %v", status, body)
	}
	if stub.calls != 1 {
		t.Fatalf("first window calls=%d", stub.calls)
	}

	second := h.queueTx(token, "b"+strings.Repeat("4", 31), "WARUNG BARU", "QRIS WARUNG BARU")
	if status, body := h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": []string{second}}); status != 200 {
		t.Fatalf("enqueue after window=%d %v", status, body)
	}
	// The recovery worker ticks while the job is still inside the window.
	NewCategoryWorker(h.api).tick()
	if stub.calls != 1 {
		t.Fatalf("recovery tick skipped the window: calls=%d", stub.calls)
	}
	if got := h.pendingQueue(); got != 1 {
		t.Fatalf("job left the queue before its window: pending=%d", got)
	}

	// Once the window opens the same worker path drains it without a client poll.
	base := h.api.Now()
	h.api.Now = func() time.Time { return base.Add(CategoryDrainInterval + time.Minute) }
	NewCategoryWorker(h.api).tick()
	if stub.calls != 2 {
		t.Fatalf("window did not open: calls=%d", stub.calls)
	}
	if got := h.pendingQueue(); got != 0 {
		t.Fatalf("backlog after window: pending=%d", got)
	}
}

// TestCategoryWindowDrainsWithoutClientPoll proves the backend owns the schedule: no call to
// /drain is needed for an acknowledged backlog to be classified.
func TestCategoryWindowDrainsWithoutClientPoll(t *testing.T) {
	stub := &stubCategory{}
	h := newCategoryHarness(t, stub)
	h.register("nopoll@example.test")
	token, _ := h.login("nopoll@example.test")
	h.grantCategoryConsent(token)
	id := h.queueTx(token, "c"+strings.Repeat("5", 31), "APOTEK SEHAT", "QRIS APOTEK SEHAT")
	if status, body := h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": []string{id}}); status != 200 {
		t.Fatalf("enqueue=%d %v", status, body)
	}
	NewCategoryWorker(h.api).tick()
	if stub.calls != 1 {
		t.Fatalf("server scheduler did not classify without a client poll: calls=%d", stub.calls)
	}
	if got := h.pendingQueue(); got != 0 {
		t.Fatalf("pending after scheduler tick: %d", got)
	}
}

// TestCategoryEightyUniqueMerchantsStayBounded proves the per-window bound: 80 unique merchants are
// never sent as one giant request, the window runs at most CategoryDrainMaxBatches provider calls of
// at most CategoryDrainBatch merchants, and the remainder keeps its backlog.
func TestCategoryEightyUniqueMerchantsStayBounded(t *testing.T) {
	stub := &stubCategory{}
	h := newCategoryHarness(t, stub)
	h.register("eighty@example.test")
	token, _ := h.login("eighty@example.test")
	h.grantCategoryConsent(token)
	var ids []string
	for i := 0; i < 80; i++ {
		ids = append(ids, h.queueTx(token, fmt.Sprintf("e%03d", i)+strings.Repeat("6", 28), fmt.Sprintf("MERCHANT %02d", i), fmt.Sprintf("QRIS MERCHANT %02d", i)))
	}
	for start := 0; start < len(ids); start += 50 {
		end := start + 50
		if end > len(ids) {
			end = len(ids)
		}
		if status, body := h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": ids[start:end]}); status != 200 {
			t.Fatalf("enqueue=%d %v", status, body)
		}
	}
	status, body := h.request("POST", "/v1/category-jobs/drain", token, map[string]any{})
	if status != 200 || body["state"] != "drained" {
		t.Fatalf("drain=%d %v", status, body)
	}
	if stub.calls != CategoryDrainMaxBatches {
		t.Fatalf("window batches=%d want %d", stub.calls, CategoryDrainMaxBatches)
	}
	if got := int(body["drained"].(float64)); got != CategoryDrainBatch*CategoryDrainMaxBatches {
		t.Fatalf("drained=%d want %d", got, CategoryDrainBatch*CategoryDrainMaxBatches)
	}
	// The 60 merchants that did not fit are still queued, and a poll inside the window is a no-op.
	if got := h.pendingQueue(); got != 60 {
		t.Fatalf("remainder=%d want 60", got)
	}
	if status, body := h.request("POST", "/v1/category-jobs/drain", token, map[string]any{}); status != 200 || body["state"] != "not_due" {
		t.Fatalf("early drain=%d %v", status, body)
	}
	base := h.api.Now()
	h.api.Now = func() time.Time { return base.Add(CategoryDrainInterval + time.Minute) }
	token, _ = h.login("eighty@example.test")
	if status, body := h.request("POST", "/v1/category-jobs/drain", token, map[string]any{}); status != 200 || body["state"] != "drained" {
		t.Fatalf("second window=%d %v", status, body)
	}
	if stub.calls != 2*CategoryDrainMaxBatches {
		t.Fatalf("total calls=%d want %d", stub.calls, 2*CategoryDrainMaxBatches)
	}
}

// TestCategoryDuplicateMerchantsShareOneResult proves the cache requirement: 80 transactions from
// eight merchants cost two provider calls, and every duplicate is categorized in the same window
// because the result is fanned out from cache, not charged again.
func TestCategoryDuplicateMerchantsShareOneResult(t *testing.T) {
	stub := &stubCategory{}
	h := newCategoryHarness(t, stub)
	h.register("dup@example.test")
	token, _ := h.login("dup@example.test")
	h.grantCategoryConsent(token)
	var ids []string
	for i := 0; i < 80; i++ {
		merchant := fmt.Sprintf("TOKO RUTIN %d", i%8)
		ids = append(ids, h.queueTx(token, fmt.Sprintf("d%03d", i)+strings.Repeat("7", 28), merchant, "QRIS "+merchant))
	}
	for start := 0; start < len(ids); start += 50 {
		end := start + 50
		if end > len(ids) {
			end = len(ids)
		}
		if status, body := h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": ids[start:end]}); status != 200 {
			t.Fatalf("enqueue=%d %v", status, body)
		}
	}
	status, body := h.request("POST", "/v1/category-jobs/drain", token, map[string]any{})
	if status != 200 || body["state"] != "drained" {
		t.Fatalf("drain=%d %v", status, body)
	}
	if got := int(body["drained"].(float64)); got != 80 {
		t.Fatalf("drained=%d want 80", got)
	}
	if stub.calls != 2 {
		t.Fatalf("eight unique merchants need two batch calls, got %d", stub.calls)
	}
	if got := h.pendingQueue(); got != 0 {
		t.Fatalf("duplicates left for a later window: pending=%d", got)
	}
}

// TestCategoryWorkerFairAcrossOwners keeps one busy owner from consuming another owner's window.
func TestCategoryWorkerFairAcrossOwners(t *testing.T) {
	stub := &stubCategory{}
	h := newCategoryHarness(t, stub)
	owners := []string{"fair-a@example.test", "fair-b@example.test"}
	counts := []int{30, 3}
	for i, email := range owners {
		h.register(email)
		token, _ := h.login(email)
		h.grantCategoryConsent(token)
		for j := 0; j < counts[i]; j++ {
			id := h.queueTx(token, fmt.Sprintf("f%d%d", i, j)+strings.Repeat("8", 28), fmt.Sprintf("OWNER%d MERCHANT%d", i, j), "QRIS OWNER")
			if status, body := h.request("POST", "/v1/category-jobs/enqueue", token, map[string]any{"transactionIds": []string{id}}); status != 200 {
				t.Fatalf("enqueue=%d %v", status, body)
			}
		}
	}
	NewCategoryWorker(h.api).tick()
	// The big owner runs four batches (20 of 30 merchants), the small owner one batch.
	if stub.calls != 5 {
		t.Fatalf("both owners should be served in one tick, calls=%d", stub.calls)
	}
	// Both owners keep their own backlog and due time: the busy one does not starve the other.
	var pending int
	if err := h.store.DB.QueryRowContext(context.Background(), `SELECT COUNT(*) FROM category_jobs WHERE state='PENDING'`).Scan(&pending); err != nil {
		t.Fatal(err)
	}
	if pending != 10 { // 30 - 20 for the big owner; the small owner is fully drained
		t.Fatalf("pending=%d want 10", pending)
	}
}
