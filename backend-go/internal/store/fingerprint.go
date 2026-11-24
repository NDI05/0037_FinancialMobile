package store

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strings"
)

// Server-side fingerprint derivation for the merchant-label repair pass.
//
// The device owns its dedupe fingerprint: it is an HMAC under a random per-install key wrapped by
// the Android Keystore, so the server can never reproduce the value the app computed at import
// time. That is why a repair never accepts a client-computed replacement fingerprint: the server
// derives the new fingerprint itself from the fields it stores plus the verified old fingerprint,
// and keeps a keyed hash of the sealed source reference as the durable identity that makes a later
// re-import a duplicate instead of a second row.
//
// The canonical form intentionally mirrors the app convention (length-prefixed fields joined by
// U+001F) so both sides stay ambiguity-free even though the keys differ.

const fingerprintFieldSeparator = "\u001F"

func normalizeFingerprintLabel(label string) string {
	return strings.Join(strings.Fields(strings.ToLower(strings.TrimSpace(label))), " ")
}

func canonicalFingerprintInput(sourceRef string, amountMinor, eventAtMs int64, direction, merchantLabel string) string {
	fields := []string{
		sourceRef,
		itoa(amountMinor),
		itoa(eventAtMs),
		strings.ToUpper(direction),
		normalizeFingerprintLabel(merchantLabel),
	}
	parts := make([]string, 0, len(fields))
	for _, field := range fields {
		parts = append(parts, itoa(int64(len(field)))+":"+field)
	}
	return strings.Join(parts, fingerprintFieldSeparator)
}

func (s *Store) transactionFingerprint(sourceRef string, amountMinor, eventAtMs int64, direction, merchantLabel string) string {
	payload := canonicalFingerprintInput(sourceRef, amountMinor, eventAtMs, direction, merchantLabel)
	mac := hmac.New(sha256.New, s.DataKey)
	mac.Write([]byte("pam/fingerprint/v1"))
	mac.Write([]byte(payload))
	return hex.EncodeToString(mac.Sum(nil))[:32]
}

// sourceRefHash binds the opaque Gmail reference to this owner without storing it in clear. The
// value is unique per owner, which is what turns a post-repair re-import into a duplicate.
func (s *Store) sourceRefHash(sourceRef string) string {
	mac := hmac.New(sha256.New, s.DataKey)
	mac.Write([]byte("pam/source-ref/v1"))
	mac.Write([]byte(sourceRef))
	return hex.EncodeToString(mac.Sum(nil))
}

// validSourceRef accepts the device's opaque reference `UIDVALIDITY:UID` (both positive integers).
// The raw Gmail message id is never part of it.
func validSourceRef(raw string) bool {
	parts := strings.Split(raw, ":")
	if len(parts) != 2 || len(raw) > 64 {
		return false
	}
	for _, part := range parts {
		if part == "" || len(part) > 20 {
			return false
		}
		for _, r := range part {
			if r < '0' || r > '9' {
				return false
			}
		}
	}
	return true
}

// isReferenceOnlyLabel mirrors the category pipeline guard: a label that is only digits and
// separators is an identifier (PAN, reference number), never a business name.
func isReferenceOnlyLabel(label string) bool {
	label = strings.TrimSpace(label)
	if label == "" {
		return true
	}
	digits := 0
	for _, r := range label {
		switch {
		case r >= '0' && r <= '9':
			digits++
		case r == ' ' || r == '-' || r == '.' || r == '/' || r == ':':
		default:
			return false
		}
	}
	return digits > 0
}

func itoa(value int64) string {
	if value == 0 {
		return "0"
	}
	negative := value < 0
	if negative {
		value = -value
	}
	var buf [20]byte
	pos := len(buf)
	for value > 0 {
		pos--
		buf[pos] = byte('0' + value%10)
		value /= 10
	}
	if negative {
		pos--
		buf[pos] = '-'
	}
	return string(buf[pos:])
}
