package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/pam/recap/backend-go/internal/analysis"
	"github.com/pam/recap/backend-go/internal/store"
)

type analysisRequest struct {
	MonthID       string          `json:"monthId"`
	InputRevision int             `json:"inputRevision"`
	Payload       json.RawMessage `json:"payload"`
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
	if len(body.Payload) == 0 {
		writeError(w, http.StatusBadRequest, "invalid_request", "payload is required", correlationOf(r))
		return
	}
	clientPayload, err := analysis.ParseClientPayload(body.Payload)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", err.Error(), correlationOf(r))
		return
	}
	if clientPayload.MonthID != body.MonthID {
		writeError(w, http.StatusBadRequest, "invalid_request", "payload.monthId must match monthId", correlationOf(r))
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
	snapshot, err := analysis.Build(r.Context(), s.Store, info.OwnerID, body.MonthID)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	if body.InputRevision != snapshot.Revision {
		writeError(w, http.StatusConflict, "revision_conflict",
			fmt.Sprintf("inputRevision is stale; server revision is %d", snapshot.Revision), correlationOf(r))
		return
	}
	requestHash := hashAnalysisRequest(body.MonthID, body.InputRevision, snapshot.Hash, body.Payload)
	existing, existingHash, err := s.Store.FindAnalysisByIdempotency(r.Context(), info.OwnerID, idempotencyKey)
	if err == nil {
		if existingHash != requestHash {
			writeError(w, http.StatusConflict, "idempotency_conflict", "This Idempotency-Key was already used with a different body", correlationOf(r))
			return
		}
		// A replay after withdrawal/deletion must not be published as a fresh AVAILABLE report.
		if current, cErr := s.Store.LatestConsent(r.Context(), info.OwnerID, "AI_PROCESSING"); cErr != nil ||
			current.ID != consent.ID || current.Decision != "GRANTED" {
			writeError(w, http.StatusConflict, "consent_required", "AI consent changed while the analysis was running", correlationOf(r))
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
		snapshot.Revision, analysis.PromptVersion, provider.ID(), provider.Model(), snapshot.Hash, consent.ID)
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
	payloadMap := snapshot.Payload.ToMap()
	var response store.AnalysisResponse
	var providerErr error
	regenerations := 0
	for attempt := 0; attempt < 2; attempt++ {
		allowed, err := s.Store.ReserveProviderCost(r.Context(), s.Config.Limits.DailyCeilingUSD,
			s.Config.Limits.UnknownCallUSD, s.Now())
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		if !allowed {
			writeError(w, http.StatusTooManyRequests, "daily_ceiling_reached", "Daily AI cost ceiling reached", correlationOf(r))
			return
		}
		response, providerErr = provider.Analyze(r.Context(), payloadMap)
		s.recordProviderUsage(r, info.OwnerID, response)
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
		writeError(w, http.StatusBadGateway, "provider_unavailable", "Analysis provider failed", correlationOf(r))
		return
	}
	if err := validateAnalysisResponse(response, snapshot.Payload); err != nil {
		writeError(w, http.StatusUnprocessableEntity, "invalid_analysis_output", err.Error(), correlationOf(r))
		return
	}
	record, err := s.Store.InsertAnalysisGuarded(r.Context(), store.AnalysisFinalizeInput{
		OwnerID: info.OwnerID, SessionID: info.SessionID, MonthID: body.MonthID,
		InputRevision: snapshot.Revision, SnapshotHash: snapshot.Hash, ConsentID: consent.ID,
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
	writeJSON(w, http.StatusOK, analysisJSON(record))
}

func (s *Server) recordProviderUsage(r *http.Request, ownerID string, response store.AnalysisResponse) {
	prompt, completion := int64(0), int64(0)
	known := response.PromptTokens != nil && response.CompletionTokens != nil
	if known {
		prompt, completion = *response.PromptTokens, *response.CompletionTokens
	}
	cost := float64(prompt)/1000*s.Config.Limits.PromptPer1K +
		float64(completion)/1000*s.Config.Limits.CompletionPer1K
	reserved := s.Config.Limits.UnknownCallUSD
	if !known {
		cost = reserved
	}
	_ = s.Store.RecordUsage(r.Context(), ownerID, prompt, completion, cost, reserved, known, s.Now())
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
		"status":           record.Status,
	}
}

func hashAnalysisRequest(monthID string, revision int, snapshotHash string, clientPayload json.RawMessage) string {
	raw := fmt.Sprintf("%s|%d|%s|%s", monthID, revision, snapshotHash, string(clientPayload))
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}
