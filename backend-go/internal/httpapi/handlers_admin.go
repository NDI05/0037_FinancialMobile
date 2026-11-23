package httpapi

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/store"
)

// Admin API. The account view intentionally contains identity/role fields only: an ADMIN must not
// be able to read another owner's financial data through this surface.

type adminInvitationRequest struct {
	Email          string `json:"email"`
	Role           string `json:"role"`
	ExpiresInHours int    `json:"expiresInHours"`
}

func adminAccountJSON(account store.AdminAccount) map[string]any {
	return map[string]any{
		"ownerId": account.OwnerID, "userId": account.OwnerID, "email": account.Email,
		"displayName": nullString(account.DisplayName), "role": account.Role,
		"status": account.Status, "revision": account.Revision,
		"createdAt": account.CreatedAt, "updatedAt": account.UpdatedAt,
	}
}

func (s *Server) createAdminInvitation(w http.ResponseWriter, r *http.Request) {
	var body adminInvitationRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	ttl := 72 * time.Hour
	if body.ExpiresInHours != 0 {
		if body.ExpiresInHours < 1 || body.ExpiresInHours > 720 {
			writeError(w, http.StatusBadRequest, "invalid_request",
				"expiresInHours must be between 1 and 720", correlationOf(r))
			return
		}
		ttl = time.Duration(body.ExpiresInHours) * time.Hour
	}
	info, _ := authFrom(r)
	invitation, token, err := s.Store.CreateAdminInvitation(r.Context(), info.OwnerID,
		body.Email, body.Role, ttl, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"invitation": map[string]any{
			"id": invitation.ID, "email": invitation.Email, "role": invitation.Role,
			"expiresAt": invitation.ExpiresAt, "createdAt": invitation.CreatedAt,
		},
		"invitationToken": token,
	})
}

func (s *Server) listAdminAccounts(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	limit, _ := strconv.Atoi(query.Get("limit"))
	page, err := s.Store.ListAdminAccounts(r.Context(), query.Get("q"), query.Get("role"),
		query.Get("status"), limit, query.Get("cursor"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	items := make([]map[string]any, 0, len(page.Items))
	for _, account := range page.Items {
		items = append(items, adminAccountJSON(account))
	}
	response := map[string]any{"accounts": items}
	if page.NextCursor != "" {
		response["nextCursor"] = page.NextCursor
	}
	writeJSON(w, http.StatusOK, response)
}

func (s *Server) getAdminAccount(w http.ResponseWriter, r *http.Request) {
	account, err := s.Store.GetAdminAccount(r.Context(), r.PathValue("ownerID"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, adminAccountJSON(account))
}

func (s *Server) updateAdminAccount(w http.ResponseWriter, r *http.Request) {
	var body struct {
		DisplayName *string `json:"displayName"`
		Role        *string `json:"role"`
		Status      *string `json:"status"`
		Revision    int     `json:"revision"`
	}
	if !decodeJSON(w, r, &body) {
		return
	}
	info, _ := authFrom(r)
	account, err := s.Store.UpdateAdminAccount(r.Context(), info.OwnerID,
		r.PathValue("ownerID"), store.AdminAccountPatch{
			DisplayName: body.DisplayName, Role: body.Role, Status: body.Status,
			Revision: body.Revision,
		}, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, adminAccountJSON(account))
}

func (s *Server) deactivateAdminAccount(w http.ResponseWriter, r *http.Request) {
	expected := -1
	if r.Header.Get("If-Match") != "" {
		expected = revisionHeader(r)
	}
	if raw := strings.TrimSpace(r.URL.Query().Get("revision")); raw != "" {
		expected, _ = strconv.Atoi(raw)
	}
	info, _ := authFrom(r)
	account, err := s.Store.DeactivateAdminAccount(r.Context(), info.OwnerID,
		r.PathValue("ownerID"), expected, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, adminAccountJSON(account))
}

func (s *Server) listAudit(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	limit, _ := strconv.Atoi(query.Get("limit"))
	page, err := s.Store.ListAudit(r.Context(), query.Get("targetOwnerId"), query.Get("action"),
		limit, query.Get("cursor"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	items := make([]map[string]any, 0, len(page.Items))
	for _, entry := range page.Items {
		items = append(items, map[string]any{
			"id": entry.ID, "actorOwnerId": entry.ActorID, "targetOwnerId": entry.TargetID,
			"action": entry.Action, "metadata": entry.Metadata, "createdAt": entry.CreatedAt,
		})
	}
	response := map[string]any{"entries": items}
	if page.NextCursor != "" {
		response["nextCursor"] = page.NextCursor
	}
	writeJSON(w, http.StatusOK, response)
}
