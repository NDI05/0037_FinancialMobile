package auth

import (
	"strings"
	"testing"
	"time"
)

func TestAccessTokenClaimsAndExpiry(t *testing.T) {
	issuer := NewIssuer([]byte(strings.Repeat("k", 32)), "pam-api", "pam-android", 15*time.Minute)
	now := time.Date(2026, 11, 21, 10, 0, 0, 0, time.UTC)
	token, expires, err := issuer.Issue("u_a", "USER", 3, "sess_1", now)
	if err != nil {
		t.Fatal(err)
	}
	if !expires.Equal(now.Add(15 * time.Minute)) {
		t.Fatalf("expiry = %s", expires)
	}
	claims, err := issuer.Parse(token, now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if claims.Subject != "u_a" || claims.SID != "sess_1" || claims.Ver != 3 || claims.Role != "USER" {
		t.Fatalf("claims = %#v", claims)
	}
	if _, err := issuer.Parse(token, now.Add(16*time.Minute)); err == nil {
		t.Fatal("expired token was accepted")
	}
	if _, err := issuer.Parse(token+"x", now); err == nil {
		t.Fatal("tampered token was accepted")
	}
	other := NewIssuer([]byte(strings.Repeat("z", 32)), "pam-api", "pam-android", time.Minute)
	if _, err := other.Parse(token, now); err == nil {
		t.Fatal("token signed by another secret was accepted")
	}
}
