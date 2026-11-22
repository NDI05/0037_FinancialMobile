package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"
)

const (
	LoginWindow      = 15 * time.Minute
	LoginMaxFailures = 5
)

var ErrRefreshReplay = errors.New("refresh token reused")

type AuthSession struct {
	ID         string
	OwnerID    string
	FamilyID   string
	CreatedAt  time.Time
	ExpiresAt  time.Time
	RevokedAt  *time.Time
	LastSeenAt time.Time
}

type RefreshRecord struct {
	ID               string
	OwnerID          string
	SessionID        string
	FamilyID         string
	IssuedAt         time.Time
	ExpiresAt        time.Time
	RevokedAt        *time.Time
	Role             string
	Status           string
	AuthVersion      int
	SessionRevokedAt *time.Time
	SessionExpiresAt time.Time
}

type RefreshContext struct {
	Record        RefreshRecord
	AccessToken   string
	AccessExpires time.Time
	Role          string
}

func (s *Store) CreateAuthSession(ctx context.Context, ownerID, familyID string, now time.Time, ttl time.Duration) (string, error) {
	id := NewID("sess")
	_, err := s.DB.ExecContext(ctx, `INSERT INTO auth_sessions
		(id, owner_id, family_id, created_at, last_seen_at, expires_at)
		VALUES (?, ?, ?, ?, ?, ?)`,
		id, ownerID, familyID, nowString(now), nowString(now), nowString(now.Add(ttl)))
	return id, err
}

func (s *Store) InsertRefreshToken(ctx context.Context, id, ownerID, sessionID, familyID, hash string, now time.Time, ttl time.Duration) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO refresh_tokens
		(id, owner_id, session_id, family_id, token_hash, issued_at, expires_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		id, ownerID, sessionID, familyID, hash, nowString(now), nowString(now.Add(ttl)))
	return err
}

// FindRefreshByHash loads the token together with the session and account state needed by the
// refresh and middleware paths.
func (s *Store) FindRefreshByHash(ctx context.Context, hash string) (RefreshRecord, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT rt.id, rt.owner_id, rt.session_id, rt.family_id,
		rt.issued_at, rt.expires_at, rt.revoked_at, u.role, u.status, u.auth_version,
		s.revoked_at, s.expires_at
		FROM refresh_tokens rt
		JOIN auth_sessions s ON s.id = rt.session_id AND s.owner_id = rt.owner_id
		JOIN users u ON u.owner_id = rt.owner_id
		WHERE rt.token_hash = ?`, hash)
	var record RefreshRecord
	var issued, expires, sessionExpires string
	var revoked, sessionRevoked sql.NullString
	err := row.Scan(&record.ID, &record.OwnerID, &record.SessionID, &record.FamilyID,
		&issued, &expires, &revoked, &record.Role, &record.Status, &record.AuthVersion,
		&sessionRevoked, &sessionExpires)
	if errors.Is(err, sql.ErrNoRows) {
		return RefreshRecord{}, ErrNotFound
	}
	if err != nil {
		return RefreshRecord{}, err
	}
	record.IssuedAt, record.ExpiresAt = parseTime(issued), parseTime(expires)
	record.SessionExpiresAt = parseTime(sessionExpires)
	if revoked.Valid {
		t := parseTime(revoked.String)
		record.RevokedAt = &t
	}
	if sessionRevoked.Valid {
		t := parseTime(sessionRevoked.String)
		record.SessionRevokedAt = &t
	}
	return record, nil
}

// RotateRefresh claims the old token atomically. The replacement is inserted before the old row is
// linked (replaced_by_id is a foreign key), then the old row is claimed with a conditional update.
// A conditional update that affects zero rows means another request already rotated it.
func (s *Store) RotateRefresh(ctx context.Context, old RefreshRecord, replacementID, replacementHash string, now time.Time, ttl time.Duration) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(ctx, `INSERT INTO refresh_tokens
		(id, owner_id, session_id, family_id, token_hash, issued_at, expires_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		replacementID, old.OwnerID, old.SessionID, old.FamilyID, replacementHash,
		nowString(now), nowString(now.Add(ttl))); err != nil {
		return err
	}
	result, err := tx.ExecContext(ctx, `UPDATE refresh_tokens
		SET revoked_at = ?, revoked_reason = 'rotated', replaced_by_id = ?
		WHERE id = ? AND revoked_at IS NULL`, nowString(now), replacementID, old.ID)
	if err != nil {
		return err
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if affected != 1 {
		return ErrRefreshReplay
	}
	return tx.Commit()
}

func (s *Store) RevokeRefreshFamily(ctx context.Context, familyID, reason string, now time.Time) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE refresh_tokens
		SET revoked_at = COALESCE(revoked_at, ?), revoked_reason = COALESCE(revoked_reason, ?)
		WHERE family_id = ?`, nowString(now), reason, familyID)
	return err
}

func (s *Store) RevokeSession(ctx context.Context, ownerID, sessionID, reason string, now time.Time) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(ctx, `UPDATE auth_sessions
		SET revoked_at = COALESCE(revoked_at, ?), revoked_reason = COALESCE(revoked_reason, ?)
		WHERE id = ? AND owner_id = ?`, nowString(now), reason, sessionID, ownerID); err != nil {
		return err
	}
	var family string
	if err := tx.QueryRowContext(ctx, `SELECT family_id FROM auth_sessions WHERE id = ? AND owner_id = ?`,
		sessionID, ownerID).Scan(&family); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE refresh_tokens
		SET revoked_at = COALESCE(revoked_at, ?), revoked_reason = COALESCE(revoked_reason, ?)
		WHERE family_id = ?`, nowString(now), reason, family); err != nil {
		return err
	}
	return tx.Commit()
}

// SessionState is the per-request session/account check behind JWT validation.
type SessionState struct {
	OwnerID     string
	Role        string
	Status      string
	AuthVersion int
	RevokedAt   *time.Time
	ExpiresAt   time.Time
}

func (s *Store) SessionState(ctx context.Context, sessionID, ownerID string) (SessionState, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT s.owner_id, u.role, u.status, u.auth_version,
		s.revoked_at, s.expires_at
		FROM auth_sessions s JOIN users u ON u.owner_id = s.owner_id
		WHERE s.id = ? AND s.owner_id = ?`, sessionID, ownerID)
	var state SessionState
	var revoked sql.NullString
	var expires string
	err := row.Scan(&state.OwnerID, &state.Role, &state.Status, &state.AuthVersion, &revoked, &expires)
	if errors.Is(err, sql.ErrNoRows) {
		return SessionState{}, ErrNotFound
	}
	if err != nil {
		return SessionState{}, err
	}
	state.ExpiresAt = parseTime(expires)
	if revoked.Valid {
		t := parseTime(revoked.String)
		state.RevokedAt = &t
	}
	return state, nil
}

// --- login throttling (FR-53) --------------------------------------------------------------

// AddressClass mirrors the Node IPv4 /24 and IPv6 /64 rule without storing the raw address.
func AddressClass(raw string) string {
	raw = strings.TrimSpace(raw)
	raw = strings.TrimPrefix(raw, "::ffff:")
	if raw == "" {
		return "unknown"
	}
	if strings.Contains(raw, ":") {
		parts := strings.Split(raw, ":")
		if len(parts) >= 4 {
			return strings.Join(parts[:4], ":") + "::/64"
		}
		return "unknown"
	}
	parts := strings.Split(raw, ".")
	if len(parts) == 4 {
		return strings.Join(parts[:3], ".") + ".0/24"
	}
	return "unknown"
}

func AttemptKey(email, remoteAddress string) string {
	sum := sha256.Sum256([]byte(NormalizeEmail(email) + "|" + AddressClass(remoteAddress)))
	return hex.EncodeToString(sum[:])
}

func (s *Store) LoginFailureCount(ctx context.Context, key string, now time.Time) (int, error) {
	count, _, err := s.loginFailureState(ctx, key, now)
	return count, err
}

func (s *Store) loginFailureState(ctx context.Context, key string, now time.Time) (int, time.Time, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT failed_count, window_start FROM login_attempts WHERE attempt_key = ?`, key)
	var count int
	var start string
	if err := row.Scan(&count, &start); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, now, nil
		}
		return 0, time.Time{}, err
	}
	windowStart := parseTime(start)
	if now.Sub(windowStart) >= LoginWindow {
		return 0, now, nil
	}
	return count, windowStart, nil
}

func (s *Store) RegisterLoginFailure(ctx context.Context, key string, now time.Time) (int, error) {
	count, windowStart, err := s.loginFailureState(ctx, key, now)
	if err != nil {
		return 0, err
	}
	next := count + 1
	if count == 0 {
		windowStart = now
	}
	_, err = s.DB.ExecContext(ctx, `INSERT INTO login_attempts (attempt_key, failed_count, window_start, updated_at)
		VALUES (?, ?, ?, ?)
		ON CONFLICT(attempt_key) DO UPDATE SET failed_count = excluded.failed_count,
			window_start = excluded.window_start, updated_at = excluded.updated_at`,
		key, next, nowString(windowStart), nowString(now))
	if err != nil {
		return 0, fmt.Errorf("record login failure: %w", err)
	}
	return next, nil
}

func (s *Store) ClearLoginFailures(ctx context.Context, key string) error {
	_, err := s.DB.ExecContext(ctx, `DELETE FROM login_attempts WHERE attempt_key = ?`, key)
	return err
}
