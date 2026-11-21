package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

const DeletionSLADays = 7

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

func (s *Store) CreateOrGetDeletion(ctx context.Context, ownerID string, now time.Time) (DeletionRequest, error) {
	pending, err := s.pendingDeletion(ctx, ownerID)
	if err == nil {
		return pending, nil
	}
	if !errors.Is(err, ErrNotFound) {
		return DeletionRequest{}, err
	}
	record := DeletionRequest{ID: NewID("del"), State: "PENDING", SLADays: DeletionSLADays,
		RequestedAt: now.UTC()}
	_, err = s.DB.ExecContext(ctx, `INSERT INTO deletion_requests
		(id, owner_id, state, sla_days, requested_at, aggregates_deleted, created_at)
		VALUES (?, ?, 'PENDING', ?, ?, 0, ?)`,
		record.ID, ownerID, DeletionSLADays, nowString(now), nowString(now))
	return record, err
}

func (s *Store) GetDeletion(ctx context.Context, ownerID, id string) (DeletionRequest, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, state, sla_days, requested_at, completed_at,
		aggregates_deleted FROM deletion_requests WHERE owner_id = ? AND id = ?`, ownerID, id)
	return scanDeletion(row)
}

func (s *Store) pendingDeletion(ctx context.Context, ownerID string) (DeletionRequest, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, state, sla_days, requested_at, completed_at,
		aggregates_deleted FROM deletion_requests
		WHERE owner_id = ? AND state = 'PENDING' ORDER BY requested_at DESC LIMIT 1`, ownerID)
	return scanDeletion(row)
}

func scanDeletion(row *sql.Row) (DeletionRequest, error) {
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
