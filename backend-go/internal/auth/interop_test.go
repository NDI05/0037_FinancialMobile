package auth

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"testing"
)

type passwordFixture struct {
	Password string `json:"password"`
	SaltB64  string `json:"saltB64"`
	HashB64  string `json:"hashB64"`
	KDF      string `json:"kdf"`
	Email    string `json:"email"`
	OwnerID  string `json:"ownerId"`
}

func TestVerifyNodePasswordFixture(t *testing.T) {
	raw, err := os.ReadFile("../../../fixtures/interop/node-password.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture passwordFixture
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	salt, _ := base64.StdEncoding.DecodeString(fixture.SaltB64)
	hash, _ := base64.StdEncoding.DecodeString(fixture.HashB64)
	ok, err := VerifyPassword(fixture.Password, salt, hash, fixture.KDF)
	if err != nil || !ok {
		t.Fatalf("Go must verify the Node password fixture: ok=%v err=%v", ok, err)
	}
	bad, err := VerifyPassword("wrong-password", salt, hash, fixture.KDF)
	if err != nil || bad {
		t.Fatalf("wrong password must not verify: ok=%v err=%v", bad, err)
	}
	if fixture.OwnerID != "u_34343446dfa5ad3d9d440ac847c20c2e" {
		t.Fatalf("fixture owner changed: %s", fixture.OwnerID)
	}
}
