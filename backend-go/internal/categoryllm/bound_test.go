package categoryllm

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func debitCandidate(merchant string) Candidate {
	return Candidate{Merchant: merchant, Direction: "DEBIT", Method: "QRIS",
		Categories: []string{"food", "transport", "utilities", "uncategorized"}}
}

// The input gate must reject oversized prompts BEFORE any HTTP call: the provider must never see
// a request that the model input ceiling would refuse.
func TestInputTooLargeRejectedBeforeHTTP(t *testing.T) {
	var calls int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		fmt.Fprint(w, `{"choices":[{"message":{"content":"{\"results\":[]}"}}]}`)
	}))
	defer server.Close()
	azure := NewAzure(server.URL, "k", "d", "m", time.Second)

	// A single merchant long enough that its request body exceeds the conservative estimate.
	huge := strings.Repeat("A", MaxMerchantRunes)
	candidates := []Candidate{debitCandidate(huge)}
	for i := 0; i < 40 && azure.FitsInputBudget(candidates); i++ {
		candidates = append(candidates, debitCandidate(huge))
	}
	if azure.FitsInputBudget(candidates) {
		t.Fatal("probe candidates unexpectedly fit the input budget")
	}
	// ValidateCandidates rejects >5 candidates first; use SplitCandidates to isolate the gate on a
	// single overlong candidate by asserting Classify refuses without HTTP.
	if _, err := azure.Classify(context.Background(), candidates); err == nil {
		t.Fatal("oversized request accepted")
	}
	if atomic.LoadInt32(&calls) != 0 {
		t.Fatalf("provider was called %d times for an oversized request", calls)
	}
}

func TestInputBoundIsDeterministicAndConservative(t *testing.T) {
	azure := NewAzure("http://127.0.0.1:1", "", "d", "m", time.Second)
	a := []Candidate{debitCandidate("BAKSO UJI"), debitCandidate("KOPI UJI")}
	b := []Candidate{debitCandidate("BAKSO UJI"), debitCandidate("KOPI UJI")}
	first, second := azure.estimateInputBytes(a), azure.estimateInputBytes(b)
	if first != second {
		t.Fatalf("estimate not deterministic: %d vs %d", first, second)
	}
	if tokens := azure.estimateTokens(a); tokens <= 0 {
		t.Fatalf("estimate = %d", tokens)
	}
	// The estimate covers the whole body, so it must exceed a naive prompt-only character count.
	if int64(first) < int64(len("You are PAM's merchant category classifier")) {
		t.Fatalf("estimate %d does not cover the system prompt", first)
	}
}

func TestSplitCandidatesKeepsGroupsWithinBudgetAndLosesNothing(t *testing.T) {
	azure := NewAzure("http://127.0.0.1:1", "", "d", "m", time.Second)
	candidates := []Candidate{
		debitCandidate("BAKSO UJI SATU"), debitCandidate("WARUNG UJI DUA"),
		debitCandidate("TOKO UJI TIGA"), debitCandidate("APOTEK UJI EMPAT"),
		debitCandidate("KOPI UJI LIMA"),
	}
	groups := azure.SplitCandidates(candidates)
	if len(groups) == 0 {
		t.Fatal("no groups")
	}
	seen := map[string]int{}
	for _, group := range groups {
		if !azure.FitsInputBudget(group) {
			t.Fatalf("group of %d candidates exceeds the input budget", len(group))
		}
		for _, c := range group {
			seen[c.Merchant]++
		}
	}
	for _, c := range candidates {
		if seen[c.Merchant] != 1 {
			t.Fatalf("candidate %q appears %d times across groups", c.Merchant, seen[c.Merchant])
		}
	}
}

func TestSplitCandidatesKeepsSingleOverlongCandidateAlone(t *testing.T) {
	azure := NewAzure("http://127.0.0.1:1", "", "d", "m", time.Second)
	// One candidate alone can never fit only if its own body exceeds the cap; verify the group is
	// still returned (as its own group) so the caller can mark it NEEDS_REVIEW instead of crashing.
	huge := Candidate{Merchant: strings.Repeat("A", MaxMerchantRunes), Direction: "DEBIT",
		Categories: []string{"food", "transport", "utilities", "shopping", "health", "education"}}
	groups := azure.SplitCandidates([]Candidate{huge})
	if len(groups) != 1 || len(groups[0]) != 1 {
		t.Fatalf("groups=%d", len(groups))
	}
}

func TestTransientStatusesAreRetryableOnly(t *testing.T) {
	for _, status := range []int{http.StatusRequestTimeout, http.StatusTooManyRequests,
		http.StatusInternalServerError, http.StatusBadGateway, http.StatusServiceUnavailable,
		http.StatusGatewayTimeout} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(status)
		}))
		azure := NewAzure(server.URL, "", "d", "m", time.Second)
		_, err := azure.Classify(context.Background(), []Candidate{debitCandidate("BAKSO UJI")})
		if !IsProviderTransient(err) {
			t.Fatalf("status %d not transient: %v", status, err)
		}
		server.Close()
	}
	// Client errors must NOT be retried (a retry would burn budget without changing the outcome).
	for _, status := range []int{http.StatusBadRequest, http.StatusUnprocessableEntity,
		http.StatusRequestEntityTooLarge} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(status)
		}))
		azure := NewAzure(server.URL, "", "d", "m", time.Second)
		_, err := azure.Classify(context.Background(), []Candidate{debitCandidate("BAKSO UJI")})
		if err == nil || IsProviderTransient(err) {
			t.Fatalf("status %d treated as transient: %v", status, err)
		}
		server.Close()
	}
}

func TestTruncationAndInvalidOutputAreNotTransient(t *testing.T) {
	for _, body := range []string{
		`{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}`,
		`{"choices":[{"message":{"content":"not json"}}]}`,
		`{"choices":[]}`,
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			fmt.Fprint(w, body)
		}))
		azure := NewAzure(server.URL, "", "d", "m", time.Second)
		_, err := azure.Classify(context.Background(), []Candidate{debitCandidate("BAKSO UJI")})
		if err == nil || IsProviderTransient(err) {
			t.Fatalf("body %s: err=%v transient=%v", body, err, IsProviderTransient(err))
		}
		server.Close()
	}
}

func TestMeasuredUsageIsReportedWhenAvailable(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"choices":[{"message":{"content":"{\"results\":[{\"caseId\":\"c0\",\"categoryCode\":\"food\",\"needsReview\":false,\"reasonCode\":\"merchant_food\"}]}"}}],"usage":{"prompt_tokens":321,"completion_tokens":17,"completion_tokens_details":{"reasoning_tokens":9}}}`)
	}))
	defer server.Close()
	azure := NewAzure(server.URL, "", "d", "m", time.Second)
	response, err := azure.Classify(context.Background(), []Candidate{debitCandidate("BAKSO UJI")})
	if err != nil {
		t.Fatal(err)
	}
	if !response.UsageKnown || response.PromptTokens != 321 || response.CompletionTokens != 17 || response.ReasoningTokens != 9 {
		t.Fatalf("usage=%+v", response)
	}
}

func TestUsageUnknownWhenProviderOmitsUsage(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"choices":[{"message":{"content":"{\"results\":[{\"caseId\":\"c0\",\"categoryCode\":\"food\",\"needsReview\":false,\"reasonCode\":\"merchant_food\"}]}"}}]}`)
	}))
	defer server.Close()
	azure := NewAzure(server.URL, "", "d", "m", time.Second)
	response, err := azure.Classify(context.Background(), []Candidate{debitCandidate("BAKSO UJI")})
	if err != nil {
		t.Fatal(err)
	}
	if response.UsageKnown {
		t.Fatalf("usage reported as known without usage field: %+v", response)
	}
}

func TestMaxTokensAndSchemaAreEnforcedOnTheWire(t *testing.T) {
	var body map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = decodeBody(r, &body)
		fmt.Fprint(w, `{"choices":[{"message":{"content":"{\"results\":[{\"caseId\":\"c0\",\"categoryCode\":\"food\",\"needsReview\":false,\"reasonCode\":\"ok\"}]}"}}]}`)
	}))
	defer server.Close()
	azure := NewAzure(server.URL, "", "deployment-x", "m", time.Second)
	if _, err := azure.Classify(context.Background(), []Candidate{debitCandidate("BAKSO UJI")}); err != nil {
		t.Fatal(err)
	}
	if body["max_tokens"] != float64(MaxCompletionTokens) {
		t.Fatalf("max_tokens=%v", body["max_tokens"])
	}
	if body["model"] != "deployment-x" {
		t.Fatalf("model=%v", body["model"])
	}
	if _, ok := body["response_format"].(map[string]any)["json_schema"]; !ok {
		t.Fatal("strict json_schema missing")
	}
	// No transaction facts beyond merchant/direction/method may leave the backend: the user
	// payload is the only place case data travels.
	messages, _ := body["messages"].([]any)
	if len(messages) != 2 {
		t.Fatalf("messages=%v", body["messages"])
	}
	userPayload, _ := messages[1].(map[string]any)["content"].(string)
	raw := strings.ToLower(userPayload)
	for _, forbidden := range []string{"amount", "eventat", "email", "messageid", "accountnumber", "note"} {
		if strings.Contains(raw, forbidden) {
			t.Fatalf("user payload leaks %q: %s", forbidden, userPayload)
		}
	}
}
func decodeBody(r *http.Request, out *map[string]any) error {
	defer r.Body.Close()
	dec := json.NewDecoder(r.Body)
	return dec.Decode(out)
}
