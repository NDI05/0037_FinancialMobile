// Package crypto implements the AES-256-GCM field format shared with the Node backend:
// 12-byte IV, 16-byte tag, key version, and AAD "purpose|owner|vN".
package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

const (
	IVBytes  = 12
	TAGBytes = 16
)

var ErrOpen = errors.New("ciphertext cannot be opened")

// Sealed is the on-disk representation used by summaries, analyses, plans and the v0.4
// sensitive-payload columns.
type Sealed struct {
	Ciphertext []byte
	IV         []byte
	Tag        []byte
	KeyVersion int
}

// AAD binds a ciphertext to one owner and one purpose.
func AAD(ownerID, purpose string, keyVersion int) string {
	return fmt.Sprintf("%s|%s|v%d", purpose, ownerID, keyVersion)
}

// SealJSON encrypts the JSON encoding of value.
func SealJSON(value any, key []byte, keyVersion int, ownerID, purpose string) (Sealed, error) {
	plain, err := json.Marshal(value)
	if err != nil {
		return Sealed{}, fmt.Errorf("marshal sealed value: %w", err)
	}
	return Seal(plain, key, keyVersion, ownerID, purpose)
}

// Seal encrypts arbitrary bytes.
func Seal(plain, key []byte, keyVersion int, ownerID, purpose string) (Sealed, error) {
	if len(key) != 32 {
		return Sealed{}, fmt.Errorf("data key must be 32 bytes")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return Sealed{}, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return Sealed{}, err
	}
	iv := make([]byte, IVBytes)
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return Sealed{}, err
	}
	// Go appends the tag to the ciphertext; the schema stores it separately, matching Node.
	combined := gcm.Seal(nil, iv, plain, []byte(AAD(ownerID, purpose, keyVersion)))
	tagStart := len(combined) - TAGBytes
	ciphertext := append([]byte(nil), combined[:tagStart]...)
	tag := append([]byte(nil), combined[tagStart:]...)
	return Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag, KeyVersion: keyVersion}, nil
}

// Open decrypts a sealed field and returns the plaintext bytes.
func Open(sealed Sealed, key []byte, ownerID, purpose string) ([]byte, error) {
	if len(key) != 32 || len(sealed.IV) != IVBytes || len(sealed.Tag) != TAGBytes {
		return nil, ErrOpen
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, ErrOpen
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, ErrOpen
	}
	combined := append(append([]byte(nil), sealed.Ciphertext...), sealed.Tag...)
	plain, err := gcm.Open(nil, sealed.IV, combined, []byte(AAD(ownerID, purpose, sealed.KeyVersion)))
	if err != nil {
		return nil, ErrOpen
	}
	return plain, nil
}

// OpenJSON decrypts and JSON-decodes into target.
func OpenJSON(sealed Sealed, key []byte, ownerID, purpose string, target any) error {
	plain, err := Open(sealed, key, ownerID, purpose)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(plain, target); err != nil {
		return fmt.Errorf("decode sealed json: %w", err)
	}
	return nil
}
