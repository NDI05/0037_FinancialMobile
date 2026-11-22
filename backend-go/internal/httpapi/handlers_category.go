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
	ID               string  `json:"id"`
	TransactionID    string  `json:"transactionId"`
	ExpectedRevision int     `json:"expectedRevision"`
	State            string  `json:"state"`
	CategoryCode     string  `json:"categoryCode,omitempty"`
	Confidence       float64 `json:"confidence,omitempty"`
	Provider         string  `json:"provider,omitempty"`
	Model            string  `json:"model,omitempty"`
	PromptVersion    string  `json:"promptVersion,omitempty"`
	TaxonomyVersion  string  `json:"taxonomyVersion,omitempty"`
	ReasonCode       string  `json:"reasonCode,omitempty"`
	ErrorCode        string  `json:"errorCode,omitempty"`
}

func categoryJobResponse(j store.CategoryJob) categoryJobJSON {
	return categoryJobJSON{j.ID, j.TransactionID, j.ExpectedRevision, j.State, j.CategoryCode,
		j.Confidence, j.Provider, j.Model, j.PromptVersion, j.TaxonomyVersion, j.ReasonCode, j.ErrorCode}
}

// providerAttempt is the outcome of one provider round: the ledger is always settled, either the
// budget gate denied the attempt (allowed=false) or a reservation was made and settled (allowed=true).
type providerAttempt struct {
	response    categoryllm.Response
	allowed     bool
	classifyErr error
	ledgerErr   error
}

func (s *Server) getCategoryJob(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	job, err := s.Store.GetCategoryJob(r.Context(), info.OwnerID, r.PathValue("id"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, categoryJobResponse(job))
}

type preparedCategory struct {
	tx        store.Transaction
	job       store.CategoryJob
	merchant  string
	method    string
	candidate categoryllm.Candidate
}

// personalOrTransferShaped rejects counterparties that are not business merchants: internal
// transfers and any non-QRIS description carrying a transfer marker are never sent to the provider
// — clearing a string sanitizer is not enough for a personal counterparty to become a merchant.
func (s *Server) personalOrTransferShaped(tx store.Transaction) bool {
	if tx.IsInternalTransfer {
		return true
	}
	d := strings.ToUpper(tx.Sensitive.Description)
	if strings.Contains(d, "QRIS") {
		return false
	}
	return strings.Contains(d, "TRANSFER") || strings.Contains(d, "BI-FAST")
}

func (s *Server) createCategoryJobs(w http.ResponseWriter, r *http.Request) {
	var body categoryJobsRequest
	if !decodeJSON(w, r, &body) {
		return
	}
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
	var jobs []store.CategoryJob
	var pending []preparedCategory
	for _, id := range body.TransactionIDs {
		if seen[id] {
			continue
		}
		seen[id] = true
		tx, err := s.Store.GetTransaction(r.Context(), info.OwnerID, id)
		if err != nil {
			writeError(w, http.StatusNotFound, "not_found", "transaction not found", correlationOf(r))
			return
		}
		job, err := s.Store.CreateCategoryJob(r.Context(), info.OwnerID, id, tx.Revision, s.Now())
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		if job.State == "FAILED" {
			// An explicit user retry: a failed job returns to PENDING and is re-attempted under
			// the same budget gates. Concurrent re-taps are safe because only one claim wins.
			if reset, rerr := s.Store.ResetFailedCategoryJob(r.Context(), info.OwnerID, job.ID, s.Now()); rerr == nil {
				job = reset
			}
		}
		if job.State != "PENDING" {
			// Duplicate tap: replay the existing job id; the winning request calls the provider.
			jobs = append(jobs, job)
			continue
		}
		claimed, err := s.Store.ClaimCategoryJob(r.Context(), info.OwnerID, job.ID, s.Now(), store.CategoryLeaseDuration)
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		if !claimed {
			job, err = s.Store.GetCategoryJob(r.Context(), info.OwnerID, job.ID)
			if err != nil {
				s.databaseOrInternal(w, r, err)
				return
			}
			jobs = append(jobs, job)
			continue
		}
		prepared, eligible, err := s.prepareForClassify(r.Context(), info.OwnerID, job)
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		if !eligible {
			jobs = append(jobs, prepared.job)
			continue
		}
		pending = append(pending, prepared)
	}
	if len(pending) > 0 {
		processed, outcome, err := s.classifyGroup(r.Context(), info.OwnerID, pending)
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		jobs = append(jobs, processed...)
		if outcome.budgetDenied && !outcome.sawSuccess {
			writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "budget_exhausted", "jobs": categoryJobsJSON(jobs)})
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"jobs": categoryJobsJSON(jobs)})
}

// prepareForClassify loads the transaction for a claimed job and either finishes the job
// immediately (NEEDS_REVIEW / FAILED with a typed error code) or arms it for provider
// classification. All finishing writes are surfaced here: a persistence failure must fail the
// request loudly instead of reporting success with a RUNNING job left behind.
func (s *Server) prepareForClassify(ctx context.Context, ownerID string, job store.CategoryJob) (preparedCategory, bool, error) {
	tx, err := s.Store.GetTransaction(ctx, ownerID, job.TransactionID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			fj, ferr := s.finishJob(ctx, ownerID, job.ID, "FAILED", "", "", "transaction_not_found", 0)
			if ferr != nil {
				return preparedCategory{}, false, ferr
			}
			return preparedCategory{job: fj}, false, nil
		}
		return preparedCategory{}, false, err
	}
	if tx.Revision != job.ExpectedRevision {
		// A concurrent edit (USER override, re-import) moved the revision: the job is stale.
		fj, ferr := s.finishJob(ctx, ownerID, job.ID, "FAILED", "", "", "stale_job", 0)
		if ferr != nil {
			return preparedCategory{}, false, ferr
		}
		return preparedCategory{job: fj}, false, nil
	}
	if tx.CategoryOrigin == "USER" || (tx.CategoryCode != "" && tx.CategoryCode != "uncategorized") {
		fj, ferr := s.finishJob(ctx, ownerID, job.ID, "NEEDS_REVIEW", "", "not_eligible", "", 0)
		if ferr != nil {
			return preparedCategory{}, false, ferr
		}
		return preparedCategory{job: fj}, false, nil
	}
	if s.personalOrTransferShaped(tx) {
		fj, ferr := s.finishJob(ctx, ownerID, job.ID, "NEEDS_REVIEW", "", "not_eligible_personal_transfer", "", 0)
		if ferr != nil {
			return preparedCategory{}, false, ferr
		}
		return preparedCategory{job: fj}, false, nil
	}
	merchant, err := categoryllm.SanitizeMerchant(tx.Sensitive.MerchantLabel)
	if err != nil {
		fj, ferr := s.finishJob(ctx, ownerID, job.ID, "NEEDS_REVIEW", "uncategorized", "unsafe_merchant", "", 0)
		if ferr != nil {
			return preparedCategory{}, false, ferr
		}
		return preparedCategory{job: fj}, false, nil
	}
	method := "OTHER"
	if strings.Contains(strings.ToUpper(tx.Sensitive.Description), "QRIS") {
		method = "QRIS"
	}
	allowed := []string{"uncategorized"}
	if tx.Direction == "CREDIT" {
		allowed = append(allowed, "income", "transfer")
	} else {
		allowed = append(allowed, "housing", "utilities", "transport", "food", "health", "education", "shopping", "other_expense", "transfer")
	}
	candidate := categoryllm.Candidate{Merchant: merchant, Direction: tx.Direction, Method: method, Categories: allowed}
	return preparedCategory{tx: tx, job: job, merchant: merchant, method: method, candidate: candidate}, true, nil
}

type budgetOutcome struct {
	budgetDenied bool
	sawSuccess   bool
}

// classifyGroup runs the durable pipeline for a set of armed jobs. Providers are coalesced by
// merchant+context within the batch (and across requests via the cache), oversized batches are
// split into bounded provider calls, every outbound attempt passes the budget gate, results are
// applied atomically (consent/deletion/revision guarded) and every job ends terminal
// (CLASSIFIED/NEEDS_REVIEW/FAILED) or is durably requeued to PENDING for one bounded retry.
func (s *Server) classifyGroup(ctx context.Context, ownerID string, pending []preparedCategory) ([]store.CategoryJob, budgetOutcome, error) {
	classifier := s.CategoryClassifier
	var out []store.CategoryJob
	var outcome budgetOutcome
	byKey := map[string][]preparedCategory{}
	var keyOrder []string
	for _, p := range pending {
		key := store.CacheKey(p.merchant, p.tx.Direction, p.method)
		code, review, reason, err := s.Store.CategoryCacheGet(ctx, ownerID, key, p.tx.Direction, p.method,
			categoryllm.TaxonomyVersion, classifier.Model(), categoryllm.PromptVersion)
		if err != nil {
			return out, outcome, err
		}
		if code != "" {
			fj, err := s.applyAndFinish(ctx, ownerID, p, code, review, reason)
			if err != nil {
				return out, outcome, err
			}
			out = append(out, fj)
			continue
		}
		if _, ok := byKey[key]; !ok {
			keyOrder = append(keyOrder, key)
		}
		byKey[key] = append(byKey[key], p)
	}
	if len(keyOrder) == 0 {
		return out, outcome, nil
	}
	unique := make([]categoryllm.Candidate, 0, len(keyOrder))
	idxOf := map[string]int{}
	for i, key := range keyOrder {
		unique = append(unique, byKey[key][0].candidate)
		t := byKey[key][0].candidate
		idxOf[t.Merchant+"\x1f"+t.Direction+"\x1f"+t.Method] = i
	}
	for _, group := range classifier.SplitCandidates(unique) {
		var shares []int
		for _, c := range group {
			shares = append(shares, idxOf[c.Merchant+"\x1f"+c.Direction+"\x1f"+c.Method])
		}
		if !classifier.FitsInputBudget(group) {
			// Even a single candidate exceeds the input cap: never call the provider or reserve spend.
			for _, si := range shares {
				for _, p := range byKey[keyOrder[si]] {
					fj, err := s.finishJob(ctx, ownerID, p.job.ID, "NEEDS_REVIEW", "uncategorized", "input_too_large", "", 0)
					if err != nil {
						return out, outcome, err
					}
					out = append(out, fj)
				}
			}
			continue
		}
		attempt := s.providerAttempt(ctx, ownerID, group)
		if attempt.ledgerErr != nil {
			return out, outcome, attempt.ledgerErr
		}
		if !attempt.allowed {
			outcome.budgetDenied = true
			for _, si := range shares {
				for _, p := range byKey[keyOrder[si]] {
					fj, err := s.finishJob(ctx, ownerID, p.job.ID, "FAILED", "", "", "budget_exhausted", 0)
					if err != nil {
						return out, outcome, err
					}
					out = append(out, fj)
				}
			}
			continue
		}
		outcome.sawSuccess = true
		if attempt.classifyErr != nil {
			code := classifyErrorCode(attempt.classifyErr)
			transient := categoryllm.IsProviderTransient(attempt.classifyErr)
			for _, si := range shares {
				for _, p := range byKey[keyOrder[si]] {
					if transient && p.job.RetryCount < 1 {
						rj, err := s.Store.RequeueCategoryJob(ctx, ownerID, p.job.ID, s.Now())
						if err != nil {
							return out, outcome, err
						}
						out = append(out, rj)
					} else {
						fj, err := s.finishJob(ctx, ownerID, p.job.ID, "FAILED", "", "", code, 0)
						if err != nil {
							return out, outcome, err
						}
						out = append(out, fj)
					}
				}
			}
			continue
		}
		resultByShare := map[int]categoryllm.Result{}
		for i, res := range attempt.response.Results {
			resultByShare[shares[i]] = res
		}
		for _, si := range shares {
			res := resultByShare[si]
			first := byKey[keyOrder[si]][0]
			if err := s.Store.CategoryCachePut(ctx, ownerID, keyOrder[si], first.tx.Direction, first.method,
				categoryllm.TaxonomyVersion, classifier.Model(), categoryllm.PromptVersion,
				res.CategoryCode, res.ReasonCode, res.NeedsReview, s.Now()); err != nil {
				return out, outcome, err
			}
			for _, p := range byKey[keyOrder[si]] {
				fj, err := s.applyAndFinish(ctx, ownerID, p, res.CategoryCode, res.NeedsReview, res.ReasonCode)
				if err != nil {
					return out, outcome, err
				}
				out = append(out, fj)
			}
		}
	}
	return out, outcome, nil
}

// providerAttempt makes exactly one provider call through the budget gate and the provider
// concurrency semaphore. The reservation is always settled — known usage when the provider reports
// it, otherwise conservatively as UNKNOWN — so a failed attempt can never silently leak reserved
// spend or leave the ledger unbalanced.
func (s *Server) providerAttempt(ctx context.Context, ownerID string, candidates []categoryllm.Candidate) providerAttempt {
	reserve := (categoryllm.MaxInputTokens*s.Config.CategoryLLM.PromptPer1K +
		categoryllm.MaxCompletionTokens*s.Config.CategoryLLM.CompletionPer1K) / 1000
	reservationID, ok, err := s.Store.ReserveCategoryCall(ctx, ownerID, s.Config.CategoryLLM.OwnerAttemptsDay,
		s.Config.CategoryLLM.InstallAttemptsDay, s.Config.CategoryLLM.DailyCeilingUSD,
		s.Config.CategoryLLM.MonthlyCeilingUSD, reserve, s.Now())
	if err != nil {
		return providerAttempt{ledgerErr: err}
	}
	if !ok {
		return providerAttempt{allowed: false}
	}
	select {
	case s.providerSlots <- struct{}{}:
		defer func() { <-s.providerSlots }()
	case <-ctx.Done():
		if serr := s.Store.SettleCategoryReservation(ctx, ownerID, reservationID, reserve, reserve, false, s.Now()); serr != nil {
			return providerAttempt{ledgerErr: serr}
		}
		return providerAttempt{allowed: true, classifyErr: ctx.Err()}
	}
	response, classifyErr := s.CategoryClassifier.Classify(ctx, candidates)
	if response.UsageKnown {
		actual := (float64(response.PromptTokens)*s.Config.CategoryLLM.PromptPer1K +
			float64(response.CompletionTokens)*s.Config.CategoryLLM.CompletionPer1K) / 1000
		if serr := s.Store.SettleCategoryReservation(ctx, ownerID, reservationID, actual, actual, true, s.Now()); serr != nil {
			return providerAttempt{ledgerErr: serr}
		}
	} else {
		if serr := s.Store.SettleCategoryReservation(ctx, ownerID, reservationID, reserve, reserve, false, s.Now()); serr != nil {
			return providerAttempt{ledgerErr: serr}
		}
	}
	return providerAttempt{response: response, allowed: true, classifyErr: classifyErr}
}

// applyAndFinish applies a provider/cache result to the transaction (atomically consent- and
// deletion-guarded) and finishes the job. Any store failure is propagated: never report success
// with the job left RUNNING.
func (s *Server) applyAndFinish(ctx context.Context, ownerID string, p preparedCategory, code string, review bool, reason string) (store.CategoryJob, error) {
	classifier := s.CategoryClassifier
	if review || code == "" || code == "uncategorized" {
		return s.finishJob(ctx, ownerID, p.job.ID, "NEEDS_REVIEW", "uncategorized", reason, "", 0)
	}
	if !directionAllows(code, p.tx.Direction, p.candidate.Categories) {
		return s.finishJob(ctx, ownerID, p.job.ID, "NEEDS_REVIEW", "uncategorized", "category_not_in_direction_allowlist", "", 0)
	}
	categoryID, err := s.Store.CategoryIDForCode(ctx, ownerID, code)
	if err != nil {
		return s.finishJob(ctx, ownerID, p.job.ID, "NEEDS_REVIEW", "uncategorized", "unknown_category", "", 0)
	}
	if _, err := s.Store.ApplyLLMCategory(ctx, ownerID, p.tx.ID, p.tx.Revision, categoryID, code,
		classifier.ID(), classifier.Model(), categoryllm.PromptVersion,
		categoryllm.TaxonomyVersion, reason, 0, s.Now()); err != nil {
		if !errors.Is(err, store.ErrRevisionConflict) {
			return store.CategoryJob{}, err
		}
		return s.finishJobConflict(ctx, ownerID, p.job.ID, code, reason)
	}
	return s.finishJob(ctx, ownerID, p.job.ID, "CLASSIFIED", code, reason, "", 0)
}

// finishJobConflict labels apply conflicts. The atomic UPDATE inside ApplyLLMCategory is the
// authoritative guard; the label is a read-only diagnostic between USER override, consent
// withdrawal and pending deletion.
func (s *Server) finishJobConflict(ctx context.Context, ownerID, jobID, code, reason string) (store.CategoryJob, error) {
	label := "stale_or_user_override"
	if c, err := s.Store.LatestConsent(ctx, ownerID, "AI_CATEGORIZATION"); err == nil && c.Decision != "GRANTED" {
		label = "consent_withdrawn"
	} else if pending, err := s.Store.HasPendingDeletion(ctx, ownerID); err == nil && pending {
		label = "deletion_pending"
	}
	return s.finishJob(ctx, ownerID, jobID, "FAILED", code, reason, label, 0)
}

func directionAllows(code, direction string, allowed []string) bool {
	for _, a := range allowed {
		if a == code {
			return true
		}
	}
	return false
}

func classifyErrorCode(err error) string {
	switch {
	case errors.Is(err, categoryllm.ErrInputTooLarge):
		return "input_too_large"
	case errors.Is(err, categoryllm.ErrTruncated):
		return "truncated"
	case errors.Is(err, categoryllm.ErrInvalidOutput):
		return "invalid_output"
	case errors.Is(err, categoryllm.ErrInvalidInput):
		return "invalid_input"
	case categoryllm.IsProviderTransient(err):
		return "provider_transient"
	default:
		return "provider_error"
	}
}

func (s *Server) finishJob(ctx context.Context, ownerID, jobID, state, code, reason, errorCode string, confidence float64) (store.CategoryJob, error) {
	classifier := s.CategoryClassifier
	provider, model := "", ""
	if classifier != nil {
		provider, model = classifier.ID(), classifier.Model()
	}
	if err := s.Store.FinishCategoryJob(ctx, ownerID, jobID, state, code, reason, errorCode, provider,
		model, categoryllm.PromptVersion, categoryllm.TaxonomyVersion, confidence, s.Now()); err != nil {
		return store.CategoryJob{}, err
	}
	return s.Store.GetCategoryJob(ctx, ownerID, jobID)
}

func categoryJobsJSON(jobs []store.CategoryJob) []categoryJobJSON {
	out := make([]categoryJobJSON, 0, len(jobs))
	for _, j := range jobs {
		out = append(out, categoryJobResponse(j))
	}
	return out
}