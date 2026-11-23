package httpapi

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/categoryllm"
	"github.com/pam/recap/backend-go/internal/store"
)

// CategoryDrainInterval is the plan's 2-hour drain target. It is a best-effort target: the client
// polls, the server decides, and a poll that arrives early is answered without a provider call.
const CategoryDrainInterval = 2 * time.Hour

// CategoryDrainBatch is the maximum number of unique merchants one drain may classify.
const CategoryDrainBatch = 5

// maxEnqueueIDs bounds one enqueue request so a whole month cannot arrive as a single body.
const maxEnqueueIDs = 50

// enqueueCategoryJobs records eligible candidates without calling the provider. The client calls it
// after a financial commit; eligibility is re-checked here because the client is not trusted to
// decide what costs money.
func (s *Server) enqueueCategoryJobs(w http.ResponseWriter, r *http.Request) {
	var body categoryJobsRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	if !s.autoCategoryEnabled() {
		writeError(w, http.StatusServiceUnavailable, "category_unavailable", "Category classifier is not enabled", correlationOf(r))
		return
	}
	info, _ := authFrom(r)
	if !s.autoCategoryConsented(r) {
		writeError(w, http.StatusForbidden, "consent_required", "AI categorization consent has not been granted", correlationOf(r))
		return
	}
	if len(body.TransactionIDs) == 0 || len(body.TransactionIDs) > maxEnqueueIDs {
		writeError(w, http.StatusBadRequest, "invalid_request", "transactionIds must contain 1..50 ids", correlationOf(r))
		return
	}
	now := s.Now()
	seen := map[string]bool{}
	queued := 0
	for _, id := range body.TransactionIDs {
		if seen[id] {
			continue
		}
		seen[id] = true
		tx, err := s.Store.GetTransaction(r.Context(), info.OwnerID, id)
		if err != nil {
			if errors.Is(err, store.ErrNotFound) {
				continue
			}
			s.databaseOrInternal(w, r, err)
			return
		}
		if !autoCategoryEligible(tx) {
			continue
		}
		key, ok := merchantKeyFor(tx)
		if !ok {
			continue
		}
		inserted, err := s.Store.EnqueueAutoCategoryJob(r.Context(), info.OwnerID, tx.ID, tx.Revision, key, now)
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		if inserted {
			queued++
		}
	}
	pending, err := s.Store.CountQueuedCategoryJobs(r.Context(), info.OwnerID)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"queued": queued, "pending": pending})
}

// drainCategoryQueue is the server-side scheduler: it answers early polls cheaply, classifies at
// most CategoryDrainBatch unique merchants, carries the remainder over to the next due time, and
// leaves the backlog intact when the budget is exhausted.
func (s *Server) drainCategoryQueue(w http.ResponseWriter, r *http.Request) {
	if !s.autoCategoryEnabled() {
		writeError(w, http.StatusServiceUnavailable, "category_unavailable", "Category classifier is not enabled", correlationOf(r))
		return
	}
	info, _ := authFrom(r)
	if !s.autoCategoryConsented(r) {
		writeError(w, http.StatusForbidden, "consent_required", "AI categorization consent has not been granted", correlationOf(r))
		return
	}
	ownerID := info.OwnerID
	now := s.Now()
	pending, err := s.Store.CountQueuedCategoryJobs(r.Context(), ownerID)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	state, err := s.Store.CategoryQueueStateFor(r.Context(), ownerID)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	// An empty queue is never a reason to look at a due time or call the provider.
	if pending == 0 {
		if err := s.Store.SaveCategoryQueueState(r.Context(), ownerID, nil, &now, "empty", now); err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		writeJSON(w, http.StatusOK, drainResponse("empty", 0, 0, nil, nil))
		return
	}
	if state.NextDrainAt != nil && now.Before(*state.NextDrainAt) {
		writeJSON(w, http.StatusOK, drainResponse("not_due", 0, pending, state.NextDrainAt, nil))
		return
	}

	jobs, err := s.Store.ListQueuedCategoryJobs(r.Context(), ownerID, CategoryDrainBatch)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	var processed []store.CategoryJob
	var pendingPrepare []preparedCategory
	for _, job := range jobs {
		leaseToken, claimed, err := s.Store.ClaimCategoryJob(r.Context(), ownerID, job.ID, now, store.CategoryLeaseDuration)
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		if !claimed {
			// Another drain or a manual retry owns this job; it is not lost, just not ours.
			continue
		}
		job.LeaseToken = leaseToken
		prepared, eligible, err := s.prepareForClassify(r.Context(), ownerID, job)
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		if !eligible {
			processed = append(processed, prepared.job)
			continue
		}
		pendingPrepare = append(pendingPrepare, prepared)
	}

	result := "drained"
	if len(pendingPrepare) > 0 {
		classified, outcome, err := s.classifyGroupWith(r.Context(), ownerID, pendingPrepare, true)
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		processed = append(processed, classified...)
		if outcome.budgetDenied && !outcome.sawSuccess {
			// Denied before any call: nothing was spent and the jobs stay PENDING as backlog.
			result = "budget_exhausted"
		}
	}
	next := now.Add(CategoryDrainInterval)
	if err := s.Store.SaveCategoryQueueState(r.Context(), ownerID, &next, &now, result, now); err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	remaining, err := s.Store.CountQueuedCategoryJobs(r.Context(), ownerID)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, drainResponse(result, len(processed), remaining, &next, categoryJobsJSON(processed)))
}

func drainResponse(state string, drained, pending int, next *time.Time, jobs []categoryJobJSON) map[string]any {
	body := map[string]any{"state": state, "drained": drained, "pending": pending}
	if next != nil {
		body["nextDueAt"] = next.UTC().Format(time.RFC3339)
	}
	if jobs != nil {
		body["jobs"] = jobs
	}
	return body
}

func (s *Server) autoCategoryEnabled() bool {
	return s.CategoryClassifier != nil && s.Config.CategoryLLM.Enabled
}

func (s *Server) autoCategoryConsented(r *http.Request) bool {
	info, ok := authFrom(r)
	if !ok {
		return false
	}
	consent, err := s.Store.LatestConsent(r.Context(), info.OwnerID, "AI_CATEGORIZATION")
	return err == nil && consent.Decision == "GRANTED"
}

// autoCategoryEligible mirrors the manual path: a USER decision, an already-categorized row, an
// internal transfer or a personal counterparty is never enqueued automatically.
func autoCategoryEligible(tx store.Transaction) bool {
	if tx.CategoryOrigin == "USER" {
		return false
	}
	if tx.CategoryCode != "" && tx.CategoryCode != "uncategorized" {
		return false
	}
	if tx.IsInternalTransfer {
		return false
	}
	return true
}

// merchantKeyFor returns the coalescing key for a transaction, or false when the merchant label is
// not safe to send anywhere (the row then waits for an explicit manual attempt instead).
func merchantKeyFor(tx store.Transaction) (string, bool) {
	merchant, err := categoryllm.SanitizeMerchant(tx.Sensitive.MerchantLabel)
	if err != nil {
		return "", false
	}
	method := "OTHER"
	if strings.Contains(strings.ToUpper(tx.Sensitive.Description), "QRIS") {
		method = "QRIS"
	}
	return store.CacheKey(merchant, tx.Direction, method), true
}
