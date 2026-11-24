package llm

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/store"
)

// OpenAI is an OpenAI-compatible chat-completions adapter (OpenAI, Ollama /v1, OpenRouter).
// Only the allow-listed aggregate payload is serialized into the prompt.
type OpenAI struct {
	BaseURL     string
	APIKey      string
	ModelID     string
	MaxTokens   int
	Temperature float64
	Client      *http.Client
	azure       bool
	maxInput    int
}

func (o OpenAI) ID() string {
	if o.azure {
		return "azure-openai-compatible"
	}
	return "openai-compatible"
}
func (o OpenAI) Model() string { return o.ModelID }

func NewOpenAI(baseURL, apiKey, model string, maxTokens int, timeout time.Duration) OpenAI {
	return OpenAI{BaseURL: strings.TrimRight(baseURL, "/"), APIKey: apiKey, ModelID: model,
		MaxTokens: maxTokens, Temperature: 0.2, Client: &http.Client{Timeout: timeout}}
}

func NewAzure(baseURL, apiKey, deployment string, maxInput, maxTokens int, timeout time.Duration) OpenAI {
	provider := NewOpenAI(baseURL, apiKey, deployment, maxTokens, timeout)
	provider.azure = true
	provider.maxInput = maxInput
	// The configured Foundry gateway closes connections on this host's default
	// hybrid key share. Limit this client to standard ECDHE groups; keep TLS verification.
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.TLSClientConfig = &tls.Config{
		MinVersion:       tls.VersionTLS12,
		CurvePreferences: []tls.CurveID{tls.X25519, tls.CurveP256},
	}
	provider.Client.Transport = transport
	provider.Client.CheckRedirect = func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }
	return provider
}

var azureAnalysisFormat = json.RawMessage(`{
	"type":"json_schema",
	"json_schema":{"name":"pam_analysis","strict":true,"schema":{
		"type":"object","additionalProperties":false,
		"required":["promptVersion","observations","suggestions","limitations"],
		"properties":{
			"promptVersion":{"type":"string","enum":["analysis-v1"]},
			"observations":{"type":"array","minItems":1,"items":{
				"type":"object","additionalProperties":false,"required":["text","metricKeys"],
				"properties":{"text":{"type":"string"},"metricKeys":{"type":"array","items":{"type":"string"}}}
			}},
			"suggestions":{"type":"array","items":{
				"type":"object","additionalProperties":false,
				"required":["text","metricKeys","categoryCode","suggestedAmountMinor"],
				"properties":{
					"text":{"type":"string"},"metricKeys":{"type":"array","items":{"type":"string"}},
					"categoryCode":{"type":["string","null"]},"suggestedAmountMinor":{"type":["integer","null"]}
				}
			}},
			"limitations":{"type":"array","minItems":1,"items":{"type":"string"}}
		}
	}}
}`)

func (o OpenAI) Analyze(ctx context.Context, payload map[string]any) (store.AnalysisResponse, error) {
	systemPrompt := "You are PAM's financial recap analyst. Return only JSON with promptVersion, observations, suggestions, limitations. Cite only the supplied aggregate numbers. Never invent amounts. Use Indonesian."
	if o.azure {
		systemPrompt = `Analyze PAM's aggregate monthly metrics in Indonesian. EMPTY email coverage can coexist with manual transactions. Return one to three observations and at least one limitation. Each observation or suggestion needs evidence. Allowed keys: summary.grossInflow, summary.grossOutflow, summary.netCashFlow, summary.classifiedIncome, summary.classifiedExpense, summary.transactionCount, summary.uncategorizedCount, summary.manualEntryCount, summary.nonIdrExcludedCount, summary.coverage, and category.<categoryCode> for supplied categories. Keep suggestions qualitative; use null for suggestedAmountMinor. Avoid amounts in prose; if essential, copy an existing Rp amount exactly. Never invent amounts. Example for nonzero outflow: {"promptVersion":"analysis-v1","observations":[{"text":"Ada pengeluaran yang tercatat bulan ini.","metricKeys":["summary.grossOutflow"]}],"suggestions":[],"limitations":["Analisis menggunakan ringkasan transaksi bulan ini."]}.`
		payload = azureRupiahAmounts(payload).(map[string]any)
	}
	body := map[string]any{
		"model": o.ModelID,
		"messages": []map[string]string{
			{"role": "system", "content": systemPrompt},
			{"role": "user", "content": mustJSON(payload)},
		},
		"max_tokens":      o.MaxTokens,
		"response_format": map[string]string{"type": "json_object"},
	}
	if o.azure {
		body["reasoning_effort"] = "low"
		body["response_format"] = azureAnalysisFormat
	} else {
		body["temperature"] = o.Temperature
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return store.AnalysisResponse{}, err
	}
	// For Azure, bound input conservatively by the serialized request bytes and
	// reserve room for provider chat framing before sending a billable request.
	if o.azure && len(raw)+64 > o.maxInput {
		return store.AnalysisResponse{}, fmt.Errorf("analysis request exceeds Azure input token budget")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, o.BaseURL+"/chat/completions", bytes.NewReader(raw))
	if err != nil {
		return store.AnalysisResponse{}, err
	}
	request.Header.Set("Content-Type", "application/json")
	if o.APIKey != "" {
		if o.azure {
			request.Header.Set("api-key", o.APIKey)
		} else {
			request.Header.Set("Authorization", "Bearer "+o.APIKey)
		}
	}
	response, err := o.Client.Do(request)
	if err != nil {
		if o.azure {
			if ctx.Err() != nil {
				return store.AnalysisResponse{}, ctx.Err()
			}
			if networkError, ok := err.(net.Error); ok && networkError.Timeout() {
				return store.AnalysisResponse{}, fmt.Errorf("Azure analysis request timed out")
			}
			return store.AnalysisResponse{}, fmt.Errorf("Azure analysis transport failed")
		}
		return store.AnalysisResponse{}, fmt.Errorf("provider request: %w", err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return store.AnalysisResponse{}, err
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return store.AnalysisResponse{}, fmt.Errorf("provider status %d", response.StatusCode)
	}
	var decoded struct {
		Choices []struct {
			FinishReason string `json:"finish_reason"`
			Message      struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
		Usage struct {
			PromptTokens     *int64 `json:"prompt_tokens"`
			CompletionTokens *int64 `json:"completion_tokens"`
			TotalTokens      *int64 `json:"total_tokens"`
			// Azure gpt-oss bills reasoning tokens inside completion_tokens; the detail is
			// reported separately so budget evidence can show the split.
			CompletionTokensDetails *struct {
				ReasoningTokens *int64 `json:"reasoning_tokens"`
			} `json:"completion_tokens_details"`
		} `json:"usage"`
	}
	if err := json.Unmarshal(data, &decoded); err != nil || len(decoded.Choices) == 0 {
		return store.AnalysisResponse{}, fmt.Errorf("provider returned no choices")
	}
	var analysis store.AnalysisResponse
	usage := store.AnalysisResponse{
		PromptTokens:     decoded.Usage.PromptTokens,
		CompletionTokens: decoded.Usage.CompletionTokens,
		TotalTokens:      decoded.Usage.TotalTokens,
	}
	if decoded.Usage.CompletionTokensDetails != nil {
		usage.ReasoningTokens = decoded.Usage.CompletionTokensDetails.ReasoningTokens
	}
	if o.azure && decoded.Choices[0].FinishReason != "stop" {
		return usage, fmt.Errorf("Azure analysis did not finish normally")
	}
	decoder := json.NewDecoder(strings.NewReader(extractJSON(decoded.Choices[0].Message.Content)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&analysis); err != nil {
		return usage, fmt.Errorf("provider returned invalid analysis json")
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return usage, fmt.Errorf("provider returned trailing analysis json")
	}
	analysis.PromptTokens, analysis.CompletionTokens = usage.PromptTokens, usage.CompletionTokens
	analysis.ReasoningTokens, analysis.TotalTokens = usage.ReasoningTokens, usage.TotalTokens
	return analysis, nil
}

// Snapshot.ToMap uses JSON numbers (float64). Format monetary fields for the
// provider without modifying the canonical snapshot used for validation.
func azureRupiahAmounts(value any) any {
	switch v := value.(type) {
	case map[string]any:
		out := make(map[string]any, len(v))
		for key, entry := range v {
			amount, isNumber := entry.(float64)
			if isNumber && strings.HasSuffix(key, "Minor") {
				minor := int64(amount)
				sign := ""
				if minor < 0 {
					sign = "-"
				}
				major, fraction := minor/100, minor%100
				if minor < 0 {
					major, fraction = -major, -fraction
				}
				out[strings.TrimSuffix(key, "Minor")+"Rupiah"] = fmt.Sprintf("%sRp%d,%02d", sign, major, fraction)
			} else {
				out[key] = azureRupiahAmounts(entry)
			}
		}
		return out
	case []any:
		out := make([]any, len(v))
		for i, entry := range v {
			out[i] = azureRupiahAmounts(entry)
		}
		return out
	default:
		return v
	}
}

func mustJSON(value any) string {
	raw, _ := json.Marshal(value)
	return string(raw)
}

// extractJSON tolerates a fenced block, as some local models wrap JSON despite response_format.
func extractJSON(text string) string {
	trimmed := strings.TrimSpace(text)
	if strings.HasPrefix(trimmed, "{") {
		return trimmed
	}
	start, end := strings.Index(trimmed, "{"), strings.LastIndex(trimmed, "}")
	if start >= 0 && end > start {
		return trimmed[start : end+1]
	}
	return trimmed
}
