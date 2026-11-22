package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/auth"
	"github.com/pam/recap/backend-go/internal/store"
)

type consentRequest struct {
	Purpose       string `json:"purpose"`
	Decision      string `json:"decision"`
	NoticeVersion string `json:"noticeVersion"`
}

func (s *Server) appendConsent(w http.ResponseWriter, r *http.Request) {
	var body consentRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.Purpose != "GMAIL_READ" && body.Purpose != "AI_PROCESSING" {
		writeError(w, http.StatusBadRequest, "invalid_request", "purpose is invalid", correlationOf(r))
		return
	}
	if body.Decision != "GRANTED" && body.Decision != "DECLINED" && body.Decision != "WITHDRAWN" {
		writeError(w, http.StatusBadRequest, "invalid_request", "decision is invalid", correlationOf(r))
		return
	}
	if body.NoticeVersion == "" || len(body.NoticeVersion) > 32 {
		writeError(w, http.StatusBadRequest, "invalid_request", "noticeVersion is invalid", correlationOf(r))
		return
	}
	info, _ := authFrom(r)
	record, err := s.Store.AppendConsent(r.Context(), info.OwnerID, body.Purpose, body.Decision,
		body.NoticeVersion, s.Now())
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"id": record.ID, "purpose": record.Purpose, "decision": record.Decision,
		"noticeVersion": record.NoticeVersion, "decidedAt": record.CreatedAt,
	})
}

func (s *Server) listConsents(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	consents, err := s.Store.ListConsents(r.Context(), info.OwnerID, 100)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	items := make([]map[string]any, 0, len(consents))
	for _, consent := range consents {
		items = append(items, map[string]any{
			"id": consent.ID, "purpose": consent.Purpose, "decision": consent.Decision,
			"noticeVersion": consent.NoticeVersion, "decidedAt": consent.CreatedAt,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"consents": items})
}

// --- categories -----------------------------------------------------------------------------

type categoryCreate struct {
	Code       string         `json:"code"`
	Label      string         `json:"label"`
	Kind       string         `json:"kind"`
	Color      string         `json:"color"`
	Attributes map[string]any `json:"attributes"`
}

type categoryPatch struct {
	Label      *string         `json:"label"`
	Kind       *string         `json:"kind"`
	Color      *string         `json:"color"`
	Attributes *map[string]any `json:"attributes"`
	Revision   int             `json:"revision"`
}

func (s *Server) listCategories(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	categories, err := s.Store.ListCategories(r.Context(), info.OwnerID, r.URL.Query().Get("q"))
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"categories": categoriesJSON(categories)})
}

func (s *Server) createCategory(w http.ResponseWriter, r *http.Request) {
	var body categoryCreate
	if !decodeJSON(w, r, &body) {
		return
	}
	info, _ := authFrom(r)
	category, err := s.Store.CreateCategory(r.Context(), info.OwnerID, store.CategoryInput{
		Code: body.Code, Label: body.Label, Kind: body.Kind, Color: body.Color,
		Attributes: body.Attributes,
	}, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, categoryJSON(category))
}

func (s *Server) getCategory(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	category, err := s.Store.GetCategory(r.Context(), info.OwnerID, r.PathValue("id"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, categoryJSON(category))
}

func (s *Server) updateCategory(w http.ResponseWriter, r *http.Request) {
	var body categoryPatch
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.Revision == 0 {
		body.Revision = revisionHeader(r)
	}
	info, _ := authFrom(r)
	category, err := s.Store.UpdateCategory(r.Context(), info.OwnerID, r.PathValue("id"),
		store.CategoryPatch{Label: body.Label, Kind: body.Kind, Color: body.Color,
			Attributes: body.Attributes, Revision: body.Revision}, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, categoryJSON(category))
}

func (s *Server) deleteCategory(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	err := s.Store.DeleteCategory(r.Context(), info.OwnerID, r.PathValue("id"),
		r.URL.Query().Get("reassignTo"), s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func categoriesJSON(categories []store.Category) []map[string]any {
	out := make([]map[string]any, 0, len(categories))
	for _, category := range categories {
		out = append(out, categoryJSON(category))
	}
	return out
}

func categoryJSON(category store.Category) map[string]any {
	return map[string]any{
		"id": category.ID, "code": category.Code, "label": category.Label,
		"kind": category.Kind, "color": category.Color, "isBuiltin": category.IsBuiltin,
		"attributes": category.Attributes, "revision": category.Revision,
		"createdAt": category.CreatedAt, "updatedAt": category.UpdatedAt,
	}
}

// --- transactions ---------------------------------------------------------------------------

type transactionCreate struct {
	CategoryID         string         `json:"categoryId"`
	Direction          string         `json:"direction"`
	Source             string         `json:"source"`
	AmountMinor        int64          `json:"amountMinor"`
	Currency           string         `json:"currency"`
	EventAt            string         `json:"eventAt"`
	Fingerprint        string         `json:"fingerprint"`
	ParserVersion      string         `json:"parserVersion"`
	ParserConfidence   float64        `json:"parserConfidence"`
	CategoryOrigin     string         `json:"categoryOrigin"`
	Description        string         `json:"description"`
	MerchantLabel      string         `json:"merchantLabel"`
	Note               string         `json:"note"`
	Attributes         map[string]any `json:"attributes"`
	IsInternalTransfer bool           `json:"isInternalTransfer"`
	LinkedRefundOf     string         `json:"linkedRefundOf"`
}

type transactionPatch struct {
	CategoryID         *string         `json:"categoryId"`
	Description        *string         `json:"description"`
	MerchantLabel      *string         `json:"merchantLabel"`
	Note               *string         `json:"note"`
	Attributes         *map[string]any `json:"attributes"`
	IsInternalTransfer *bool           `json:"isInternalTransfer"`
	LinkedRefundOf     *string         `json:"linkedRefundOf"`
	Revision           int             `json:"revision"`
}

func (s *Server) createTransaction(w http.ResponseWriter, r *http.Request) {
	var body transactionCreate
	if !decodeJSON(w, r, &body) {
		return
	}
	idempotencyKey := strings.TrimSpace(r.Header.Get("Idempotency-Key"))
	if !validIdempotencyKey(idempotencyKey) {
		writeError(w, http.StatusBadRequest, "invalid_request", "Idempotency-Key is required (1..200 printable characters)", correlationOf(r))
		return
	}
	eventAt, err := time.Parse(time.RFC3339, body.EventAt)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "eventAt must be RFC3339", correlationOf(r))
		return
	}
	if body.Currency == "" {
		body.Currency = "IDR"
	}
	if body.Source == "" {
		body.Source = "MANUAL"
	}
	if body.CategoryOrigin == "" {
		body.CategoryOrigin = "USER"
	}
	body.EventAt = eventAt.UTC().Format(time.RFC3339)
	// Hash the normalized request, not the raw bytes: whitespace/field order must not change identity.
	requestHash := hashTransactionRequest(body)
	info, _ := authFrom(r)
	result, err := s.Store.CreateTransactionIdempotent(r.Context(), info.OwnerID, idempotencyKey,
		requestHash, store.TransactionInput{
			CategoryID: body.CategoryID, Direction: body.Direction, Source: body.Source,
			AmountMinor: body.AmountMinor, Currency: body.Currency, EventAt: eventAt,
			Fingerprint: body.Fingerprint, ParserVersion: body.ParserVersion,
			ParserConfidence: body.ParserConfidence, CategoryOrigin: body.CategoryOrigin,
			Description: body.Description, MerchantLabel: body.MerchantLabel, Note: body.Note,
			Attributes: body.Attributes, IsInternalTransfer: body.IsInternalTransfer,
			LinkedRefundOf: body.LinkedRefundOf,
		}, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	w.Header().Set("Idempotency-Replayed", strconv.FormatBool(result.Replayed))
	status := http.StatusCreated
	if result.Replayed {
		status = http.StatusOK
	}
	writeJSON(w, status, transactionJSON(result.Transaction))
}

func validIdempotencyKey(key string) bool {
	if len(key) == 0 || len(key) > 200 {
		return false
	}
	for _, r := range key {
		if r < 0x20 || r == 0x7f {
			return false
		}
	}
	return true
}

func hashTransactionRequest(body transactionCreate) string {
	raw, _ := json.Marshal(body)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

func (s *Server) listTransactions(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	query := r.URL.Query()
	limit, _ := strconv.Atoi(query.Get("limit"))
	page, err := s.Store.ListTransactions(r.Context(), info.OwnerID, query.Get("month"),
		query.Get("categoryId"), query.Get("direction"), query.Get("source"), query.Get("q"),
		limit, query.Get("cursor"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	items := make([]map[string]any, 0, len(page.Items))
	for _, transaction := range page.Items {
		items = append(items, transactionJSON(transaction))
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items": items, "nextCursor": nullString(page.NextCursor),
		"search": map[string]any{"scanned": page.Scanned, "truncated": page.Truncated},
	})
}

func (s *Server) getTransaction(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	transaction, err := s.Store.GetTransaction(r.Context(), info.OwnerID, r.PathValue("id"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, transactionJSON(transaction))
}

func (s *Server) updateTransaction(w http.ResponseWriter, r *http.Request) {
	var body transactionPatch
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.Revision == 0 {
		body.Revision = revisionHeader(r)
	}
	info, _ := authFrom(r)
	transaction, err := s.Store.UpdateTransaction(r.Context(), info.OwnerID, r.PathValue("id"),
		store.TransactionPatch{CategoryID: body.CategoryID, Description: body.Description,
			MerchantLabel: body.MerchantLabel, Note: body.Note, Attributes: body.Attributes,
			IsInternalTransfer: body.IsInternalTransfer, LinkedRefundOf: body.LinkedRefundOf,
			Revision: body.Revision}, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, transactionJSON(transaction))
}

func (s *Server) deleteTransaction(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	if err := s.Store.DeleteTransaction(r.Context(), info.OwnerID, r.PathValue("id"), s.Now()); err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func transactionJSON(t store.Transaction) map[string]any {
	return map[string]any{
		"id": t.ID, "categoryId": nullString(t.CategoryID), "categoryCode": t.CategoryCode,
		"categoryKind": t.CategoryKind, "direction": t.Direction, "source": t.Source,
		"amountMinor": t.AmountMinor, "currency": t.Currency, "eventAt": t.EventAt,
		"monthId": t.MonthID, "fingerprint": t.Fingerprint, "parserVersion": t.ParserVersion,
		"parserConfidence": t.ParserConfidence, "categoryOrigin": t.CategoryOrigin,
		"description": t.Sensitive.Description, "merchantLabel": t.Sensitive.MerchantLabel,
		"note": t.Sensitive.Note, "attributes": t.Sensitive.Attributes,
		"isInternalTransfer": t.IsInternalTransfer, "linkedRefundOf": nullString(t.LinkedRefundOf),
		"revision": t.Revision, "createdAt": t.CreatedAt, "updatedAt": t.UpdatedAt,
		"deletedAt": t.DeletedAt,
	}
}

// --- summaries ------------------------------------------------------------------------------

type putSummaryRequest struct {
	InputRevision int            `json:"inputRevision"`
	Payload       map[string]any `json:"payload"`
}

func (s *Server) putSummary(w http.ResponseWriter, r *http.Request) {
	var body putSummaryRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	monthID := r.PathValue("monthID")
	if _, _, err := store.ParseMonth(monthID); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "monthId must be YYYY-MM", correlationOf(r))
		return
	}
	if payloadMonth, _ := body.Payload["monthId"].(string); payloadMonth != "" && payloadMonth != monthID {
		writeError(w, http.StatusBadRequest, "invalid_request", "payload.monthId must match the path", correlationOf(r))
		return
	}
	info, _ := authFrom(r)
	if pending, err := s.Store.HasPendingDeletion(r.Context(), info.OwnerID); err == nil && pending {
		writeError(w, http.StatusConflict, "deletion_in_progress", "A deletion request is in progress", correlationOf(r))
		return
	}
	stored, err := s.Store.UpsertSummary(r.Context(), info.OwnerID, monthID, body.InputRevision,
		body.Payload, s.Now())
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"monthId": stored.MonthID, "inputRevision": stored.InputRevision,
		"coverage": stored.Coverage, "updatedAt": stored.UpdatedAt,
	})
}

func (s *Server) getSummary(w http.ResponseWriter, r *http.Request) {
	monthID := r.PathValue("monthID")
	if _, _, err := store.ParseMonth(monthID); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "monthId must be YYYY-MM", correlationOf(r))
		return
	}
	info, _ := authFrom(r)
	computed, err := s.Store.ComputeSummary(r.Context(), info.OwnerID, monthID, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	response := map[string]any{
		"monthId": computed.MonthID, "inputRevision": computed.InputRevision,
		"coverage": computed.Coverage, "grossInflowMinor": computed.GrossInflowMinor,
		"grossOutflowMinor": computed.GrossOutflowMinor, "netMinor": computed.NetMinor,
		"classifiedIncomeMinor":  computed.ClassifiedIncomeMinor,
		"classifiedExpenseMinor": computed.ClassifiedExpenseMinor,
		"transactionCount":       computed.TransactionCount, "manualEntryCount": computed.ManualEntryCount,
		"uncategorizedCount": computed.UncategorizedCount, "categoryTotals": computed.CategoryTotals,
		"generatedAt": computed.GeneratedAt, "emailDerived": true,
	}
	if stored, err := s.Store.GetStoredSummary(r.Context(), info.OwnerID, monthID); err == nil {
		response["payload"] = stored.Payload
		response["storedInputRevision"] = stored.InputRevision
		response["storedCoverage"] = stored.Coverage
		response["storedUpdatedAt"] = stored.UpdatedAt
	}
	writeJSON(w, http.StatusOK, response)
}

// --- budgets --------------------------------------------------------------------------------

type budgetLimitJSON struct {
	CategoryID  string `json:"categoryId"`
	AmountMinor int64  `json:"amountMinor"`
}

type budgetRequest struct {
	ExpectedIncomeMinor int64             `json:"expectedIncomeMinor"`
	PlannedSavingsMinor int64             `json:"plannedSavingsMinor"`
	Limits              []budgetLimitJSON `json:"limits"`
	Attributes          map[string]any    `json:"attributes"`
	Revision            int               `json:"revision"`
	Plan                *budgetRequest    `json:"plan"`
}

func (s *Server) getBudget(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	plan, err := s.Store.GetBudgetPlan(r.Context(), info.OwnerID, r.PathValue("monthID"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, budgetJSON(plan))
}

func (s *Server) putBudget(w http.ResponseWriter, r *http.Request) {
	var body budgetRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.Plan != nil {
		// v0.3 Android sends { plan: {...} }.
		body.ExpectedIncomeMinor = body.Plan.ExpectedIncomeMinor
		body.PlannedSavingsMinor = body.Plan.PlannedSavingsMinor
		body.Attributes = body.Plan.Attributes
	}
	if body.Revision == 0 {
		body.Revision = revisionHeader(r)
	}
	limits := make([]store.BudgetLimit, 0, len(body.Limits))
	for _, limit := range body.Limits {
		limits = append(limits, store.BudgetLimit{CategoryID: limit.CategoryID, AmountMinor: limit.AmountMinor})
	}
	info, _ := authFrom(r)
	plan, err := s.Store.PutBudgetPlan(r.Context(), info.OwnerID, r.PathValue("monthID"),
		store.BudgetInput{ExpectedIncomeMinor: body.ExpectedIncomeMinor,
			PlannedSavingsMinor: body.PlannedSavingsMinor, Limits: limits,
			Attributes: body.Attributes, Revision: body.Revision}, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, budgetJSON(plan))
}

func (s *Server) deleteBudget(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	if err := s.Store.DeleteBudgetPlan(r.Context(), info.OwnerID, r.PathValue("monthID")); err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func budgetJSON(plan store.BudgetPlan) map[string]any {
	return map[string]any{
		"monthId": plan.MonthID, "expectedIncomeMinor": plan.ExpectedIncomeMinor,
		"plannedSavingsMinor": plan.PlannedSavingsMinor, "limits": plan.Limits,
		"attributes": plan.Attributes, "revision": plan.Revision, "gapMinor": plan.GapMinor,
		"createdAt": plan.CreatedAt, "updatedAt": plan.UpdatedAt,
	}
}

func revisionHeader(r *http.Request) int {
	raw := strings.Trim(r.Header.Get("If-Match"), `"`)
	value, _ := strconv.Atoi(raw)
	return value
}

func (s *Server) mapWriteError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "Not found", correlationOf(r))
	case errors.Is(err, store.ErrConflict), errors.Is(err, store.ErrCategoryInUse):
		writeError(w, http.StatusConflict, "conflict", err.Error(), correlationOf(r))
	case errors.Is(err, store.ErrBuiltinCategory):
		writeError(w, http.StatusForbidden, "builtin_category", err.Error(), correlationOf(r))
	case errors.Is(err, store.ErrRevisionConflict):
		writeError(w, http.StatusConflict, "revision_conflict", "Revision changed", correlationOf(r))
	case errors.Is(err, store.ErrDeletionInProgress):
		writeError(w, http.StatusConflict, "deletion_in_progress", "A deletion request is in progress", correlationOf(r))
	case errors.Is(err, store.ErrIdempotencyConflict):
		writeError(w, http.StatusConflict, "idempotency_conflict", "This Idempotency-Key was already used with a different body", correlationOf(r))
	default:
		// Validation errors from the store are client errors, not 500s.
		message := err.Error()
		if strings.Contains(message, "must") || strings.Contains(message, "invalid") ||
			strings.Contains(message, "required") || strings.Contains(message, "exceed") ||
			strings.Contains(message, "only IDR") {
			writeError(w, http.StatusBadRequest, "invalid_request", message, correlationOf(r))
			return
		}
		s.databaseOrInternal(w, r, err)
	}
}

// --- deletion -------------------------------------------------------------------------------

type deletionCreateRequest struct {
	Password     string `json:"password"`
	Confirmation string `json:"confirmation"`
}

func (s *Server) createDeletion(w http.ResponseWriter, r *http.Request) {
	var body deletionCreateRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.Confirmation != "DELETE_WORKSPACE" {
		writeError(w, http.StatusBadRequest, "confirmation_required",
			"Explicit confirmation DELETE_WORKSPACE is required", correlationOf(r))
		return
	}
	if body.Password == "" {
		writeError(w, http.StatusBadRequest, "reauth_required", "Password reauthentication is required", correlationOf(r))
		return
	}
	info, _ := authFrom(r)
	user, err := s.Store.GetUser(r.Context(), info.OwnerID)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	account, err := s.Store.FindAccountByEmail(r.Context(), user.Email)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	ok, err := auth.VerifyPassword(body.Password, account.Salt, account.PasswordHash, account.KDF)
	if err != nil || !ok {
		writeError(w, http.StatusForbidden, "reauth_failed", "Password reauthentication failed", correlationOf(r))
		return
	}
	receipt := store.NewID("receipt") + store.NewID("receipt")
	receiptHash := sha256.Sum256([]byte(receipt))
	record, err := s.Store.CreateOrGetDeletion(r.Context(), info.OwnerID,
		hex.EncodeToString(receiptHash[:]), s.Now())
	if err != nil {
		if errors.Is(err, store.ErrLastAdmin) {
			writeError(w, http.StatusConflict, "last_admin", "The last active admin cannot delete the workspace", correlationOf(r))
			return
		}
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{
		"id": record.ID, "state": record.State, "slaDays": record.SLADays,
		"receiptToken": receipt,
		"notice":       "Financial data is blocked immediately and removed within 7 days; backups follow the disclosed retention window.",
	})
}

func (s *Server) getDeletionReceipt(w http.ResponseWriter, r *http.Request) {
	record, err := s.Store.GetDeletionByReceipt(r.Context(), r.PathValue("token"))
	if err != nil {
		if errors.Is(err, store.ErrReceiptNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "Deletion receipt not found", correlationOf(r))
			return
		}
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"id": record.ID, "state": record.State, "slaDays": record.SLADays,
		"requestedAt": record.RequestedAt, "completedAt": record.CompletedAt,
		"aggregatesDeleted": record.AggregatesDeleted,
	})
}

func (s *Server) getDeletion(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	record, err := s.Store.GetDeletion(r.Context(), info.OwnerID, r.PathValue("id"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"id": record.ID, "state": record.State, "slaDays": record.SLADays,
		"requestedAt": record.RequestedAt, "completedAt": record.CompletedAt,
		"aggregatesDeleted": record.AggregatesDeleted,
	})
}
