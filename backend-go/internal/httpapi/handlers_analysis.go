package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/analysis"
	"github.com/pam/recap/backend-go/internal/store"
)

type analysisRequest struct {
	MonthID       string          `json:"monthId"`
	InputRevision int             `json:"inputRevision"`
	Payload       json.RawMessage `json:"payload"`
}


// invalidAnalysisRequest writes the contract 400 and records the validation reason, which is a
// field name only (never the payload or an amount), so a failing client can be diagnosed from the
// server log without storing owner data.
func (s *Server) invalidAnalysisRequest(w http.ResponseWriter, r *http.Request, message string) {
	if s.Log != nil {
		s.Log.Warn("analysis request rejected", "reason", message, "correlation_id", correlationOf(r))
	}
	writeError(w, http.StatusBadRequest, "invalid_request", message, correlationOf(r))
}

func (s *Server) createAnalysis(w http.ResponseWriter, r *http.Request) {
	var body analysisRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	if _, _, err := store.ParseMonth(body.MonthID); err != nil {
		s.invalidAnalysisRequest(w, r, "monthId must be YYYY-MM")
		return
	}
	if len(body.Payload) == 0 {
		s.invalidAnalysisRequest(w, r, "payload is required")
		return
	}
	clientPayload, err := analysis.ParseClientPayload(body.Payload)
	if err != nil {
		s.invalidAnalysisRequest(w, r, err.Error())
		return
	}
	if clientPayload.MonthID != body.MonthID {
		s.invalidAnalysisRequest(w, r, "payload.monthId must match monthId")
		return
	}
	idempotencyKey := strings.TrimSpace(r.Header.Get("Idempotency-Key"))
	if idempotencyKey == "" || len(idempotencyKey) > 200 {
		s.invalidAnalysisRequest(w, r, "Idempotency-Key header is required")
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
	requestHash := hashAnalysisRequest(body.MonthID, body.InputRevision, body.Payload)
	existing, existingHash, err := s.Store.FindAnalysisByIdempotency(r.Context(), info.OwnerID, idempotencyKey)
	if err == nil {
		if existingHash != requestHash {
			writeError(w, http.StatusConflict, "idempotency_conflict", "This Idempotency-Key was already used with a different body", correlationOf(r))
			return
		}
		if current, cErr := s.Store.LatestConsent(r.Context(), info.OwnerID, "AI_PROCESSING"); cErr != nil ||
			current.ID != consent.ID || current.Decision != "GRANTED" {
			writeError(w, http.StatusConflict, "consent_required", "AI consent changed while the analysis was running", correlationOf(r))
			return
		}
		dependencyHash, currentRevision, depErr := s.Store.DependencyHash(r.Context(), info.OwnerID, body.MonthID)
		if depErr != nil {
			s.databaseOrInternal(w, r, depErr)
			return
		}
		// A replay may return provenance after a mutation, but the freshness field marks it STALE.
		writeJSON(w, http.StatusOK, analysisJSON(existing, dependencyHash, currentRevision))
		return
	}
	snapshot, err := analysis.BuildConsistent(r.Context(), s.Store, info.OwnerID, body.MonthID)
	if err != nil {
		if errors.Is(err, analysis.ErrSnapshotUnstable) {
			writeError(w, http.StatusConflict, "snapshot_unstable", "The monthly data changed while the snapshot was read", correlationOf(r))
			return
		}
		s.databaseOrInternal(w, r, err)
		return
	}
	if body.InputRevision != snapshot.Revision {
		writeError(w, http.StatusConflict, "revision_conflict",
			fmt.Sprintf("inputRevision is stale; server revision is %d", snapshot.Revision), correlationOf(r))
		return
	}
	provider := s.Provider
	if provider == nil {
		writeError(w, http.StatusBadGateway, "provider_unavailable", "No analysis provider is configured", correlationOf(r))
		return
	}
	cached, err := s.Store.FindAnalysisByCache(r.Context(), info.OwnerID, body.MonthID,
		snapshot.Revision, analysis.PromptVersion, provider.ID(), provider.Model(), snapshot.Hash,
		consent.ID, snapshot.DependencyHash)
	if err == nil {
		writeJSON(w, http.StatusOK, analysisJSON(cached, snapshot.DependencyHash, snapshot.Revision))
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
	payloadMap := snapshot.Payload.ToMap()
	var response store.AnalysisResponse
	var providerErr error
	regenerations := 0
	for attempt := 0; attempt < 2; attempt++ {
		allowed, err := s.Store.ReserveProviderCost(r.Context(), s.Config.Limits.DailyCeilingUSD,
			s.Config.Limits.MaxCallCostUSD, s.Now())
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		if !allowed {
			writeError(w, http.StatusTooManyRequests, "daily_ceiling_reached", "Daily AI cost ceiling reached", correlationOf(r))
			return
		}
		response, providerErr = provider.Analyze(r.Context(), payloadMap)
		if usageErr := s.recordProviderUsage(info.OwnerID, response); usageErr != nil {
			writeError(w, http.StatusInternalServerError, "usage_accounting_failed",
				"Provider usage could not be recorded; the reservation remains", correlationOf(r))
			return
		}
		if providerErr == nil {
			// One structured line per billable call: prompt/completion are the billed counters,
			// reasoning is the reported subset of completion tokens. Aggregated payload only.
			s.Log.Info("analysis provider usage",
				"correlationId", correlationOf(r),
				"provider", provider.ID(),
				"promptTokens", int64OrZero(response.PromptTokens),
				"completionTokens", int64OrZero(response.CompletionTokens),
				"reasoningTokens", int64OrZero(response.ReasoningTokens),
				"totalTokens", int64OrZero(response.TotalTokens))
		}
		if providerErr != nil {
			break
		}
		if err := validateAnalysisResponse(response, snapshot.Payload); err == nil {
			break
		} else if attempt == 1 {
			writeError(w, http.StatusUnprocessableEntity, "invalid_analysis_output", err.Error(), correlationOf(r))
			return
		}
		regenerations = 1
	}
	if providerErr != nil {
		if s.Config.LLM.Provider == "azure" {
			s.Log.Warn("Azure analysis rejected", "correlationId", correlationOf(r), "reason", providerErr.Error())
		}
		writeError(w, http.StatusBadGateway, "provider_unavailable", "Analysis provider failed", correlationOf(r))
		return
	}
	if err := validateAnalysisResponse(response, snapshot.Payload); err != nil {
		writeError(w, http.StatusUnprocessableEntity, "invalid_analysis_output", err.Error(), correlationOf(r))
		return
	}
	record, err := s.Store.InsertAnalysisGuarded(r.Context(), store.AnalysisFinalizeInput{
		OwnerID: info.OwnerID, SessionID: info.SessionID, MonthID: body.MonthID,
		InputRevision: snapshot.Revision, SnapshotHash: snapshot.Hash,
		DependencyHash: snapshot.DependencyHash, ConsentID: consent.ID,
		Provider: provider.ID(), Model: provider.Model(), PromptVersion: analysis.PromptVersion,
		IdempotencyKey: idempotencyKey, RequestHash: requestHash, Response: response,
		RegenerationCount: regenerations, Now: s.Now(),
	})
	if err != nil {
		switch {
		case errors.Is(err, store.ErrConsentChanged):
			writeError(w, http.StatusConflict, "consent_required", "AI consent changed while the analysis was running", correlationOf(r))
		case errors.Is(err, store.ErrDeletionInProgress):
			writeError(w, http.StatusConflict, "deletion_in_progress", "A deletion request is in progress", correlationOf(r))
		case errors.Is(err, store.ErrRevisionConflict):
			writeError(w, http.StatusConflict, "revision_conflict", "The monthly revision changed while the analysis was running", correlationOf(r))
		case errors.Is(err, store.ErrSessionStale):
			writeError(w, http.StatusUnauthorized, "session_stale", "Session is no longer active", correlationOf(r))
		default:
			s.databaseOrInternal(w, r, err)
		}
		return
	}
	writeJSON(w, http.StatusOK, analysisJSON(record, snapshot.DependencyHash, snapshot.Revision))
}

func int64OrZero(value *int64) int64 {
	if value == nil {
		return 0
	}
	return *value
}

func (s *Server) recordProviderUsage(ownerID string, response store.AnalysisResponse) error {
	prompt, completion := int64(0), int64(0)
	known := response.PromptTokens != nil && response.CompletionTokens != nil
	if known {
		prompt, completion = *response.PromptTokens, *response.CompletionTokens
	}
	cost := float64(prompt)/1000*s.Config.Limits.PromptPer1K +
		float64(completion)/1000*s.Config.Limits.CompletionPer1K
	// The reservation is the enforceable max input/output cost, not the lower unknown fallback.
	reserved := s.Config.Limits.MaxCallCostUSD
	if !known {
		cost = reserved
	}
	// Accounting must survive request cancellation: use a bounded background context. On failure
	// the reservation is deliberately left in place; it is conservative and will expire with the
	// day bucket rather than silently under-reporting a billed call.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err := s.Store.RecordUsage(ctx, ownerID, prompt, completion, cost, reserved, known, s.Now())
	if err != nil {
		s.Log.Error("usage accounting failed", "correlationId", "", "error", err.Error())
	}
	return err
}

func (s *Server) getAnalysis(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	monthID := r.PathValue("monthID")
	record, err := s.Store.LatestAnalysis(r.Context(), info.OwnerID, monthID)
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	dependencyHash, currentRevision, err := s.Store.DependencyHash(r.Context(), info.OwnerID, monthID)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, analysisJSON(record, dependencyHash, currentRevision))
}

func analysisJSON(record store.AnalysisRecord, currentDependencyHash string, currentRevision int) map[string]any {
	freshness := "STALE"
	if record.Status == "HISTORICAL" {
		freshness = "HISTORICAL"
	} else if record.Status == "AVAILABLE" && record.DependencyHash != "" &&
		record.DependencyHash == currentDependencyHash && record.InputRevision == currentRevision {
		freshness = "CURRENT"
	}
	return map[string]any{
		"monthId":          record.MonthID,
		"inputRevision":    record.InputRevision,
		"currentRevision":  currentRevision,
		"createdAt":        record.CreatedAt,
		"freshness":        freshness,
		"status":           record.Status,
		"promptVersion":    record.Response.PromptVersion,
		"observations":     record.Response.Observations,
		"suggestions":      record.Response.Suggestions,
		"limitations":      record.Response.Limitations,
		"promptTokens":     record.Response.PromptTokens,
		"completionTokens": record.Response.CompletionTokens,
	}
}

func hashAnalysisRequest(monthID string, revision int, clientPayload json.RawMessage) string {
	raw := fmt.Sprintf("%s|%d|%s", monthID, revision, string(clientPayload))
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}
