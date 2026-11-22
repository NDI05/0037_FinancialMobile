package crypto

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"os"
	"testing"
)

type sealedFixture struct {
	OwnerID       string         `json:"ownerId"`
	KeyVersion    int            `json:"keyVersion"`
	Purpose       string         `json:"purpose"`
	Value         map[string]any `json:"value"`
	CiphertextB64 string         `json:"ciphertextB64"`
	IVB64         string         `json:"ivB64"`
	TagB64        string         `json:"tagB64"`
}

func TestOpenNodeSealedFixture(t *testing.T) {
	raw, err := os.ReadFile("../../../fixtures/interop/node-sealed.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture sealedFixture
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	ciphertext, _ := base64.StdEncoding.DecodeString(fixture.CiphertextB64)
	iv, _ := base64.StdEncoding.DecodeString(fixture.IVB64)
	tag, _ := base64.StdEncoding.DecodeString(fixture.TagB64)
	key := testKey()
	var value map[string]any
	if err := OpenJSON(Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag, KeyVersion: fixture.KeyVersion},
		key, fixture.OwnerID, fixture.Purpose, &value); err != nil {
		t.Fatalf("Go must open the Node fixture: %v", err)
	}
	if value["marker"] != "node-sealed" || value["grossInflowMinor"] != float64(500000000) {
		t.Fatalf("unexpected fixture value: %#v", value)
	}
	// AAD owner binding: another owner must not open it.
	if err := OpenJSON(Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag, KeyVersion: fixture.KeyVersion},
		key, "u_other", fixture.Purpose, &value); err == nil {
		t.Fatal("ciphertext opened under the wrong owner")
	}
}

func testKey() []byte {
	// sha256("pam-dev-data-key"), the development key both backends use.
	sum := sha256.Sum256([]byte("pam-dev-data-key"))
	return sum[:]
}
