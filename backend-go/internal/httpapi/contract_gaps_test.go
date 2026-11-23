package httpapi

import (
	"net/url"
	"strings"
	"testing"
)

// TestAttachmentSearchWhitespaceAndOwnerScope locks the C20 search contract: q filters the sealed
// file name (and hash) case-insensitively with a normalized term, whitespace behaves like no filter,
// and no-filter paging does not over-fetch or lose rows.
func TestAttachmentSearchWhitespaceAndOwnerScope(t *testing.T) {
	h := newHarness(t)
	h.register("attachsearch@example.test")
	h.register("attachother@example.test")
	token, _ := h.login("attachsearch@example.test")
	otherToken, _ := h.login("attachother@example.test")
	txID := createAttachmentTx(t, h, token)
	otherTx := createAttachmentTx(t, h, otherToken)

	seed := func(t *testing.T, accessToken, tx, fileName, mime, storageRef string) {
		t.Helper()
		status, created := h.request("POST", "/v1/attachments", accessToken,
			attachmentBody(tx, fileName, mime, storageRef))
		if status != 201 {
			t.Fatalf("seed attachment %s: %d %#v", fileName, status, created)
		}
	}
	seed(t, token, txID, "struk-kopi.pdf", "application/pdf", "attachments/2026/11/struk-kopi.pdf")
	seed(t, token, txID, "struk-teh.pdf", "application/pdf", "attachments/2026/11/struk-teh.pdf")
	seed(t, token, txID, "nota-kopi.heic", "image/heic", "attachments/2026/11/nota-kopi.heic")
	seed(t, otherToken, otherTx, "struk-kopi.pdf", "application/pdf", "attachments/2026/11/other-kopi.pdf")

	status, all := h.request("GET", "/v1/attachments?transactionId="+txID+"&limit=100", token, nil)
	if status != 200 || len(all["items"].([]any)) != 3 {
		t.Fatalf("plain listing: %d %#v", status, all)
	}
	status, blank := h.request("GET", "/v1/attachments?transactionId="+txID+"&q=%20%20", token, nil)
	if status != 200 || len(blank["items"].([]any)) != 3 {
		t.Fatalf("blank q must equal no filter: %d %#v", status, blank)
	}

	// No-filter paging with limit+1 fetching still walks every row exactly once.
	seen := map[string]bool{}
	cursor := ""
	for page := 0; page < 5; page++ {
		path := "/v1/attachments?transactionId=" + txID + "&limit=2"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		status, body := h.request("GET", path, token, nil)
		if status != 200 {
			t.Fatalf("attachment page %d: %d %#v", page, status, body)
		}
		for _, raw := range body["items"].([]any) {
			id := raw.(map[string]any)["id"].(string)
			if seen[id] {
				t.Fatalf("duplicate attachment %s across pages", id)
			}
			seen[id] = true
		}
		next, _ := body["nextCursor"].(string)
		if next == "" {
			break
		}
		cursor = next
	}
	if len(seen) != 3 {
		t.Fatalf("attachment paging returned %d of 3 rows", len(seen))
	}

	// Cursor issued for the unfiltered scope is accepted for whitespace-only q (normalized scope).
	_, firstPage := h.request("GET", "/v1/attachments?transactionId="+txID+"&limit=1", token, nil)
	scopedCursor := firstPage["nextCursor"].(string)
	if scopedCursor == "" {
		t.Fatalf("attachment page did not issue a cursor: %#v", firstPage)
	}
	if status, body := h.request("GET", "/v1/attachments?transactionId="+txID+"&limit=1&q=%20&cursor="+
		scopedCursor, token, nil); status != 200 {
		t.Fatalf("blank q reused unfiltered cursor: %d %#v", status, body)
	}

	// q filters the sealed file name case-insensitively, with surrounding spaces trimmed.
	for _, q := range []string{"kopi", "KOPI", "  kopi  ", "STRUK-TEH"} {
		status, body := h.request("GET", "/v1/attachments?transactionId="+txID+"&q="+url.QueryEscape(q),
			token, nil)
		if status != 200 {
			t.Fatalf("q=%q: %d %#v", q, status, body)
		}
		items := body["items"].([]any)
		want := 2
		if q == "STRUK-TEH" {
			want = 1
		}
		if len(items) != want {
			t.Fatalf("q=%q returned %d attachments, want %d", q, len(items), want)
		}
		for _, raw := range items {
			fileName := strings.ToLower(raw.(map[string]any)["fileName"].(string))
			if !strings.Contains(fileName, strings.ToLower(strings.TrimSpace(q))) {
				t.Fatalf("q=%q matched unrelated attachment %v", q, fileName)
			}
		}
	}
	if status, body := h.request("GET", "/v1/attachments?transactionId="+txID+"&q=zzz-no-match",
		token, nil); status != 200 || len(body["items"].([]any)) != 0 {
		t.Fatalf("no-match attachment search: %d %#v", status, body)
	}
	// Unsupported mime filter is still a clean 400 before any scanning.
	if status, body := h.request("GET", "/v1/attachments?mime=image/gif", token, nil); status != 400 ||
		body["code"] != "invalid_request" {
		t.Fatalf("invalid mime filter: %d %#v", status, body)
	}

	// Owner scope: same-named attachment of another owner is never listed and their cursor is 400.
	status, otherList := h.request("GET", "/v1/attachments?q=kopi", otherToken, nil)
	if status != 200 || len(otherList["items"].([]any)) != 1 {
		t.Fatalf("other owner listing: %d %#v", status, otherList)
	}
	if status, body := h.request("GET", "/v1/attachments?transactionId="+txID+"&limit=1&cursor="+
		scopedCursor, otherToken, nil); status != 400 || body["code"] != "invalid_cursor" {
		t.Fatalf("other owner reused attachment cursor: %d %#v", status, body)
	}
}

// TestAdminAccountListFiltersAndDeletedExclusion locks the C25 admin listing contract: the default
// listing excludes soft-deleted accounts, role/status filters are validated and applied, and a
// whitespace-only q is the same listing.
func TestAdminAccountListFiltersAndDeletedExclusion(t *testing.T) {
	h := newHarness(t)
	h.register("masteradmin@example.test")
	h.register("plainuser@example.test")
	h.register("suspended@example.test")
	h.register("purged@example.test")
	adminToken := promoteAdmin(t, h, "masteradmin@example.test")

	// DELETE /v1/admin/accounts/{ownerID} suspends; the DELETED status is written by the account
	// purge path, so the filter test seeds it directly (same technique as promoteAdmin).
	if status, body := h.request("DELETE", "/v1/admin/accounts/"+
		h.ownerIDOf("suspended@example.test"), adminToken, nil); status != 200 {
		t.Fatalf("suspend account: %d %#v", status, body)
	}
	if _, err := h.store.DB.Exec(`UPDATE users SET status = 'DELETED', revision = revision + 1
		WHERE email = ?`, "purged@example.test"); err != nil {
		t.Fatalf("seed deleted account: %v", err)
	}

	emails := func(body map[string]any) []string {
		var out []string
		for _, raw := range body["items"].([]any) {
			out = append(out, raw.(map[string]any)["email"].(string))
		}
		return out
	}
	status, all := h.request("GET", "/v1/admin/accounts?limit=100", adminToken, nil)
	if status != 200 {
		t.Fatalf("admin listing: %d %#v", status, all)
	}
	if len(all["items"].([]any)) != 3 {
		t.Fatalf("default listing must exclude DELETED: %#v", emails(all))
	}
	for _, email := range emails(all) {
		if email == "purged@example.test" {
			t.Fatalf("default listing leaked a DELETED account: %#v", emails(all))
		}
	}
	status, blank := h.request("GET", "/v1/admin/accounts?q=%20%20&limit=100", adminToken, nil)
	if status != 200 || len(blank["items"].([]any)) != 3 {
		t.Fatalf("whitespace q changed admin listing: %d %v", status, emails(blank))
	}

	status, deleted := h.request("GET", "/v1/admin/accounts?status=DELETED", adminToken, nil)
	if status != 200 || len(deleted["items"].([]any)) != 1 ||
		emails(deleted)[0] != "purged@example.test" {
		t.Fatalf("status=DELETED: %d %v", status, emails(deleted))
	}
	status, suspended := h.request("GET", "/v1/admin/accounts?status=SUSPENDED", adminToken, nil)
	if status != 200 || len(suspended["items"].([]any)) != 1 ||
		emails(suspended)[0] != "suspended@example.test" {
		t.Fatalf("status=SUSPENDED: %d %v", status, emails(suspended))
	}
	status, admins := h.request("GET", "/v1/admin/accounts?role=ADMIN&limit=100", adminToken, nil)
	if status != 200 || len(admins["items"].([]any)) != 1 {
		t.Fatalf("role=ADMIN: %d %v", status, emails(admins))
	}
	if admins["items"].([]any)[0].(map[string]any)["role"] != "ADMIN" {
		t.Fatalf("role filter leaked a non-admin: %#v", admins["items"])
	}
	status, users := h.request("GET", "/v1/admin/accounts?role=USER&limit=100", adminToken, nil)
	if status != 200 || len(users["items"].([]any)) != 2 {
		t.Fatalf("role=USER: %d %v", status, emails(users))
	}
	for _, email := range emails(users) {
		if email == "masteradmin@example.test" || email == "purged@example.test" {
			t.Fatalf("role=USER leaked %s", email)
		}
	}
	// Status is case-insensitive on input and invalid values are rejected before the query.
	if status, upper := h.request("GET", "/v1/admin/accounts?status=suspended", adminToken, nil); status != 200 ||
		len(upper["items"].([]any)) != 1 {
		t.Fatalf("lowercase status: %d %#v", status, upper)
	}
	for _, query := range []string{"role=SUPERUSER", "status=GONE"} {
		status, body := h.request("GET", "/v1/admin/accounts?"+query, adminToken, nil)
		if status != 400 || body["code"] != "invalid_request" {
			t.Fatalf("%s: %d %#v", query, status, body)
		}
	}

	// Default paging walks the 3 non-deleted accounts once, excluding DELETED on every page.
	seen := map[string]bool{}
	cursor := ""
	for page := 0; page < 5; page++ {
		path := "/v1/admin/accounts?limit=1"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		status, body := h.request("GET", path, adminToken, nil)
		if status != 200 {
			t.Fatalf("admin page %d: %d %#v", page, status, body)
		}
		for _, email := range emails(body) {
			if email == "purged@example.test" {
				t.Fatalf("paged listing leaked a DELETED account")
			}
			if seen[email] {
				t.Fatalf("duplicate admin account %s across pages", email)
			}
			seen[email] = true
		}
		next, _ := body["nextCursor"].(string)
		if next == "" {
			break
		}
		cursor = next
	}
	if len(seen) != 3 {
		t.Fatalf("admin paging returned %d of 3 active accounts", len(seen))
	}
}
