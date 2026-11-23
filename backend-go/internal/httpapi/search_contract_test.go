package httpapi

import (
	"net/url"
	"strings"
	"testing"
	"time"

)

// TestCategorySearchNormalizationKindPagingAndIsolation locks the C10 search contract: q matches
// the decrypted label and the code case-insensitively, whitespace is normalized once so the
// filter and the cursor scope always agree, kind narrows the plaintext kind, and paging never
// leaks another owner's rows or reinterprets a cursor issued for another query.
func TestCategorySearchNormalizationKindPagingAndIsolation(t *testing.T) {
	h := newHarness(t)
	h.register("catsearch@example.test")
	h.register("catother@example.test")
	token, _ := h.login("catsearch@example.test")
	otherToken, _ := h.login("catother@example.test")

	for _, body := range []map[string]any{
		{"code": "kopi-nus", "label": "Kopi Nusantara", "kind": "EXPENSE",
			"attributes": map[string]any{"sumber": "kamera"}},
		{"code": "kopi-susu", "label": "Kopi Susu", "kind": "EXPENSE"},
		{"code": "gaji-bonus", "label": "Gaji Bonus", "kind": "INCOME"},
	} {
		if status, created := h.request("POST", "/v1/categories", token, body); status != 201 {
			t.Fatalf("create category %v: %d %#v", body["code"], status, created)
		}
	}
	if status, created := h.request("POST", "/v1/categories", otherToken, map[string]any{
		"code": "kopi-lain", "label": "Kopi Rahasia", "kind": "EXPENSE",
	}); status != 201 {
		t.Fatalf("create other category: %d %#v", status, created)
	}

	const total = 11 + 3 // 11 built-ins + 3 custom categories
	status, all := h.request("GET", "/v1/categories?limit=100", token, nil)
	if status != 200 || len(all["categories"].([]any)) != total {
		t.Fatalf("baseline categories: %d %#v", status, all)
	}
	allItems := all["categories"].([]any)
	if len(allItems) == 0 {
		t.Fatal("baseline categories empty")
	}
	baselineScanned := int(all["search"].(map[string]any)["scanned"].(float64))
	if baselineScanned != total || all["search"].(map[string]any)["truncated"] != false {
		t.Fatalf("baseline search envelope: %#v", all["search"])
	}

	// Whitespace-only q means "no filter" and shares the no-filter cursor scope. Before the fix it
	// searched for a space while the cursor scope normalized to empty.
	status, blank := h.request("GET", "/v1/categories?limit=100&q=%20%20", token, nil)
	if status != 200 || len(blank["categories"].([]any)) != total {
		t.Fatalf("whitespace q must equal no filter: %d %d rows %#v", status,
			len(blank["categories"].([]any)), blank)
	}
	status, pageOne := h.request("GET", "/v1/categories?limit=5", token, nil)
	if status != 200 || len(pageOne["categories"].([]any)) != 5 {
		t.Fatalf("page one: %d %#v", status, pageOne)
	}
	cursor := pageOne["nextCursor"].(string)
	if status, body := h.request("GET", "/v1/categories?limit=5&q=%20%20&cursor="+cursor,
		token, nil); status != 200 {
		t.Fatalf("whitespace q reused a no-filter cursor: %d %#v", status, body)
	}

	// q matches label and code case-insensitively; surrounding spaces are trimmed.
	for _, q := range []string{"kopi", "KOPI", "  kopi  ", "kopi-susu"} {
		status, body := h.request("GET", "/v1/categories?limit=100&q="+url.QueryEscape(q), token, nil)
		if status != 200 {
			t.Fatalf("q=%q: %d %#v", q, status, body)
		}
		items := body["categories"].([]any)
		want := 2
		if q == "kopi-susu" {
			want = 1
		}
		if len(items) != want {
			t.Fatalf("q=%q returned %d categories, want %d: %#v", q, len(items), want, items)
		}
		for _, raw := range items {
			category := raw.(map[string]any)
			if !strings.Contains(strings.ToLower(category["label"].(string)+" "+category["code"].(string)),
				strings.ToLower(strings.TrimSpace(q))) {
				t.Fatalf("q=%q matched unrelated category %#v", q, category)
			}
		}
	}
	status, custom := h.request("GET", "/v1/categories?q=KOPI-NUS", token, nil)
	if status != 200 || len(custom["categories"].([]any)) != 1 {
		t.Fatalf("code search: %d %#v", status, custom)
	}
	if got := custom["categories"].([]any)[0].(map[string]any); got["label"] != "Kopi Nusantara" ||
		got["attributes"].(map[string]any)["sumber"] != "kamera" {
		t.Fatalf("searched category payload lost sealed metadata: %#v", got)
	}

	// LIKE metacharacters and SQL-looking input are literal substrings, never wildcards or 500s.
	for _, q := range []string{"%", "kopi%", "' OR 1=1 --", `" OR "1"="1`} {
		status, body := h.request("GET", "/v1/categories?q="+url.QueryEscape(q), token, nil)
		if status != 200 {
			t.Fatalf("hostile q=%q: %d %#v", q, status, body)
		}
		if len(body["categories"].([]any)) != 0 {
			t.Fatalf("hostile q=%q matched %#v", q, body["categories"])
		}
	}
	// "_" is a literal too: it may match a code that contains it, but must never act as a wildcard.
	status, underscore := h.request("GET", "/v1/categories?q=_&limit=100", token, nil)
	if status != 200 {
		t.Fatalf("hostile q=_: %d %#v", status, underscore)
	}
	for _, raw := range underscore["categories"].([]any) {
		category := raw.(map[string]any)
		haystack := strings.ToLower(category["label"].(string) + " " + category["code"].(string))
		if !strings.Contains(haystack, "_") {
			t.Fatalf("q=_ acted as a wildcard: %#v", category)
		}
	}
	// An empty search is a clean empty page with no cursor.
	status, empty := h.request("GET", "/v1/categories?q=zzz-no-match", token, nil)
	if status != 200 || len(empty["categories"].([]any)) != 0 {
		t.Fatalf("no-match search: %d %#v", status, empty)
	}
	if _, ok := empty["nextCursor"]; ok {
		t.Fatalf("no-match search returned a cursor: %#v", empty)
	}

	// kind filters the plaintext kind and is validated before touching the store.
	status, income := h.request("GET", "/v1/categories?kind=INCOME&limit=100", token, nil)
	if status != 200 || len(income["categories"].([]any)) != 2 {
		t.Fatalf("kind=INCOME: %d %#v", status, income)
	}
	for _, raw := range income["categories"].([]any) {
		if raw.(map[string]any)["kind"] != "INCOME" {
			t.Fatalf("kind filter leaked %#v", raw)
		}
	}
	if status, body := h.request("GET", "/v1/categories?kind=FOO", token, nil); status != 400 ||
		body["code"] != "invalid_request" {
		t.Fatalf("invalid kind: %d %#v", status, body)
	}

	// Paging walks every match once; the last page closes the cursor.
	seen := map[string]bool{}
	cursor = ""
	for page := 0; page < 6; page++ {
		path := "/v1/categories?q=kopi&limit=1"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		status, body := h.request("GET", path, token, nil)
		if status != 200 {
			t.Fatalf("category search page %d: %d %#v", page, status, body)
		}
		for _, raw := range body["categories"].([]any) {
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
	if len(seen) != 2 {
		t.Fatalf("category paging returned %d of 2 matches", len(seen))
	}

	// A cursor is bound to owner+q+kind; another owner or another filter is a clean 400.
	_, scoped := h.request("GET", "/v1/categories?q=kopi&limit=1", token, nil)
	scopedCursor := scoped["nextCursor"].(string)
	for _, path := range []string{
		"/v1/categories?q=teh&limit=1&cursor=" + scopedCursor,
		"/v1/categories?kind=INCOME&limit=1&cursor=" + scopedCursor,
		"/v1/categories?q=kopi&limit=1&cursor=not-base64",
	} {
		status, body := h.request("GET", path, token, nil)
		if status != 400 || body["code"] != "invalid_cursor" {
			t.Fatalf("foreign cursor %s: %d %#v", path, status, body)
		}
	}
	status, body := h.request("GET",
		"/v1/categories?q=kopi&limit=1&cursor="+scopedCursor, otherToken, nil)
	if status != 400 || body["code"] != "invalid_cursor" {
		t.Fatalf("other owner reused cursor: %d %#v", status, body)
	}
	// The other owner sees only their own category for the same search term.
	status, otherSearch := h.request("GET", "/v1/categories?q=kopi&limit=100", otherToken, nil)
	if status != 200 || len(otherSearch["categories"].([]any)) != 1 {
		t.Fatalf("other owner search: %d %#v", status, otherSearch)
	}
	if got := otherSearch["categories"].([]any)[0].(map[string]any); got["label"] != "Kopi Rahasia" {
		t.Fatalf("other owner leaked an owner row: %#v", got)
	}
}

// TestTransactionSearchNormalizationAndEnvelope locks the C05 search envelope: a blank q is a
// plain listing (scanned counts every examined row, truncated=false), surrounding spaces do not
// change the scope, and a no-match search is a clean empty page with no cursor.
func TestTransactionSearchNormalizationAndEnvelope(t *testing.T) {
	h := newHarness(t)
	h.register("txsearch@example.test")
	token, _ := h.login("txsearch@example.test")
	ownerID := h.ownerIDOf("txsearch@example.test")
	food := h.category(token, "food")
	base := time.Date(2026, 11, 20, 3, 0, 0, 0, time.UTC)
	descriptions := []string{"kopi pagi", "teh sore", "kopi malam"}
	for i, description := range descriptions {
		seedTransaction(h, ownerID, food, "DEBIT", description, 1_500,
			base.Add(-time.Duration(i)*time.Hour))
	}

	status, plain := h.request("GET", "/v1/transactions?month=2026-11&limit=50", token, nil)
	if status != 200 || len(plain["items"].([]any)) != 3 {
		t.Fatalf("plain listing: %d %#v", status, plain)
	}
	search := plain["search"].(map[string]any)
	if search["scanned"] != float64(3) || search["truncated"] != false {
		t.Fatalf("plain search envelope: %#v", search)
	}
	status, blank := h.request("GET", "/v1/transactions?month=2026-11&q=%20%20", token, nil)
	if status != 200 || len(blank["items"].([]any)) != 3 {
		t.Fatalf("blank q must equal no filter: %d %#v", status, blank)
	}

	status, page := h.request("GET", "/v1/transactions?month=2026-11&limit=1", token, nil)
	if status != 200 {
		t.Fatalf("page one: %d %#v", status, page)
	}
	cursor := page["nextCursor"].(string)
	if status, body := h.request("GET", "/v1/transactions?month=2026-11&limit=1&q=%20&cursor="+
		cursor, token, nil); status != 200 {
		t.Fatalf("blank q reused a no-filter cursor: %d %#v", status, body)
	}

	for _, q := range []string{"kopi", "  KOPI  "} {
		status, matched := h.request("GET", "/v1/transactions?month=2026-11&q="+
			url.QueryEscape(q), token, nil)
		if status != 200 || len(matched["items"].([]any)) != 2 {
			t.Fatalf("q=%q: %d %#v", q, status, matched)
		}
	}
	status, none := h.request("GET", "/v1/transactions?month=2026-11&q=zzz-no-match", token, nil)
	if status != 200 || len(none["items"].([]any)) != 0 {
		t.Fatalf("no-match search: %d %#v", status, none)
	}
	envelope := none["search"].(map[string]any)
	if envelope["scanned"] != float64(3) || envelope["truncated"] != false {
		t.Fatalf("no-match search envelope: %#v", envelope)
	}
	if next, ok := none["nextCursor"]; ok && next != nil {
		t.Fatalf("no-match search returned nextCursor=%#v", next)
	}

	// Search is owner-scoped: the same term in another owner's month returns nothing.
	h.register("txother@example.test")
	otherToken, _ := h.login("txother@example.test")
	otherOwner := h.ownerIDOf("txother@example.test")
	otherFood := h.category(otherToken, "food")
	seedTransaction(h, otherOwner, otherFood, "DEBIT", "kopi orang lain", 2_000, base)
	status, otherSearch := h.request("GET", "/v1/transactions?month=2026-11&q=kopi", otherToken, nil)
	if status != 200 {
		t.Fatalf("other owner search: %d %#v", status, otherSearch)
	}
	for _, raw := range otherSearch["items"].([]any) {
		if raw.(map[string]any)["description"] == "kopi pagi" {
			t.Fatalf("other owner search leaked an owner row: %#v", otherSearch)
		}
	}
	if status, body := h.request("GET", "/v1/transactions?month=2026-02", token, nil); status != 200 ||
		len(body["items"].([]any)) != 0 {
		t.Fatalf("empty month: %d %#v", status, body)
	}
}
