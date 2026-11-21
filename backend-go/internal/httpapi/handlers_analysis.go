package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"

	"github.com/pam/recap/backend-go/internal/store"
)

// ALLOWED_PAYLOAD_FIELDS mirrors the Kotlin AiAggregatePayload allowlist. It is deliberately a
// closed set: a body containing subject/body/sender/messageId/token/pan is rejected, not ignored.
var allowedPayloadFields = map[string]bool{
	"schemaVersion": true, "monthId": true, "coverage": true,
	"grossInflowMinor": true, "grossOutflowMinor": true, "netCashFlowMinor": true,
	"classifiedIncomeMinor": true, "classifiedExpenseMinor": true,
	"transactionCount": true, "uncategorizedCount": true, "manualEntryCount": true,
	"nonIdrExcludedCount": true, "categoryTotals": true, "priorMonth": true, "plan": true,
	"categoryCode": true, "amountMinor": true, "limitMinor": true, "expenseMinor": true,
	"incomeMinor": true, "expectedIncomeMinor": true, "plannedSavingsMinor": true,
	"categoryLimitTotals": true,
}

type analysisRequest struct {
	MonthID       string         `json:"monthId"`
	InputRevision int            `json:"inputRevision"`
	Payload       map[string]any `json:"payload"`
}

func (s *Server) createAnalysis(w http.ResponseWriter, r *http.Request) {
	var body analysisRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	if _, _, err := store.ParseMonth(body.MonthID); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "monthId must be YYYY-MM", correlationOf(r))
		return
	}
	if body.Payload == nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "payload is required", correlationOf(r))
		return
	}
	if payloadMonth, _ := body.Payload["monthId"].(string); payloadMonth != body.MonthID {
		writeError(w, http.StatusBadRequest, "invalid_request", "payload.monthId must match monthId", correlationOf(r))
		return
	}
	if disallowed := findDisallowedFields(body.Payload, ""); len(disallowed) > 0 {
		writeError(w, http.StatusBadRequest, "invalid_request",
			"payload contains fields that may never leave the device: "+strings.Join(disallowed[:min(3, len(disallowed))], ", "),
			correlationOf(r))
		return
	}
	if !validPayloadShape(body.Payload) {
		writeError(w, http.StatusBadRequest, "invalid_request", "payload does not match the aggregate schema", correlationOf(r))
		return
	}
	idempotencyKey := strings.TrimSpace(r.Header.Get("Idempotency-Key"))
	if idempotencyKey == "" || len(idempotencyKey) > 200 {
		writeError(w, http.StatusBadRequest, "invalid_request", "Idempotency-Key header is required", correlationOf(r))
		return
	}
	info, _ := authFrom(r)
	if pending, err := s.Store.HasPendingDeletion(r.Context(), info.OwnerID); err == nil && pending {
		writeError(w, http.StatusConflict, "deletion_in_progress", "A deletion request is in progress", correlationOf(r))
		return
	}
	consent, err := s.Store.LatestConsent(r.Context(), info.OwnerID, "AI_PROCESSING")
	if err != nil || consent.Decision != "GRANTED" {
		writeError(w, http.StatusForbidden, "consent_required", "AI consent has not been granted", correlationOf(r))
		return
	}
	requestHash := hashAnalysisRequest(body)
	existing, existingHash, err := s.Store.FindAnalysisByIdempotency(r.Context(), info.OwnerID, idempotencyKey)
	if err == nil {
		if existingHash != requestHash {
			writeError(w, http.StatusConflict, "conflict", "This Idempotency-Key was already used with a different body", correlationOf(r))
			return
		}
		writeJSON(w, http.StatusOK, analysisJSON(existing))
		return
	}
	provider := s.Provider
	if provider == nil {
		writeError(w, http.StatusBadGateway, "provider_unavailable", "No analysis provider is configured", correlationOf(r))
		return
	}
	cached, err := s.Store.FindAnalysisByCache(r.Context(), info.OwnerID, body.MonthID,
		body.InputRevision, "analysis-v1", provider.ID(), provider.Model())
	if err == nil {
		writeJSON(w, http.StatusOK, analysisJSON(cached))
		return
	}
	reason, err := s.Store.TryReserveAnalysis(r.Context(), info.OwnerID,
		s.Config.Limits.AnalysesPerHour, s.Config.Limits.AnalysesPerDay, s.Now())
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	if reason != "" {
		writeError(w, http.StatusTooManyRequests, reason, "Analysis limit reached", correlationOf(r))
		return
	}
	response, err := provider.Analyze(r.Context(), body.Payload)
	if err != nil {
		writeError(w, http.StatusBadGateway, "provider_unavailable", "Analysis provider failed", correlationOf(r))
		return
	}
	if err := validateAnalysisResponse(response, body.Payload); err != nil {
		writeError(w, http.StatusUnprocessableEntity, "invalid_analysis_output", err.Error(), correlationOf(r))
		return
	}
	record, err := s.Store.InsertAnalysis(r.Context(), info.OwnerID, body.MonthID, body.InputRevision,
		consent.ID, provider.ID(), provider.Model(), "analysis-v1", idempotencyKey, requestHash,
		response, "AVAILABLE", 0, s.Now())
	if err != nil {
		if err == store.ErrConflict {
			existing, _, findErr := s.Store.FindAnalysisByIdempotency(r.Context(), info.OwnerID, idempotencyKey)
			if findErr == nil {
				writeJSON(w, http.StatusOK, analysisJSON(existing))
				return
			}
		}
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, analysisJSON(record))
}

func (s *Server) getAnalysis(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	record, err := s.Store.LatestAnalysis(r.Context(), info.OwnerID, r.PathValue("monthID"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, analysisJSON(record))
}

func analysisJSON(record store.AnalysisRecord) map[string]any {
	return map[string]any{
		"promptVersion":    record.Response.PromptVersion,
		"observations":     record.Response.Observations,
		"suggestions":      record.Response.Suggestions,
		"limitations":      record.Response.Limitations,
		"promptTokens":     record.Response.PromptTokens,
		"completionTokens": record.Response.CompletionTokens,
	}
}

func findDisallowedFields(value any, path string) []string {
	switch typed := value.(type) {
	case map[string]any:
		var found []string
		for key, child := range typed {
			if !allowedPayloadFields[key] {
				if path == "" {
					found = append(found, key)
				} else {
					found = append(found, path+"."+key)
				}
			}
			childPath := key
			if path != "" {
				childPath = path + "." + key
			}
			found = append(found, findDisallowedFields(child, childPath)...)
		}
		return found
	case []any:
		var found []string
		for _, child := range typed {
			found = append(found, findDisallowedFields(child, path)...)
		}
		return found
	default:
		return nil
	}
}

func validPayloadShape(payload map[string]any) bool {
	if schema, _ := payload["schemaVersion"].(float64); schema != 1 {
		return false
	}
	coverage, _ := payload["coverage"].(string)
	if coverage != "COMPLETE" && coverage != "PARTIAL" && coverage != "UNKNOWN" {
		return false
	}
	for _, key := range []string{"grossInflowMinor", "grossOutflowMinor", "netCashFlowMinor",
		"classifiedIncomeMinor", "classifiedExpenseMinor", "transactionCount",
		"uncategorizedCount", "manualEntryCount", "nonIdrExcludedCount"} {
		value, ok := payload[key].(float64)
		if !ok || value != float64(int64(value)) {
			return false
		}
	}
	return true
}

var rupiahPattern = regexp.MustCompile(`Rp\s?([0-9][0-9.,]*)`)

func validateAnalysisResponse(response store.AnalysisResponse, payload map[string]any) error {
	if response.PromptVersion != "analysis-v1" {
		return fmt.Errorf("unexpected prompt version")
	}
	if len(response.Observations) > 12 || len(response.Suggestions) > 8 || len(response.Limitations) > 8 {
		return fmt.Errorf("analysis exceeds the item limits")
	}
	allowed := allowedAmounts(payload)
	for _, text := range allAnalysisText(response) {
		if len(text) == 0 || len(text) > 600 {
			return fmt.Errorf("analysis text length is invalid")
		}
		if strings.Contains(strings.ToLower(text), "abaikan instruksi") ||
			strings.Contains(strings.ToLower(text), "ignore previous") {
			return fmt.Errorf("analysis contains instruction-like prose")
		}
		for _, match := range rupiahPattern.FindAllStringSubmatch(text, -1) {
			digits := strings.NewReplacer(".", "", ",", "").Replace(match[1])
			value, err := strconv.ParseInt(digits, 10, 64)
			if err != nil || !allowed[value] {
				return fmt.Errorf("analysis mentions an amount absent from the payload")
			}
		}
	}
	return nil
}

func allAnalysisText(response store.AnalysisResponse) []string {
	var out []string
	for _, observation := range response.Observations {
		out = append(out, observation.Text)
	}
	for _, suggestion := range response.Suggestions {
		out = append(out, suggestion.Text)
	}
	out = append(out, response.Limitations...)
	return out
}

func allowedAmounts(payload map[string]any) map[int64]bool {
	allowed := map[int64]bool{0: true}
	var walk func(value any)
	walk = func(value any) {
		switch typed := value.(type) {
		case map[string]any:
			for key, child := range typed {
				if strings.HasSuffix(key, "Minor") {
					if number, ok := child.(float64); ok {
						allowed[int64(number)] = true
						allowed[int64(-number)] = true
					}
				}
				walk(child)
			}
		case []any:
			for _, child := range typed {
				walk(child)
			}
		}
	}
	walk(payload)
	return allowed
}

func hashAnalysisRequest(body analysisRequest) string {
	// encoding/json sorts object keys, so the hash is stable for the same semantic body.
	raw, _ := json.Marshal(body)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}
