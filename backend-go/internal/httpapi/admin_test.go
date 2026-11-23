package httpapi

import (
	"strings"
	"testing"
)

func promoteAdmin(t *testing.T, h *harness, email string) string {
	t.Helper()
	if _, err := h.store.DB.Exec(`UPDATE users SET role = 'ADMIN', revision = revision + 1
		WHERE email = ?`, email); err != nil {
		t.Fatalf("promote admin: %v", err)
	}
	token, _ := h.login(email)
	return token
}

func inviteAccount(t *testing.T, h *harness, adminToken, email, role string) string {
	t.Helper()
	status, body := h.request("POST", "/v1/admin/invitations", adminToken, map[string]any{
		"email": email, "role": role, "expiresInHours": 2,
	})
	if status != 201 {
		t.Fatalf("create invitation %s: %d %#v", email, status, body)
	}
	token, _ := body["invitationToken"].(string)
	if token == "" || body["invitation"] == nil {
		t.Fatalf("invitation response: %#v", body)
	}
	return token
}

func registerWithInvitation(h *harness, email, inviteToken string) (int, map[string]any) {
	return h.request("POST", "/v1/accounts", "", map[string]any{
		"email": email, "password": testPassword, "displayName": "Invited",
		"inviteCode": "pam-test", "invitationToken": inviteToken,
	})
}

func TestAdminAuthorizationAndAccountListing(t *testing.T) {
	h := newHarness(t)
	h.register("admin@example.test")
	h.register("user@example.test")
	userToken, _ := h.login("user@example.test")
	adminToken := promoteAdmin(t, h, "admin@example.test")

	for _, path := range []string{"/v1/admin/accounts", "/v1/admin/audit"} {
		if status, body := h.request("GET", path, userToken, nil); status != 403 ||
			body["code"] != "forbidden" {
			t.Fatalf("USER %s: %d %#v", path, status, body)
		}
	}
	if status, _ := h.request("POST", "/v1/admin/invitations", userToken,
		map[string]any{"email": "x@example.test", "role": "ADMIN"}); status != 403 {
		t.Fatalf("USER invitation: %d", status)
	}

	hostile := strings.Repeat("x", 300) + `" OR 1=1 --`
	if _, err := h.store.DB.Exec(`UPDATE users SET email = ? WHERE email = 'user@example.test'`,
		"user@exa"+hostile+"mple.test"); err != nil {
		t.Fatal(err)
	}

	status, body := h.request("GET", "/v1/admin/accounts?q=admin", adminToken, nil)
	if status != 200 || len(body["items"].([]any)) != 1 {
		t.Fatalf("admin account search: %d %#v", status, body)
	}
	account := body["items"].([]any)[0].(map[string]any)
	if account["email"] != "admin@example.test" || account["role"] != "ADMIN" ||
		account["status"] != "ACTIVE" {
		t.Fatalf("account view: %#v", account)
	}
	for _, forbidden := range []string{"passwordHash", "salt", "kdf", "accessToken", "refreshToken"} {
		if _, ok := account[forbidden]; ok {
			t.Fatalf("account view leaked %s: %#v", forbidden, account)
		}
	}

	// Listing is paginated with a cursor bound to the filter scope.
	status, pageOne := h.request("GET", "/v1/admin/accounts?limit=1", adminToken, nil)
	if status != 200 || len(pageOne["items"].([]any)) != 1 {
		t.Fatalf("account page one: %d %#v", status, pageOne)
	}
	seen := map[string]bool{}
	cursor := ""
	for i := 0; i < 5; i++ {
		path := "/v1/admin/accounts?limit=1"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		status, page := h.request("GET", path, adminToken, nil)
		if status != 200 {
			t.Fatalf("account page %d: %d %#v", i, status, page)
		}
		for _, raw := range page["items"].([]any) {
			id := raw.(map[string]any)["ownerId"].(string)
			if seen[id] {
				t.Fatalf("duplicate account %s", id)
			}
			seen[id] = true
		}
		next, _ := page["nextCursor"].(string)
		if next == "" {
			break
		}
		cursor = next
	}
	if len(seen) != 2 {
		t.Fatalf("account pages returned %d of 2 accounts", len(seen))
	}
	if status, body := h.request("GET", "/v1/admin/accounts?q=admin&cursor="+cursor, adminToken, nil); status != 400 ||
		body["code"] != "invalid_cursor" {
		t.Fatalf("account cursor scope: %d %#v", status, body)
	}
}

func TestAdminInvitationIsOneTimeAndControlled(t *testing.T) {
	h := newHarness(t)
	h.register("inviter@example.test")
	h.register("public@example.test")
	adminToken := promoteAdmin(t, h, "inviter@example.test")

	// Public registration remains USER-only: an unknown role field is rejected outright, and a
	// normal public registration always lands as USER.
	status, hostile := h.request("POST", "/v1/accounts", "", map[string]any{
		"email": "sneaky@example.test", "password": testPassword, "inviteCode": "pam-test",
		"role": "ADMIN",
	})
	if status != 400 {
		t.Fatalf("public role injection: %d %#v", status, hostile)
	}
	status, public := h.request("POST", "/v1/accounts", "", map[string]any{
		"email": "plain@example.test", "password": testPassword, "inviteCode": "pam-test",
	})
	if status != 201 || public["role"] != "USER" {
		t.Fatalf("public registration role: %d %#v", status, public)
	}

	inviteToken := inviteAccount(t, h, adminToken, "invited@example.test", "ADMIN")
	status, accepted := registerWithInvitation(h, "invited@example.test", inviteToken)
	if status != 201 || accepted["role"] != "ADMIN" {
		t.Fatalf("invited registration: %d %#v", status, accepted)
	}
	invitedToken, _ := h.login("invited@example.test")
	if status, _ := h.request("GET", "/v1/admin/accounts", invitedToken, nil); status != 200 {
		t.Fatalf("invited admin cannot list accounts: %d", status)
	}

	// The token is single-use and bound to one email.
	status, body := registerWithInvitation(h, "invited2@example.test", inviteToken)
	if status != 400 || body["code"] != "invalid_invitation" {
		t.Fatalf("reuse invitation for another email: %d %#v", status, body)
	}
	status, body = registerWithInvitation(h, "invited@example.test", inviteToken)
	if status != 409 || body["code"] != "invitation_used" {
		t.Fatalf("reuse invitation: %d %#v", status, body)
	}

	// Expired invitations are rejected and cannot create accounts.
	expiredToken := inviteAccount(t, h, adminToken, "expired@example.test", "USER")
	if _, err := h.store.DB.Exec(`UPDATE admin_invitations SET expires_at = ? WHERE email = ?`,
		"2020-01-01T00:00:00Z", "expired@example.test"); err != nil {
		t.Fatal(err)
	}
	status, body = registerWithInvitation(h, "expired@example.test", expiredToken)
	if status != 410 || body["code"] != "invitation_expired" {
		t.Fatalf("expired invitation: %d %#v", status, body)
	}
	if _, err := h.store.DB.Exec(`SELECT 1`); err != nil {
		t.Fatal(err)
	}
	if status, _ := h.request("POST", "/v1/sessions", "",
		map[string]any{"email": "expired@example.test", "password": testPassword}); status != 401 {
		t.Fatalf("expired invitation created an account: %d", status)
	}

	status, audit := h.request("GET", "/v1/admin/audit?action=admin.invitation.create", adminToken, nil)
	if status != 200 || len(audit["items"].([]any)) < 2 {
		t.Fatalf("invitation audit: %d %#v", status, audit)
	}
}

func TestAdminAccountUpdateRevisionLastAdminAndDeactivation(t *testing.T) {
	h := newHarness(t)
	h.register("boss@example.test")
	h.register("staff@example.test")
	adminToken := promoteAdmin(t, h, "boss@example.test")
	staffToken, staffRefresh := h.login("staff@example.test")
	status, staff := h.request("GET", "/v1/admin/accounts?q=staff", adminToken, nil)
	if status != 200 || len(staff["items"].([]any)) != 1 {
		t.Fatalf("find staff: %d %#v", status, staff)
	}
	staffID := staff["items"].([]any)[0].(map[string]any)["ownerId"].(string)

	status, updated := h.request("PATCH", "/v1/admin/accounts/"+staffID, adminToken, map[string]any{
		"displayName": "Staff Baru", "revision": 0,
	})
	if status != 200 || updated["displayName"] != "Staff Baru" || updated["revision"].(float64) != 1 {
		t.Fatalf("rename account: %d %#v", status, updated)
	}
	if status, body := h.request("PATCH", "/v1/admin/accounts/"+staffID, adminToken, map[string]any{
		"displayName": "Stale", "revision": 0,
	}); status != 409 || body["code"] != "revision_conflict" {
		t.Fatalf("stale account patch: %d %#v", status, body)
	}

	// Role change revokes the old session and the next login carries the new role.
	status, promoted := h.request("PATCH", "/v1/admin/accounts/"+staffID, adminToken, map[string]any{
		"role": "ADMIN", "revision": 1,
	})
	if status != 200 || promoted["role"] != "ADMIN" {
		t.Fatalf("promote account: %d %#v", status, promoted)
	}
	if status, _ := h.request("GET", "/v1/transactions?month=2026-11", staffToken, nil); status != 401 {
		t.Fatalf("stale role token still works: %d", status)
	}
	if status, _ := h.request("POST", "/v1/sessions/refresh", "", map[string]any{
		"refreshToken": staffRefresh,
	}); status == 200 || status == 201 {
		t.Fatalf("revoked session refreshed: %d", status)
	}
	if token, _ := h.login("staff@example.test"); func() bool {
		s, _ := h.request("GET", "/v1/admin/accounts", token, nil)
		return s == 200
	}() != true {
		t.Fatal("promoted account cannot use admin API after re-login")
	}

	// The only remaining active admin cannot be demoted.
	if status, body := h.request("PATCH", "/v1/admin/accounts/"+staffID, adminToken, map[string]any{
		"role": "ADMIN", "revision": 2,
	}); status != 200 {
		t.Fatalf("idempotent admin role patch: %d %#v", status, body)
	}

	// Deactivating a user revokes sessions and blocks new logins.
	status, deactivated := h.request("PATCH", "/v1/admin/accounts/"+staffID, adminToken, map[string]any{
		"status": "SUSPENDED", "revision": 3,
	})
	if status != 200 || deactivated["status"] != "SUSPENDED" {
		t.Fatalf("suspend account: %d %#v", status, deactivated)
	}
	if status, _ := h.request("POST", "/v1/sessions", "",
		map[string]any{"email": "staff@example.test", "password": testPassword}); status != 403 {
		t.Fatalf("suspended account login: %d", status)
	}

	// A fresh ACTIVE user is deactivated through DELETE and its metadata survives.
	h.register("gone@example.test")
	status, listed := h.request("GET", "/v1/admin/accounts?q=gone", adminToken, nil)
	if status != 200 {
		t.Fatalf("find gone: %d", status)
	}
	goneID := listed["items"].([]any)[0].(map[string]any)["ownerId"].(string)
	goneToken, _ := h.login("gone@example.test")
	status, deleted := h.request("DELETE", "/v1/admin/accounts/"+goneID, adminToken, nil)
	if status != 200 || deleted["status"] != "SUSPENDED" {
		t.Fatalf("deactivate account: %d %#v", status, deleted)
	}
	if status, _ := h.request("GET", "/v1/transactions?month=2026-11", goneToken, nil); status != 401 {
		t.Fatalf("deactivated session still works: %d", status)
	}
	if status, _ := h.request("PATCH", "/v1/admin/accounts/"+goneID, adminToken,
		map[string]any{"displayName": "Nope", "revision": 4}); status != 409 {
		t.Fatalf("stale revision after delete: %d", status)
	}

	// An admin cannot deactivate themselves, and the last admin cannot be demoted.
	// boss is the only ACTIVE admin left (staff is suspended).
	status, self := h.request("GET", "/v1/admin/accounts?q=boss", adminToken, nil)
	if status != 200 {
		t.Fatalf("find boss: %d", status)
	}
	bossID := self["items"].([]any)[0].(map[string]any)["ownerId"].(string)
	if status, body := h.request("DELETE", "/v1/admin/accounts/"+bossID, adminToken, nil); status != 400 ||
		body["code"] != "invalid_request" {
		t.Fatalf("self deactivation: %d %#v", status, body)
	}
	bossRevision := self["items"].([]any)[0].(map[string]any)["revision"].(float64)
	status, body := h.request("PATCH", "/v1/admin/accounts/"+bossID, adminToken,
		map[string]any{"role": "USER", "revision": int(bossRevision)})
	if status != 409 || body["code"] != "last_admin" {
		t.Fatalf("last admin demotion: %d %#v", status, body)
	}

	status, audit := h.request("GET", "/v1/admin/audit?action=admin.account.deactivate", adminToken, nil)
	if status != 200 || len(audit["items"].([]any)) == 0 {
		t.Fatalf("deactivation audit: %d %#v", status, audit)
	}
	raw := strings.ToLower(audit["items"].([]any)[0].(map[string]any)["action"].(string))
	if strings.Contains(raw, "password") || strings.Contains(raw, "token") {
		t.Fatalf("audit action leaked sensitive data: %s", raw)
	}
}
