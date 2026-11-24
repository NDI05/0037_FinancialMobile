package httpapi

import (
	"fmt"
	"strings"
	"testing"
)

// The merchant-label repair contract (P0: a 19-digit Merchant PAN was stored instead of the
// Penerima business name). The app reparses the same notification, then patches only the label;
// the server proves identity with expectedFingerprint, adopts the sealed source reference when the
// row predates it, derives the replacement fingerprint itself, and keeps the row count unchanged.

func (h *harness) grantGmailConsent(token string) {
	h.t.Helper()
	status, body := h.request("POST", "/v1/consents", token, map[string]any{
		"purpose": "GMAIL_READ", "decision": "GRANTED", "noticeVersion": "v0.4-review",
	})
	if status != 201 {
		h.t.Fatalf("gmail consent: %d %#v", status, body)
	}
}

func repairCandidates(count int, categoryID string, withSourceRef bool) []any {
	candidates := make([]any, 0, count)
	for i := 0; i < count; i++ {
		candidate := map[string]any{
			"fingerprint": fmt.Sprintf("%032d", i+1),
			"status":      "PARSED", "direction": "DEBIT",
			"amountMinor": 17_500_000 + i,
			"eventAt":     fmt.Sprintf("2026-11-%02dT04:10:00Z", i+1),
			"categoryId":  categoryID, "parserVersion": "livin-v1", "confidence": 0.9,
			"description": "Pembayaran", "merchantLabel": "9360012345678901234",
		}
		if withSourceRef {
			candidate["sourceRef"] = fmt.Sprintf("777:%d", 100+i)
		}
		candidates = append(candidates, candidate)
	}
	return candidates
}

func (h *harness) importCandidates(token, month, batchKey string, candidates []any) map[string]any {
	h.t.Helper()
	status, job := h.request("POST", "/v1/import-jobs", token, map[string]any{
		"monthId": month, "templateVersion": "livin-v1",
	})
	if status != 201 {
		h.t.Fatalf("create import job: %d %#v", status, job)
	}
	jobID := job["id"].(string)
	status, counts := h.request("POST", "/v1/import-jobs/"+jobID+"/batches", token,
		map[string]any{"batchKey": batchKey, "pageOrdinal": 0, "candidates": candidates})
	if status != 200 {
		h.t.Fatalf("import batch: %d %#v", status, counts)
	}
	status, _ = h.request("PATCH", "/v1/import-jobs/"+jobID, token,
		map[string]any{"state": "COMPLETE"})
	if status != 200 {
		h.t.Fatalf("finalize import job: %d", status)
	}
	return counts
}

func (h *harness) transactionsOf(token, month string) []map[string]any {
	h.t.Helper()
	status, body := h.request("GET", "/v1/transactions?month="+month, token, nil)
	if status != 200 {
		h.t.Fatalf("list transactions: %d %#v", status, body)
	}
	raw, _ := body["items"].([]any)
	items := make([]map[string]any, 0, len(raw))
	for _, item := range raw {
		items = append(items, item.(map[string]any))
	}
	return items
}

func TestMerchantRepairKeepsThirteenRowsAndDedupesReimport(t *testing.T) {
	h := newHarness(t)
	h.register("repair@example.test")
	token, _ := h.login("repair@example.test")
	h.grantGmailConsent(token)
	food := h.category(token, "food")

	first := h.importCandidates(token, "2026-11", "page-1",
		repairCandidates(13, food, true))
	if first["imported"].(float64) != 13 {
		t.Fatalf("initial import: %#v", first)
	}

	rows := h.transactionsOf(token, "2026-11")
	if len(rows) != 13 {
		t.Fatalf("expected 13 rows, found %d", len(rows))
	}

	// Each row is repaired with the business name and the fingerprint it was read with.
	for i, row := range rows {
		id := row["id"].(string)
		oldFingerprint := row["fingerprint"].(string)
		business := fmt.Sprintf("Warung Nasi %02d", i)
		status, patched := h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
			"revision": row["revision"], "expectedFingerprint": oldFingerprint,
			"merchantLabel": business,
		})
		if status != 200 {
			t.Fatalf("repair %d: %d %#v", i, status, patched)
		}
		if patched["merchantLabel"] != business {
			t.Fatalf("repair %d merchant: %#v", i, patched["merchantLabel"])
		}
		if patched["fingerprint"] == oldFingerprint {
			t.Fatalf("repair %d fingerprint did not change", i)
		}
		if patched["categoryId"] != food {
			t.Fatalf("repair %d category changed: %#v", i, patched["categoryId"])
		}
		// Idempotent replay: the same request after success returns the stored row unchanged.
		status, replay := h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
			"revision": patched["revision"], "expectedFingerprint": patched["fingerprint"],
			"merchantLabel": business,
		})
		if status != 200 || replay["fingerprint"] != patched["fingerprint"] ||
			replay["revision"] != patched["revision"] {
			t.Fatalf("replay %d: %d %#v", i, status, replay)
		}
	}

	if len(h.transactionsOf(token, "2026-11")) != 13 {
		t.Fatalf("repair changed the row count")
	}

	// A full month re-scan after the repair re-parses the same notifications. Their fingerprints
	// changed, so only the sealed source reference can stop a second row: duplicates, not imports.
	again := h.importCandidates(token, "2026-11", "page-rescan",
		repairCandidates(13, food, true))
	if again["imported"].(float64) != 0 || again["duplicates"].(float64) != 13 {
		t.Fatalf("post-repair re-import: %#v", again)
	}
	if len(h.transactionsOf(token, "2026-11")) != 13 {
		t.Fatalf("re-import changed the row count")
	}
}

func TestMerchantRepairRejectsStaleIdentityAndUnsafeLabels(t *testing.T) {
	h := newHarness(t)
	h.register("repair-guards@example.test")
	token, _ := h.login("repair-guards@example.test")
	h.grantGmailConsent(token)
	h.importCandidates(token, "2026-11", "page-1", repairCandidates(1, "", true))
	row := h.transactionsOf(token, "2026-11")[0]
	id := row["id"].(string)

	// Stale expected fingerprint: another read already changed the row.
	status, body := h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"revision": row["revision"], "expectedFingerprint": strings.Repeat("0", 32),
		"merchantLabel": "Warung Nasi Ibu Siti",
	})
	if status != 409 || body["code"] != "identity_conflict" {
		t.Fatalf("stale fingerprint: %d %#v", status, body)
	}

	// A replacement label that is still an identifier is refused, so a repair can never re-import
	// the bug it exists to fix.
	status, body = h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"revision": row["revision"], "expectedFingerprint": row["fingerprint"],
		"merchantLabel": "9360012345678901234",
	})
	if status != 400 || body["code"] != "invalid_request" {
		t.Fatalf("identifier label: %d %#v", status, body)
	}

	// Missing expected fingerprint: fail closed rather than guess.
	status, _ = h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"revision": row["revision"], "merchantLabel": "Warung Nasi Ibu Siti",
	})
	if status != 400 {
		t.Fatalf("missing expectedFingerprint: %d", status)
	}

	// An ordinary edit (not a repair) still works without the repair fields.
	status, edited := h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"revision": row["revision"], "note": "catatan pemilik",
	})
	if status != 200 || edited["note"] != "catatan pemilik" {
		t.Fatalf("plain note edit: %d %#v", status, edited)
	}
}

func TestLegacyRowRepairRequiresSourceRefOnce(t *testing.T) {
	h := newHarness(t)
	h.register("repair-legacy@example.test")
	token, _ := h.login("repair-legacy@example.test")
	h.grantGmailConsent(token)
	// Rows imported before source references existed carry no repair identity.
	h.importCandidates(token, "2026-11", "page-1", repairCandidates(1, "", false))
	row := h.transactionsOf(token, "2026-11")[0]
	id := row["id"].(string)

	status, body := h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"revision": row["revision"], "expectedFingerprint": row["fingerprint"],
		"merchantLabel": "Warung Nasi Ibu Siti",
	})
	if status != 400 || body["code"] != "invalid_request" {
		t.Fatalf("legacy repair without sourceRef: %d %#v", status, body)
	}

	status, patched := h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"revision": row["revision"], "expectedFingerprint": row["fingerprint"],
		"sourceRef": "777:100", "merchantLabel": "Warung Nasi Ibu Siti",
	})
	if status != 200 || patched["merchantLabel"] != "Warung Nasi Ibu Siti" {
		t.Fatalf("legacy repair with sourceRef: %d %#v", status, patched)
	}

	// The adopted reference now identifies the row: a later re-import of the same notification is
	// a duplicate even though its fingerprint changed.
	again := h.importCandidates(token, "2026-11", "page-rescan", repairCandidates(1, "", true))
	if again["imported"].(float64) != 0 || again["duplicates"].(float64) != 1 {
		t.Fatalf("legacy post-repair re-import: %#v", again)
	}
	// A second repair of the same row must use the stored reference and reject a different claim.
	row = h.transactionsOf(token, "2026-11")[0]
	status, _ = h.request("PATCH", "/v1/transactions/"+id, token, map[string]any{
		"revision": row["revision"], "expectedFingerprint": row["fingerprint"],
		"sourceRef": "999:777", "merchantLabel": "Warung Nasi Ibu Siti",
	})
	if status != 409 {
		t.Fatalf("conflicting sourceRef: %d", status)
	}
}
