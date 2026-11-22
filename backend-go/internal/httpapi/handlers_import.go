package httpapi

import (
	"errors"
	"net/http"
	"time"

	"github.com/pam/recap/backend-go/internal/store"
)

type importJobCreate struct {
	MonthID         string `json:"monthId"`
	TemplateVersion string `json:"templateVersion"`
}

type importCandidateJSON struct {
	Fingerprint   string         `json:"fingerprint"`
	Status        string         `json:"status"`
	Reason        string         `json:"reason"`
	Direction     string         `json:"direction"`
	AmountMinor   int64          `json:"amountMinor"`
	EventAt       string         `json:"eventAt"`
	CategoryID    string         `json:"categoryId"`
	ParserVersion string         `json:"parserVersion"`
	Confidence    float64        `json:"confidence"`
	Description   string         `json:"description"`
	MerchantLabel string         `json:"merchantLabel"`
	Attributes    map[string]any `json:"attributes"`
}

type importBatchRequest struct {
	BatchKey    string                `json:"batchKey"`
	PageOrdinal int                   `json:"pageOrdinal"`
	Candidates  []importCandidateJSON `json:"candidates"`
}

type importFinalizeRequest struct {
	State     string `json:"state"`
	ErrorCode string `json:"errorCode"`
}

func (s *Server) createImportJob(w http.ResponseWriter, r *http.Request) {
	var body importJobCreate
	if !decodeJSON(w, r, &body) {
		return
	}
	info, _ := authFrom(r)
	if err := s.requireConsent(r, info.OwnerID, "GMAIL_READ"); err != nil {
		writeError(w, http.StatusForbidden, "consent_required", "Gmail consent has not been granted", correlationOf(r))
		return
	}
	job, err := s.Store.CreateImportJob(r.Context(), info.OwnerID, body.MonthID, body.TemplateVersion, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, importJobJSON(job))
}

func (s *Server) getImportJob(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	job, err := s.Store.GetImportJob(r.Context(), info.OwnerID, r.PathValue("id"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, importJobJSON(job))
}

func (s *Server) importBatch(w http.ResponseWriter, r *http.Request) {
	var body importBatchRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	info, _ := authFrom(r)
	if err := s.requireConsent(r, info.OwnerID, "GMAIL_READ"); err != nil {
		writeError(w, http.StatusForbidden, "consent_required", "Gmail consent has not been granted", correlationOf(r))
		return
	}
	jobID := r.PathValue("id")
	job, err := s.Store.GetImportJob(r.Context(), info.OwnerID, jobID)
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	candidates := make([]store.ImportCandidate, 0, len(body.Candidates))
	for _, candidate := range body.Candidates {
		eventAt, err := time.Parse(time.RFC3339, candidate.EventAt)
		if candidate.Status == "PARSED" && err != nil {
			writeError(w, http.StatusBadRequest, "invalid_request", "candidate eventAt must be RFC3339", correlationOf(r))
			return
		}
		candidates = append(candidates, store.ImportCandidate{
			Fingerprint: candidate.Fingerprint, Status: candidate.Status, Reason: candidate.Reason,
			Direction: candidate.Direction, AmountMinor: candidate.AmountMinor, EventAt: eventAt,
			CategoryID: candidate.CategoryID, ParserVersion: candidate.ParserVersion,
			Confidence: candidate.Confidence, Description: candidate.Description,
			MerchantLabel: candidate.MerchantLabel, Attributes: candidate.Attributes,
		})
	}
	requestHash := store.HashImportBatch(job.MonthID, body.BatchKey, body.PageOrdinal, candidates)
	counts, replayed, err := s.Store.ImportBatch(r.Context(), info.OwnerID, jobID, body.BatchKey,
		requestHash, body.PageOrdinal, candidates, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	updated, err := s.Store.GetImportJob(r.Context(), info.OwnerID, jobID)
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	revision, err := s.Store.MonthRevision(r.Context(), info.OwnerID, updated.MonthID)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	w.Header().Set("Idempotency-Replayed", boolText(replayed))
	writeJSON(w, http.StatusOK, map[string]any{
		"batchKey": body.BatchKey, "monthId": updated.MonthID, "inputRevision": revision,
		"matched": counts.Matched, "imported": counts.Imported, "duplicates": counts.Duplicates,
		"skipped": counts.Skipped, "unresolved": counts.Unresolved,
		"jobState": updated.State,
	})
}

func (s *Server) finalizeImportJob(w http.ResponseWriter, r *http.Request) {
	var body importFinalizeRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	info, _ := authFrom(r)
	job, err := s.Store.FinalizeImportJob(r.Context(), info.OwnerID, r.PathValue("id"),
		body.State, body.ErrorCode, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, importJobJSON(job))
}

func (s *Server) requireConsent(r *http.Request, ownerID, purpose string) error {
	consent, err := s.Store.LatestConsent(r.Context(), ownerID, purpose)
	if err != nil || consent.Decision != "GRANTED" {
		return errors.New("consent_required")
	}
	return nil
}

func importJobJSON(job store.ImportJob) map[string]any {
	return map[string]any{
		"id": job.ID, "monthId": job.MonthID, "state": job.State,
		"templateVersion": job.TemplateVersion, "pagesProcessed": job.PagesProcessed,
		"matched": job.Matched, "imported": job.Imported, "duplicates": job.Duplicates,
		"skipped": job.Skipped, "unresolved": job.Unresolved,
		"startedAt": job.StartedAt, "finishedAt": job.FinishedAt, "errorCode": job.ErrorCode,
	}
}

func boolText(value bool) string {
	if value {
		return "true"
	}
	return "false"
}

func (s *Server) internalSweep(w http.ResponseWriter, r *http.Request) {
	if s.Config.InternalSweepToken == "" {
		writeError(w, http.StatusNotFound, "not_found", "Not found", correlationOf(r))
		return
	}
	if !secureEqual(r.Header.Get("x-internal-token"), s.Config.InternalSweepToken) {
		writeError(w, http.StatusUnauthorized, "unauthorized", "Invalid internal token", correlationOf(r))
		return
	}
	completed, err := s.Store.SweepDeletions(r.Context(), s.Now())
	if err != nil {
		writeError(w, http.StatusConflict, "conflict", err.Error(), correlationOf(r))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"completed": completed})
}
