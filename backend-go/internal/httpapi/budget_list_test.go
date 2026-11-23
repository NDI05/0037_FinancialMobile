package httpapi

import (
	"testing"
	"time"

)

// TestBudgetPlanListRangePagingScopeAndValidation locks the C15 budget period/metadata search
// contract used by the budget screen: GET /v1/budget-plans lists owner months newest-first,
// optionally bounded by an inclusive from/to range, pages with an opaque scope-bound cursor and
// returns the same derived totals/gap as the single-month detail.
func TestBudgetPlanListRangePagingScopeAndValidation(t *testing.T) {
	h := newHarness(t)
	h.register("budgetlist@example.test")
	h.register("budgetother@example.test")
	token, _ := h.login("budgetlist@example.test")
	otherToken, _ := h.login("budgetother@example.test")
	food := h.category(token, "food")
	otherFood := h.category(otherToken, "food")

	put := func(t *testing.T, accessToken, month string, body map[string]any) map[string]any {
		t.Helper()
		status, created := h.request("PUT", "/v1/budget-plans/"+month, accessToken, body)
		if status != 200 {
			t.Fatalf("put budget %s: %d %#v", month, status, created)
		}
		return created
	}
	put(t, otherToken, "2026-10", map[string]any{
		"expectedIncomeMinor": 1_000_000, "plannedSavingsMinor": 0,
		"limits": []map[string]any{{"categoryId": otherFood, "amountMinor": 100_000}},
	})
	september := put(t, token, "2026-09", map[string]any{
		"expectedIncomeMinor": 4_000_000, "plannedSavingsMinor": 500_000,
		"limits": []map[string]any{{"categoryId": food, "amountMinor": 750_000}},
		"attributes": map[string]any{"catatan": "anggaran awal", "currency": "IDR"},
	})
	if september["gapMinor"] != float64(2_750_000) || september["totalLimitMinor"] != float64(750_000) {
		t.Fatalf("detail derived totals: %#v", september)
	}
	put(t, token, "2026-10", map[string]any{
		"expectedIncomeMinor": 5_000_000, "plannedSavingsMinor": 1_000_000,
		"limits": []map[string]any{{"categoryId": food, "amountMinor": 1_250_000}},
	})
	put(t, token, "2026-11", map[string]any{
		"expectedIncomeMinor": 6_000_000, "plannedSavingsMinor": 1_500_000,
		"limits": []map[string]any{{"categoryId": food, "amountMinor": 2_000_000}},
	})

	// Default listing: every owner month, newest first, detail-equivalent payload.
	status, list := h.request("GET", "/v1/budget-plans?limit=100", token, nil)
	if status != 200 {
		t.Fatalf("list budgets: %d %#v", status, list)
	}
	plans := list["plans"].([]any)
	if len(plans) != 3 {
		t.Fatalf("default listing returned %d plans: %#v", len(plans), plans)
	}
	for i, want := range []string{"2026-11", "2026-10", "2026-09"} {
		plan := plans[i].(map[string]any)
		if plan["monthId"] != want {
			t.Fatalf("plan %d monthId=%v, want %s", i, plan["monthId"], want)
		}
		if _, ok := plan["gapMinor"]; !ok {
			t.Fatalf("listed plan %v lost gapMinor", plan["monthId"])
		}
		if _, ok := plan["totalLimitMinor"]; !ok {
			t.Fatalf("listed plan %v lost totalLimitMinor", plan["monthId"])
		}
		limits := plan["limits"].([]any)
		if len(limits) != 1 || limits[0].(map[string]any)["categoryCode"] != "food" {
			t.Fatalf("listed plan %v limits: %#v", plan["monthId"], limits)
		}
	}
	last := plans[2].(map[string]any)
	if last["attributes"].(map[string]any)["catatan"] != "anggaran awal" ||
		last["attributes"].(map[string]any)["currency"] != "IDR" {
		t.Fatalf("listed plan lost sealed attributes: %#v", last["attributes"])
	}
	// The listed payload must equal the detail payload for the same month.
	_, detail := h.request("GET", "/v1/budget-plans/2026-09", token, nil)
	for _, key := range []string{"expectedIncomeMinor", "plannedSavingsMinor", "gapMinor",
		"totalLimitMinor", "revision"} {
		if detail[key] != last[key] {
			t.Fatalf("list/detail disagree on %s: %v vs %v", key, last[key], detail[key])
		}
	}

	// Inclusive from/to range.
	status, ranged := h.request("GET", "/v1/budget-plans?from=2026-10&to=2026-10", token, nil)
	if status != 200 || len(ranged["plans"].([]any)) != 1 {
		t.Fatalf("inclusive range: %d %#v", status, ranged)
	}
	if ranged["plans"].([]any)[0].(map[string]any)["monthId"] != "2026-10" {
		t.Fatalf("range returned wrong month: %#v", ranged)
	}
	status, openRange := h.request("GET", "/v1/budget-plans?from=2026-10&to=2026-12", token, nil)
	if status != 200 || len(openRange["plans"].([]any)) != 2 {
		t.Fatalf("open range upper bound: %d %#v", status, openRange)
	}

	// Invalid period arguments are clean 400s, not a 500 or a silently dropped filter.
	for _, path := range []string{
		"/v1/budget-plans?from=2026-10",
		"/v1/budget-plans?to=2026-10",
		"/v1/budget-plans?from=2026-13&to=2026-12",
		"/v1/budget-plans?from=2026-10&to=2026-9",
		"/v1/budget-plans?from=2026-10-01&to=2026-12",
	} {
		status, body := h.request("GET", path, token, nil)
		if status != 400 || body["code"] != "invalid_request" {
			t.Fatalf("%s: %d %#v", path, status, body)
		}
	}

	// limit=1 walks every plan once, newest-first, and the last page closes the cursor.
	seen := map[string]bool{}
	cursor := ""
	for page := 0; page < 5; page++ {
		path := "/v1/budget-plans?limit=1"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		status, body := h.request("GET", path, token, nil)
		if status != 200 {
			t.Fatalf("budget page %d: %d %#v", page, status, body)
		}
		for _, raw := range body["plans"].([]any) {
			month := raw.(map[string]any)["monthId"].(string)
			if seen[month] {
				t.Fatalf("duplicate month %s across pages", month)
			}
			seen[month] = true
		}
		next, _ := body["nextCursor"].(string)
		if next == "" {
			break
		}
		cursor = next
	}
	if len(seen) != 3 {
		t.Fatalf("budget paging returned %d of 3 months", len(seen))
	}

	// The cursor is opaque and bound to owner+range; replaying it elsewhere is a clean 400.
	_, firstPage := h.request("GET", "/v1/budget-plans?limit=1", token, nil)
	scopedCursor := firstPage["nextCursor"].(string)
	if status, body := h.request("GET", "/v1/budget-plans?from=2026-09&to=2026-11&limit=1&cursor="+
		scopedCursor, token, nil); status != 400 || body["code"] != "invalid_cursor" {
		t.Fatalf("cursor replayed with another range: %d %#v", status, body)
	}
	if status, body := h.request("GET", "/v1/budget-plans?limit=1&cursor="+scopedCursor,
		otherToken, nil); status != 400 || body["code"] != "invalid_cursor" {
		t.Fatalf("other owner replayed cursor: %d %#v", status, body)
	}
	if status, body := h.request("GET", "/v1/budget-plans?limit=1&cursor=not-base64", token, nil); status != 400 ||
		body["code"] != "invalid_cursor" {
		t.Fatalf("malformed cursor: %d %#v", status, body)
	}

	// Owner isolation: the other account only ever sees its own month.
	status, otherList := h.request("GET", "/v1/budget-plans?limit=100", otherToken, nil)
	if status != 200 || len(otherList["plans"].([]any)) != 1 {
		t.Fatalf("other owner listing: %d %#v", status, otherList)
	}
	if otherList["plans"].([]any)[0].(map[string]any)["monthId"] != "2026-10" {
		t.Fatalf("other owner saw a foreign month: %#v", otherList)
	}

	// A deleted month disappears from the list and from the range.
	if status, body := h.request("DELETE", "/v1/budget-plans/2026-10", token, nil); status != 204 {
		t.Fatalf("delete budget: %d %#v", status, body)
	}
	status, afterDelete := h.request("GET", "/v1/budget-plans?limit=100", token, nil)
	if status != 200 || len(afterDelete["plans"].([]any)) != 2 {
		t.Fatalf("post-delete listing: %d %#v", status, afterDelete)
	}
	status, deletedRange := h.request("GET", "/v1/budget-plans?from=2026-10&to=2026-10", token, nil)
	if status != 200 || len(deletedRange["plans"].([]any)) != 0 {
		t.Fatalf("post-delete range: %d %#v", status, deletedRange)
	}

	ownerID := h.ownerIDOf("budgetlist@example.test")
	seedTransaction(h, ownerID, food, "DEBIT", "belanja november", 500_000,
		time.Date(2026, 11, 10, 4, 0, 0, 0, time.UTC))

	// C12 evidence: the budget payload (limit per category) plus the summary payload (actual per
	// category) already give the UI everything it needs for actual-vs-limit; no speculative field
	// is added on the server. The numbers below must agree on the same category id and month.
	_, summary := h.request("GET", "/v1/summaries/2026-11", token, nil)
	if summary["categoryTotals"] == nil {
		t.Fatalf("summary has no categoryTotals: %#v", summary)
	}
	// The summary keys totals by categoryCode, which is exactly what budget limits also carry, so
	// the UI can join actual (summary.categoryTotals[].amountMinor) to limit
	// (budget.limits[].amountMinor) without a new server-side field.
	findTotal := func(body map[string]any, code string) map[string]any {
		for _, raw := range body["categoryTotals"].([]any) {
			total := raw.(map[string]any)
			if total["categoryCode"] == code {
				return total
			}
		}
		return nil
	}
	if findTotal(summary, "food") == nil {
		t.Fatalf("summary categoryTotals missing the budgeted category: %#v", summary["categoryTotals"])
	}
	_, november := h.request("GET", "/v1/budget-plans/2026-11", token, nil)
	limit := november["limits"].([]any)[0].(map[string]any)
	if limit["categoryId"] != food || limit["categoryCode"] != "food" ||
		limit["amountMinor"] != float64(2_000_000) {
		t.Fatalf("budget limit mismatch: %#v", limit)
	}
	if november["gapMinor"] != float64(6_000_000-1_500_000-2_000_000) {
		t.Fatalf("gapMinor does not match income - savings - limits: %#v", november)
	}

	// The stored plan survives a transaction in the same month, proving actual and plan are
	// independently addressable (this is what C12 needs; the UI joins them by categoryId).
	_, summaryAfter := h.request("GET", "/v1/summaries/2026-11", token, nil)
	totalsAfter := findTotal(summaryAfter, "food")
	if totalsAfter == nil || totalsAfter["amountMinor"].(float64) < 500_000 {
		t.Fatalf("summary category actual does not include the seeded spend: %#v", summaryAfter["categoryTotals"])
	}
	_, novemberAfter := h.request("GET", "/v1/budget-plans/2026-11", token, nil)
	if novemberAfter["limits"].([]any)[0].(map[string]any)["amountMinor"] != float64(2_000_000) {
		t.Fatalf("transaction mutated the budget plan: %#v", novemberAfter)
	}
}
