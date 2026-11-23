package httpapi

import (
	"context"
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

// CategoryDrainBatch is the maximum number of unique merchants one provider request may classify.
// It is a per-request cap, not a per-window cap: one window may run several bounded batches while
// attempts, tokens, budget and concurrency allow it.
const CategoryDrainBatch = 5

// CategoryDrainMaxBatches bounds how many provider requests one window may run, so a large backlog
// cannot turn into an unbounded burst. Whatever is left is deferred to the next window.
const CategoryDrainMaxBatches = 4

// CategoryCacheFanoutLimit bounds the provider-free pass that applies an already-known merchant
// result to every queued transaction sharing it.
const CategoryCacheFanoutLimit = 50

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
	// A job enqueued after a drain belongs to the next window; the due time is durable on the row
	// so the recovery worker honours it too.
	dueAt := now
	if state, err := s.Store.CategoryQueueStateFor(r.Context(), info.OwnerID); err == nil &&
		state.NextDrainAt != nil && state.NextDrainAt.After(now) {
		dueAt = *state.NextDrainAt
	}
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
		inserted, err := s.Store.EnqueueAutoCategoryJob(r.Context(), info.OwnerID, tx.ID, tx.Revision, key, dueAt, now)
		if err != nil {
			s.databaseOrInternal(w, r, err)
			return
		}
		if inserted {
			queued++
		}
	}
	// A merchant result the cache already knows is applied at once to every queued transaction that
	// shares it: provider-free, and never a reason to wait for another window.
	fanned, err := s.applyCachedQueuedJobs(r.Context(), info.OwnerID, now, CategoryCacheFanoutLimit)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	pending, err := s.Store.CountQueuedCategoryJobs(r.Context(), info.OwnerID)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"queued": queued, "cached": fanned, "pending": pending})
}

// drainOwnerQueue is one bounded window of the category queue. It is shared by the HTTP endpoint
// and the backend scheduler, so an owner whose client never polls still gets drained. Within the
// window it runs up to CategoryDrainMaxBatches batches of at most CategoryDrainBatch unique
// merchants, and the remainder is deferred to the next window.
func (s *Server) drainOwnerQueue(ctx context.Context, ownerID string, now time.Time, ignoreDue bool) (drainOutcome, error) {
	out := drainOutcome{State: "drained"}
	pending, err := s.Store.CountQueuedCategoryJobs(ctx, ownerID)
	if err != nil {
		return out, err
	}
	state, err := s.Store.CategoryQueueStateFor(ctx, ownerID)
	if err != nil {
		return out, err
	}
	next := now.Add(CategoryDrainInterval)
	if pending == 0 {
		// An empty queue has no open window: the first transaction enqueued after it must be
		// eligible at once rather than waiting for a spend window that never spent anything.
		if err := s.Store.SaveCategoryQueueState(ctx, ownerID, nil, &now, "empty", now); err != nil {
			return out, err
		}
		out.State = "empty"
		return out, nil
	}
	if !ignoreDue && state.NextDrainAt != nil && now.Before(*state.NextDrainAt) {
		out.State = "not_due"
		out.Pending = pending
		out.NextDue = state.NextDrainAt
		return out, nil
	}

	var processed []store.CategoryJob
	for batch := 0; batch < CategoryDrainMaxBatches; batch++ {
		jobs, err := s.Store.ListQueuedCategoryJobs(ctx, ownerID, CategoryDrainBatch, now)
		if err != nil {
			return out, err
		}
		if len(jobs) == 0 {
			break
		}
		var pendingPrepare []preparedCategory
		before := len(processed)
		for _, job := range jobs {
			leaseToken, claimed, err := s.Store.ClaimCategoryJob(ctx, ownerID, job.ID, now, store.CategoryLeaseDuration)
			if err != nil {
				return out, err
			}
			if !claimed {
				continue
			}
			job.LeaseToken = leaseToken
			prepared, eligible, err := s.prepareForClassify(ctx, ownerID, job)
			if err != nil {
				return out, err
			}
			if !eligible {
				processed = append(processed, prepared.job)
				continue
			}
			pendingPrepare = append(pendingPrepare, prepared)
		}
		if len(pendingPrepare) > 0 {
			classified, outcome, err := s.classifyGroupWith(ctx, ownerID, pendingPrepare, true)
			if err != nil {
				return out, err
			}
			processed = append(processed, classified...)
			if outcome.budgetDenied && !outcome.sawSuccess {
				out.State = "budget_exhausted"
				break
			}
		}
		// Fan the fresh merchant results out to every queued transaction that shares them: those
		// rows are provider-free cache hits and must not consume the next window's budget.
		if fanned, err := s.applyCachedQueuedJobs(ctx, ownerID, now, CategoryCacheFanoutLimit); err != nil {
			return out, err
		} else if fanned > 0 {
			out.Cached += fanned
		}
		if len(processed) == before {
			// Nothing could be claimed (another drain or a manual retry owns the rows), or every
			// row was provider-free: if provider-free, loop again to pick up the duplicates that
			// now hit the cache; if claimless, stop to avoid a tight spin.
			if len(pendingPrepare) == 0 {
				break
			}
		}
	}
	out.Drained = len(processed) + out.Cached
	out.Jobs = processed
	if err := s.Store.SaveCategoryQueueState(ctx, ownerID, &next, &now, out.State, now); err != nil {
		return out, err
	}
	// Anything that did not fit this window is carried over, not retried seconds later by the
	// recovery tick.
	if _, err := s.Store.DeferQueuedCategoryJobs(ctx, ownerID, next); err != nil {
		return out, err
	}
	remaining, err := s.Store.CountQueuedCategoryJobs(ctx, ownerID)
	if err != nil {
		return out, err
	}
	out.Pending = remaining
	out.NextDue = &next
	return out, nil
}

type drainOutcome struct {
	State   string
	Drained int
	Cached  int
	Pending int
	NextDue *time.Time
	Jobs    []store.CategoryJob
}

// drainCategoryQueue is the client-facing entry point: it answers an early poll cheaply and never
// calls the provider for an empty or not-yet-due queue.
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
	out, err := s.drainOwnerQueue(r.Context(), ownerID, now, false)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, drainResponse(out.State, out.Drained, out.Pending, out.NextDue, categoryJobsJSON(out.Jobs)))
}

// applyCachedQueuedJobs finishes queued transactions whose merchant result is already cached. It
// claims a job only after the cache answered, so a miss stays PENDING with its due time intact and
// no retry budget is spent.
func (s *Server) applyCachedQueuedJobs(ctx context.Context, ownerID string, now time.Time, limit int) (int, error) {
	classifier := s.CategoryClassifier
	if classifier == nil {
		return 0, nil
	}
	jobs, err := s.Store.ListAllQueuedCategoryJobs(ctx, ownerID, limit)
	if err != nil {
		return 0, err
	}
	applied := 0
	for _, job := range jobs {
		tx, err := s.Store.GetTransaction(ctx, ownerID, job.TransactionID)
		if err != nil {
			continue
		}
		if !autoCategoryEligible(tx) {
			continue
		}
		key, ok := merchantKeyFor(tx)
		if !ok {
			continue
		}
		method := "OTHER"
		if strings.Contains(strings.ToUpper(tx.Sensitive.Description), "QRIS") {
			method = "QRIS"
		}
		code, review, reason, err := s.Store.CategoryCacheGet(ctx, ownerID, key, tx.Direction, method,
			categoryllm.TaxonomyVersion, classifier.Model(), categoryllm.PromptVersion, categoryllm.MappingVersion)
		if err != nil || code == "" {
			continue
		}
		// Claim only after the cache answered: a miss keeps the row PENDING with its due time and
		// its retry budget untouched.
		leaseToken, claimed, err := s.Store.ClaimCategoryJob(ctx, ownerID, job.ID, now, store.CategoryLeaseDuration)
		if err != nil {
			return applied, err
		}
		if !claimed {
			continue
		}
		job.LeaseToken = leaseToken
		prepared, eligible, err := s.prepareForClassify(ctx, ownerID, job)
		if err != nil {
			if errors.Is(err, store.ErrLeaseLost) || errors.Is(err, store.ErrNotFound) {
				continue
			}
			return applied, err
		}
		if !eligible {
			continue
		}
		if _, err := s.applyAndFinish(ctx, ownerID, prepared, code, review, reason); err != nil {
			if errors.Is(err, store.ErrLeaseLost) || errors.Is(err, store.ErrNotFound) {
				continue
			}
			return applied, err
		}
		applied++
	}
	return applied, nil
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
