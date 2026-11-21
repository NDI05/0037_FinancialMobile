// Package auth implements the password, JWT and refresh primitives.
package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"errors"
	"fmt"
	"regexp"
	"strconv"

	"golang.org/x/crypto/scrypt"
)

const (
	ScryptN       = 1 << 15
	ScryptR       = 8
	ScryptP       = 1
	ScryptKeyLen  = 64
	ScryptSaltLen = 16
	ScryptMaxMem  = 128 * 1024 * 1024
)

// KDFVersion matches the Node `kdf` string so rows remain readable in both implementations.
const KDFVersion = "scrypt:N=32768,r=8,p=1"

var ErrInvalidKDF = errors.New("unsupported password KDF")

var kdfPattern = regexp.MustCompile(`^scrypt:N=(\d+),r=(\d+),p=(\d+)$`)

type Digest struct {
	Salt []byte
	Hash []byte
	KDF  string
}

// HashPassword hashes a password with the fixed CR-001 parameters.
func HashPassword(password string) (Digest, error) {
	salt := make([]byte, ScryptSaltLen)
	if _, err := rand.Read(salt); err != nil {
		return Digest{}, err
	}
	hash, err := scrypt.Key([]byte(password), salt, ScryptN, ScryptR, ScryptP, ScryptKeyLen)
	if err != nil {
		return Digest{}, err
	}
	return Digest{Salt: salt, Hash: hash, KDF: KDFVersion}, nil
}

// VerifyPassword checks a password against a stored digest with the parameters named by its KDF.
func VerifyPassword(password string, salt, hash []byte, kdf string) (bool, error) {
	n, r, p, err := parseKDF(kdf)
	if err != nil {
		return false, err
	}
	candidate, err := scrypt.Key([]byte(password), salt, n, r, p, len(hash))
	if err != nil {
		return false, err
	}
	if len(candidate) != len(hash) {
		return false, nil
	}
	return subtle.ConstantTimeCompare(candidate, hash) == 1, nil
}

// DummyVerify spends the same scrypt work as a real verification for an unknown email.
func DummyVerify(password string) {
	salt := make([]byte, ScryptSaltLen)
	for i := range salt {
		salt[i] = 0x5a
	}
	_, _ = scrypt.Key([]byte(password), salt, ScryptN, ScryptR, ScryptP, ScryptKeyLen)
}

func parseKDF(kdf string) (int, int, int, error) {
	m := kdfPattern.FindStringSubmatch(kdf)
	if m == nil {
		return 0, 0, 0, ErrInvalidKDF
	}
	n, _ := strconv.Atoi(m[1])
	r, _ := strconv.Atoi(m[2])
	p, _ := strconv.Atoi(m[3])
	// Refuse hostile rows that would force unbounded work.
	if n < 1<<12 || n > 1<<20 || r < 1 || r > 16 || p < 1 || p > 4 {
		return 0, 0, 0, fmt.Errorf("%w: parameters out of range", ErrInvalidKDF)
	}
	return n, r, p, nil
}
