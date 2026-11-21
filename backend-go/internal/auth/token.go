package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

var ErrInvalidToken = errors.New("invalid access token")

// Claims is the PAM access-token claim set. sid references auth_sessions.id, ver compares
// users.auth_version so a role/status change invalidates outstanding tokens.
type Claims struct {
	Role string `json:"role"`
	Ver  int    `json:"ver"`
	SID  string `json:"sid"`
	jwt.RegisteredClaims
}

type Issuer struct {
	secret   []byte
	issuer   string
	audience string
	ttl      time.Duration
}

func NewIssuer(secret []byte, issuer, audience string, ttl time.Duration) *Issuer {
	return &Issuer{secret: secret, issuer: issuer, audience: audience, ttl: ttl}
}

// Issue creates a signed HS256 access token.
func (i *Issuer) Issue(ownerID, role string, version int, sessionID string, now time.Time) (string, time.Time, error) {
	expires := now.Add(i.ttl)
	claims := Claims{
		Role: role,
		Ver:  version,
		SID:  sessionID,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    i.issuer,
			Audience:  jwt.ClaimStrings{i.audience},
			Subject:   ownerID,
			ExpiresAt: jwt.NewNumericDate(expires),
			IssuedAt:  jwt.NewNumericDate(now),
			NotBefore: jwt.NewNumericDate(now.Add(-5 * time.Second)),
			ID:        randomID(),
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signed, err := token.SignedString(i.secret)
	if err != nil {
		return "", time.Time{}, err
	}
	return signed, expires, nil
}

// Parse verifies signature, algorithm, issuer, audience and time bounds, then returns the claims.
func (i *Issuer) Parse(raw string, now time.Time) (*Claims, error) {
	claims := &Claims{}
	token, err := jwt.ParseWithClaims(
		raw,
		claims,
		func(token *jwt.Token) (any, error) {
			if token.Method != jwt.SigningMethodHS256 {
				return nil, ErrInvalidToken
			}
			return i.secret, nil
		},
		jwt.WithValidMethods([]string{"HS256"}),
		jwt.WithIssuer(i.issuer),
		jwt.WithAudience(i.audience),
		jwt.WithLeeway(60*time.Second),
		jwt.WithTimeFunc(func() time.Time { return now }),
	)
	if err != nil || !token.Valid {
		return nil, ErrInvalidToken
	}
	if claims.Subject == "" || claims.SID == "" || claims.Role == "" {
		return nil, ErrInvalidToken
	}
	return claims, nil
}

// NewRefreshToken returns 32 random bytes as base64url plus its server-side hash.
func NewRefreshToken(pepper string) (token, hash string, err error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", "", err
	}
	token = base64.RawURLEncoding.EncodeToString(raw)
	return token, RefreshHash(token, pepper), nil
}

// RefreshHash is sha256(token + pepper); the raw token is never stored.
func RefreshHash(token, pepper string) string {
	sum := sha256.Sum256([]byte(token + pepper))
	return hex.EncodeToString(sum[:])
}

func randomID() string {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return fmt.Sprintf("fallback-%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(raw)
}
