package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

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
	api    *Server
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	return newHarnessWithProvider(t, &llm.Fake{Scenario: "valid"})
}

func newHarnessWithProvider(t *testing.T, provider llm.Provider) *harness {
	return newHarnessWithConfig(t, provider, nil)
}

func newHarnessWithConfig(t *testing.T, provider llm.Provider, extraEnv map[string]string) *harness {
	t.Helper()
	path := filepath.Join(t.TempDir(), "pam.db")
	env := map[string]string{
		"APP_ENV": "test", "DATABASE_URL": "file:" + path,
		"REGISTRATION_INVITE_CODE": "pam-test",
		"JWT_SECRET":               "0123456789abcdef0123456789abcdef",
		"REFRESH_PEPPER":           "abcdef0123456789abcdef0123456789",
	}
	for key, value := range extraEnv {
		env[key] = value
	}
	cfg, err := config.Load(env)
	if err != nil {
		t.Fatal(err)
	}
	db, err := store.Open(context.Background(), cfg.Database)
	if err != nil {
		t.Fatal(err)
	}
	s := store.New(db, cfg.DataKey, cfg.KeyVersion)
	issuer := auth.NewIssuer(cfg.JWT.Secret, cfg.JWT.Issuer, cfg.JWT.Audience, cfg.JWT.AccessTTL)
	api := New(s, cfg, issuer, testLogger(), provider)
	server := httptest.NewServer(api.Handler())
	t.Cleanup(func() { server.Close(); s.Close() })
	return &harness{t: t, server: server, store: s, api: api}
}

func (h *harness) request(method, path, token string, body any) (int, map[string]any) {
	h.t.Helper()
	return h.requestWithHeaders(method, path, token, body, nil)
}

var idempotencyCounter atomic.Int64

func (h *harness) requestWithHeaders(method, path, token string, body any,
	headers map[string]string) (int, map[string]any) {
	h.t.Helper()
	if method == http.MethodPost && path == "/v1/transactions" {
		if headers == nil {
			headers = map[string]string{}
		}
		if headers["Idempotency-Key"] == "" {
			headers["Idempotency-Key"] = fmt.Sprintf("test-tx-%d", idempotencyCounter.Add(1))
		}
	}
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
	successes, failures := 0, 0
	for status := range statuses {
		switch status {
		case 200:
			successes++
		case 401:
			failures++
		}
	}
	if successes != 1 || failures != 1 {
		t.Fatalf("concurrent refresh must have exactly one winner and one loser: %d/%d", successes, failures)
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
	foodForAnalysis := h.category(token, "food")
	if status, body := h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 17_500_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": foodForAnalysis,
	}); status != 201 {
		t.Fatalf("seed transaction: %d %#v", status, body)
	}
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
	if status, _ := h.request("POST", "/v1/deletion-requests", token, map[string]any{
		"password": testPassword, "confirmation": "DELETE_WORKSPACE",
	}); status != 202 {
		t.Fatalf("deletion create status %d", status)
	}
	status, body = h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 2, "payload": payload,
	}, map[string]string{"Idempotency-Key": "analysis-5"})
	if status != 401 && (status != 409 || body["code"] != "deletion_in_progress") {
		t.Fatalf("deletion gate: %d %#v", status, body)
	}
	// A transaction write is blocked too, so the sweep cannot race a new row.
	status, body = h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "eventAt": "2026-11-19T04:10:00Z",
		"categoryId": foodForAnalysis,
	})
	if status != 401 && (status != 409 || body["code"] != "deletion_in_progress") {
		t.Fatalf("deletion write gate: %d %#v", status, body)
	}
}

func TestManualTransactionIdempotency(t *testing.T) {
	h := newHarness(t)
	h.register("idem@example.test")
	token, _ := h.login("idem@example.test")
	food := h.category(token, "food")
	body := map[string]any{
		"direction": "DEBIT", "amountMinor": 12_500_000, "currency": "IDR",
		"eventAt": "2026-11-10T04:00:00Z", "categoryId": food, "description": "Synthetic",
	}
	headers := map[string]string{"Idempotency-Key": "manual-retry-1"}

	status, first := h.requestWithHeaders("POST", "/v1/transactions", token, body, headers)
	if status != 201 {
		t.Fatalf("first create: %d %#v", status, first)
	}
	status, replay := h.requestWithHeaders("POST", "/v1/transactions", token, body, headers)
	if status != 200 || replay["id"] != first["id"] {
		t.Fatalf("retry must replay the same row: %d %#v", status, replay)
	}
	_, summary := h.request("GET", "/v1/summaries/2026-11", token, nil)
	if summary["transactionCount"].(float64) != 1 || summary["inputRevision"].(float64) != 1 {
		t.Fatalf("retry changed totals/revision: %#v", summary)
	}

	conflicting := map[string]any{
		"direction": "DEBIT", "amountMinor": 99_000, "currency": "IDR",
		"eventAt": "2026-11-10T04:00:00Z", "categoryId": food,
	}
	status, conflict := h.requestWithHeaders("POST", "/v1/transactions", token, conflicting, headers)
	if status != 409 || conflict["code"] != "idempotency_conflict" {
		t.Fatalf("same key/different body: %d %#v", status, conflict)
	}

	// Owner B may reuse the same key without seeing owner A's result.
	h.register("idem-b@example.test")
	bob, _ := h.login("idem-b@example.test")
	bobFood := h.category(bob, "food")
	bobBody := map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-10T04:00:00Z", "categoryId": bobFood,
	}
	status, bobRow := h.requestWithHeaders("POST", "/v1/transactions", bob, bobBody, headers)
	if status != 201 || bobRow["id"] == first["id"] {
		t.Fatalf("cross-owner key isolation: %d %#v", status, bobRow)
	}

	// Two parallel writers with the same key must produce one row and one winner.
	h.register("idem-race@example.test")
	raceToken, _ := h.login("idem-race@example.test")
	raceFood := h.category(raceToken, "food")
	raceBody := map[string]any{
		"direction": "DEBIT", "amountMinor": 2_500, "currency": "IDR",
		"eventAt": "2026-11-11T04:00:00Z", "categoryId": raceFood,
	}
	raceHeaders := map[string]string{"Idempotency-Key": "parallel-manual"}
	statuses := make(chan int, 2)
	ids := make(chan string, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, row := h.requestWithHeaders("POST", "/v1/transactions", raceToken, raceBody, raceHeaders)
			statuses <- status
			if id, ok := row["id"].(string); ok {
				ids <- id
			}
		}()
	}
	wg.Wait()
	close(statuses)
	close(ids)
	created, replayed := 0, 0
	for status := range statuses {
		if status == 201 {
			created++
		}
		if status == 200 {
			replayed++
		}
	}
	if created != 1 || replayed != 1 {
		t.Fatalf("parallel idempotency statuses: created=%d replayed=%d", created, replayed)
	}
	firstID := ""
	for id := range ids {
		if firstID == "" {
			firstID = id
		} else if id != firstID {
			t.Fatalf("parallel writers returned different ids: %s %s", firstID, id)
		}
	}
	_, raceSummary := h.request("GET", "/v1/summaries/2026-11", raceToken, nil)
	if raceSummary["transactionCount"].(float64) != 1 {
		t.Fatalf("parallel writers inserted more than one row: %#v", raceSummary)
	}

	// A replayed key after soft delete returns the tombstone, not a resurrected row.
	status, _ = h.request("DELETE", "/v1/transactions/"+first["id"].(string), token, nil)
	if status != 204 {
		t.Fatalf("delete: %d", status)
	}
	status, deletedReplay := h.requestWithHeaders("POST", "/v1/transactions", token, body, headers)
	if status != 200 || deletedReplay["deletedAt"] == nil || deletedReplay["id"] != first["id"] {
		t.Fatalf("replay after delete: %d %#v", status, deletedReplay)
	}
	_, after := h.request("GET", "/v1/summaries/2026-11", token, nil)
	if after["transactionCount"].(float64) != 0 {
		t.Fatalf("replay resurrected a deleted row: %#v", after)
	}
}

type blockingProvider struct {
	entered chan struct{}
	release chan struct{}
	once    sync.Once
}

func (p *blockingProvider) ID() string    { return "blocking" }
func (p *blockingProvider) Model() string { return "blocking-v1" }
func (p *blockingProvider) Analyze(ctx context.Context, payload map[string]any) (store.AnalysisResponse, error) {
	p.once.Do(func() { close(p.entered) })
	select {
	case <-p.release:
	case <-ctx.Done():
		return store.AnalysisResponse{}, ctx.Err()
	}
	return store.AnalysisResponse{
		PromptVersion: "analysis-v1",
		Observations:  []store.Observation{{Text: "Agregat tersedia.", MetricKeys: []string{"summary.grossOutflow"}}},
		Limitations:   []string{"Data sintetis."},
	}, nil
}

func TestAnalysisRaceWithConsentAndDeletion(t *testing.T) {
	for _, action := range []string{"withdraw", "delete", "mutate", "prior", "budget", "category", "coverage"} {
		t.Run(action, func(t *testing.T) {
			provider := &blockingProvider{entered: make(chan struct{}), release: make(chan struct{})}
			h := newHarnessWithProvider(t, provider)
			h.register("race-analysis@example.test")
			token, _ := h.login("race-analysis@example.test")
			food := h.category(token, "food")
			h.request("POST", "/v1/transactions", token, map[string]any{
				"direction": "DEBIT", "amountMinor": 17_500_000, "currency": "IDR",
				"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
			})
			for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
				h.request("POST", "/v1/consents", token, map[string]any{
					"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
				})
			}
			payload := sampleAnalysisPayload("2026-11")
			result := make(chan int, 1)
			go func() {
				status, _ := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
					"monthId": "2026-11", "inputRevision": 1, "payload": payload,
				}, map[string]string{"Idempotency-Key": "race-" + action})
				result <- status
			}()
			select {
			case <-provider.entered:
			case <-time.After(5 * time.Second):
				t.Fatal("provider was not entered")
			}
			switch action {
			case "withdraw":
				status, _ := h.request("POST", "/v1/consents", token, map[string]any{
					"purpose": "AI_PROCESSING", "decision": "WITHDRAWN", "noticeVersion": "v0.4-review",
				})
				if status != 201 {
					t.Fatalf("withdraw: %d", status)
				}
			case "delete":
				if status, _ := h.request("POST", "/v1/deletion-requests", token, map[string]any{
					"password": testPassword, "confirmation": "DELETE_WORKSPACE",
				}); status != 202 {
					t.Fatalf("deletion: %d", status)
				}
			case "mutate":
				if status, _ := h.request("POST", "/v1/transactions", token, map[string]any{
					"direction": "DEBIT", "amountMinor": 2_500, "currency": "IDR",
					"eventAt": "2026-11-20T04:10:00Z", "categoryId": food,
				}); status != 201 {
					t.Fatalf("mutation: %d", status)
				}
			case "prior":
				if status, _ := h.request("POST", "/v1/transactions", token, map[string]any{
					"direction": "DEBIT", "amountMinor": 3_500, "currency": "IDR",
					"eventAt": "2026-10-20T04:10:00Z", "categoryId": food,
				}); status != 201 {
					t.Fatalf("prior mutation: %d", status)
				}
			case "budget":
				if status, _ := h.request("PUT", "/v1/budget-plans/2026-11", token, map[string]any{
					"expectedIncomeMinor": 1_000_000, "plannedSavingsMinor": 100_000,
					"limits": []any{}, "revision": 0,
				}); status != 200 {
					t.Fatalf("budget mutation: %d", status)
				}
			case "category":
				if status, _ := h.request("PATCH", "/v1/categories/"+food, token, map[string]any{
					"label": "Makanan baru", "revision": 0,
				}); status != 200 {
					t.Fatalf("category mutation: %d", status)
				}
			case "coverage":
				if status, _ := h.request("POST", "/v1/import-jobs", token, map[string]any{
					"monthId": "2026-11", "templateVersion": "livin-v1",
				}); status != 201 {
					t.Fatalf("coverage mutation: %d", status)
				}
			}
			close(provider.release)
			select {
			case status := <-result:
				if action == "delete" {
					if status != 401 && status != 409 {
						t.Fatalf("in-flight analysis after deletion = %d, want 401/409", status)
					}
				} else if status != 409 {
					t.Fatalf("in-flight analysis after %s = %d, want 409", action, status)
				}
			case <-time.After(5 * time.Second):
				t.Fatal("analysis did not finish")
			}
			var count int
			if err := h.store.DB.QueryRow(`SELECT COUNT(*) FROM analyses WHERE idempotency_key = ?`,
				"race-"+action).Scan(&count); err != nil {
				t.Fatal(err)
			}
			if count != 0 {
				t.Fatalf("analysis was persisted after %s", action)
			}
			// The provider call must still be billed, with unknown usage recorded conservatively.
			var calls, unknown int
			var cost float64
			if err := h.store.DB.QueryRow(`SELECT provider_calls, usage_unknown, cost_usd
				FROM usage_counters WHERE scope = '*' ORDER BY bucket DESC LIMIT 1`).Scan(
				&calls, &unknown, &cost); err != nil {
				t.Fatal(err)
			}
			if calls < 1 || unknown < 1 || cost <= 0 {
				t.Fatalf("usage not recorded: calls=%d unknown=%d cost=%f", calls, unknown, cost)
			}
		})
	}
}

type captureProvider struct {
	mu      sync.Mutex
	payload map[string]any
	calls   int
}

func (p *captureProvider) ID() string    { return "capture" }
func (p *captureProvider) Model() string { return "capture-v1" }
func (p *captureProvider) Analyze(_ context.Context, payload map[string]any) (store.AnalysisResponse, error) {
	p.mu.Lock()
	p.calls++
	p.payload = payload
	p.mu.Unlock()
	return store.AnalysisResponse{PromptVersion: "analysis-v1",
		Observations: []store.Observation{{Text: "Agregat.", MetricKeys: []string{"summary.grossOutflow"}}},
		Limitations:  []string{"Data sintetis."}}, nil
}

func TestAnalysisUsesCanonicalServerPayloadAndStaleRevisionFails(t *testing.T) {
	provider := &captureProvider{}
	h := newHarnessWithProvider(t, provider)
	h.register("canonical@example.test")
	token, _ := h.login("canonical@example.test")
	food := h.category(token, "food")
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 17_500_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	})
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	clientPayload := sampleAnalysisPayload("2026-11")
	clientPayload["grossOutflowMinor"] = 1 // deliberately false; server must ignore it
	status, body := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": clientPayload,
	}, map[string]string{"Idempotency-Key": "canonical-1"})
	if status != 200 {
		t.Fatalf("canonical analysis: %d %#v", status, body)
	}
	provider.mu.Lock()
	seenOutflow, _ := provider.payload["grossOutflowMinor"].(float64)
	provider.mu.Unlock()
	if seenOutflow != 17_500_000 {
		t.Fatalf("provider saw client value instead of server aggregate: %v", seenOutflow)
	}

	callsBefore := provider.calls
	for _, revision := range []int{101, 102, 103, 104, 105, 106} {
		status, body = h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
			"monthId": "2026-11", "inputRevision": revision, "payload": sampleAnalysisPayload("2026-11"),
		}, map[string]string{"Idempotency-Key": fmt.Sprintf("canonical-stale-%d", revision)})
		if status != 409 || body["code"] != "revision_conflict" {
			t.Fatalf("stale revision %d: %d %#v", revision, status, body)
		}
	}
	if provider.calls != callsBefore {
		t.Fatal("provider was called for a stale revision")
	}
}

type usageProvider struct {
	response store.AnalysisResponse
	mu       sync.Mutex
	calls    int
}

func (p *usageProvider) ID() string    { return "usage" }
func (p *usageProvider) Model() string { return "usage-v1" }
func (p *usageProvider) Analyze(context.Context, map[string]any) (store.AnalysisResponse, error) {
	p.mu.Lock()
	p.calls++
	p.mu.Unlock()
	return p.response, nil
}

func TestProviderUsageIsRecordedForEveryCall(t *testing.T) {
	prompt, completion := int64(10), int64(5)
	provider := &usageProvider{response: store.AnalysisResponse{
		PromptVersion: "analysis-v1",
		Observations:  []store.Observation{{Text: "Agregat.", MetricKeys: []string{"summary.grossOutflow"}}},
		Limitations:   []string{"Data sintetis."}, PromptTokens: &prompt, CompletionTokens: &completion,
	}}
	h := newHarnessWithProvider(t, provider)
	h.register("usage@example.test")
	token, _ := h.login("usage@example.test")
	food := h.category(token, "food")
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 17_500_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	})
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	status, body := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "usage-1"})
	if status != 200 {
		t.Fatalf("analysis: %d %#v", status, body)
	}
	var calls, unknown int
	var promptTokens, completionTokens int64
	var cost float64
	if err := h.store.DB.QueryRow(`SELECT provider_calls, usage_unknown, tokens_prompt,
		tokens_completion, cost_usd FROM usage_counters WHERE scope = '*' ORDER BY bucket DESC LIMIT 1`).Scan(
		&calls, &unknown, &promptTokens, &completionTokens, &cost); err != nil {
		t.Fatal(err)
	}
	if calls != 1 || unknown != 0 || promptTokens != 10 || completionTokens != 5 || cost <= 0 {
		t.Fatalf("usage not recorded: calls=%d unknown=%d tokens=%d/%d cost=%f",
			calls, unknown, promptTokens, completionTokens, cost)
	}
}

func TestImportBatchIdempotencyAndSummary(t *testing.T) {
	h := newHarness(t)
	h.register("import@example.test")
	token, _ := h.login("import@example.test")
	food := h.category(token, "food")
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	status, job := h.request("POST", "/v1/import-jobs", token, map[string]any{
		"monthId": "2026-11", "templateVersion": "livin-v1",
	})
	if status != 201 {
		t.Fatalf("create job: %d %#v", status, job)
	}
	jobID := job["id"].(string)
	candidate := map[string]any{
		"fingerprint": "a" + strings.Repeat("0", 31), "status": "PARSED", "direction": "DEBIT",
		"amountMinor": 17_500_000, "eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
		"parserVersion": "livin-v1", "confidence": 0.9, "description": "Impor",
	}
	batch := map[string]any{"batchKey": "page-1", "pageOrdinal": 0, "candidates": []any{
		candidate,
		candidate, // same fingerprint in one batch = duplicate, not a second row
		map[string]any{"fingerprint": strings.Repeat("b", 32), "status": "UNRESOLVED",
			"reason": "FAILED_PAYMENT"},
	}}
	status, counts := h.request("POST", "/v1/import-jobs/"+jobID+"/batches", token, batch)
	if status != 200 || counts["imported"].(float64) != 1 || counts["duplicates"].(float64) != 1 ||
		counts["skipped"].(float64) != 1 {
		t.Fatalf("batch counts: %d %#v", status, counts)
	}
	status, replay := h.request("POST", "/v1/import-jobs/"+jobID+"/batches", token, batch)
	if status != 200 || replay["imported"].(float64) != 1 {
		t.Fatalf("batch replay: %d %#v", status, replay)
	}
	_, summary := h.request("GET", "/v1/summaries/2026-11", token, nil)
	if summary["transactionCount"].(float64) != 1 || summary["inputRevision"].(float64) != 1 {
		t.Fatalf("import totals: %#v", summary)
	}
	// Same batch key with a different body must conflict.
	different := map[string]any{"batchKey": "page-1", "pageOrdinal": 0, "candidates": []any{candidate}}
	status, conflict := h.request("POST", "/v1/import-jobs/"+jobID+"/batches", token, different)
	if status != 409 || conflict["code"] != "idempotency_conflict" {
		t.Fatalf("batch conflict: %d %#v", status, conflict)
	}
	status, finalized := h.request("PATCH", "/v1/import-jobs/"+jobID, token, map[string]any{"state": "COMPLETE"})
	if status != 200 || finalized["state"] != "COMPLETE" {
		t.Fatalf("finalize: %d %#v", status, finalized)
	}
	// A lost batch response can be retried even after finalization.
	status, replay = h.request("POST", "/v1/import-jobs/"+jobID+"/batches", token, batch)
	if status != 200 || replay["imported"].(float64) != 1 {
		t.Fatalf("batch replay after finalize: %d %#v", status, replay)
	}
	_, summary = h.request("GET", "/v1/summaries/2026-11", token, nil)
	if summary["coverage"] != "COMPLETE" {
		t.Fatalf("coverage after finalize: %#v", summary)
	}
}

func TestImportRejectsInvalidCandidateAtomicallyAndWithoutConsent(t *testing.T) {
	h := newHarness(t)
	h.register("import-atomic@example.test")
	token, _ := h.login("import-atomic@example.test")
	status, _ := h.request("POST", "/v1/import-jobs", token, map[string]any{
		"monthId": "2026-11", "templateVersion": "livin-v1",
	})
	if status != 403 {
		t.Fatalf("import without Gmail consent: %d", status)
	}
	if status, _ := h.request("POST", "/v1/consents", token, map[string]any{
		"purpose": "GMAIL_READ", "decision": "GRANTED", "noticeVersion": "v0.4-review",
	}); status != 201 {
		t.Fatalf("gmail consent: %d", status)
	}
	_, job := h.request("POST", "/v1/import-jobs", token, map[string]any{
		"monthId": "2026-11", "templateVersion": "livin-v1",
	})
	jobID := job["id"].(string)
	batch := map[string]any{"batchKey": "page-1", "pageOrdinal": 0, "candidates": []any{
		map[string]any{"fingerprint": strings.Repeat("c", 32), "status": "PARSED",
			"direction": "DEBIT", "amountMinor": 1_000, "eventAt": "2026-11-19T04:10:00Z",
			"parserVersion": "livin-v1"},
		map[string]any{"fingerprint": "bad", "status": "PARSED", "direction": "DEBIT",
			"amountMinor": 1_000, "eventAt": "2026-11-19T04:10:00Z", "parserVersion": "livin-v1"},
	}}
	status, body := h.request("POST", "/v1/import-jobs/"+jobID+"/batches", token, batch)
	if status != 400 {
		t.Fatalf("invalid batch: %d %#v", status, body)
	}
	_, summary := h.request("GET", "/v1/summaries/2026-11", token, nil)
	if summary["transactionCount"].(float64) != 0 {
		t.Fatalf("invalid batch partially imported: %#v", summary)
	}
}

func TestDeletionSweepRemovesFinanceAndRevokesSessions(t *testing.T) {
	h := newHarness(t)
	h.register("sweep@example.test")
	token, refresh := h.login("sweep@example.test")
	food := h.category(token, "food")
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	})
	status, deletion := h.request("POST", "/v1/deletion-requests", token, map[string]any{
		"password": testPassword, "confirmation": "DELETE_WORKSPACE",
	})
	if status != 202 {
		t.Fatalf("deletion request: %d %#v", status, deletion)
	}
	receipt, _ := deletion["receiptToken"].(string)
	if receipt == "" {
		t.Fatal("deletion response has no receipt token")
	}
	if status, _ := h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 2_000, "currency": "IDR",
		"eventAt": "2026-11-20T04:10:00Z", "categoryId": food,
	}); status != 401 {
		t.Fatalf("write during deletion (session must be revoked): %d", status)
	}
	completed, err := h.store.SweepDeletions(context.Background(), time.Now())
	if err != nil || completed != 1 {
		t.Fatalf("sweep: completed=%d err=%v", completed, err)
	}
	var transactions, categories, analyses int
	_ = h.store.DB.QueryRow(`SELECT COUNT(*) FROM transactions`).Scan(&transactions)
	_ = h.store.DB.QueryRow(`SELECT COUNT(*) FROM categories`).Scan(&categories)
	_ = h.store.DB.QueryRow(`SELECT COUNT(*) FROM analyses`).Scan(&analyses)
	if transactions != 0 || categories != 0 || analyses != 0 {
		t.Fatalf("finance rows remain: tx=%d cat=%d analyses=%d", transactions, categories, analyses)
	}
	var accountStatus string
	_ = h.store.DB.QueryRow(`SELECT status FROM users WHERE email = ?`, "sweep@example.test").Scan(&accountStatus)
	if accountStatus != "DELETED" {
		t.Fatalf("account status = %s", accountStatus)
	}
	if code, _ := h.request("POST", "/v1/sessions/refresh", "", map[string]any{"refreshToken": refresh}); code != 401 {
		t.Fatalf("refresh after sweep: %d", code)
	}
	if code, _ := h.request("GET", "/v1/transactions?month=2026-11", token, nil); code != 401 {
		t.Fatalf("access after sweep: %d", code)
	}
	if code, _ := h.request("POST", "/v1/sessions", "", map[string]any{
		"email": "sweep@example.test", "password": testPassword,
	}); code != 403 {
		t.Fatalf("login after deletion: %d", code)
	}
	statusCode, receiptState := h.request("GET", "/v1/deletion-receipts/"+receipt, "", nil)
	if statusCode != 200 || receiptState["state"] != "COMPLETED" || receiptState["completedAt"] == nil {
		t.Fatalf("deletion receipt after sweep: %d %#v", statusCode, receiptState)
	}
	if code, _ := h.request("GET", "/v1/deletion-receipts/not-a-receipt", "", nil); code != 404 {
		t.Fatalf("invalid receipt: %d", code)
	}
}

func TestDailyCostCeilingStopsMoreProviderCalls(t *testing.T) {
	provider := &usageProvider{response: store.AnalysisResponse{
		PromptVersion: "analysis-v1",
		Observations:  []store.Observation{{Text: "Agregat.", MetricKeys: []string{"summary.grossOutflow"}}},
		Limitations:   []string{"Data sintetis."},
	}}
	h := newHarnessWithConfig(t, provider, map[string]string{
		"DAILY_AI_CEILING_USD": "0.002", "UNKNOWN_AI_CALL_COST_USD": "0.001",
	})
	h.register("ceiling@example.test")
	token, _ := h.login("ceiling@example.test")
	food := h.category(token, "food")
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	})
	status, body := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "ceiling-1"})
	if status != 200 {
		t.Fatalf("first analysis: %d %#v", status, body)
	}
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 2_000, "currency": "IDR",
		"eventAt": "2026-11-20T04:10:00Z", "categoryId": food,
	})
	status, body = h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 2, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "ceiling-2"})
	if status != 429 || body["code"] != "daily_ceiling_reached" {
		t.Fatalf("daily ceiling: %d %#v", status, body)
	}
}

func TestReservationRejectsCeilingBelowOneCallBeforeProvider(t *testing.T) {
	provider := &usageProvider{response: store.AnalysisResponse{
		PromptVersion: "analysis-v1",
		Observations:  []store.Observation{{Text: "Agregat.", MetricKeys: []string{"summary.grossOutflow"}}},
		Limitations:   []string{"Data sintetis."},
	}}
	h := newHarnessWithConfig(t, provider, map[string]string{
		"DAILY_AI_CEILING_USD": "0.0005", "UNKNOWN_AI_CALL_COST_USD": "0.001",
	})
	h.register("ceiling-low@example.test")
	token, _ := h.login("ceiling-low@example.test")
	food := h.category(token, "food")
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	})
	status, body := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "ceiling-low-1"})
	if status != 429 || body["code"] != "daily_ceiling_reached" {
		t.Fatalf("low ceiling: %d %#v", status, body)
	}
	if provider.calls != 0 {
		t.Fatalf("provider was called despite insufficient budget: %d", provider.calls)
	}
	var cost float64
	_ = h.store.DB.QueryRow(`SELECT COALESCE(SUM(cost_usd),0) FROM usage_counters WHERE scope='*'`).Scan(&cost)
	if cost != 0 {
		t.Fatalf("reservation was written despite rejection: %f", cost)
	}
}

func TestIdempotencyFailureDoesNotReserveKey(t *testing.T) {
	h := newHarness(t)
	h.register("idem-fail@example.test")
	token, _ := h.login("idem-fail@example.test")
	headers := map[string]string{"Idempotency-Key": "failed-create"}
	status, body := h.requestWithHeaders("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": "cat_missing",
	}, headers)
	if status != 404 {
		t.Fatalf("invalid create: %d %#v", status, body)
	}
	food := h.category(token, "food")
	status, created := h.requestWithHeaders("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	}, headers)
	if status != 201 {
		t.Fatalf("retry after failed transaction was blocked: %d %#v", status, created)
	}
	var count int
	_ = h.store.DB.QueryRow(`SELECT COUNT(*) FROM http_idempotency WHERE idem_key = 'failed-create'`).Scan(&count)
	if count != 1 {
		t.Fatalf("idempotency rows = %d", count)
	}
}

func TestAnalysisLifecycleWithConsentWithdrawal(t *testing.T) {
	h := newHarness(t)
	h.register("lifecycle@example.test")
	token, _ := h.login("lifecycle@example.test")
	food := h.category(token, "food")
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	})
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	status, _ := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "life-1"})
	if status != 200 {
		t.Fatalf("initial analysis: %d", status)
	}
	if status, _ := h.request("POST", "/v1/consents", token, map[string]any{
		"purpose": "AI_PROCESSING", "decision": "WITHDRAWN", "noticeVersion": "v0.4-review",
	}); status != 201 {
		t.Fatalf("withdraw: %d", status)
	}
	status, stored := h.request("GET", "/v1/analyses/2026-11", token, nil)
	if status != 200 || stored["status"] != "HISTORICAL" {
		t.Fatalf("stored analysis after withdrawal: %d %#v", status, stored)
	}
	status, _ = h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "life-2"})
	if status != 403 {
		t.Fatalf("new analysis after withdrawal: %d", status)
	}
	status, _ = h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "life-1"})
	if status != 403 {
		t.Fatalf("replay after withdrawal: %d", status)
	}
	if status, _ := h.request("POST", "/v1/consents", token, map[string]any{
		"purpose": "AI_PROCESSING", "decision": "GRANTED", "noticeVersion": "v0.4-review",
	}); status != 201 {
		t.Fatalf("regrant: %d", status)
	}
	status, _ = h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "life-3"})
	if status != 200 {
		t.Fatalf("analysis after regrant: %d", status)
	}
}

func TestInvalidAnalysisIsBilledButNotStored(t *testing.T) {
	provider := &usageProvider{response: store.AnalysisResponse{
		PromptVersion: "analysis-v1", Observations: []store.Observation{}, Limitations: []string{},
	}}
	h := newHarnessWithProvider(t, provider)
	h.register("invalid-output@example.test")
	token, _ := h.login("invalid-output@example.test")
	food := h.category(token, "food")
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	})
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	status, _ := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "invalid-1"})
	if status != 422 {
		t.Fatalf("invalid output status: %d", status)
	}
	var calls, unknown int
	_ = h.store.DB.QueryRow(`SELECT provider_calls, usage_unknown FROM usage_counters
		WHERE scope = '*' ORDER BY bucket DESC LIMIT 1`).Scan(&calls, &unknown)
	if calls < 2 || unknown < 2 {
		t.Fatalf("billable invalid/regenerated calls not recorded: calls=%d unknown=%d", calls, unknown)
	}
	var analyses int
	_ = h.store.DB.QueryRow(`SELECT COUNT(*) FROM analyses`).Scan(&analyses)
	if analyses != 0 {
		t.Fatalf("invalid analysis was stored: %d", analyses)
	}
}

func TestLastAdminCannotBeSwept(t *testing.T) {
	h := newHarness(t)
	h.register("last-admin@example.test")
	token, _ := h.login("last-admin@example.test")
	if _, err := h.store.DB.Exec(`UPDATE users SET role = 'ADMIN' WHERE email = ?`, "last-admin@example.test"); err != nil {
		t.Fatal(err)
	}
	// Role changed in the database: the old JWT is stale by design. Issue an ADMIN token.
	token, _ = h.login("last-admin@example.test")
	status, deletion := h.request("POST", "/v1/deletion-requests", token, map[string]any{
		"password": testPassword, "confirmation": "DELETE_WORKSPACE",
	})
	if status != 409 || deletion["code"] != "last_admin" {
		t.Fatalf("last admin deletion request: %d %#v", status, deletion)
	}
	var accountStatus string
	_ = h.store.DB.QueryRow(`SELECT status FROM users WHERE email = ?`, "last-admin@example.test").Scan(&accountStatus)
	if accountStatus != "ACTIVE" {
		t.Fatalf("admin status changed to %s", accountStatus)
	}
}

func TestAnalysisRegeneratesAtMostOnce(t *testing.T) {
	provider := &llm.Fake{Scenario: "invalid_then_valid"}
	h := newHarnessWithProvider(t, provider)
	h.register("regen@example.test")
	token, _ := h.login("regen@example.test")
	food := h.category(token, "food")
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	})
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	status, body := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "regen-1"})
	if status != 200 {
		t.Fatalf("regenerated analysis: %d %#v", status, body)
	}
	if provider.Calls() != 2 {
		t.Fatalf("provider calls = %d, want 2", provider.Calls())
	}
	var calls int
	_ = h.store.DB.QueryRow(`SELECT provider_calls FROM usage_counters
		WHERE scope = '*' ORDER BY bucket DESC LIMIT 1`).Scan(&calls)
	if calls != 2 {
		t.Fatalf("billed provider calls = %d", calls)
	}
}

func TestCustomCategoryRollsUpToSafeProjectionCode(t *testing.T) {
	provider := &captureProvider{}
	h := newHarnessWithProvider(t, provider)
	h.register("rollup@example.test")
	token, _ := h.login("rollup@example.test")
	status, custom := h.request("POST", "/v1/categories", token, map[string]any{
		"label": "PRIVATE_CATEGORY_LABEL", "kind": "EXPENSE",
	})
	if status != 201 {
		t.Fatalf("category: %d %#v", status, custom)
	}
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 3_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": custom["id"],
	})
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	status, body := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "rollup-1"})
	if status != 200 {
		t.Fatalf("analysis: %d %#v", status, body)
	}
	provider.mu.Lock()
	raw, _ := json.Marshal(provider.payload)
	provider.mu.Unlock()
	if strings.Contains(string(raw), "PRIVATE_CATEGORY_LABEL") || strings.Contains(string(raw), "cat_") {
		t.Fatalf("custom category identifier leaked to provider: %s", raw)
	}
	if !strings.Contains(string(raw), "other_expense") {
		t.Fatalf("custom expense was not rolled into other_expense: %s", raw)
	}
}

func TestImportRejectsEventOutsideJobMonthAtomically(t *testing.T) {
	h := newHarness(t)
	h.register("month-boundary@example.test")
	token, _ := h.login("month-boundary@example.test")
	food := h.category(token, "food")
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	_, job := h.request("POST", "/v1/import-jobs", token, map[string]any{
		"monthId": "2026-11", "templateVersion": "livin-v1",
	})
	jobID := job["id"].(string)
	makeCandidate := func(fingerprint, eventAt string) map[string]any {
		return map[string]any{"fingerprint": fingerprint, "status": "PARSED", "direction": "DEBIT",
			"amountMinor": 10_000, "eventAt": eventAt, "categoryId": food,
			"parserVersion": "livin-v1", "confidence": 0.9}
	}
	// Jakarta November starts at 2026-10-31T17:00:00Z.
	validEdge := makeCandidate(strings.Repeat("a", 32), "2026-10-31T17:00:00Z")
	invalidEdge := makeCandidate(strings.Repeat("b", 32), "2026-10-31T16:59:59Z")
	status, body := h.request("POST", "/v1/import-jobs/"+jobID+"/batches", token, map[string]any{
		"batchKey": "mixed", "pageOrdinal": 0, "candidates": []any{validEdge, invalidEdge},
	})
	if status != 400 {
		t.Fatalf("mixed month batch: %d %#v", status, body)
	}
	var txCount, batchCount int
	var revision int
	_ = h.store.DB.QueryRow(`SELECT COUNT(*) FROM transactions`).Scan(&txCount)
	_ = h.store.DB.QueryRow(`SELECT COUNT(*) FROM import_batches WHERE job_id = ?`, jobID).Scan(&batchCount)
	_ = h.store.DB.QueryRow(`SELECT COALESCE(MAX(revision),0) FROM month_revisions`).Scan(&revision)
	if txCount != 0 || batchCount != 0 || revision != 0 {
		t.Fatalf("rejected batch left state: tx=%d batch=%d rev=%d", txCount, batchCount, revision)
	}
	// The same batch key can be retried after correcting the body.
	status, counts := h.request("POST", "/v1/import-jobs/"+jobID+"/batches", token, map[string]any{
		"batchKey": "mixed", "pageOrdinal": 0, "candidates": []any{validEdge},
	})
	if status != 200 || counts["imported"].(float64) != 1 || counts["inputRevision"].(float64) != 1 {
		t.Fatalf("retry after rejection: %d %#v", status, counts)
	}
	// The exact October boundary is rejected for a November job.
	status, _ = h.request("POST", "/v1/import-jobs/"+jobID+"/batches", token, map[string]any{
		"batchKey": "outside", "pageOrdinal": 1, "candidates": []any{
			makeCandidate(strings.Repeat("c", 32), "2026-10-31T16:59:59Z")},
	})
	if status != 400 {
		t.Fatalf("October boundary accepted by November job: %d", status)
	}
}

func TestImportDuplicateFingerprintAcrossMonthsAndFinalizeRetry(t *testing.T) {
	h := newHarness(t)
	h.register("cross-month@example.test")
	token, _ := h.login("cross-month@example.test")
	food := h.category(token, "food")
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	fingerprint := strings.Repeat("d", 32)
	candidate := func(month, eventAt string) (string, map[string]any) {
		_, job := h.request("POST", "/v1/import-jobs", token, map[string]any{
			"monthId": month, "templateVersion": "livin-v1"})
		jobID := job["id"].(string)
		status, counts := h.request("POST", "/v1/import-jobs/"+jobID+"/batches", token, map[string]any{
			"batchKey": "page-1", "pageOrdinal": 0, "candidates": []any{map[string]any{
				"fingerprint": fingerprint, "status": "PARSED", "direction": "DEBIT",
				"amountMinor": 10_000, "eventAt": eventAt, "categoryId": food,
				"parserVersion": "livin-v1"}}})
		if status != 200 {
			t.Fatalf("%s batch: %d %#v", month, status, counts)
		}
		return jobID, counts
	}
	octJob, octCounts := candidate("2026-10", "2026-10-20T00:00:00Z")
	if octCounts["imported"].(float64) != 1 {
		t.Fatalf("October import: %#v", octCounts)
	}
	novJob, novCounts := candidate("2026-11", "2026-11-20T00:00:00Z")
	if novCounts["imported"].(float64) != 0 || novCounts["duplicates"].(float64) != 1 {
		t.Fatalf("cross-month fingerprint: %#v", novCounts)
	}
	_, nov := h.request("GET", "/v1/summaries/2026-11", token, nil)
	if nov["transactionCount"].(float64) != 0 || nov["inputRevision"].(float64) != 0 {
		t.Fatalf("duplicate leaked into November: %#v", nov)
	}
	status, first := h.request("PATCH", "/v1/import-jobs/"+novJob, token, map[string]any{"state": "COMPLETE"})
	if status != 200 {
		t.Fatalf("finalize: %d %#v", status, first)
	}
	status, retry := h.request("PATCH", "/v1/import-jobs/"+novJob, token, map[string]any{"state": "COMPLETE"})
	if status != 200 || retry["state"] != "COMPLETE" || retry["duplicates"].(float64) != 1 {
		t.Fatalf("finalize retry: %d %#v", status, retry)
	}
	_ = octJob
}

func TestAnalysisFreshnessOnReadReplayAndDependencies(t *testing.T) {
	provider := &captureProvider{}
	h := newHarnessWithProvider(t, provider)
	h.register("freshness@example.test")
	token, _ := h.login("freshness@example.test")
	food := h.category(token, "food")
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	})
	body := map[string]any{"monthId": "2026-11", "inputRevision": 1,
		"payload": sampleAnalysisPayload("2026-11")}
	status, _ := h.requestWithHeaders("POST", "/v1/analyses", token, body,
		map[string]string{"Idempotency-Key": "fresh-1"})
	if status != 200 {
		t.Fatalf("analysis: %d", status)
	}
	_, current := h.request("GET", "/v1/analyses/2026-11", token, nil)
	if current["freshness"] != "CURRENT" || current["inputRevision"].(float64) != 1 ||
		current["currentRevision"].(float64) != 1 || current["monthId"] != "2026-11" ||
		current["createdAt"] == nil {
		t.Fatalf("current metadata: %#v", current)
	}

	mutations := []struct {
		name string
		run  func()
	}{
		{"transaction", func() {
			h.request("POST", "/v1/transactions", token, map[string]any{
				"direction": "DEBIT", "amountMinor": 2_000, "currency": "IDR",
				"eventAt": "2026-11-20T04:10:00Z", "categoryId": food})
		}},
		{"prior month", func() {
			h.request("POST", "/v1/transactions", token, map[string]any{
				"direction": "DEBIT", "amountMinor": 3_000, "currency": "IDR",
				"eventAt": "2026-10-20T04:10:00Z", "categoryId": food})
		}},
		{"budget", func() {
			h.request("PUT", "/v1/budget-plans/2026-11", token, map[string]any{
				"expectedIncomeMinor": 1_000_000, "plannedSavingsMinor": 0,
				"limits": []any{}, "revision": 0})
		}},
		{"category", func() {
			h.request("PATCH", "/v1/categories/"+food, token, map[string]any{
				"label": "Makanan berubah", "revision": 0})
		}},
	}
	for _, mutation := range mutations {
		mutation.run()
		_, report := h.request("GET", "/v1/analyses/2026-11", token, nil)
		if report["status"] != "AVAILABLE" || report["freshness"] != "STALE" {
			t.Fatalf("%s did not make the report stale: %#v", mutation.name, report)
		}
		if report["inputRevision"].(float64) != 1 || report["currentRevision"].(float64) < 2 {
			t.Fatalf("%s revision metadata: %#v", mutation.name, report)
		}
	}
	// Replaying an old idempotency key may return provenance, but must not claim CURRENT.
	callsBefore := provider.calls
	status, replay := h.requestWithHeaders("POST", "/v1/analyses", token, body,
		map[string]string{"Idempotency-Key": "fresh-1"})
	if status != 200 || replay["freshness"] != "STALE" {
		t.Fatalf("stale replay: %d %#v", status, replay)
	}
	if provider.calls != callsBefore {
		t.Fatal("stale replay called the provider")
	}
}

func TestDeletionRequiresReauthConfirmationAndRevokesSessions(t *testing.T) {
	h := newHarness(t)
	h.register("reauth@example.test")
	token, refresh := h.login("reauth@example.test")
	if status, _ := h.request("POST", "/v1/deletion-requests", token, nil); status != 400 {
		t.Fatalf("empty deletion body: %d", status)
	}
	if status, _ := h.request("POST", "/v1/deletion-requests", token, map[string]any{
		"password": testPassword, "confirmation": "yes",
	}); status != 400 {
		t.Fatalf("weak confirmation: %d", status)
	}
	if status, _ := h.request("POST", "/v1/deletion-requests", token, map[string]any{
		"password": "wrong-password", "confirmation": "DELETE_WORKSPACE",
	}); status != 403 {
		t.Fatalf("wrong password: %d", status)
	}
	status, deletion := h.request("POST", "/v1/deletion-requests", token, map[string]any{
		"password": testPassword, "confirmation": "DELETE_WORKSPACE",
	})
	if status != 202 || deletion["receiptToken"] == nil {
		t.Fatalf("reauth deletion: %d %#v", status, deletion)
	}
	receipt := deletion["receiptToken"].(string)
	if code, _ := h.request("GET", "/v1/transactions", token, nil); code != 401 {
		t.Fatalf("financial read after deletion request: %d", code)
	}
	if code, _ := h.request("POST", "/v1/sessions/refresh", "", map[string]any{"refreshToken": refresh}); code != 401 {
		t.Fatalf("refresh after deletion request: %d", code)
	}
	if code, body := h.request("GET", "/v1/deletion-receipts/"+receipt, "", nil); code != 200 || body["state"] != "PENDING" {
		t.Fatalf("receipt while pending: %d %#v", code, body)
	}
}

type accountingFailureProvider struct {
	onCall func()
}

func (p *accountingFailureProvider) ID() string    { return "accounting-failure" }
func (p *accountingFailureProvider) Model() string { return "accounting-failure-v1" }
func (p *accountingFailureProvider) Analyze(context.Context, map[string]any) (store.AnalysisResponse, error) {
	if p.onCall != nil {
		p.onCall()
	}
	return store.AnalysisResponse{
		PromptVersion: "analysis-v1",
		Observations:  []store.Observation{{Text: "Agregat.", MetricKeys: []string{"summary.grossOutflow"}}},
		Limitations:   []string{"Data sintetis."},
	}, nil
}

func TestUsageAccountingFailureIsReportedAndNotPublished(t *testing.T) {
	provider := &accountingFailureProvider{}
	h := newHarnessWithProvider(t, provider)
	provider.onCall = func() { _ = h.store.DB.Close() }
	h.register("accounting-failure@example.test")
	token, _ := h.login("accounting-failure@example.test")
	food := h.category(token, "food")
	h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 1_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food,
	})
	for _, purpose := range []string{"GMAIL_READ", "AI_PROCESSING"} {
		h.request("POST", "/v1/consents", token, map[string]any{
			"purpose": purpose, "decision": "GRANTED", "noticeVersion": "v0.4-review",
		})
	}
	status, body := h.requestWithHeaders("POST", "/v1/analyses", token, map[string]any{
		"monthId": "2026-11", "inputRevision": 1, "payload": sampleAnalysisPayload("2026-11"),
	}, map[string]string{"Idempotency-Key": "accounting-failure"})
	if status != 500 || body["code"] != "usage_accounting_failed" {
		t.Fatalf("accounting failure: %d %#v", status, body)
	}
}

func TestReceiptTokenIsNeverWrittenToLogs(t *testing.T) {
	path := filepath.Join(t.TempDir(), "log.db")
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
	defer db.Close()
	s := store.New(db, cfg.DataKey, cfg.KeyVersion)
	defer s.Close()
	var logs strings.Builder
	logger := slog.New(slog.NewTextHandler(&logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	api := New(s, cfg, auth.NewIssuer(cfg.JWT.Secret, cfg.JWT.Issuer, cfg.JWT.Audience,
		cfg.JWT.AccessTTL), logger, &llm.Fake{})
	server := httptest.NewServer(api.Handler())
	defer server.Close()
	canary := "receipt-canary-7f3a9d"
	for _, target := range []string{
		"/v1/deletion-receipts/" + canary,
		"/v1/deletion-receipts/unknown/" + canary,
		"/v1/unknown-" + canary,
	} {
		response, err := http.Get(server.URL + target)
		if err != nil {
			t.Fatal(err)
		}
		_ = response.Body.Close()
	}
	if strings.Contains(logs.String(), canary) {
		t.Fatalf("receipt canary leaked into logs: %s", logs.String())
	}
	if !strings.Contains(logs.String(), "/v1/deletion-receipts/{token}") {
		t.Fatalf("route template missing from logs: %s", logs.String())
	}
}
