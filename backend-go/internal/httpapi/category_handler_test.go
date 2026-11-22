package httpapi

import (
	"context"
	"strings"
	"testing"

	"github.com/pam/recap/backend-go/internal/categoryllm"
)

type stubCategory struct{ calls int }

func (s *stubCategory) ID() string { return "stub" }
func (s *stubCategory) Model() string { return "stub-model" }
func (s *stubCategory) SplitCandidates(candidates []categoryllm.Candidate) [][]categoryllm.Candidate {
	if len(candidates) == 0 { return nil }
	return [][]categoryllm.Candidate{candidates}
}
func (s *stubCategory) FitsInputBudget(_ []categoryllm.Candidate) bool { return true }
func (s *stubCategory) Classify(_ context.Context, candidates []categoryllm.Candidate) (categoryllm.Response, error) {
	s.calls++
	out := make([]categoryllm.Result, len(candidates))
	for i, c := range candidates {
		code, review := "uncategorized", true
		if c.Merchant == "BAKSO UJI" { code, review = "food", false }
		out[i] = categoryllm.Result{CaseID: "c" + string(rune('0'+i)), CategoryCode: code, NeedsReview: review, ReasonCode: "stub"}
	}
	return categoryllm.Response{Results: out, PromptTokens: 10, CompletionTokens: 10, UsageKnown: true}, nil
}

func newCategoryHarness(t *testing.T, classifier categoryllm.Classifier) *harness {
	return newCategoryHarnessWithEnv(t, classifier, nil)
}

func newCategoryHarnessWithEnv(t *testing.T, classifier categoryllm.Classifier, extra map[string]string) *harness {
	env := map[string]string{
		"CATEGORY_LLM_ENABLED":                    "true",
		"AZURE_CLASSIFIER_BASE_URL":               "https://example.test/openai/v1",
		"AZURE_CLASSIFIER_DEPLOYMENT":             "d",
		"AZURE_CLASSIFIER_API_KEY":                "k",
		"CATEGORY_LLM_PRICE_PROMPT_PER_1K_USD":    "0.00015",
		"CATEGORY_LLM_PRICE_COMPLETION_PER_1K_USD": "0.0006",
	}
	for k, v := range extra {
		env[k] = v
	}
	h := newHarnessWithConfig(t, nil, env)
	h.api.CategoryClassifier = classifier
	return h
}

func TestCategoryJobsRequireConsentCacheAndUserOverride(t *testing.T) {
	stub := &stubCategory{}
	h := newCategoryHarness(t, stub)
	h.register("cat@example.test")
	token, _ := h.login("cat@example.test")
	body := map[string]any{"direction":"DEBIT","source":"MANUAL","categoryOrigin":"IMPORTED","amountMinor":16000,
		"currency":"IDR","eventAt":"2026-11-19T02:15:20Z","fingerprint":"a"+strings.Repeat("0",31),
		"parserVersion":"livin-v1","parserConfidence":0.9,"description":"QRIS","merchantLabel":"BAKSO UJI"}
	// No categorization consent yet: no provider call and 403.
	status, _ := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds":[]string{"missing"}})
	if status != 403 { t.Fatalf("without consent status=%d", status) }
	if stub.calls != 0 { t.Fatal("provider called without consent") }
	if status, _ := h.request("POST", "/v1/consents", token, map[string]any{"purpose":"AI_CATEGORIZATION","decision":"GRANTED","noticeVersion":"notice-v2"}); status != 201 { t.Fatal(status) }
	status, created := h.request("POST", "/v1/transactions", token, body)
	if status != 201 { t.Fatalf("create=%d %v", status, created) }
	txID := created["id"].(string)
	status, jobs := h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds":[]string{txID}})
	if status != 200 || stub.calls != 1 { t.Fatalf("classify=%d calls=%d body=%v", status, stub.calls, jobs) }
	if jobs["jobs"].([]any)[0].(map[string]any)["state"] != "CLASSIFIED" { t.Fatalf("jobs=%v", jobs) }

	// Same merchant in a new transaction is a cache hit; one provider call total.
	body["fingerprint"] = "b"+strings.Repeat("0",31)
	_, created2 := h.request("POST", "/v1/transactions", token, body)
	status, _ = h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds":[]string{created2["id"].(string)}})
	if status != 200 || stub.calls != 1 { t.Fatalf("cache status=%d calls=%d", status, stub.calls) }

	// USER override is never sent to the provider.
	_, tx := h.request("GET", "/v1/transactions/"+txID, token, nil)
	cats := h.store
	_ = cats
	foodID := ""
	rows, err := h.store.DB.QueryContext(context.Background(), `SELECT id FROM categories WHERE owner_id=(SELECT owner_id FROM transactions WHERE id=?) AND code='food'`, txID)
	if err != nil { t.Fatal(err) }
	if rows.Next() { _ = rows.Scan(&foodID) }; rows.Close()
	status, _ = h.request("PATCH", "/v1/transactions/"+txID, token, map[string]any{"categoryId":foodID,"revision":tx["revision"]})
	if status != 200 { t.Fatalf("patch=%d", status) }
	status, jobs = h.request("POST", "/v1/category-jobs", token, map[string]any{"transactionIds":[]string{txID}})
	if status != 200 || stub.calls != 1 { t.Fatalf("user override status=%d calls=%d", status, stub.calls) }
	if jobs["jobs"].([]any)[0].(map[string]any)["state"] != "NEEDS_REVIEW" { t.Fatalf("user job=%v", jobs) }
}
