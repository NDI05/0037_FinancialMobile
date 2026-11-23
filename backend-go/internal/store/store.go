package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

var (
	ErrNotFound            = errors.New("not found")
	ErrEmailExists         = errors.New("email already registered")
	ErrConflict            = errors.New("conflict")
	ErrCategoryInUse       = errors.New("category is still referenced")
	ErrBuiltinCategory     = errors.New("built-in category cannot be deleted")
	ErrRevisionConflict    = errors.New("revision conflict")
	ErrDeletionInProgress  = errors.New("deletion in progress")
	ErrIdempotencyConflict = errors.New("idempotency key conflict")
	ErrInvalidCursor       = errors.New("cursor does not match the current query")
	ErrConsentChanged      = errors.New("consent changed")
	ErrSessionStale        = errors.New("session no longer active")
	ErrCrossOwner          = errors.New("object belongs to another owner")
)

// Store is the owner-scoped persistence layer. Every method that touches finance data takes the
// owner from the authenticated session, never from a request body.
type Store struct {
	DB         *sql.DB
	DataKey    []byte
	KeyVersion int
}

func New(db *sql.DB, dataKey []byte, keyVersion int) *Store {
	return &Store{DB: db, DataKey: dataKey, KeyVersion: keyVersion}
}

func (s *Store) Close() error { return s.DB.Close() }

func (s *Store) ensureNoDeletion(ctx context.Context, ownerID string) error {
	pending, err := s.HasPendingDeletion(ctx, ownerID)
	if err != nil {
		return err
	}
	if pending {
		return ErrDeletionInProgress
	}
	return nil
}

// ensureNoDeletionTx is the commit-time guard: a request that passed the early check still cannot
// insert data after a deletion request wins.
func ensureNoDeletionTx(ctx context.Context, tx *sql.Tx, ownerID string) error {
	var count int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM deletion_requests
		WHERE owner_id = ? AND state = 'PENDING'`, ownerID).Scan(&count); err != nil {
		return err
	}
	if count > 0 {
		return ErrDeletionInProgress
	}
	return nil
}

// OwnerIDForEmail is byte-for-byte compatible with the Node owner id derivation.
func OwnerIDForEmail(email string) string {
	normalized := NormalizeEmail(email)
	sum := sha256.Sum256([]byte("pam-owner:" + normalized))
	return "u_" + hex.EncodeToString(sum[:])[:32]
}

func NormalizeEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}

// NormalizeSearchTerm makes a free-text filter and the cursor scope derived from it agree. A
// whitespace-only term means "no filter" instead of searching for a space, and surrounding spaces
// are ignored by both, so equal result sets always share one cursor scope.
func NormalizeSearchTerm(q string) string {
	return strings.TrimSpace(q)
}

func NewID(prefix string) string {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		// crypto/rand failure is unrecoverable for identity generation; fail loudly rather than
		// silently reusing an id.
		panic("crypto/rand unavailable: " + err.Error())
	}
	return prefix + "_" + hex.EncodeToString(raw)
}

func nowString(t time.Time) string { return t.UTC().Format(time.RFC3339Nano) }

func parseTime(raw string) time.Time {
	t, err := time.Parse(time.RFC3339Nano, raw)
	if err != nil {
		return time.Time{}
	}
	return t.UTC()
}

func isUnique(errorString string) bool {
	return strings.Contains(errorString, "UNIQUE constraint failed") ||
		strings.Contains(errorString, "constraint failed: UNIQUE")
}

func isForeignKey(errorString string) bool {
	return strings.Contains(errorString, "FOREIGN KEY constraint failed")
}

func isLastAdmin(errorString string) bool {
	return strings.Contains(errorString, "last_active_admin")
}

func boolInt(value bool) int {
	if value {
		return 1
	}
	return 0
}

func intBool(value int64) bool { return value != 0 }

func monthIDFor(t time.Time) string {
	jakarta := t.In(jakartaLocation)
	return fmt.Sprintf("%04d-%02d", jakarta.Year(), int(jakarta.Month()))
}

var jakartaLocation = mustLoadJakarta()

func mustLoadJakarta() *time.Location {
	loc, err := time.LoadLocation("Asia/Jakarta")
	if err != nil {
		// The platform can lack tzdata in a scratch container. UTC+7 has no DST, so the fixed zone
		// is exact for this product rather than a guess.
		return time.FixedZone("Asia/Jakarta", 7*60*60)
	}
	return loc
}

func validateAttributes(attributes map[string]any) error {
	if len(attributes) > 10 {
		return fmt.Errorf("attributes must contain at most 10 key/value pairs")
	}
	for key, value := range attributes {
		if strings.TrimSpace(key) == "" || len(key) > 40 {
			return fmt.Errorf("attribute key is invalid")
		}
		if text, ok := value.(string); ok && len([]rune(text)) > 200 {
			return fmt.Errorf("attribute value exceeds 200 characters")
		}
	}
	raw, err := json.Marshal(attributes)
	if err != nil {
		return fmt.Errorf("attributes are not JSON")
	}
	if len(raw) > 2048 {
		return fmt.Errorf("attributes exceed 2 KB")
	}
	return nil
}

// ParseMonth validates YYYY-MM and returns the half-open Jakarta month range.
func ParseMonth(raw string) (time.Time, time.Time, error) {
	if len(raw) != 7 || raw[4] != '-' {
		return time.Time{}, time.Time{}, fmt.Errorf("month must be YYYY-MM")
	}
	year, err := strconv.Atoi(raw[:4])
	if err != nil {
		return time.Time{}, time.Time{}, fmt.Errorf("month must be YYYY-MM")
	}
	month, err := strconv.Atoi(raw[5:])
	if err != nil || month < 1 || month > 12 {
		return time.Time{}, time.Time{}, fmt.Errorf("month must be YYYY-MM")
	}
	start := time.Date(year, time.Month(month), 1, 0, 0, 0, 0, jakartaLocation)
	end := start.AddDate(0, 1, 0)
	return start.UTC(), end.UTC(), nil
}
