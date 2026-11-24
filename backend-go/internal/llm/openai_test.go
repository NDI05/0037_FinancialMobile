package llm

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestAzureAnalysisRequestAndInputCap(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/openai/v1/chat/completions" || r.Header.Get("api-key") != "fixture-key" || r.Header.Get("Authorization") != "" {
			t.Error("unexpected Azure route or authentication header")
		}
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Error(err)
			http.Error(w, "invalid fixture request", http.StatusBadRequest)
			return
		}
		if body["model"] != "fixture-deployment" || body["reasoning_effort"] != "low" || body["max_tokens"] != float64(1200) || body["temperature"] != nil {
			t.Errorf("unexpected Azure analysis parameters: %v", body)
		}
		messages := body["messages"].([]any)
		content := messages[1].(map[string]any)["content"].(string)
		if !strings.Contains(content, `"grossOutflowRupiah":"Rp5,01"`) || strings.Contains(content, "grossOutflowMinor") {
			t.Errorf("expected rupiah in the provider payload, got %s", content)
		}
		_, _ = io.WriteString(w, `{"choices":[{"finish_reason":"stop","message":{"content":"{\"promptVersion\":\"analysis-v1\",\"observations\":[],\"suggestions\":[],\"limitations\":[]}"}}],"usage":{"prompt_tokens":200,"completion_tokens":300}}`)
	}))
	t.Cleanup(server.Close)

	provider := NewAzure(server.URL+"/openai/v1", "fixture-key", "fixture-deployment", 4000, 1200, time.Second)
	result, err := provider.Analyze(t.Context(), map[string]any{"summary": map[string]any{"grossOutflowMinor": float64(501)}})
	if err != nil || result.PromptVersion != "analysis-v1" || result.PromptTokens == nil || *result.PromptTokens != 200 {
		t.Fatalf("invalid result or usage: %+v, %v", result, err)
	}
	if provider.ID() != "azure-openai-compatible" || provider.Model() != "fixture-deployment" {
		t.Fatal("unexpected Azure provider provenance")
	}
	if _, err := provider.Analyze(t.Context(), map[string]any{"summary": strings.Repeat("x", 4000)}); err == nil {
		t.Fatal("oversized aggregate request should be rejected")
	}
	if calls.Load() != 1 {
		t.Fatalf("got %d calls, want one", calls.Load())
	}
}
