package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type Consent struct {
	ID            string
	Purpose       string
	Decision      string
	NoticeVersion string
	CreatedAt     time.Time
}

func (s *Store) AppendConsent(ctx context.Context, ownerID, purpose, decision, noticeVersion string, now time.Time) (Consent, error) {
	record := Consent{
		ID: NewID("consent"), Purpose: purpose, Decision: decision,
		NoticeVersion: noticeVersion, CreatedAt: now.UTC(),
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return Consent{}, err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(ctx, `INSERT INTO consents
		(id, owner_id, purpose, decision, notice_version, created_at)
		VALUES (?, ?, ?, ?, ?, ?)`,
		record.ID, ownerID, purpose, decision, noticeVersion, nowString(now)); err != nil {
		return Consent{}, err
	}
	if purpose == "AI_PROCESSING" && decision == "WITHDRAWN" {
		if _, err := tx.ExecContext(ctx, `UPDATE analyses SET status = 'HISTORICAL'
			WHERE owner_id = ? AND status = 'AVAILABLE'`, ownerID); err != nil {
			return Consent{}, err
		}
	}
	if err := tx.Commit(); err != nil {
		return Consent{}, err
	}
	return record, nil
}

func (s *Store) ListConsents(ctx context.Context, ownerID string, limit int) ([]Consent, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT id, purpose, decision, notice_version, created_at
		FROM consents WHERE owner_id = ? ORDER BY created_at DESC, rowid DESC LIMIT ?`, ownerID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Consent
	for rows.Next() {
		var c Consent
		var created string
		if err := rows.Scan(&c.ID, &c.Purpose, &c.Decision, &c.NoticeVersion, &created); err != nil {
			return nil, err
		}
		c.CreatedAt = parseTime(created)
		out = append(out, c)
	}
	return out, rows.Err()
}

func (s *Store) LatestConsent(ctx context.Context, ownerID, purpose string) (Consent, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, purpose, decision, notice_version, created_at
		FROM consents WHERE owner_id = ? AND purpose = ?
		ORDER BY created_at DESC, rowid DESC LIMIT 1`, ownerID, purpose)
	var c Consent
	var created string
	if err := row.Scan(&c.ID, &c.Purpose, &c.Decision, &c.NoticeVersion, &created); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Consent{}, ErrNotFound
		}
		return Consent{}, err
	}
	c.CreatedAt = parseTime(created)
	return c, nil
}
