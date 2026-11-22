// Package httpapi exposes the PAM REST surface. Handlers never take an owner id from the body:
// the authenticated session supplies it.
package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/auth"
	"github.com/pam/recap/backend-go/internal/categoryllm"
	"github.com/pam/recap/backend-go/internal/config"
	"github.com/pam/recap/backend-go/internal/llm"
	"github.com/pam/recap/backend-go/internal/store"
)

type contextKey string

const authContextKey contextKey = "pam.auth"

type authInfo struct {
	OwnerID   string
	SessionID string
	Role      string
}

type Server struct {
	Store    *store.Store
	Config   *config.Config
	Issuer   *auth.Issuer
	Log      *slog.Logger
	Now      func() time.Time
	Provider llm.Provider
	CategoryClassifier categoryllm.Classifier
}

func New(s *store.Store, cfg *config.Config, issuer *auth.Issuer, logger *slog.Logger,
	provider llm.Provider) *Server {
	return &Server{Store: s, Config: cfg, Issuer: issuer, Log: logger, Now: time.Now, Provider: provider}
}

// Handler builds the route table and middleware chain.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("GET /readyz", s.ready)
	mux.HandleFunc("POST /v1/accounts", s.register)
	mux.HandleFunc("POST /v1/sessions", s.login)
	mux.HandleFunc("POST /v1/sessions/refresh", s.refresh)
	mux.Handle("DELETE /v1/sessions", s.authed(s.logout))
	mux.Handle("GET /v1/me", s.authed(s.me))
	mux.Handle("GET /v1/fingerprint-key", s.authed(s.fingerprintKey))
	mux.Handle("GET /v1/consents", s.authed(s.listConsents))
	mux.Handle("POST /v1/consents", s.authed(s.appendConsent))
	mux.Handle("GET /v1/categories", s.authed(s.listCategories))
	mux.Handle("POST /v1/categories", s.authed(s.createCategory))
	mux.Handle("GET /v1/categories/{id}", s.authed(s.getCategory))
	mux.Handle("PATCH /v1/categories/{id}", s.authed(s.updateCategory))
	mux.Handle("DELETE /v1/categories/{id}", s.authed(s.deleteCategory))
	mux.Handle("GET /v1/transactions", s.authed(s.listTransactions))
	mux.Handle("POST /v1/transactions", s.authed(s.createTransaction))
	mux.Handle("GET /v1/transactions/{id}", s.authed(s.getTransaction))
	mux.Handle("PATCH /v1/transactions/{id}", s.authed(s.updateTransaction))
	mux.Handle("DELETE /v1/transactions/{id}", s.authed(s.deleteTransaction))
	mux.Handle("PUT /v1/summaries/{monthID}", s.authed(s.putSummary))
	mux.Handle("GET /v1/summaries/{monthID}", s.authed(s.getSummary))
	mux.Handle("GET /v1/budget-plans/{monthID}", s.authed(s.getBudget))
	mux.Handle("PUT /v1/budget-plans/{monthID}", s.authed(s.putBudget))
	mux.Handle("DELETE /v1/budget-plans/{monthID}", s.authed(s.deleteBudget))
	mux.Handle("POST /v1/import-jobs", s.authed(s.createImportJob))
	mux.Handle("GET /v1/import-jobs/{id}", s.authed(s.getImportJob))
	mux.Handle("POST /v1/import-jobs/{id}/batches", s.authed(s.importBatch))
	mux.Handle("PATCH /v1/import-jobs/{id}", s.authed(s.finalizeImportJob))
	mux.Handle("POST /v1/category-jobs", s.authed(s.createCategoryJobs))
	mux.Handle("GET /v1/category-jobs/{id}", s.authed(s.getCategoryJob))
	mux.Handle("POST /v1/analyses", s.authed(s.createAnalysis))
	mux.Handle("GET /v1/analyses/{monthID}", s.authed(s.getAnalysis))
	mux.Handle("POST /v1/deletion-requests", s.authed(s.createDeletion))
	mux.Handle("GET /v1/deletion-requests/{id}", s.authed(s.getDeletion))
	mux.HandleFunc("GET /v1/deletion-receipts/{token}", s.getDeletionReceipt)
	mux.HandleFunc("POST /v1/internal/sweep", s.internalSweep)
	return s.middleware(mux)
}

func (s *Server) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlation := store.NewID("req")
		w.Header().Set("X-Correlation-Id", correlation)
		ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
		defer cancel()
		started := time.Now()
		sw := &statusWriter{ResponseWriter: w}
		defer func() {
			if recovered := recover(); recovered != nil {
				// Never log the recovered value: it can contain request data or a path secret.
				s.Log.Error("panic", "correlationId", correlation)
				writeError(sw, http.StatusInternalServerError, "internal_error", "Unexpected error", correlation)
			}
			s.Log.Info("request", "correlationId", correlation, "method", r.Method,
				"route", sanitizedRoute(r.URL.Path), "status", sw.status,
				"ms", time.Since(started).Milliseconds())
		}()
		r.Body = http.MaxBytesReader(sw, r.Body, 256*1024)
		next.ServeHTTP(sw, r.WithContext(context.WithValue(ctx, correlationKey, correlation)))
	})
}

const correlationKey contextKey = "pam.correlation"

// sanitizedRoute is a closed route label. Dynamic ids are replaced, and receipt tokens are never
// logged; unknown paths are not echoed because they may contain a bearer secret.
func sanitizedRoute(path string) string {
	switch {
	case path == "/healthz" || path == "/readyz":
		return path
	case path == "/v1/deletion-receipts" || strings.HasPrefix(path, "/v1/deletion-receipts/"):
		return "/v1/deletion-receipts/{token}"
	case path == "/v1/internal/sweep":
		return path
	case strings.HasPrefix(path, "/v1/transactions/"):
		return "/v1/transactions/{id}"
	case strings.HasPrefix(path, "/v1/categories/"):
		return "/v1/categories/{id}"
	case strings.HasPrefix(path, "/v1/import-jobs/"):
		if strings.HasSuffix(path, "/batches") {
			return "/v1/import-jobs/{id}/batches"
		}
		return "/v1/import-jobs/{id}"
	case strings.HasPrefix(path, "/v1/analyses/"):
		return "/v1/analyses/{monthId}"
	case strings.HasPrefix(path, "/v1/budget-plans/"):
		return "/v1/budget-plans/{monthId}"
	case strings.HasPrefix(path, "/v1/summaries/"):
		return "/v1/summaries/{monthId}"
	case strings.HasPrefix(path, "/v1/deletion-requests/"):
		return "/v1/deletion-requests/{id}"
	case strings.HasPrefix(path, "/v1/admin/"):
		return "/v1/admin/{route}"
	}
	switch path {
	case "/v1/accounts", "/v1/sessions", "/v1/sessions/refresh", "/v1/consents",
		"/v1/me", "/v1/me/fingerprint-key", "/v1/transactions", "/v1/categories",
		"/v1/import-jobs", "/v1/analyses", "/v1/deletion-requests":
		return path
	}
	return "{unknown}"
}

func correlationOf(r *http.Request) string {
	if v, ok := r.Context().Value(correlationKey).(string); ok {
		return v
	}
	return store.NewID("req")
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = 200
	}
	return w.ResponseWriter.Write(b)
}

func (s *Server) authed(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := bearerToken(r)
		if token == "" {
			writeError(w, http.StatusUnauthorized, "unauthorized", "Missing or invalid access token", correlationOf(r))
			return
		}
		claims, err := s.Issuer.Parse(token, s.Now())
		if err != nil {
			writeError(w, http.StatusUnauthorized, "unauthorized", "Missing or invalid access token", correlationOf(r))
			return
		}
		state, err := s.Store.SessionState(r.Context(), claims.SID, claims.Subject)
		if err != nil || state.RevokedAt != nil || state.Status != "ACTIVE" ||
			state.AuthVersion != claims.Ver || claims.ExpiresAt == nil ||
			!claims.ExpiresAt.Time.After(s.Now()) || state.ExpiresAt.Before(s.Now()) {
			writeError(w, http.StatusUnauthorized, "session_stale", "Session is no longer active", correlationOf(r))
			return
		}
		if claims.Role != state.Role {
			writeError(w, http.StatusUnauthorized, "session_stale", "Session role changed", correlationOf(r))
			return
		}
		info := authInfo{OwnerID: claims.Subject, SessionID: claims.SID, Role: state.Role}
		next(w, r.WithContext(context.WithValue(r.Context(), authContextKey, info)))
	})
}

func requiresAdmin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		info, ok := authFrom(r)
		if !ok || info.Role != "ADMIN" {
			writeError(w, http.StatusForbidden, "forbidden", "Admin role required", correlationOf(r))
			return
		}
		next(w, r)
	}
}

func authFrom(r *http.Request) (authInfo, bool) {
	value := r.Context().Value(authContextKey)
	info, ok := value.(authInfo)
	return info, ok
}

func bearerToken(r *http.Request) string {
	header := strings.TrimSpace(r.Header.Get("Authorization"))
	if header == "" {
		return ""
	}
	parts := strings.Fields(header)
	if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
		return ""
	}
	return parts[1]
}

type apiError struct {
	Code          string `json:"code"`
	Message       string `json:"message"`
	Retryable     bool   `json:"retryable"`
	CorrelationID string `json:"correlationId"`
}

func writeError(w http.ResponseWriter, status int, code, message, correlation string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(apiError{Code: code, Message: message,
		Retryable: retryable(code), CorrelationID: correlation})
}

func retryable(code string) bool {
	switch code {
	case "rate_limited", "daily_ceiling_reached", "provider_unavailable", "database_unavailable", "internal_error":
		return true
	}
	return false
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any) bool {
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "Request body is not valid JSON", correlationOf(r))
		return false
	}
	return true
}

func isDatabaseUnavailable(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, sql.ErrConnDone) {
		return true
	}
	text := strings.ToLower(err.Error())
	return strings.Contains(text, "connection refused") || strings.Contains(text, "no such host") ||
		strings.Contains(text, "timeout") || strings.Contains(text, "unavailable")
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "uptimeSeconds": 0})
}

func (s *Server) ready(w http.ResponseWriter, r *http.Request) {
	if err := s.Store.DB.PingContext(r.Context()); err != nil {
		writeError(w, http.StatusServiceUnavailable, "database_unavailable", "Database is unavailable", correlationOf(r))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "ready"})
}

func clientAddress(r *http.Request) string {
	host, _, err := net.SplitHostPort(strings.TrimSpace(r.RemoteAddr))
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
