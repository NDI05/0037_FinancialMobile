package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// SweepDeletions completes pending workspace deletions. Active finance data, import records,
// summaries, analyses, budgets and attachment metadata are removed; sessions and refresh tokens are
// revoked; the account row is retained as DELETED for audit and the deletion request keeps its
// lifecycle. Consent and deletion audit rows are retained.
func (s *Store) SweepDeletions(ctx context.Context, now time.Time) (int, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT DISTINCT owner_id FROM deletion_requests
		WHERE state = 'PENDING' ORDER BY requested_at`)
	if err != nil {
		return 0, err
	}
	var owners []string
	for rows.Next() {
		var owner string
		if err := rows.Scan(&owner); err != nil {
			rows.Close()
			return 0, err
		}
		owners = append(owners, owner)
	}
	rows.Close()
	completed := 0
	for _, owner := range owners {
		if err := s.sweepOwner(ctx, owner, now); err != nil {
			return completed, err
		}
		completed++
	}
	return completed, nil
}

func (s *Store) sweepOwner(ctx context.Context, ownerID string, now time.Time) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	// A closing ADMIN must not become the "last active admin" by disappearing.
	var activeAdmins int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM users
		WHERE role = 'ADMIN' AND status = 'ACTIVE'`).Scan(&activeAdmins); err != nil {
		return err
	}
	var role, status string
	if err := tx.QueryRowContext(ctx, `SELECT role, status FROM users WHERE owner_id = ?`,
		ownerID).Scan(&role, &status); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if role == "ADMIN" && status == "ACTIVE" && activeAdmins <= 1 {
		return errors.New("last active admin cannot delete the workspace")
	}
	deleted := 0
	// Child tables first; owner predicates are explicit even though most are cascaded.
	tables := []string{
		"attachments", "budget_limits", "budget_plans", "transactions", "categories",
		"month_revisions", "import_skipped", "import_batches", "import_jobs",
		"summaries", "analyses", "account_keys", "http_idempotency",
	}
	for _, table := range tables {
		result, err := tx.ExecContext(ctx, `DELETE FROM `+table+` WHERE owner_id = ?`, ownerID)
		if err != nil {
			return err
		}
		affected, _ := result.RowsAffected()
		deleted += int(affected)
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM usage_counters WHERE scope = ?`, ownerID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, ?),
		revoked_reason = COALESCE(revoked_reason, 'workspace_deleted') WHERE owner_id = ?`,
		nowString(now), ownerID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, ?),
		revoked_reason = COALESCE(revoked_reason, 'workspace_deleted') WHERE owner_id = ?`,
		nowString(now), ownerID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE users SET status = 'DELETED', display_name = NULL,
		updated_at = ?, revision = revision + 1 WHERE owner_id = ?`, nowString(now), ownerID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE deletion_requests SET state = 'COMPLETED',
		completed_at = ?, aggregates_deleted = ? WHERE owner_id = ? AND state = 'PENDING'`,
		nowString(now), deleted, ownerID); err != nil {
		return err
	}
	return tx.Commit()
}
