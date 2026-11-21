package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"

	"github.com/pam/recap/backend-go/internal/auth"
	"github.com/pam/recap/backend-go/internal/config"
	"github.com/pam/recap/backend-go/internal/llm"
	"github.com/pam/recap/backend-go/internal/store"
)

const testPassword = "correct-horse-battery-staple"

type harness struct {
	t      *testing.T
	server *httptest.Server
	store  *store.Store
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	path := filepath.Join(t.TempDir(), "pam.db")
	cfg, err := config.Load(map[string]string{
		"APP_ENV": "test", "DATABASE_URL": "file:" + path,
		"REGISTRATION_INVITE_CODE": "pam-test",
		"JWT_SECRET":               "0123456789abcdef0123456789abcdef",
		"REFRESH_PEPPER":           "abcdef0123456789abcdef0123456789",
	})
	if err != nil {
		t.Fatal(err)
	}
	db, err := store.Open(context.Background(), cfg.Database)
	if err != nil {
		t.Fatal(err)
	}
	s := store.New(db, cfg.DataKey, cfg.KeyVersion)
	issuer := auth.NewIssuer(cfg.JWT.Secret, cfg.JWT.Issuer, cfg.JWT.Audience, cfg.JWT.AccessTTL)
	api := New(s, cfg, issuer, testLogger(), llm.Fake{Scenario: "valid"})
	server := httptest.NewServer(api.Handler())
	t.Cleanup(func() { server.Close(); s.Close() })
	return &harness{t: t, server: server, store: s}
}

func (h *harness) request(method, path, token string, body any) (int, map[string]any) {
	h.t.Helper()
	return h.requestWithHeaders(method, path, token, body, nil)
}

func (h *harness) requestWithHeaders(method, path, token string, body any,
	headers map[string]string) (int, map[string]any) {
	h.t.Helper()
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			h.t.Fatal(err)
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, h.server.URL+path, reader)
	if err != nil {
		h.t.Fatal(err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		h.t.Fatal(err)
	}
	defer response.Body.Close()
	raw, _ := io.ReadAll(response.Body)
	var decoded map[string]any
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &decoded)
	}
	return response.StatusCode, decoded
}

func (h *harness) register(email string) {
	h.t.Helper()
	status, body := h.request("POST", "/v1/accounts", "", map[string]any{
		"email": email, "password": testPassword, "displayName": "Test", "inviteCode": "pam-test",
	})
	if status != 201 {
		h.t.Fatalf("register %s: %d %#v", email, status, body)
	}
}

func (h *harness) login(email string) (string, string) {
	h.t.Helper()
	status, body := h.request("POST", "/v1/sessions", "", map[string]any{
		"email": email, "password": testPassword,
	})
	if status != 201 {
		h.t.Fatalf("login %s: %d %#v", email, status, body)
	}
	return body["accessToken"].(string), body["refreshToken"].(string)
}

func (h *harness) category(token, code string) string {
	h.t.Helper()
	status, body := h.request("GET", "/v1/categories", token, nil)
	if status != 200 {
		h.t.Fatalf("categories: %d %#v", status, body)
	}
	for _, item := range body["categories"].([]any) {
		category := item.(map[string]any)
		if category["code"] == code {
			return category["id"].(string)
		}
	}
	h.t.Fatalf("category %s not found", code)
	return ""
}

func TestRegisterLoginConsentTransactionSummaryIsolation(t *testing.T) {
	h := newHarness(t)
	h.register("alice@example.test")
	h.register("bob@example.test")
	alice, _ := h.login("alice@example.test")
	bob, _ := h.login("bob@example.test")

	if status, _ := h.request("POST", "/v1/consents", alice, map[string]any{
		"purpose": "GMAIL_READ", "decision": "GRANTED", "noticeVersion": "v0.4-review",
	}); status != 201 {
		t.Fatalf("consent status %d", status)
	}
	food := h.category(alice, "food")
	status, created := h.request("POST", "/v1/transactions", alice, map[string]any{
		"direction": "DEBIT", "amountMinor": 17_500_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
		"description": "Makan", "merchantLabel": "Indomaret",
		"attributes": map[string]any{"tags": []string{"makan"}},
	})
	if status != 201 {
		t.Fatalf("create tx: %d %#v", status, created)
	}
	id := created["id"].(string)

	status, summary := h.request("GET", "/v1/summaries/2026-11", alice, nil)
	if status != 200 {
		t.Fatalf("summary: %d %#v", status, summary)
	}
	if summary["grossOutflowMinor"].(float64) != 17_500_000 ||
		summary["classifiedExpenseMinor"].(float64) != 17_500_000 ||
		summary["inputRevision"].(float64) != 1 {
		t.Fatalf("unexpected summary: %#v", summary)
	}
	if summary["coverage"] != "PARTIAL" {
		t.Fatalf("coverage = %v", summary["coverage"])
	}

	// Cross-owner reads must be indistinguishable from missing rows.
	if status, _ := h.request("GET", "/v1/transactions/"+id, bob, nil); status != 404 {
		t.Fatalf("bob read alice tx: %d", status)
	}
	status, listed := h.request("GET", "/v1/transactions?month=2026-11", bob, nil)
	if status != 200 || len(listed["items"].([]any)) != 0 {
		t.Fatalf("bob list: %d %#v", status, listed)
	}

	// Update then delete: revision and totals must move.
	status, updated := h.request("PATCH", "/v1/transactions/"+id, alice, map[string]any{
		"note": "catatan", "revision": 0,
	})
	if status != 200 || updated["revision"].(float64) != 1 {
		t.Fatalf("update: %d %#v", status, updated)
	}
	if status, _ := h.request("DELETE", "/v1/transactions/"+id, alice, nil); status != 204 {
		t.Fatalf("delete status %d", status)
	}
	_, after := h.request("GET", "/v1/summaries/2026-11", alice, nil)
	if after["grossOutflowMinor"].(float64) != 0 || after["coverage"] != "EMPTY" {
		t.Fatalf("soft delete did not leave totals: %#v", after)
	}
}

func TestRefreshRotationReplayAndConcurrentUse(t *testing.T) {
	h := newHarness(t)
	h.register("refresh@example.test")
	_, refresh := h.login("refresh@example.test")

	status, rotated := h.request("POST", "/v1/sessions/refresh", "", map[string]any{"refreshToken": refresh})
	if status != 200 || rotated["refreshToken"] == "" {
		t.Fatalf("rotate: %d %#v", status, rotated)
	}
	status, replay := h.request("POST", "/v1/sessions/refresh", "", map[string]any{"refreshToken": refresh})
	if status != 401 || replay["code"] != "refresh_reused" {
		t.Fatalf("replay: %d %#v", status, replay)
	}
	status, _ = h.request("POST", "/v1/sessions/refresh", "", map[string]any{
		"refreshToken": rotated["refreshToken"],
	})
	if status != 401 {
		t.Fatalf("family was not revoked after replay: %d", status)
	}

	// Concurrent rotation: exactly one request may claim the token. The loser must not create a
	// second valid family or a silent success.
	_, concurrent := h.login("refresh@example.test")
	var wg sync.WaitGroup
	statuses := make(chan int, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, _ := h.request("POST", "/v1/sessions/refresh", "", map[string]any{"refreshToken": concurrent})
			statuses <- status
		}()
	}
	wg.Wait()
	close(statuses)
	successes := 0
	for status := range statuses {
		if status == 200 {
			successes++
		}
	}
	if successes > 1 {
		t.Fatalf("concurrent refresh produced %d successes", successes)
	}
}

func TestBudgetCRUDAndCategoryReassign(t *testing.T) {
	h := newHarness(t)
	h.register("budget@example.test")
	token, _ := h.login("budget@example.test")
	food := h.category(token, "food")
	other := h.category(token, "transport")

	status, plan := h.request("PUT", "/v1/budget-plans/2026-12", token, map[string]any{
		"expectedIncomeMinor": 500_000_000, "plannedSavingsMinor": 100_000_000,
		"limits":     []map[string]any{{"categoryId": food, "amountMinor": 90_000_000}},
		"attributes": map[string]any{"catatan": "desember"},
	})
	if status != 200 || plan["gapMinor"].(float64) != 310_000_000 {
		t.Fatalf("budget put: %d %#v", status, plan)
	}
	status, plan = h.request("PUT", "/v1/budget-plans/2026-12", token, map[string]any{
		"expectedIncomeMinor": 500_000_000, "plannedSavingsMinor": 100_000_000,
		"limits":   []map[string]any{{"categoryId": food, "amountMinor": 50_000_000}},
		"revision": 0,
	})
	if status != 200 || plan["gapMinor"].(float64) != 350_000_000 {
		t.Fatalf("budget update: %d %#v", status, plan)
	}

	// A used category needs an explicit reassignment.
	status, created := h.request("POST", "/v1/categories", token, map[string]any{
		"label": "Kopi", "kind": "EXPENSE", "attributes": map[string]any{"warna": "coklat"},
	})
	if status != 201 {
		t.Fatalf("category create: %d %#v", status, created)
	}
	categoryID := created["id"].(string)
	status, tx := h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 2_500_000, "currency": "IDR",
		"eventAt": "2026-12-02T03:00:00Z", "categoryId": categoryID,
	})
	if status != 201 {
		t.Fatalf("tx create: %d %#v", status, tx)
	}
	if status, _ := h.request("DELETE", "/v1/categories/"+categoryID, token, nil); status != 409 {
		t.Fatalf("used category delete: %d", status)
	}
	if status, _ := h.request("DELETE", "/v1/categories/"+categoryID+"?reassignTo="+other, token, nil); status != 204 {
		t.Fatalf("reassign delete: %d", status)
	}
	status, moved := h.request("GET", "/v1/transactions/"+tx["id"].(string), token, nil)
	if status != 200 || moved["categoryId"] != other {
		t.Fatalf("transaction not reassigned: %d %#v", status, moved)
	}
}

func TestAuthAndValidationFailures(t *testing.T) {
	h := newHarness(t)
	if status, _ := h.request("GET", "/v1/transactions?month=2026-11", "", nil); status != 401 {
		t.Fatalf("missing token: %d", status)
	}
	h.register("validation@example.test")
	token, _ := h.login("validation@example.test")
	if status, _ := h.request("GET", "/v1/transactions", token, nil); status != 400 {
		t.Fatalf("missing month: %d", status)
	}
	status, body := h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 0, "eventAt": "2026-11-19T04:10:00Z",
	})
	if status != 400 {
		t.Fatalf("zero amount: %d %#v", status, body)
	}
	status, body = h.request("POST", "/v1/accounts", "", map[string]any{
		"email": "bad", "password": testPassword, "inviteCode": "pam-test",
	})
	if status != 400 {
		t.Fatalf("bad email: %d %#v", status, body)
	}
	if status, _ := h.request("POST", "/v1/accounts", "", map[string]any{
		"email": "x@example.test", "password": testPassword, "inviteCode": "wrong",
	}); status != 403 {
		t.Fatalf("wrong invite: %d", status)
	}
}

func testLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func sampleAnalysisPayload(month string) map[string]any {
	return map[string]any{
		"schemaVersion": 1, "monthId": month, "coverage": "PARTIAL",
		"grossInflowMinor": 500_000_000, "grossOutflowMinor": 100_000_000,
		"netCashFlowMinor": 400_000_000, "classifiedIncomeMinor": 500_000_000,
		"classifiedExpenseMinor": 100_000_000, "transactionCount": 2,
		"uncategorizedCount": 0, "manualEntryCount": 0, "nonIdrExcludedCount": 0,
		"categoryTotals": []map[string]any{
			{"categoryCode": "housing", "amountMinor": 100_000_000, "transactionCount": 1},
		},
	}
}

func TestAnalysisConsentAllowlistCacheAndDeletion(t *testing.T) {
	h := newHarness(t)
	h.register("analysis@example.test")
	token, _ := h.login("analysis@example.test")
	payload := sampleAnalysisPayload("2026-11")

	// No AI consent yet.
	status, body := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": payload,
	}, map[string]string{"Idempotency-Key": "analysis-1"})
	if status != 403 || body["code"] != "consent_required" {
		t.Fatalf("consent gate: %d %#v", status, body)
	}
	if status, _ := h.request("POST", "/v1/consents", token, map[string]any{
		"purpose": "AI_PROCESSING", "decision": "GRANTED", "noticeVersion": "v0.4-review",
	}); status != 201 {
		t.Fatalf("AI consent status %d", status)
	}

	// A forbidden field is rejected by name, not silently ignored.
	leaky := sampleAnalysisPayload("2026-11")
	leaky["merchantLabel"] = "Indomaret"
	status, body = h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": leaky,
	}, map[string]string{"Idempotency-Key": "analysis-2"})
	if status != 400 {
		t.Fatalf("allowlist: %d %#v", status, body)
	}

	status, first := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": payload,
	}, map[string]string{"Idempotency-Key": "analysis-3"})
	if status != 200 || first["promptVersion"] != "analysis-v1" {
		t.Fatalf("analysis: %d %#v", status, first)
	}
	if len(first["observations"].([]any)) == 0 {
		t.Fatal("analysis has no observations")
	}

	// Same revision/provider/model is served from cache without a second provider call.
	status, cached := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": payload,
	}, map[string]string{"Idempotency-Key": "analysis-4"})
	if status != 200 || len(cached["observations"].([]any)) != len(first["observations"].([]any)) {
		t.Fatalf("cache: %d %#v", status, cached)
	}

	// A pending deletion blocks new analyses.
	if status, _ := h.request("POST", "/v1/deletion-requests", token, nil); status != 202 {
		t.Fatalf("deletion create status %d", status)
	}
	status, body = h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 2, "payload": payload,
	}, map[string]string{"Idempotency-Key": "analysis-5"})
	if status != 409 || body["code"] != "deletion_in_progress" {
		t.Fatalf("deletion gate: %d %#v", status, body)
	}
	// A transaction write is blocked too, so the sweep cannot race a new row.
	food := h.category(token, "food")
	status, body = h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "eventAt": "2026-11-19T04:10:00Z",
		"categoryId": food,
	})
	if status != 409 || body["code"] != "deletion_in_progress" {
		t.Fatalf("deletion write gate: %d %#v", status, body)
	}
}
