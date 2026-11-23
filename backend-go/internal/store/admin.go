package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

// Admin operations are owner-scoped at the data layer and role-scoped at the HTTP layer. Nothing
// here returns another owner's financial data: the account view is identity/role metadata only.

var (
	ErrInvitationInvalid = errors.New("invitation is invalid")
	ErrInvitationUsed    = errors.New("invitation was already used")
	ErrInvitationExpired = errors.New("invitation expired")
	ErrSelfAdminChange   = errors.New("an admin cannot change their own privileged state")
)

type AdminInvitation struct {
	ID        string
	Email     string
	Role      string
	CreatedBy string
	ExpiresAt time.Time
	UsedAt    *time.Time
	CreatedAt time.Time
}

type AdminAccount struct {
	OwnerID     string
	Email       string
	DisplayName string
	Role        string
	Status      string
	Revision    int
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

type AdminAccountPage struct {
	Items      []AdminAccount
	NextCursor string
}

type AdminAccountPatch struct {
	DisplayName *string
	Role        *string
	Status      *string
	Revision    int
}

type AuditEntry struct {
	ID        string
	ActorID   string
	TargetID  string
	Action    string
	Metadata  map[string]any
	CreatedAt time.Time
}

type AuditPage struct {
	Items      []AuditEntry
	NextCursor string
}

// createdAtBucket matches the strftime('%s', created_at) expression used for keyset paging: the
// cursor compares whole seconds, not the nanoseconds stored in the text timestamp.
func createdAtBucket(t time.Time) int64 {
	return t.Truncate(time.Second).UnixMilli()
}

func hashInvitationToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// CreateAdminInvitation mints a one-time token bound to one email and one role. The raw token is
// returned exactly once and only its hash is stored.
func (s *Store) CreateAdminInvitation(ctx context.Context, actorID, email, role string, ttl time.Duration,
	now time.Time) (AdminInvitation, string, error) {
	email = NormalizeEmail(email)
	if !validAccountEmail(email) {
		return AdminInvitation{}, "", fmt.Errorf("email is invalid")
	}
	role = strings.ToUpper(strings.TrimSpace(role))
	if role != "USER" && role != "ADMIN" {
		return AdminInvitation{}, "", fmt.Errorf("role must be USER or ADMIN")
	}
	if ttl <= 0 || ttl > 30*24*time.Hour {
		return AdminInvitation{}, "", fmt.Errorf("expiry must be between 1 hour and 30 days")
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return AdminInvitation{}, "", err
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	invitation := AdminInvitation{
		ID: NewID("inv"), Email: email, Role: role, CreatedBy: actorID,
		ExpiresAt: now.Add(ttl), CreatedAt: now,
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return AdminInvitation{}, "", err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(ctx, `INSERT INTO admin_invitations
		(id, created_by, email, role, token_hash, expires_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		invitation.ID, actorID, email, role, hashInvitationToken(token),
		nowString(invitation.ExpiresAt), nowString(now)); err != nil {
		return AdminInvitation{}, "", err
	}
	if err := insertAuditTx(ctx, tx, actorID, email, "admin.invitation.create",
		map[string]any{"role": role, "expiresAt": invitation.ExpiresAt}, now); err != nil {
		return AdminInvitation{}, "", err
	}
	if err := tx.Commit(); err != nil {
		return AdminInvitation{}, "", err
	}
	return invitation, token, nil
}

// CreateAccountFromInvitation accepts a one-time admin invitation and creates the account with the
// invited role. The public registration path never reaches this function and always creates USER.
func (s *Store) CreateAccountFromInvitation(ctx context.Context, token, email, displayName string,
	digest PasswordDigest, now time.Time) (Account, error) {
	email = NormalizeEmail(email)
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return Account{}, err
	}
	defer func() { _ = tx.Rollback() }()
	var invitation AdminInvitation
	var expiresRaw, createdRaw string
	var usedRaw sql.NullString
	err = tx.QueryRowContext(ctx, `SELECT id, email, role, created_by, expires_at, used_at, created_at
		FROM admin_invitations WHERE token_hash = ?`, hashInvitationToken(token)).Scan(
		&invitation.ID, &invitation.Email, &invitation.Role, &invitation.CreatedBy, &expiresRaw,
		&usedRaw, &createdRaw)
	if errors.Is(err, sql.ErrNoRows) {
		return Account{}, ErrInvitationInvalid
	}
	if err != nil {
		return Account{}, err
	}
	if invitation.Email != email {
		return Account{}, ErrInvitationInvalid
	}
	if usedRaw.Valid {
		return Account{}, ErrInvitationUsed
	}
	invitation.ExpiresAt = parseTime(expiresRaw)
	if !now.Before(invitation.ExpiresAt) {
		return Account{}, ErrInvitationExpired
	}
	ownerID, err := s.insertAccountTx(ctx, tx, email, displayName, digest, invitation.Role, now)
	if err != nil {
		return Account{}, err
	}
	result, err := tx.ExecContext(ctx, `UPDATE admin_invitations SET used_at = ?
		WHERE id = ? AND used_at IS NULL`, nowString(now), invitation.ID)
	if err != nil {
		return Account{}, err
	}
	if changed, _ := result.RowsAffected(); changed != 1 {
		return Account{}, ErrInvitationUsed
	}
	if err := insertAuditTx(ctx, tx, invitation.CreatedBy, ownerID, "admin.invitation.accept",
		map[string]any{"role": invitation.Role, "invitationId": invitation.ID}, now); err != nil {
		return Account{}, err
	}
	if err := tx.Commit(); err != nil {
		return Account{}, err
	}
	return s.FindAccountByEmail(ctx, email)
}

func validAccountEmail(email string) bool {
	return strings.Contains(email, "@") && len(email) <= 254 && !strings.ContainsAny(email, " \t\r\n")
}

const adminAccountSelect = `SELECT owner_id, email, display_name, role, status, revision,
	created_at, updated_at FROM users`

func scanAdminAccount(scanner rowScanner) (AdminAccount, error) {
	var account AdminAccount
	var display sql.NullString
	var createdRaw, updatedRaw string
	if err := scanner.Scan(&account.OwnerID, &account.Email, &display, &account.Role,
		&account.Status, &account.Revision, &createdRaw, &updatedRaw); err != nil {
		return AdminAccount{}, err
	}
	account.DisplayName = display.String
	account.CreatedAt = parseTime(createdRaw)
	account.UpdatedAt = parseTime(updatedRaw)
	return account, nil
}

func adminAccountCursorScope(q, role, status string) string {
	sum := sha256.Sum256([]byte(strings.Join([]string{
		strings.ToLower(strings.TrimSpace(q)), strings.ToUpper(strings.TrimSpace(role)),
		strings.ToUpper(strings.TrimSpace(status)),
	}, "\x00")))
	return hex.EncodeToString(sum[:8])
}

func (s *Store) ListAdminAccounts(ctx context.Context, q, role, status string, limit int,
	cursor string) (AdminAccountPage, error) {
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	role = strings.ToUpper(strings.TrimSpace(role))
	status = strings.ToUpper(strings.TrimSpace(status))
	if role != "" && role != "USER" && role != "ADMIN" {
		return AdminAccountPage{}, fmt.Errorf("role filter is invalid")
	}
	if status != "" && status != "ACTIVE" && status != "SUSPENDED" && status != "DELETED" {
		return AdminAccountPage{}, fmt.Errorf("status filter is invalid")
	}
	scope := adminAccountCursorScope(q, role, status)
	sqlText := adminAccountSelect + ` WHERE 1 = 1`
	args := []any{}
	if trimmed := strings.TrimSpace(q); trimmed != "" {
		like := "%" + strings.ToLower(trimmed) + "%"
		sqlText += ` AND (LOWER(email) LIKE ? OR LOWER(COALESCE(display_name, '')) LIKE ?)`
		args = append(args, like, like)
	}
	if role != "" {
		sqlText += ` AND role = ?`
		args = append(args, role)
	}
	if status != "" {
		sqlText += ` AND status = ?`
		args = append(args, status)
	}
	if cursor != "" {
		ms, id, cursorScope, err := decodeCursor(cursor)
		if err != nil || cursorScope != scope {
			return AdminAccountPage{}, ErrInvalidCursor
		}
		sqlText += ` AND (CAST(strftime('%s', created_at) AS INTEGER) * 1000 < ?
			OR (CAST(strftime('%s', created_at) AS INTEGER) * 1000 = ? AND owner_id < ?))`
		args = append(args, ms, ms, id)
	}
	sqlText += ` ORDER BY CAST(strftime('%s', created_at) AS INTEGER) DESC, owner_id DESC LIMIT ?`
	args = append(args, limit+1)
	rows, err := s.DB.QueryContext(ctx, sqlText, args...)
	if err != nil {
		return AdminAccountPage{}, err
	}
	defer rows.Close()
	page := AdminAccountPage{}
	for rows.Next() {
		account, err := scanAdminAccount(rows)
		if err != nil {
			return AdminAccountPage{}, err
		}
		page.Items = append(page.Items, account)
	}
	if err := rows.Err(); err != nil {
		return AdminAccountPage{}, err
	}
	if len(page.Items) > limit {
		page.Items = page.Items[:limit]
		last := page.Items[len(page.Items)-1]
		page.NextCursor = encodeCursor(createdAtBucket(last.CreatedAt), last.OwnerID, scope)
	}
	return page, nil
}

func (s *Store) GetAdminAccount(ctx context.Context, ownerID string) (AdminAccount, error) {
	row := s.DB.QueryRowContext(ctx, adminAccountSelect+` WHERE owner_id = ?`, ownerID)
	account, err := scanAdminAccount(row)
	if errors.Is(err, sql.ErrNoRows) {
		return AdminAccount{}, ErrNotFound
	}
	return account, err
}

// UpdateAdminAccount applies identity/role changes with a revision CAS. A role or status change
// bumps auth_version and revokes live sessions so no stale token survives the change.
func (s *Store) UpdateAdminAccount(ctx context.Context, actorID, ownerID string, patch AdminAccountPatch,
	now time.Time) (AdminAccount, error) {
	current, err := s.GetAdminAccount(ctx, ownerID)
	if err != nil {
		return AdminAccount{}, err
	}
	if patch.Revision != current.Revision {
		return AdminAccount{}, ErrRevisionConflict
	}
	displayName := current.DisplayName
	if patch.DisplayName != nil {
		displayName = strings.TrimSpace(*patch.DisplayName)
	}
	role := current.Role
	if patch.Role != nil {
		role = strings.ToUpper(strings.TrimSpace(*patch.Role))
		if role != "USER" && role != "ADMIN" {
			return AdminAccount{}, fmt.Errorf("role must be USER or ADMIN")
		}
	}
	status := current.Status
	if patch.Status != nil {
		status = strings.ToUpper(strings.TrimSpace(*patch.Status))
		if status != "ACTIVE" && status != "SUSPENDED" {
			return AdminAccount{}, fmt.Errorf("status must be ACTIVE or SUSPENDED")
		}
	}
	if len([]rune(displayName)) > 80 {
		return AdminAccount{}, fmt.Errorf("displayName is too long")
	}
	if status == "SUSPENDED" && ownerID == actorID {
		return AdminAccount{}, ErrSelfAdminChange
	}
	identityChanged := role != current.Role || status != current.Status
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return AdminAccount{}, err
	}
	defer func() { _ = tx.Rollback() }()
	authBump := 0
	if identityChanged {
		authBump = 1
	}
	result, err := tx.ExecContext(ctx, `UPDATE users SET display_name = ?, role = ?, status = ?,
		auth_version = auth_version + ?, revision = revision + 1, updated_at = ?
		WHERE owner_id = ? AND revision = ?`,
		nullString(displayName), role, status, authBump, nowString(now), ownerID, current.Revision)
	if err != nil {
		if strings.Contains(err.Error(), "last_active_admin") {
			return AdminAccount{}, ErrLastAdmin
		}
		return AdminAccount{}, err
	}
	if changed, _ := result.RowsAffected(); changed != 1 {
		return AdminAccount{}, ErrRevisionConflict
	}
	if identityChanged {
		if err := revokeSessionsTx(ctx, tx, ownerID, "admin_role_changed", now); err != nil {
			return AdminAccount{}, err
		}
	}
	if err := insertAuditTx(ctx, tx, actorID, ownerID, "admin.account.update", map[string]any{
		"role": role, "status": status, "displayName": displayName,
	}, now); err != nil {
		return AdminAccount{}, err
	}
	if err := tx.Commit(); err != nil {
		return AdminAccount{}, err
	}
	return s.GetAdminAccount(ctx, ownerID)
}

// DeactivateAdminAccount suspends an account and revokes every live session. expectedRevision < 0
// is unconditional; 0 or more is a compare-and-swap. The last active admin cannot be suspended.
func (s *Store) DeactivateAdminAccount(ctx context.Context, actorID, ownerID string, expectedRevision int,
	now time.Time) (AdminAccount, error) {
	if actorID == ownerID {
		return AdminAccount{}, ErrSelfAdminChange
	}
	current, err := s.GetAdminAccount(ctx, ownerID)
	if err != nil {
		return AdminAccount{}, err
	}
	if expectedRevision >= 0 && expectedRevision != current.Revision {
		return AdminAccount{}, ErrRevisionConflict
	}
	if current.Status == "DELETED" {
		return AdminAccount{}, ErrNotFound
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return AdminAccount{}, err
	}
	defer func() { _ = tx.Rollback() }()
	_, err = tx.ExecContext(ctx, `UPDATE users SET status = 'SUSPENDED', auth_version = auth_version + 1,
		revision = revision + 1, updated_at = ? WHERE owner_id = ?`,
		nowString(now), ownerID)
	if err != nil {
		if strings.Contains(err.Error(), "last_active_admin") {
			return AdminAccount{}, ErrLastAdmin
		}
		return AdminAccount{}, err
	}
	if err := revokeSessionsTx(ctx, tx, ownerID, "admin_deactivated", now); err != nil {
		return AdminAccount{}, err
	}
	if err := insertAuditTx(ctx, tx, actorID, ownerID, "admin.account.deactivate",
		map[string]any{"status": "SUSPENDED"}, now); err != nil {
		return AdminAccount{}, err
	}
	if err := tx.Commit(); err != nil {
		return AdminAccount{}, err
	}
	return s.GetAdminAccount(ctx, ownerID)
}

func revokeSessionsTx(ctx context.Context, tx *sql.Tx, ownerID, reason string, now time.Time) error {
	_, err := tx.ExecContext(ctx, `UPDATE auth_sessions SET revoked_at = ?, revoked_reason = ?
		WHERE owner_id = ? AND revoked_at IS NULL`, nowString(now), reason, ownerID)
	return err
}

func insertAuditTx(ctx context.Context, tx *sql.Tx, actorID, targetID, action string,
	metadata map[string]any, now time.Time) error {
	raw, err := json.Marshal(metadata)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO admin_audit
		(id, actor_owner_id, target_owner_id, action, metadata_json, created_at)
		VALUES (?, ?, ?, ?, ?, ?)`, NewID("aud"), actorID, targetID, action, string(raw), nowString(now))
	return err
}

func auditCursorScope(targetID, action string) string {
	sum := sha256.Sum256([]byte(strings.Join([]string{strings.TrimSpace(targetID),
		strings.TrimSpace(action)}, "\x00")))
	return hex.EncodeToString(sum[:8])
}

func (s *Store) ListAudit(ctx context.Context, targetID, action string, limit int,
	cursor string) (AuditPage, error) {
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	scope := auditCursorScope(targetID, action)
	sqlText := `SELECT id, actor_owner_id, target_owner_id, action, metadata_json, created_at
		FROM admin_audit WHERE 1 = 1`
	args := []any{}
	if targetID != "" {
		sqlText += ` AND target_owner_id = ?`
		args = append(args, targetID)
	}
	if action != "" {
		sqlText += ` AND action = ?`
		args = append(args, action)
	}
	if cursor != "" {
		ms, id, cursorScope, err := decodeCursor(cursor)
		if err != nil || cursorScope != scope {
			return AuditPage{}, ErrInvalidCursor
		}
		sqlText += ` AND (CAST(strftime('%s', created_at) AS INTEGER) * 1000 < ?
			OR (CAST(strftime('%s', created_at) AS INTEGER) * 1000 = ? AND id < ?))`
		args = append(args, ms, ms, id)
	}
	sqlText += ` ORDER BY created_at DESC, id DESC LIMIT ?`
	args = append(args, limit+1)
	rows, err := s.DB.QueryContext(ctx, sqlText, args...)
	if err != nil {
		return AuditPage{}, err
	}
	defer rows.Close()
	page := AuditPage{}
	for rows.Next() {
		var entry AuditEntry
		var metadataRaw, createdRaw string
		if err := rows.Scan(&entry.ID, &entry.ActorID, &entry.TargetID, &entry.Action,
			&metadataRaw, &createdRaw); err != nil {
			return AuditPage{}, err
		}
		entry.CreatedAt = parseTime(createdRaw)
		if err := json.Unmarshal([]byte(metadataRaw), &entry.Metadata); err != nil {
			entry.Metadata = map[string]any{}
		}
		page.Items = append(page.Items, entry)
	}
	if err := rows.Err(); err != nil {
		return AuditPage{}, err
	}
	if len(page.Items) > limit {
		page.Items = page.Items[:limit]
		last := page.Items[len(page.Items)-1]
		page.NextCursor = encodeCursor(createdAtBucket(last.CreatedAt), last.ID, scope)
	}
	return page, nil
}
