package httpapi

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"net/http"
	"net/mail"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/auth"
	"github.com/pam/recap/backend-go/internal/store"
)

type registerRequest struct {
	Email           string `json:"email"`
	Password        string `json:"password"`
	DisplayName     string `json:"displayName"`
	InviteCode      string `json:"inviteCode"`
	InvitationToken string `json:"invitationToken"`
}

type sessionResponse struct {
	AccessToken      string    `json:"accessToken"`
	AccessExpiresAt  time.Time `json:"accessExpiresAt"`
	ExpiresAt        time.Time `json:"expiresAt"` // legacy Android field
	RefreshToken     string    `json:"refreshToken"`
	RefreshExpiresAt time.Time `json:"refreshExpiresAt"`
	DisplayName      string    `json:"displayName"`
	User             userJSON  `json:"user"`
}

type userJSON struct {
	ID          string `json:"id"`
	OwnerID     string `json:"ownerId"`
	Email       string `json:"email"`
	DisplayName string `json:"displayName"`
	Role        string `json:"role"`
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) {
	var body registerRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	email := strings.ToLower(strings.TrimSpace(body.Email))
	if !validEmail(email) {
		writeError(w, http.StatusBadRequest, "invalid_request", "email is invalid", correlationOf(r))
		return
	}
	if len(body.Password) < 8 || len(body.Password) > 200 {
		writeError(w, http.StatusBadRequest, "invalid_request", "password must be 8..200 characters", correlationOf(r))
		return
	}
	if !secureEqual(body.InviteCode, s.Config.InviteCode) {
		writeError(w, http.StatusForbidden, "forbidden", "invalid_invite", correlationOf(r))
		return
	}
	display := strings.TrimSpace(body.DisplayName)
	if len([]rune(display)) > 80 {
		writeError(w, http.StatusBadRequest, "invalid_request", "displayName is too long", correlationOf(r))
		return
	}
	digest, err := auth.HashPassword(body.Password)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal_error", "Unexpected error", correlationOf(r))
		return
	}
	account, err := s.Store.CreateAccount(r.Context(), email, display,
		store.PasswordDigest{Salt: digest.Salt, Hash: digest.Hash, KDF: digest.KDF}, s.Now())
	if err != nil {
		if errors.Is(err, store.ErrEmailExists) {
			writeError(w, http.StatusConflict, "conflict", "Email is already registered", correlationOf(r))
			return
		}
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"ownerId": account.OwnerID, "userId": account.OwnerID, "email": account.Email,
		"displayName": nullString(account.DisplayName), "role": account.Role,
	})
}

type loginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var body loginRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	email := strings.ToLower(strings.TrimSpace(body.Email))
	if !validEmail(email) || body.Password == "" || len(body.Password) > 200 {
		writeError(w, http.StatusBadRequest, "invalid_request", "email and password are required", correlationOf(r))
		return
	}
	now := s.Now()
	key := store.AttemptKey(email, clientAddress(r))
	failures, err := s.Store.LoginFailureCount(r.Context(), key, now)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	if failures >= store.LoginMaxFailures {
		writeError(w, http.StatusTooManyRequests, "rate_limited",
			"Too many failed sign-in attempts. Try again in a few minutes.", correlationOf(r))
		return
	}
	account, err := s.Store.FindAccountByEmail(r.Context(), email)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			auth.DummyVerify(body.Password)
			_, _ = s.Store.RegisterLoginFailure(r.Context(), key, now)
			writeError(w, http.StatusUnauthorized, "unauthorized", "Email or password is incorrect", correlationOf(r))
			return
		}
		s.databaseOrInternal(w, r, err)
		return
	}
	ok, err := auth.VerifyPassword(body.Password, account.Salt, account.PasswordHash, account.KDF)
	if err != nil || !ok {
		_, _ = s.Store.RegisterLoginFailure(r.Context(), key, now)
		writeError(w, http.StatusUnauthorized, "unauthorized", "Email or password is incorrect", correlationOf(r))
		return
	}
	if account.Status != "ACTIVE" {
		writeError(w, http.StatusForbidden, "forbidden", "Account is not active", correlationOf(r))
		return
	}
	_ = s.Store.ClearLoginFailures(r.Context(), key)
	_ = s.Store.TouchAccount(r.Context(), account.OwnerID, now)

	response, err := s.issueSession(r, account, now)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, response)
}

func (s *Server) issueSession(r *http.Request, account store.Account, now time.Time) (sessionResponse, error) {
	family := store.NewID("fam")
	sessionID, err := s.Store.CreateAuthSession(r.Context(), account.OwnerID, family, now, s.Config.JWT.RefreshTTL)
	if err != nil {
		return sessionResponse{}, err
	}
	refresh, hash, err := auth.NewRefreshToken(s.Config.JWT.RefreshPepper)
	if err != nil {
		return sessionResponse{}, err
	}
	if err := s.Store.InsertRefreshToken(r.Context(), store.NewID("rt"), account.OwnerID,
		sessionID, family, hash, now, s.Config.JWT.RefreshTTL); err != nil {
		return sessionResponse{}, err
	}
	access, accessExpiry, err := s.Issuer.Issue(account.OwnerID, account.Role,
		account.AuthVersion, sessionID, now)
	if err != nil {
		return sessionResponse{}, err
	}
	return sessionResponse{
		AccessToken: access, AccessExpiresAt: accessExpiry, ExpiresAt: accessExpiry,
		RefreshToken: refresh, RefreshExpiresAt: now.Add(s.Config.JWT.RefreshTTL),
		DisplayName: account.DisplayName,
		User: userJSON{ID: account.OwnerID, OwnerID: account.OwnerID, Email: account.Email,
			DisplayName: account.DisplayName, Role: account.Role},
	}, nil
}

type refreshRequest struct {
	RefreshToken string `json:"refreshToken"`
}

func (s *Server) refresh(w http.ResponseWriter, r *http.Request) {
	var body refreshRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.RefreshToken == "" {
		writeError(w, http.StatusUnauthorized, "refresh_invalid", "Refresh token is missing", correlationOf(r))
		return
	}
	now := s.Now()
	hash := auth.RefreshHash(body.RefreshToken, s.Config.JWT.RefreshPepper)
	record, err := s.Store.FindRefreshByHash(r.Context(), hash)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "refresh_invalid", "Refresh token is invalid", correlationOf(r))
		return
	}
	if record.RevokedAt != nil {
		// A rotated token used again is a replay: revoke the whole family.
		_ = s.Store.RevokeRefreshFamily(r.Context(), record.FamilyID, "reuse_detected", now)
		writeError(w, http.StatusUnauthorized, "refresh_reused", "Refresh token was already used", correlationOf(r))
		return
	}
	if record.ExpiresAt.Before(now) || record.SessionExpiresAt.Before(now) ||
		record.SessionRevokedAt != nil || record.Status != "ACTIVE" {
		writeError(w, http.StatusUnauthorized, "refresh_invalid", "Session is no longer active", correlationOf(r))
		return
	}
	replacement, replacementHash, err := auth.NewRefreshToken(s.Config.JWT.RefreshPepper)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal_error", "Unexpected error", correlationOf(r))
		return
	}
	replacementID := store.NewID("rt")
	if err := s.Store.RotateRefresh(r.Context(), record, replacementID, replacementHash, now,
		s.Config.JWT.RefreshTTL); err != nil {
		if errors.Is(err, store.ErrRefreshReplay) {
			_ = s.Store.RevokeRefreshFamily(r.Context(), record.FamilyID, "reuse_detected", now)
			writeError(w, http.StatusUnauthorized, "refresh_reused", "Refresh token was already used", correlationOf(r))
			return
		}
		s.databaseOrInternal(w, r, err)
		return
	}
	access, accessExpiry, err := s.Issuer.Issue(record.OwnerID, record.Role, record.AuthVersion,
		record.SessionID, now)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal_error", "Unexpected error", correlationOf(r))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"accessToken": access, "accessExpiresAt": accessExpiry, "expiresAt": accessExpiry,
		"refreshToken": replacement, "refreshExpiresAt": now.Add(s.Config.JWT.RefreshTTL),
	})
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	if err := s.Store.RevokeSession(r.Context(), info.OwnerID, info.SessionID, "logout", s.Now()); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "Session not found", correlationOf(r))
			return
		}
		s.databaseOrInternal(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) me(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	user, err := s.Store.GetUser(r.Context(), info.OwnerID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", "Account not found", correlationOf(r))
		return
	}
	consents, err := s.Store.ListConsents(r.Context(), info.OwnerID, 50)
	if err != nil {
		s.databaseOrInternal(w, r, err)
		return
	}
	latest := map[string]any{"GMAIL_READ": nil, "AI_PROCESSING": nil}
	items := make([]map[string]any, 0, len(consents))
	seen := map[string]bool{}
	for _, consent := range consents {
		items = append(items, map[string]any{
			"id": consent.ID, "purpose": consent.Purpose, "decision": consent.Decision,
			"noticeVersion": consent.NoticeVersion, "decidedAt": consent.CreatedAt,
		})
		if !seen[consent.Purpose] {
			latest[consent.Purpose] = consent.Decision
			seen[consent.Purpose] = true
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"displayName": nullString(user.DisplayName), "role": user.Role,
		"consents": items, "status": latest, "lastSummaryAt": nil, "lastAnalysisAt": nil,
		"deletionInProgress": false,
	})
}

func (s *Server) fingerprintKey(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	key, version, err := s.Store.FingerprintKey(r.Context(), info.OwnerID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", "Fingerprint key not found", correlationOf(r))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"keyVersion": version, "keyBase64": base64.StdEncoding.EncodeToString(key),
	})
}

func (s *Server) databaseOrInternal(w http.ResponseWriter, r *http.Request, err error) {
	if isDatabaseUnavailable(err) {
		writeError(w, http.StatusServiceUnavailable, "database_unavailable", "Database is unavailable", correlationOf(r))
		return
	}
	s.Log.Error("request_failed", "correlationId", correlationOf(r), "error", err.Error())
	writeError(w, http.StatusInternalServerError, "internal_error", "Unexpected error", correlationOf(r))
}

func validEmail(raw string) bool {
	address, err := mail.ParseAddress(raw)
	return err == nil && address.Address == raw && len(raw) <= 254 && strings.Contains(raw, "@")
}

func secureEqual(left, right string) bool {
	a := sha256.Sum256([]byte(left))
	b := sha256.Sum256([]byte(right))
	return subtle.ConstantTimeCompare(a[:], b[:]) == 1
}

func nullString(value string) any {
	if value == "" {
		return nil
	}
	return value
}
