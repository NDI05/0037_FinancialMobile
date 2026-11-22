package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/pam/recap/backend-go/internal/categoryllm"
	"github.com/pam/recap/backend-go/internal/store"
)

type categoryJobsRequest struct {
	TransactionIDs []string `json:"transactionIds"`
}

type categoryJobJSON struct {
	ID              string  `json:"id"`
	TransactionID   string  `json:"transactionId"`
	ExpectedRevision int    `json:"expectedRevision"`
	State           string  `json:"state"`
	CategoryCode    string  `json:"categoryCode,omitempty"`
	Confidence      float64 `json:"confidence,omitempty"`
	Provider        string  `json:"provider,omitempty"`
	Model           string  `json:"model,omitempty"`
	PromptVersion   string  `json:"promptVersion,omitempty"`
	TaxonomyVersion string  `json:"taxonomyVersion,omitempty"`
	ReasonCode      string  `json:"reasonCode,omitempty"`
	ErrorCode       string  `json:"errorCode,omitempty"`
}

func categoryJobResponse(j store.CategoryJob) categoryJobJSON {
	return categoryJobJSON{j.ID, j.TransactionID, j.ExpectedRevision, j.State, j.CategoryCode,
		j.Confidence, j.Provider, j.Model, j.PromptVersion, j.TaxonomyVersion, j.ReasonCode, j.ErrorCode}
}

func (s *Server) getCategoryJob(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	job, err := s.Store.GetCategoryJob(r.Context(), info.OwnerID, r.PathValue("id"))
	if err != nil { s.mapWriteError(w, r, err); return }
	writeJSON(w, http.StatusOK, categoryJobResponse(job))
}

type preparedCategory struct {
	tx store.Transaction
	job store.CategoryJob
	merchant string
	method string
	candidate categoryllm.Candidate
}

func (s *Server) createCategoryJobs(w http.ResponseWriter, r *http.Request) {
	var body categoryJobsRequest
	if !decodeJSON(w, r, &body) { return }
	info, _ := authFrom(r)
	if s.CategoryClassifier == nil || !s.Config.CategoryLLM.Enabled {
		writeError(w, http.StatusServiceUnavailable, "category_unavailable", "Category classifier is not enabled", correlationOf(r))
		return
	}
	consent, err := s.Store.LatestConsent(r.Context(), info.OwnerID, "AI_CATEGORIZATION")
	if err != nil || consent.Decision != "GRANTED" {
		writeError(w, http.StatusForbidden, "consent_required", "AI categorization consent has not been granted", correlationOf(r))
		return
	}
	if len(body.TransactionIDs) == 0 || len(body.TransactionIDs) > categoryllm.MaxCandidates {
		writeError(w, http.StatusBadRequest, "invalid_request", "transactionIds must contain 1..5 ids", correlationOf(r))
		return
	}
	seen := map[string]bool{}
	var prepared []preparedCategory
	var jobs []store.CategoryJob
	for _, id := range body.TransactionIDs {
		if seen[id] { continue }
		seen[id] = true
		tx, err := s.Store.GetTransaction(r.Context(), info.OwnerID, id)
		if err != nil {
			writeError(w, http.StatusNotFound, "not_found", "transaction not found", correlationOf(r)); return
		}
		job, err := s.Store.CreateCategoryJob(r.Context(), info.OwnerID, id, tx.Revision, s.Now())
		if err != nil { s.databaseOrInternal(w, r, err); return }
		if job.State != "PENDING" {
			jobs = append(jobs, job); continue
		}
		claimed, err := s.Store.ClaimCategoryJob(r.Context(), info.OwnerID, job.ID, s.Now())
		if err != nil { s.databaseOrInternal(w, r, err); return }
		if !claimed {
			job, _ = s.Store.GetCategoryJob(r.Context(), info.OwnerID, job.ID)
			jobs = append(jobs, job); continue
		}
		if tx.CategoryOrigin == "USER" || (tx.CategoryCode != "" && tx.CategoryCode != "uncategorized") {
			_ = s.Store.FinishCategoryJob(r.Context(), info.OwnerID, job.ID, "NEEDS_REVIEW", "", "not_eligible", "", "", "", "", "", 0, s.Now())
			job, _ = s.Store.GetCategoryJob(r.Context(), info.OwnerID, job.ID)
			jobs = append(jobs, job); continue
		}
		merchant, err := categoryllm.SanitizeMerchant(tx.Sensitive.MerchantLabel)
		if err != nil {
			_ = s.Store.FinishCategoryJob(r.Context(), info.OwnerID, job.ID, "NEEDS_REVIEW", "uncategorized", "unsafe_merchant", "", "", "", "", "", 0, s.Now())
			job, _ = s.Store.GetCategoryJob(r.Context(), info.OwnerID, job.ID)
			jobs = append(jobs, job); continue
		}
		method := "OTHER"
		if strings.Contains(strings.ToUpper(tx.Sensitive.Description), "QRIS") { method = "QRIS" }
		allowed := []string{"uncategorized"}
		if tx.Direction == "CREDIT" { allowed = append(allowed, "income", "transfer") } else {
			allowed = append(allowed, "housing","utilities","transport","food","health","education","shopping","other_expense","transfer")
		}
		prepared = append(prepared, preparedCategory{tx: tx, job: job, merchant: merchant, method: method,
			candidate: categoryllm.Candidate{Merchant: merchant, Direction: tx.Direction, Method: method, Categories: allowed}})
	}

	classifier := s.CategoryClassifier
	var call []categoryllm.Candidate
	var callPrepared []preparedCategory
	for _, p := range prepared {
		key := store.CacheKey(p.merchant, p.tx.Direction, p.method)
		code, review, reason, err := s.Store.CategoryCacheGet(r.Context(), info.OwnerID, key, p.tx.Direction, p.method,
			categoryllm.TaxonomyVersion, classifier.Model(), categoryllm.PromptVersion)
		if err == nil && code != "" {
			if appErr := s.applyCategoryResult(r.Context(), info.OwnerID, p, code, review, reason); appErr != nil {
				_ = s.Store.FinishCategoryJob(r.Context(), info.OwnerID, p.job.ID, "FAILED", code, reason, "apply_failed", classifier.ID(), classifier.Model(), categoryllm.PromptVersion, categoryllm.TaxonomyVersion, 0, s.Now())
			}
			job, _ := s.Store.GetCategoryJob(r.Context(), info.OwnerID, p.job.ID)
			jobs = append(jobs, job); continue
		}
		call = append(call, p.candidate); callPrepared = append(callPrepared, p)
	}

	if len(call) > 0 {
		reserve := (1024*s.Config.CategoryLLM.PromptPer1K + 512*s.Config.CategoryLLM.CompletionPer1K) / 1000
		reservationID, ok, err := s.Store.ReserveCategoryCall(r.Context(), info.OwnerID, s.Config.CategoryLLM.OwnerAttemptsDay,
			s.Config.CategoryLLM.InstallAttemptsDay, s.Config.CategoryLLM.DailyCeilingUSD,
			s.Config.CategoryLLM.MonthlyCeilingUSD, reserve, s.Now())
		if err != nil { s.databaseOrInternal(w, r, err); return }
		if !ok {
			for _, p := range callPrepared {
				_ = s.Store.FinishCategoryJob(r.Context(), info.OwnerID, p.job.ID, "FAILED", "", "", "budget_exhausted", classifier.ID(), classifier.Model(), categoryllm.PromptVersion, categoryllm.TaxonomyVersion, 0, s.Now())
				job, _ := s.Store.GetCategoryJob(r.Context(), info.OwnerID, p.job.ID); jobs = append(jobs, job)
			}
			writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "budget_exhausted", "jobs": categoryJobsJSON(jobs)})
			return
		}
		response, providerErr := classifier.Classify(r.Context(), call)
		if response.UsageKnown {
			actual := (float64(response.PromptTokens)*s.Config.CategoryLLM.PromptPer1K +
				float64(response.CompletionTokens)*s.Config.CategoryLLM.CompletionPer1K) / 1000
			_ = s.Store.SettleCategoryReservation(r.Context(), info.OwnerID, reservationID, actual, actual, true, s.Now())
		} else {
			_ = s.Store.SettleCategoryReservation(r.Context(), info.OwnerID, reservationID, reserve, reserve, false, s.Now())
		}
		if providerErr != nil {
			code := "provider_failed"
			if errors.Is(providerErr, categoryllm.ErrTruncated) { code = "provider_truncated" }
			if errors.Is(providerErr, categoryllm.ErrInputTooLarge) { code = "input_too_large" }
			for _, p := range callPrepared {
				_ = s.Store.FinishCategoryJob(r.Context(), info.OwnerID, p.job.ID, "FAILED", "", "", code, classifier.ID(), classifier.Model(), categoryllm.PromptVersion, categoryllm.TaxonomyVersion, 0, s.Now())
				job, _ := s.Store.GetCategoryJob(r.Context(), info.OwnerID, p.job.ID); jobs = append(jobs, job)
			}
			writeJSON(w, http.StatusBadGateway, map[string]any{"error": code, "jobs": categoryJobsJSON(jobs)}); return
		}
		for _, result := range response.Results {
			idx := result.CaseID
			if len(idx) < 2 { continue }
			n := int(idx[1]-'0')
			if n < 0 || n >= len(callPrepared) { continue }
			p := callPrepared[n]
			key := store.CacheKey(p.merchant, p.tx.Direction, p.method)
			_ = s.Store.CategoryCachePut(r.Context(), info.OwnerID, key, p.tx.Direction, p.method,
				categoryllm.TaxonomyVersion, classifier.Model(), categoryllm.PromptVersion,
				result.CategoryCode, result.ReasonCode, result.NeedsReview, s.Now())
			_ = s.applyCategoryResult(r.Context(), info.OwnerID, p, result.CategoryCode, result.NeedsReview, result.ReasonCode)
			job, _ := s.Store.GetCategoryJob(r.Context(), info.OwnerID, p.job.ID); jobs = append(jobs, job)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"jobs": categoryJobsJSON(jobs)})
}

func categoryJobsJSON(jobs []store.CategoryJob) []categoryJobJSON {
	out := make([]categoryJobJSON, len(jobs))
	for i, job := range jobs { out[i] = categoryJobResponse(job) }
	return out
}

func (s *Server) applyCategoryResult(ctx context.Context, ownerID string, p preparedCategory, code string, review bool, reason string) error {
	consent, err := s.Store.LatestConsent(ctx, ownerID, "AI_CATEGORIZATION")
	if err != nil || consent.Decision != "GRANTED" {
		_ = s.Store.FinishCategoryJob(ctx, ownerID, p.job.ID, "CANCELLED", code, reason, "consent_withdrawn", s.CategoryClassifier.ID(), s.CategoryClassifier.Model(), categoryllm.PromptVersion, categoryllm.TaxonomyVersion, 0, s.Now())
		return errors.New("consent_withdrawn")
	}
	if review || code == "uncategorized" {
		return s.Store.FinishCategoryJob(ctx, ownerID, p.job.ID, "NEEDS_REVIEW", "uncategorized", reason, "", s.CategoryClassifier.ID(), s.CategoryClassifier.Model(), categoryllm.PromptVersion, categoryllm.TaxonomyVersion, 0, s.Now())
	}
	allowed := false
	for _, candidate := range p.candidate.Categories { if candidate == code { allowed = true; break } }
	if !allowed {
		return s.Store.FinishCategoryJob(ctx, ownerID, p.job.ID, "NEEDS_REVIEW", "uncategorized", "category_not_in_direction_allowlist", "", s.CategoryClassifier.ID(), s.CategoryClassifier.Model(), categoryllm.PromptVersion, categoryllm.TaxonomyVersion, 0, s.Now())
	}
	categoryID, err := s.Store.CategoryIDForCode(ctx, ownerID, code)
	if err != nil {
		return s.Store.FinishCategoryJob(ctx, ownerID, p.job.ID, "NEEDS_REVIEW", "uncategorized", "unknown_category", "", s.CategoryClassifier.ID(), s.CategoryClassifier.Model(), categoryllm.PromptVersion, categoryllm.TaxonomyVersion, 0, s.Now())
	}
	if _, err := s.Store.ApplyLLMCategory(ctx, ownerID, p.tx.ID, p.tx.Revision, categoryID, code,
		s.CategoryClassifier.ID(), s.CategoryClassifier.Model(), categoryllm.PromptVersion,
		categoryllm.TaxonomyVersion, reason, 0, s.Now()); err != nil {
		return s.Store.FinishCategoryJob(ctx, ownerID, p.job.ID, "FAILED", code, reason, "stale_or_user_override", s.CategoryClassifier.ID(), s.CategoryClassifier.Model(), categoryllm.PromptVersion, categoryllm.TaxonomyVersion, 0, s.Now())
	}
	return s.Store.FinishCategoryJob(ctx, ownerID, p.job.ID, "CLASSIFIED", code, reason, "", s.CategoryClassifier.ID(), s.CategoryClassifier.Model(), categoryllm.PromptVersion, categoryllm.TaxonomyVersion, 0, s.Now())
}

var _ = strings.TrimSpace
