package httpapi

import (
	"fmt"
	"strings"
	"testing"
)

func createAttachmentTx(t *testing.T, h *harness, token string) string {
	t.Helper()
	status, body := h.request("POST", "/v1/transactions", token, map[string]any{
		"direction": "DEBIT", "amountMinor": 25_000, "currency": "IDR",
		"eventAt": "2026-11-18T04:00:00Z",
	})
	if status != 201 {
		t.Fatalf("attachment tx: %d %#v", status, body)
	}
	return body["id"].(string)
}

func attachmentBody(txID, fileName, mime, storageRef string) map[string]any {
	return map[string]any{
		"transactionId": txID, "fileName": fileName, "mimeType": mime,
		"sizeBytes": 2048, "sha256": strings.Repeat("a", 64), "storageRef": storageRef,
	}
}

func TestAttachmentCRUDRevisionAndTransactionCascade(t *testing.T) {
	h := newHarness(t)
	h.register("attach@example.test")
	token, _ := h.login("attach@example.test")
	txID := createAttachmentTx(t, h, token)

	status, created := h.request("POST", "/v1/attachments", token,
		attachmentBody(txID, "struk.heic", "image/heic", "attachments/2026/11/struk.heic"))
	if status != 201 {
		t.Fatalf("create attachment: %d %#v", status, created)
	}
	id := created["id"].(string)
	if created["fileName"] != "struk.heic" || created["mimeType"] != "image/heic" ||
		created["revision"].(float64) != 0 || created["transactionId"] != txID ||
		created["storageRef"] != "attachments/2026/11/struk.heic" {
		t.Fatalf("created attachment: %#v", created)
	}
	if status, fetched := h.request("GET", "/v1/attachments/"+id, token, nil); status != 200 ||
		fetched["id"] != id {
		t.Fatalf("get attachment: %d %#v", status, fetched)
	}

	// Only metadata fields can be patched, revision is required, stale is 409.
	if status, _ := h.request("PATCH", "/v1/attachments/"+id, token, map[string]any{
		"mimeType": "image/png", "revision": 0,
	}); status != 400 {
		t.Fatalf("immutable mime patch: %d", status)
	}
	if status, body := h.request("PATCH", "/v1/attachments/"+id, token, map[string]any{
		"fileName": "struk-2.heic", "revision": 7,
	}); status != 409 || body["code"] != "revision_conflict" {
		t.Fatalf("stale attachment patch: %d %#v", status, body)
	}
	if status, updated := h.request("PATCH", "/v1/attachments/"+id, token, map[string]any{
		"fileName": "struk-2.heic", "storageRef": "attachments/2026/11/struk-2.heic",
		"attributes": map[string]any{"side": "front"}, "revision": 0,
	}); status != 200 || updated["revision"].(float64) != 1 ||
		updated["fileName"] != "struk-2.heic" {
		t.Fatalf("update attachment: %d %#v", status, updated)
	}

	// Delete compares revision when supplied, then soft-deletes.
	if status, body := h.request("DELETE", "/v1/attachments/"+id+"?revision=0", token, nil); status != 409 ||
		body["code"] != "revision_conflict" {
		t.Fatalf("stale attachment delete: %d %#v", status, body)
	}
	if status, _ := h.request("DELETE", "/v1/attachments/"+id+"?revision=1", token, nil); status != 204 {
		t.Fatalf("delete attachment: %d", status)
	}
	if status, _ := h.request("GET", "/v1/attachments/"+id, token, nil); status != 404 {
		t.Fatalf("deleted attachment readable: %d", status)
	}
	if status, _ := h.request("DELETE", "/v1/attachments/"+id, token, nil); status != 404 {
		t.Fatalf("double delete attachment: %d", status)
	}

	// Deleting the transaction soft-deletes its remaining attachment metadata.
	status, second := h.request("POST", "/v1/attachments", token,
		attachmentBody(txID, "invoice.pdf", "application/pdf", "attachments/2026/11/invoice.pdf"))
	if status != 201 {
		t.Fatalf("second attachment: %d %#v", status, second)
	}
	if status, _ := h.request("DELETE", "/v1/transactions/"+txID, token, nil); status != 204 {
		t.Fatalf("delete tx: %d", status)
	}
	if status, _ := h.request("GET", "/v1/attachments/"+second["id"].(string), token, nil); status != 404 {
		t.Fatalf("attachment survived transaction delete: %d", status)
	}
}

func TestAttachmentValidationAndOwnerIsolation(t *testing.T) {
	h := newHarness(t)
	h.register("attachv@example.test")
	h.register("attachx@example.test")
	token, _ := h.login("attachv@example.test")
	otherToken, _ := h.login("attachx@example.test")
	txID := createAttachmentTx(t, h, token)
	otherTx := createAttachmentTx(t, h, otherToken)

	valid := attachmentBody(txID, "ok.jpg", "image/jpeg", "attachments/ok.jpg")
	valid["sha256"] = strings.Repeat("A", 64) // uppercase is normalized, not rejected.
	status, created := h.request("POST", "/v1/attachments", token, valid)
	if status != 201 || created["sha256"] != strings.Repeat("a", 64) {
		t.Fatalf("uppercase sha256: %d %#v", status, created)
	}

	cases := []map[string]any{
		attachmentBody(txID, "bad.gif", "image/gif", "attachments/bad.gif"),
		attachmentBody(txID, "big.pdf", "application/pdf", "attachments/big.pdf"),
		attachmentBody(txID, "bad.pdf", "application/pdf", "attachments/bad.pdf"),
		attachmentBody(txID, "a.pdf", "application/pdf", "attachments/a.pdf"),
		attachmentBody(txID, "b.pdf", "application/pdf", "attachments/b.pdf"),
		attachmentBody(txID, "c.pdf", "application/pdf", "attachments/c.pdf"),
		attachmentBody(txID, "d.pdf", "application/pdf", "attachments/d.pdf"),
		attachmentBody(txID, "e.pdf", "application/pdf", "attachments/e.pdf"),
		attachmentBody(txID, "f.pdf", "application/pdf", "attachments/f.pdf"),
	}
	cases[0]["mimeType"] = "image/gif"
	cases[1]["sizeBytes"] = 10*1024*1024 + 1
	cases[2]["sha256"] = "not-a-hash"
	cases[3]["storageRef"] = "../escape.pdf"
	cases[4]["storageRef"] = "/absolute/escape.pdf"
	cases[5]["storageRef"] = `C:\Users\escape.pdf`
	cases[6]["storageRef"] = "https://example.test/escape.pdf"
	cases[7]["fileName"] = "sub/dir.pdf"
	cases[8]["storageRef"] = "attachments/./hidden.pdf"
	for _, body := range cases {
		status, response := h.request("POST", "/v1/attachments", token, body)
		if status != 400 || response["code"] != "invalid_request" {
			t.Fatalf("invalid attachment %#v: %d %#v", body, status, response)
		}
	}

	// Foreign owner's transaction is not found; foreign attachment is invisible.
	status, _ = h.request("POST", "/v1/attachments", token,
		attachmentBody(otherTx, "foreign.pdf", "application/pdf", "attachments/foreign.pdf"))
	if status != 404 {
		t.Fatalf("foreign transaction attachment: %d", status)
	}
	foreignID := "missing"
	status, foreign := h.request("POST", "/v1/attachments", otherToken,
		attachmentBody(otherTx, "mine.pdf", "application/pdf", "attachments/mine.pdf"))
	if status == 201 {
		foreignID = foreign["id"].(string)
	}
	if status != 201 {
		t.Fatalf("other owner create: %d %#v", status, foreign)
	}
	if status, _ := h.request("GET", "/v1/attachments/"+foreignID, token, nil); status != 404 {
		t.Fatalf("read foreign attachment: %d", status)
	}
	if status, _ := h.request("PATCH", "/v1/attachments/"+foreignID, token, map[string]any{
		"fileName": "stolen.pdf", "revision": 0,
	}); status != 404 {
		t.Fatalf("patch foreign attachment: %d", status)
	}
	if status, _ := h.request("DELETE", "/v1/attachments/"+foreignID, token, nil); status != 404 {
		t.Fatalf("delete foreign attachment: %d", status)
	}
	status, list := h.request("GET", "/v1/attachments?transactionId="+txID, token, nil)
	if status != 200 {
		t.Fatalf("own attachment list: %d %#v", status, list)
	}
	for _, raw := range list["items"].([]any) {
		if raw.(map[string]any)["id"] == foreignID {
			t.Fatal("attachment list leaked another owner's row")
		}
	}

	// Same storage ref in the same transaction is a conflict, not a duplicate row.
	if status, body := h.request("POST", "/v1/attachments", token,
		attachmentBody(txID, "dup.jpg", "image/jpeg", "attachments/ok.jpg")); status != 409 ||
		body["code"] != "conflict" {
		t.Fatalf("duplicate storageRef: %d %#v", status, body)
	}
}

func TestAttachmentListPaginationFilterAndCursorScope(t *testing.T) {
	h := newHarness(t)
	h.register("attachp@example.test")
	token, _ := h.login("attachp@example.test")
	txID := createAttachmentTx(t, h, token)
	for i := 0; i < 12; i++ {
		mime := "image/jpeg"
		if i%3 == 0 {
			mime = "application/pdf"
		}
		body := attachmentBody(txID, fmt.Sprintf("file-%02d", i), mime,
			fmt.Sprintf("attachments/2026/11/file-%02d", i))
		if status, created := h.request("POST", "/v1/attachments", token, body); status != 201 {
			t.Fatalf("seed attachment %d: %d %#v", i, status, created)
		}
	}

	seen := map[string]bool{}
	cursor := ""
	for page := 0; page < 10; page++ {
		path := "/v1/attachments?transactionId=" + txID + "&limit=5"
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		status, body := h.request("GET", path, token, nil)
		if status != 200 {
			t.Fatalf("attachment page %d: %d %#v", page, status, body)
		}
		items := body["items"].([]any)
		if len(items) > 5 {
			t.Fatalf("attachment page returned %d rows for limit 5", len(items))
		}
		for _, raw := range items {
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
	if len(seen) != 12 {
		t.Fatalf("attachment pages returned %d of 12 rows", len(seen))
	}

	status, filtered := h.request("GET", "/v1/attachments?transactionId="+txID+"&mime=application/pdf",
		token, nil)
	if status != 200 || len(filtered["items"].([]any)) != 4 {
		t.Fatalf("mime filter: %d %#v", status, filtered)
	}
	status, searched := h.request("GET", "/v1/attachments?transactionId="+txID+"&q=file-07", token, nil)
	if status != 200 || len(searched["items"].([]any)) != 1 {
		t.Fatalf("attachment search: %d %#v", status, searched)
	}
	status, _ = h.request("GET", "/v1/attachments?mime=image/gif", token, nil)
	if status != 400 {
		t.Fatalf("invalid mime filter: %d", status)
	}

	// A cursor belongs to its owner+transaction+mime+q scope.
	status, scoped := h.request("GET", "/v1/attachments?transactionId="+txID+"&limit=2", token, nil)
	if status != 200 {
		t.Fatalf("scope page: %d", status)
	}
	scopedCursor := scoped["nextCursor"].(string)
	for _, path := range []string{
		"/v1/attachments?transactionId=" + txID + "&limit=2&q=file&cursor=" + scopedCursor,
		"/v1/attachments?transactionId=" + txID + "&limit=2&mime=application/pdf&cursor=" + scopedCursor,
		"/v1/attachments?limit=2&cursor=" + scopedCursor,
	} {
		status, body := h.request("GET", path, token, nil)
		if status != 400 || body["code"] != "invalid_cursor" {
			t.Fatalf("foreign attachment cursor: %d %#v", status, body)
		}
	}
}
