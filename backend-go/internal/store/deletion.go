package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"time"
)

const DeletionSLADays = 7

var ErrLastAdmin = errors.New("last active admin cannot delete the workspace")
var ErrReceiptNotFound = errors.New("deletion receipt not found")

type DeletionRequest struct {
	ID                string
	State             string
	SLADays           int
	RequestedAt       time.Time
	CompletedAt       *time.Time
	AggregatesDeleted int
}

func (s *Store) HasPendingDeletion(ctx context.Context, ownerID string) (bool, error) {
	var count int
	err := s.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM deletion_requests
		WHERE owner_id = ? AND state = 'PENDING'`, ownerID).Scan(&count)
	return count > 0, err
}

// CreateOrGetDeletion creates the one pending request, rotates its status receipt and revokes all
// sessions in the same write transaction. A retry can therefore obtain a fresh receipt without
// regaining access to financial data.
func (s *Store) CreateOrGetDeletion(ctx context.Context, ownerID, receiptHash string, now time.Time) (DeletionRequest, error) {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return DeletionRequest{}, err
	}
	defer func() { _ = tx.Rollback() }()
	var role, status string
	if err := tx.QueryRowContext(ctx, `SELECT role, status FROM users WHERE owner_id = ?`,
		ownerID).Scan(&role, &status); err != nil {
		return DeletionRequest{}, err
	}
	var activeAdmins int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM users
		WHERE role = 'ADMIN' AND status = 'ACTIVE'`).Scan(&activeAdmins); err != nil {
		return DeletionRequest{}, err
	}
	if role == "ADMIN" && status == "ACTIVE" && activeAdmins <= 1 {
		return DeletionRequest{}, ErrLastAdmin
	}
	record, err := scanDeletionTx(tx.QueryRowContext(ctx, `SELECT id, state, sla_days, requested_at,
		completed_at, aggregates_deleted FROM deletion_requests
		WHERE owner_id = ? AND state = 'PENDING' ORDER BY requested_at DESC LIMIT 1`, ownerID))
	if err != nil && !errors.Is(err, ErrNotFound) {
		return DeletionRequest{}, err
	}
	if errors.Is(err, ErrNotFound) {
		record = DeletionRequest{ID: NewID("del"), State: "PENDING", SLADays: DeletionSLADays,
			RequestedAt: now.UTC()}
		if _, err := tx.ExecContext(ctx, `INSERT INTO deletion_requests
			(id, owner_id, state, sla_days, requested_at, aggregates_deleted, receipt_hash, created_at)
			VALUES (?, ?, 'PENDING', ?, ?, 0, ?, ?)`,
			record.ID, ownerID, DeletionSLADays, nowString(now), receiptHash, nowString(now)); err != nil {
			return DeletionRequest{}, err
		}
	} else {
		if _, err := tx.ExecContext(ctx, `UPDATE deletion_requests SET receipt_hash = ?
			WHERE owner_id = ? AND id = ?`, receiptHash, ownerID, record.ID); err != nil {
			return DeletionRequest{}, err
		}
	}
	if _, err := tx.ExecContext(ctx, `UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, ?),
		revoked_reason = COALESCE(revoked_reason, 'workspace_deletion') WHERE owner_id = ?`,
		nowString(now), ownerID); err != nil {
		return DeletionRequest{}, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, ?),
		revoked_reason = COALESCE(revoked_reason, 'workspace_deletion') WHERE owner_id = ?`,
		nowString(now), ownerID); err != nil {
		return DeletionRequest{}, err
	}
	if err := tx.Commit(); err != nil {
		return DeletionRequest{}, err
	}
	return record, nil
}

func (s *Store) GetDeletion(ctx context.Context, ownerID, id string) (DeletionRequest, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, state, sla_days, requested_at, completed_at,
		aggregates_deleted FROM deletion_requests WHERE owner_id = ? AND id = ?`, ownerID, id)
	return scanDeletion(row)
}

func (s *Store) GetDeletionByReceipt(ctx context.Context, receipt string) (DeletionRequest, error) {
	sum := sha256.Sum256([]byte(receipt))
	row := s.DB.QueryRowContext(ctx, `SELECT id, state, sla_days, requested_at, completed_at,
		aggregates_deleted FROM deletion_requests WHERE receipt_hash = ? LIMIT 1`,
		hex.EncodeToString(sum[:]))
	record, err := scanDeletion(row)
	if errors.Is(err, ErrNotFound) {
		return DeletionRequest{}, ErrReceiptNotFound
	}
	return record, err
}

func (s *Store) pendingDeletion(ctx context.Context, ownerID string) (DeletionRequest, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, state, sla_days, requested_at, completed_at,
		aggregates_deleted FROM deletion_requests
		WHERE owner_id = ? AND state = 'PENDING' ORDER BY requested_at DESC LIMIT 1`, ownerID)
	return scanDeletion(row)
}

func scanDeletion(row *sql.Row) (DeletionRequest, error) {
	return scanDeletionRow(row)
}

func scanDeletionRow(row rowScanner) (DeletionRequest, error) {
	var record DeletionRequest
	var requested string
	var completed sql.NullString
	if err := row.Scan(&record.ID, &record.State, &record.SLADays, &requested, &completed,
		&record.AggregatesDeleted); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return DeletionRequest{}, ErrNotFound
		}
		return DeletionRequest{}, err
	}
	record.RequestedAt = parseTime(requested)
	if completed.Valid {
		t := parseTime(completed.String)
		record.CompletedAt = &t
	}
	return record, nil
}

func scanDeletionTx(row *sql.Row) (DeletionRequest, error) {
	return scanDeletionRow(row)
}
