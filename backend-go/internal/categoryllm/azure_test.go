package categoryllm

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAzureStrictOutputAndSanitization(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-key" {
			t.Fatal("missing auth")
		}
		if strings.Contains(r.URL.Path, "merchant") { t.Fatal("merchant in URL") }
		fmt.Fprint(w, `{"choices":[{"message":{"content":"{\"results\":[{\"index\":0,\"categoryCode\":\"food\",\"needsReview\":false,\"reasonCode\":\"merchant_food\"}]}"}}]}`)
	}))
	defer server.Close()
	azure := NewAzure(server.URL, "test-key", "deployment", "gpt-oss-120b", time.Second)
	results, err := azure.Classify(context.Background(), []Candidate{{Merchant: "BAKSO UJI", Direction: "DEBIT", Method: "QRIS", Categories: []string{"food", "uncategorized"}}})
	if err != nil || len(results) != 1 || results[0].CategoryCode != "food" {
		t.Fatalf("unexpected %v %v", results, err)
	}
}

func TestAzureRejectsWrongSchemaAndExtraFields(t *testing.T) {
	for _, content := range []string{
		`{"results":[{"index":0,"categoryCode":"invented","needsReview":false,"reasonCode":"x"}]}`,
		`{"results":[{"index":0,"categoryCode":"food","needsReview":false,"reasonCode":"x","amountMinor":1}]}`,
		`{"results":[{"index":1,"categoryCode":"food","needsReview":false,"reasonCode":"x"}]}`,
		`{"results":[{"index":0,"categoryCode":"food","needsReview":true,"reasonCode":"x"}]}`,
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			fmt.Fprintf(w, `{"choices":[{"message":{"content":%q}}]}`, content)
		}))
		azure := NewAzure(server.URL, "", "d", "m", time.Second)
		_, err := azure.Classify(context.Background(), []Candidate{{Merchant: "BAKSO UJI", Direction: "DEBIT", Categories: []string{"food", "uncategorized"}}})
		if err == nil { t.Fatalf("accepted invalid output %s", content) }
		server.Close()
	}
}

func TestMerchantSanitizerRejectsPersonalOrInjectionShapedValues(t *testing.T) {
	bad := []string{"", "a@b.test", "https://evil.test", "line\nbreak", strings.Repeat("a", 121), "123456789"}
	for _, value := range bad {
		if _, err := SanitizeMerchant(value); err == nil { t.Fatalf("accepted %q", value) }
	}
	if _, err := SanitizeMerchant("BAKSO UJI"); err != nil { t.Fatal(err) }
}

func TestValidationRejectsDuplicateAndTooManyCandidates(t *testing.T) {
	c := Candidate{Merchant: "WARUNG UJI", Direction: "DEBIT", Categories: []string{"food"}}
	if err := ValidateCandidates([]Candidate{c, c, c, c, c, c}); err == nil { t.Fatal("accepted >5") }
	c.Categories = []string{"food", "food"}
	if err := ValidateCandidates([]Candidate{c}); err == nil { t.Fatal("accepted duplicate category") }
}
