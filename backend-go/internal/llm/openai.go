package llm

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
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
}

func (o OpenAI) ID() string    { return "openai-compatible" }
func (o OpenAI) Model() string { return o.ModelID }

func NewOpenAI(baseURL, apiKey, model string, maxTokens int, timeout time.Duration) OpenAI {
	return OpenAI{BaseURL: strings.TrimRight(baseURL, "/"), APIKey: apiKey, ModelID: model,
		MaxTokens: maxTokens, Temperature: 0.2, Client: &http.Client{Timeout: timeout}}
}

func (o OpenAI) Analyze(ctx context.Context, payload map[string]any) (store.AnalysisResponse, error) {
	body := map[string]any{
		"model": o.ModelID,
		"messages": []map[string]string{
			{"role": "system", "content": "You are PAM's financial recap analyst. Return only JSON with promptVersion, observations, suggestions, limitations. Cite only the supplied aggregate numbers. Never invent amounts. Use Indonesian."},
			{"role": "user", "content": mustJSON(payload)},
		},
		"temperature":     o.Temperature,
		"max_tokens":      o.MaxTokens,
		"response_format": map[string]string{"type": "json_object"},
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return store.AnalysisResponse{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, o.BaseURL+"/chat/completions", bytes.NewReader(raw))
	if err != nil {
		return store.AnalysisResponse{}, err
	}
	request.Header.Set("Content-Type", "application/json")
	if o.APIKey != "" {
		request.Header.Set("Authorization", "Bearer "+o.APIKey)
	}
	response, err := o.Client.Do(request)
	if err != nil {
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
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
		} `json:"choices"`
		Usage struct {
			PromptTokens     int64 `json:"prompt_tokens"`
			CompletionTokens int64 `json:"completion_tokens"`
		} `json:"usage"`
	}
	if err := json.Unmarshal(data, &decoded); err != nil || len(decoded.Choices) == 0 {
		return store.AnalysisResponse{}, fmt.Errorf("provider returned no choices")
	}
	var analysis store.AnalysisResponse
	decoder := json.NewDecoder(strings.NewReader(extractJSON(decoded.Choices[0].Message.Content)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&analysis); err != nil {
		return store.AnalysisResponse{}, fmt.Errorf("provider returned invalid analysis json")
	}
	if decoder.More() {
		return store.AnalysisResponse{}, fmt.Errorf("provider returned trailing analysis json")
	}
	promptTokens, completionTokens := decoded.Usage.PromptTokens, decoded.Usage.CompletionTokens
	analysis.PromptTokens, analysis.CompletionTokens = &promptTokens, &completionTokens
	return analysis, nil
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
