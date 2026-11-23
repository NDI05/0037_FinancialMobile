package httpapi

import (
	"context"
	"encoding/base64"
	"fmt"
	"testing"
	"time"

	"github.com/pam/recap/backend-go/internal/store"
)

// ownerIDOf returns the deterministic owner id the harness registered for one email.
func (h *harness) ownerIDOf(email string) string {
	h.t.Helper()
	var ownerID string
	if err := h.store.DB.QueryRow(`SELECT owner_id FROM users WHERE email = ?`, email).Scan(&ownerID); err != nil {
		h.t.Fatalf("owner id %s: %v", email, err)
	}
	return ownerID
}

// seedTransaction inserts a row straight through the store so pagination tests do not pay for a
// full HTTP round trip per row.
func seedTransaction(h *harness, ownerID, categoryID, direction, description string,
	amount int64, eventAt time.Time) string {
	h.t.Helper()
	row, err := h.store.CreateTransaction(context.Background(), ownerID, store.TransactionInput{
		CategoryID: categoryID, Direction: direction, AmountMinor: amount, Currency: "IDR",
		EventAt: eventAt, Description: description,
	}, eventAt)
	if err != nil {
		h.t.Fatalf("seed transaction: %v", err)
	}
	return row.ID
}

func TestTransactionPaginationSameTimestampIsStableAndComplete(t *testing.T) {
	h := newHarness(t)
	h.register("pager@example.test")
	token, _ := h.login("pager@example.test")
	ownerID := h.ownerIDOf("pager@example.test")
	food := h.category(token, "food")

	const rows = 105
	sameInstant := time.Date(2026, 11, 15, 4, 0, 0, 0, time.UTC)
	for i := 0; i < rows; i++ {
		seedTransaction(h, ownerID, food, "DEBIT", fmt.Sprintf("same-ts-%03d", i), 1_000, sameInstant)
	}

	seen := map[string]bool{}
	order := []string{}
	cursor := ""
	for page := 0; page < 20; page++ {
		path := "/v1/transactions?month=2026-11&limit=20"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		status, body := h.request("GET", path, token, nil)
		if status != 200 {
			t.Fatalf("page %d: %d %#v", page, status, body)
		}
		items := body["items"].([]any)
		if len(items) > 20 {
			t.Fatalf("page %d returned %d items for limit 20", page, len(items))
		}
		for _, raw := range items {
			item := raw.(map[string]any)
			id := item["id"].(string)
			if seen[id] {
				t.Fatalf("duplicate row %s across pages", id)
			}
			seen[id] = true
			order = append(order, id)
		}
		next, _ := body["nextCursor"].(string)
		if next == "" {
			break
		}
		cursor = next
	}
	if len(seen) != rows {
		t.Fatalf("paged through %d of %d rows", len(seen), rows)
	}
	// Every row shares event_at_ms, so the secondary key decides: strict descending id order.
	for i := 1; i < len(order); i++ {
		if order[i-1] <= order[i] {
			t.Fatalf("unstable tie-break at %d: %q <= %q", i, order[i-1], order[i])
		}
	}
}

func TestSearchPaginationWalksMatchesBeyondTheFirstPage(t *testing.T) {
	h := newHarness(t)
	h.register("search@example.test")
	token, _ := h.login("search@example.test")
	ownerID := h.ownerIDOf("search@example.test")
	food := h.category(token, "food")

	base := time.Date(2026, 11, 20, 3, 0, 0, 0, time.UTC)
	// Newest-first order: index 0 is the newest row. Only index 4 and 9 match.
	for i := 0; i < 12; i++ {
		description := fmt.Sprintf("ordinary-%02d", i)
		if i == 4 || i == 9 {
			description = fmt.Sprintf("kopi-%02d", i)
		}
		seedTransaction(h, ownerID, food, "DEBIT", description, 1_000, base.Add(-time.Duration(i)*time.Hour))
	}

	var matched []string
	cursor := ""
	for page := 0; page < 5; page++ {
		path := "/v1/transactions?month=2026-11&q=kopi&limit=1"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		status, body := h.request("GET", path, token, nil)
		if status != 200 {
			t.Fatalf("search page %d: %d %#v", page, status, body)
		}
		for _, raw := range body["items"].([]any) {
			matched = append(matched, raw.(map[string]any)["description"].(string))
		}
		next, _ := body["nextCursor"].(string)
		if next == "" {
			break
		}
		cursor = next
	}
	if len(matched) != 2 || matched[0] != "kopi-04" || matched[1] != "kopi-09" {
		t.Fatalf("search pages = %#v, want both matches newest-first", matched)
	}
}

func TestTransactionCursorIsBoundToItsQuery(t *testing.T) {
	h := newHarness(t)
	h.register("scope@example.test")
	token, _ := h.login("scope@example.test")
	ownerID := h.ownerIDOf("scope@example.test")
	food := h.category(token, "food")
	base := time.Date(2026, 11, 20, 3, 0, 0, 0, time.UTC)
	for i := 0; i < 4; i++ {
		seedTransaction(h, ownerID, food, "DEBIT", "kopi "+string(rune('a'+i)), 1_000,
			base.Add(-time.Duration(i)*time.Hour))
	}

	status, body := h.request("GET", "/v1/transactions?month=2026-11&q=kopi&limit=1", token, nil)
	if status != 200 {
		t.Fatalf("first page: %d %#v", status, body)
	}
	cursor, _ := body["nextCursor"].(string)
	if cursor == "" {
		t.Fatal("first page has no cursor")
	}

	// Same query: accepted.
	if status, body := h.request("GET",
		"/v1/transactions?month=2026-11&q=kopi&limit=1&cursor="+cursor, token, nil); status != 200 {
		t.Fatalf("same-scope cursor rejected: %d %#v", status, body)
	}
	// Different q, direction, or month: rejected with a clear error instead of reinterpreting.
	for _, path := range []string{
		"/v1/transactions?month=2026-11&q=teh&limit=1&cursor=" + cursor,
		"/v1/transactions?month=2026-11&direction=CREDIT&limit=1&cursor=" + cursor,
		"/v1/transactions?month=2026-10&limit=1&cursor=" + cursor,
	} {
		status, body := h.request("GET", path, token, nil)
		if status != 400 || body["code"] != "invalid_cursor" {
			t.Fatalf("foreign cursor %s: %d %#v", path, status, body)
		}
	}
	// Malformed and legacy (unscoped) cursors are rejected the same way.
	legacy := base64.RawURLEncoding.EncodeToString([]byte("1700000000000|tx_legacy"))
	for _, bad := range []string{"not-base64", legacy} {
		status, body := h.request("GET", "/v1/transactions?month=2026-11&limit=1&cursor="+bad, token, nil)
		if status != 400 || body["code"] != "invalid_cursor" {
			t.Fatalf("bad cursor %q: %d %#v", bad, status, body)
		}
	}
}

func TestTransactionLimitBoundsAndOwnerIsolation(t *testing.T) {
	h := newHarness(t)
	h.register("bounds@example.test")
	h.register("other@example.test")
	token, _ := h.login("bounds@example.test")
	otherToken, _ := h.login("other@example.test")
	ownerID := h.ownerIDOf("bounds@example.test")
	food := h.category(token, "food")
	base := time.Date(2026, 11, 21, 3, 0, 0, 0, time.UTC)
	for i := 0; i < 120; i++ {
		seedTransaction(h, ownerID, food, "DEBIT", fmt.Sprintf("row-%03d", i), 1_000,
			base.Add(-time.Duration(i)*time.Minute))
	}
	for _, limit := range []int{0, -3, 1000} {
		status, body := h.request("GET",
			fmt.Sprintf("/v1/transactions?month=2026-11&limit=%d", limit), token, nil)
		if status != 200 {
			t.Fatalf("limit %d: %d %#v", limit, status, body)
		}
		if count := len(body["items"].([]any)); count > 100 {
			t.Fatalf("limit %d returned %d rows", limit, count)
		}
	}
	status, body := h.request("GET", "/v1/transactions?month=2026-11&limit=0", token, nil)
	if status != 200 || len(body["items"].([]any)) != 50 {
		t.Fatalf("limit 0 default: %d rows", len(body["items"].([]any)))
	}

	// Cross-owner list and cursor must not leak rows.
	status, body = h.request("GET", "/v1/transactions?month=2026-11", otherToken, nil)
	if status != 200 || len(body["items"].([]any)) != 0 {
		t.Fatalf("other owner list: %d %#v", status, body)
	}
	_, page := h.request("GET", "/v1/transactions?month=2026-11&limit=1", token, nil)
	cursor := page["nextCursor"].(string)
	status, body = h.request("GET", "/v1/transactions?month=2026-11&limit=1&cursor="+cursor,
		otherToken, nil)
	if status != 400 || body["code"] != "invalid_cursor" {
		t.Fatalf("foreign owner reused cursor: %d %#v", status, body)
	}
}

func TestTransactionPatchDeleteRevisionValidationAndAdminIsolation(t *testing.T) {
	h := newHarness(t)
	h.register("editor@example.test")
	h.register("victim@example.test")
	token, _ := h.login("editor@example.test")
	victimToken, _ := h.login("victim@example.test")
	food := h.category(token, "food")
	victimFood := h.category(victimToken, "food")

	status, created := h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 10_000, "currency": "IDR",
		"eventAt": "2026-11-19T04:10:00Z", "categoryId": food, "description": "awal",
	})
	if status != 201 {
		t.Fatalf("create: %d %#v", status, created)
	}
	id := created["id"].(string)

	// Stale revision is a 409 with the revision_conflict code.
	status, stale := h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"note": "stale", "revision": 3,
	})
	if status != 409 || stale["code"] != "revision_conflict" {
		t.Fatalf("stale patch: %d %#v", status, stale)
	}
	// Immutable/provenance fields are not accepted in a patch body.
	for _, body := range []map[string]any{
		{"amountMinor": 99, "revision": 0},
		{"categoryOrigin": "LLM", "revision": 0},
		{"classifierOrigin": "LLM", "revision": 0},
		{"eventAt": "2026-11-20T00:00:00Z", "revision": 0},
	} {
		status, response := h.request("PATCH", "/v1/transactions/"+id, token, body)
		if status != 400 || response["code"] != "invalid_request" {
			t.Fatalf("immutable field %#v: %d %#v", body, status, response)
		}
	}
	// Dynamic attribute validation still applies.
	status, invalid := h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"attributes": map[string]any{"": "x"}, "revision": 0,
	})
	if status != 400 {
		t.Fatalf("invalid attributes: %d %#v", status, invalid)
	}
	// A foreign category id is not found, never silently accepted.
	status, _ = h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"categoryId": victimFood, "revision": 0,
	})
	if status != 404 {
		t.Fatalf("foreign category: %d", status)
	}

	status, updated := h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"description": "baru", "attributes": map[string]any{"tags": []string{"kopi"}}, "revision": 0,
	})
	if status != 200 || updated["revision"].(float64) != 1 {
		t.Fatalf("patch: %d %#v", status, updated)
	}

	// Delete with a stale revision is rejected; a fresh revision deletes once.
	status, conflict := h.request("DELETE", "/v1/transactions/"+id+"?revision=0", token, nil)
	if status != 409 || conflict["code"] != "revision_conflict" {
		t.Fatalf("stale delete: %d %#v", status, conflict)
	}
	if status, _ := h.request("DELETE", "/v1/transactions/"+id+"?revision=1", token, nil); status != 204 {
		t.Fatalf("delete: %d", status)
	}
	if status, _ := h.request("GET", "/v1/transactions/"+id, token, nil); status != 404 {
		t.Fatalf("deleted row still readable: %d", status)
	}

	// Promote the editor to ADMIN. Admin routing must still be owner-scoped for finance rows.
	if _, err := h.store.DB.Exec(`UPDATE users SET role = 'ADMIN', revision = revision + 1 WHERE email = ?`,
		"editor@example.test"); err != nil {
		t.Fatal(err)
	}
	adminToken, _ := h.login("editor@example.test")
	status, victimTx := h.request("POST", "/v1/transactions", victimToken, map[string]any{
		"direction": "DEBIT", "amountMinor": 42_000, "currency": "IDR",
		"eventAt": "2026-11-19T05:00:00Z", "categoryId": victimFood,
	})
	if status != 201 {
		t.Fatalf("victim create: %d %#v", status, victimTx)
	}
	victimID := victimTx["id"].(string)
	if status, _ := h.request("GET", "/v1/transactions/"+victimID, adminToken, nil); status != 404 {
		t.Fatalf("admin read foreign tx: %d", status)
	}
	if status, _ := h.request("PATCH", "/v1/transactions/"+victimID, adminToken, map[string]any{
		"note": "admin", "revision": 0,
	}); status != 404 {
		t.Fatalf("admin patched foreign tx: %d", status)
	}
	if status, _ := h.request("DELETE", "/v1/transactions/"+victimID, adminToken, nil); status != 404 {
		t.Fatalf("admin deleted foreign tx: %d", status)
	}
	status, list := h.request("GET", "/v1/transactions?month=2026-11", adminToken, nil)
	if status != 200 {
		t.Fatalf("admin list: %d", status)
	}
	for _, raw := range list["items"].([]any) {
		if raw.(map[string]any)["id"] == victimID {
			t.Fatal("admin list leaked another owner's row")
		}
	}
}

func TestCategoryListPaginationSearchAndScope(t *testing.T) {
	h := newHarness(t)
	h.register("catpager@example.test")
	h.register("catother@example.test")
	token, _ := h.login("catpager@example.test")
	otherToken, _ := h.login("catother@example.test")
	for i := 0; i < 6; i++ {
		status, body := h.request("POST", "/v1/categories", token, map[string]any{
			"code": fmt.Sprintf("custom-%02d", i), "label": fmt.Sprintf("Custom %02d", i),
			"kind": "EXPENSE", "color": "#112233",
		})
		if status != 201 {
			t.Fatalf("create category: %d %#v", status, body)
		}
	}

	seen := map[string]bool{}
	cursor := ""
	pages := 0
	for {
		path := "/v1/categories?limit=5"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		status, body := h.request("GET", path, token, nil)
		if status != 200 {
			t.Fatalf("category page: %d %#v", status, body)
		}
		pages++
		items := body["categories"].([]any)
		if len(items) > 5 {
			t.Fatalf("category page returned %d rows for limit 5", len(items))
		}
		for _, raw := range items {
			id := raw.(map[string]any)["id"].(string)
			if seen[id] {
				t.Fatalf("duplicate category %s across pages", id)
			}
			seen[id] = true
		}
		next, _ := body["nextCursor"].(string)
		if next == "" {
			break
		}
		cursor = next
	}
	if len(seen) != 17 { // 11 builtins + 6 custom
		t.Fatalf("category pages returned %d of 17 rows", len(seen))
	}
	if pages < 4 {
		t.Fatalf("expected several pages, got %d", pages)
	}

	// Search: matches label or code, no results is a clean empty page, and a cursor from one q
	// cannot be replayed against another.
	status, search := h.request("GET", "/v1/categories?q=custom&limit=2", token, nil)
	if status != 200 || len(search["categories"].([]any)) != 2 {
		t.Fatalf("category search: %d %#v", status, search)
	}
	searchCursor := search["nextCursor"].(string)
	if status, _ := h.request("GET", "/v1/categories?q=custom&limit=2&cursor="+searchCursor,
		token, nil); status != 200 {
		t.Fatalf("same-scope category cursor rejected: %d", status)
	}
	status, bad := h.request("GET", "/v1/categories?q=dompet&limit=2&cursor="+searchCursor, token, nil)
	if status != 400 || bad["code"] != "invalid_cursor" {
		t.Fatalf("foreign q cursor: %d %#v", status, bad)
	}
	status, empty := h.request("GET", "/v1/categories?q=zzz-no-match", token, nil)
	if status != 200 || len(empty["categories"].([]any)) != 0 {
		t.Fatalf("empty search: %d %#v", status, empty)
	}
	if _, ok := empty["nextCursor"]; ok {
		t.Fatalf("empty search returned a cursor: %#v", empty)
	}

	// Owner isolation: another owner's cursor is rejected and their list excludes our rows.
	status, other := h.request("GET", "/v1/categories", otherToken, nil)
	if status != 200 || len(other["categories"].([]any)) != 11 {
		t.Fatalf("other owner categories: %d %#v", status, other)
	}
	status, _ = h.request("GET", "/v1/categories?cursor="+searchCursor, otherToken, nil)
	if status != 400 {
		t.Fatalf("other owner reused category cursor: %d", status)
	}
}

func TestSummaryFreshnessAndCoverageCounts(t *testing.T) {
	h := newHarness(t)
	h.register("summary@example.test")
	token, _ := h.login("summary@example.test")
	food := h.category(token, "food")
	income := h.category(token, "income")
	transfer := h.category(token, "transfer")

	create := func(body map[string]any) map[string]any {
		status, created := h.request("POST", "/v1/transactions", token, body)
		if status != 201 {
			t.Fatalf("create %#v: %d %#v", body, status, created)
		}
		return created
	}
	create(map[string]any{"direction": "CREDIT", "amountMinor": 500_000, "currency": "IDR",
		"eventAt": "2026-11-01T02:00:00Z", "categoryId": income})
	expense := create(map[string]any{"direction": "DEBIT", "amountMinor": 100_000,
		"currency": "IDR", "eventAt": "2026-11-02T02:00:00Z", "categoryId": food})
	create(map[string]any{"direction": "DEBIT", "amountMinor": 50_000, "currency": "IDR",
		"eventAt": "2026-11-03T02:00:00Z", "categoryId": transfer, "isInternalTransfer": true})
	create(map[string]any{"direction": "CREDIT", "amountMinor": 20_000, "currency": "IDR",
		"eventAt": "2026-11-04T02:00:00Z", "categoryId": food,
		"linkedRefundOf": expense["id"].(string)})

	status, summary := h.request("GET", "/v1/summaries/2026-11", token, nil)
	if status != 200 {
		t.Fatalf("summary: %d %#v", status, summary)
	}
	checks := map[string]float64{
		"grossInflowMinor": 520_000, "grossOutflowMinor": 150_000, "netMinor": 370_000,
		"classifiedIncomeMinor": 500_000, "classifiedExpenseMinor": 100_000,
		"refundMinor": 20_000, "refundCount": 1,
		"transferInMinor": 0, "transferOutMinor": 50_000, "transferCount": 1,
		"transactionCount": 4, "categorizedCount": 4, "uncategorizedCount": 0,
	}
	for field, want := range checks {
		if got, ok := summary[field].(float64); !ok || got != want {
			t.Fatalf("summary %s = %#v, want %v", field, summary[field], want)
		}
	}
	if summary["freshness"] != "CURRENT" || summary["coverage"] != "PARTIAL" {
		t.Fatalf("freshness/coverage: %#v", summary)
	}
	if summary["currentRevision"] != summary["inputRevision"] {
		t.Fatalf("currentRevision mismatch: %#v", summary)
	}

	current := int(summary["inputRevision"].(float64))
	put := func(revision int) map[string]any {
		status, body := h.request("PUT", "/v1/summaries/2026-11", token, map[string]any{
			"inputRevision": revision, "payload": map[string]any{"coverage": "PARTIAL"},
		})
		if status != 200 {
			t.Fatalf("put summary: %d %#v", status, body)
		}
		return body
	}
	put(current - 1)
	_, stale := h.request("GET", "/v1/summaries/2026-11", token, nil)
	if stale["freshness"] != "STALE" || stale["storedInputRevision"].(float64) != float64(current-1) {
		t.Fatalf("stale summary: %#v", stale)
	}
	put(current + 5)
	_, historical := h.request("GET", "/v1/summaries/2026-11", token, nil)
	if historical["freshness"] != "HISTORICAL" {
		t.Fatalf("historical summary: %#v", historical)
	}
	put(current)
	_, fresh := h.request("GET", "/v1/summaries/2026-11", token, nil)
	if fresh["freshness"] != "CURRENT" {
		t.Fatalf("current summary: %#v", fresh)
	}
}

func TestBudgetPlanRevisionDeleteAndLimitValidation(t *testing.T) {
	h := newHarness(t)
	h.register("planner@example.test")
	h.register("planner2@example.test")
	token, _ := h.login("planner@example.test")
	otherToken, _ := h.login("planner2@example.test")
	food := h.category(token, "food")
	transport := h.category(token, "transport")
	foreign := h.category(otherToken, "food")

	if status, _ := h.request("GET", "/v1/budget-plans/2026-12", token, nil); status != 404 {
		t.Fatalf("missing plan: %d", status)
	}
	status, plan := h.request("PUT", "/v1/budget-plans/2026-12", token, map[string]any{
		"expectedIncomeMinor": 500_000_000, "plannedSavingsMinor": 100_000_000,
		"limits": []map[string]any{
			{"categoryId": food, "amountMinor": 90_000_000},
			{"categoryId": transport, "amountMinor": 10_000_000},
		},
	})
	if status != 200 {
		t.Fatalf("put plan: %d %#v", status, plan)
	}
	if plan["gapMinor"].(float64) != 300_000_000 ||
		plan["totalLimitMinor"].(float64) != 100_000_000 ||
		plan["expectedIncomeMinor"].(float64) != 500_000_000 ||
		plan["plannedSavingsMinor"].(float64) != 100_000_000 ||
		plan["revision"].(float64) != 0 {
		t.Fatalf("plan arithmetic: %#v", plan)
	}
	limits := plan["limits"].([]any)
	if len(limits) != 2 {
		t.Fatalf("limits = %#v", limits)
	}
	for _, raw := range limits {
		if raw.(map[string]any)["categoryCode"] == "" {
			t.Fatalf("limit missing categoryCode: %#v", raw)
		}
	}

	// Stale revision and foreign category are rejected.
	if status, body := h.request("PUT", "/v1/budget-plans/2026-12", token, map[string]any{
		"expectedIncomeMinor": 1, "revision": 5,
	}); status != 409 || body["code"] != "revision_conflict" {
		t.Fatalf("stale plan: %d %#v", status, body)
	}
	if status, _ := h.request("PUT", "/v1/budget-plans/2026-12", token, map[string]any{
		"expectedIncomeMinor": 1, "limits": []map[string]any{{"categoryId": foreign, "amountMinor": 1}},
		"revision": 0,
	}); status != 404 {
		t.Fatalf("foreign category plan: %d", status)
	}
	if status, body := h.request("PUT", "/v1/budget-plans/2026-12", token, map[string]any{
		"expectedIncomeMinor": 1, "limits": []map[string]any{
			{"categoryId": food, "amountMinor": 1}, {"categoryId": food, "amountMinor": 2}},
		"revision": 0,
	}); status != 400 || body["code"] != "invalid_request" {
		t.Fatalf("duplicate limit: %d %#v", status, body)
	}

	// Update then delete; a second delete is not found.
	if status, body := h.request("PUT", "/v1/budget-plans/2026-12", token, map[string]any{
		"expectedIncomeMinor": 500_000_000, "plannedSavingsMinor": 50_000_000,
		"limits": []map[string]any{{"categoryId": food, "amountMinor": 50_000_000}}, "revision": 0,
	}); status != 200 || body["revision"].(float64) != 1 {
		t.Fatalf("update plan: %d %#v", status, body)
	}
	if status, _ := h.request("DELETE", "/v1/budget-plans/2026-12", token, nil); status != 204 {
		t.Fatalf("delete plan: %d", status)
	}
	if status, _ := h.request("GET", "/v1/budget-plans/2026-12", token, nil); status != 404 {
		t.Fatalf("plan after delete: %d", status)
	}
	if status, _ := h.request("DELETE", "/v1/budget-plans/2026-12", token, nil); status != 404 {
		t.Fatalf("double delete: %d", status)
	}
}
