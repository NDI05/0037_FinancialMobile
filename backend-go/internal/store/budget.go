package store

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/pam/recap/backend-go/internal/crypto"
)

type BudgetLimit struct {
	CategoryID   string `json:"categoryId"`
	CategoryCode string `json:"categoryCode,omitempty"`
	AmountMinor  int64  `json:"amountMinor"`
}

type BudgetPlan struct {
	MonthID             string         `json:"monthId"`
	ExpectedIncomeMinor int64          `json:"expectedIncomeMinor"`
	PlannedSavingsMinor int64          `json:"plannedSavingsMinor"`
	Limits              []BudgetLimit  `json:"limits"`
	Attributes          map[string]any `json:"attributes,omitempty"`
	Revision            int            `json:"revision"`
	GapMinor            int64          `json:"gapMinor"`
	CreatedAt           time.Time      `json:"createdAt"`
	UpdatedAt           time.Time      `json:"updatedAt"`
}

type BudgetInput struct {
	ExpectedIncomeMinor int64
	PlannedSavingsMinor int64
	Limits              []BudgetLimit
	Attributes          map[string]any
	Revision            int
}

func (s *Store) GetBudgetPlan(ctx context.Context, ownerID, monthID string) (BudgetPlan, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, expected_income_minor, planned_savings_minor,
		ciphertext, iv, tag, key_version, revision, created_at, updated_at
		FROM budget_plans WHERE owner_id = ? AND month_id = ?`, ownerID, monthID)
	var id string
	var plan BudgetPlan
	var ciphertext, iv, tag []byte
	var keyVersion int
	var created, updated string
	if err := row.Scan(&id, &plan.ExpectedIncomeMinor, &plan.PlannedSavingsMinor, &ciphertext,
		&iv, &tag, &keyVersion, &plan.Revision, &created, &updated); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return BudgetPlan{}, ErrNotFound
		}
		return BudgetPlan{}, err
	}
	plan.MonthID = monthID
	plan.CreatedAt, plan.UpdatedAt = parseTime(created), parseTime(updated)
	if err := crypto.OpenJSON(crypto.Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag,
		KeyVersion: keyVersion}, s.DataKey, ownerID, "budget_plan", &plan.Attributes); err != nil {
		return BudgetPlan{}, err
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT bl.category_id, COALESCE(c.code, ''), bl.amount_minor
		FROM budget_limits bl LEFT JOIN categories c
		  ON c.owner_id = bl.owner_id AND c.id = bl.category_id
		WHERE bl.owner_id = ? AND bl.plan_id = ? ORDER BY bl.amount_minor DESC`, ownerID, id)
	if err != nil {
		return BudgetPlan{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var limit BudgetLimit
		if err := rows.Scan(&limit.CategoryID, &limit.CategoryCode, &limit.AmountMinor); err != nil {
			return BudgetPlan{}, err
		}
		plan.Limits = append(plan.Limits, limit)
	}
	if err := rows.Err(); err != nil {
		return BudgetPlan{}, err
	}
	plan.GapMinor = plan.ExpectedIncomeMinor - plan.PlannedSavingsMinor
	for _, limit := range plan.Limits {
		plan.GapMinor -= limit.AmountMinor
	}
	return plan, nil
}

func (s *Store) PutBudgetPlan(ctx context.Context, ownerID, monthID string, input BudgetInput, now time.Time) (BudgetPlan, error) {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return BudgetPlan{}, err
	}
	if input.ExpectedIncomeMinor < 0 || input.ExpectedIncomeMinor > 1_000_000_000_000_000 ||
		input.PlannedSavingsMinor < 0 || input.PlannedSavingsMinor > 1_000_000_000_000_000 {
		return BudgetPlan{}, ErrConflict
	}
	for _, limit := range input.Limits {
		if limit.AmountMinor < 0 || limit.AmountMinor > 1_000_000_000_000_000 {
			return BudgetPlan{}, ErrConflict
		}
		if err := s.ensureCategory(ctx, ownerID, limit.CategoryID); err != nil {
			return BudgetPlan{}, err
		}
	}
	sealed, err := crypto.SealJSON(input.Attributes, s.DataKey, s.KeyVersion, ownerID, "budget_plan")
	if err != nil {
		return BudgetPlan{}, err
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return BudgetPlan{}, err
	}
	defer func() { _ = tx.Rollback() }()
	var id string
	var revision int
	err = tx.QueryRowContext(ctx, `SELECT id, revision FROM budget_plans
		WHERE owner_id = ? AND month_id = ?`, ownerID, monthID).Scan(&id, &revision)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if input.Revision != 0 {
			return BudgetPlan{}, ErrRevisionConflict
		}
		id = NewID("plan")
		_, err = tx.ExecContext(ctx, `INSERT INTO budget_plans
			(id, owner_id, month_id, expected_income_minor, planned_savings_minor,
			 ciphertext, iv, tag, key_version, created_at, updated_at, revision)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)`,
			id, ownerID, monthID, input.ExpectedIncomeMinor, input.PlannedSavingsMinor,
			sealed.Ciphertext, sealed.IV, sealed.Tag, s.KeyVersion, nowString(now), nowString(now))
	case err != nil:
		return BudgetPlan{}, err
	default:
		if revision != input.Revision {
			return BudgetPlan{}, ErrRevisionConflict
		}
		_, err = tx.ExecContext(ctx, `UPDATE budget_plans SET expected_income_minor = ?,
			planned_savings_minor = ?, ciphertext = ?, iv = ?, tag = ?, key_version = ?,
			revision = revision + 1, updated_at = ? WHERE owner_id = ? AND month_id = ?`,
			input.ExpectedIncomeMinor, input.PlannedSavingsMinor, sealed.Ciphertext, sealed.IV,
			sealed.Tag, s.KeyVersion, nowString(now), ownerID, monthID)
	}
	if err != nil {
		return BudgetPlan{}, err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM budget_limits WHERE owner_id = ? AND plan_id = ?`,
		ownerID, id); err != nil {
		return BudgetPlan{}, err
	}
	for _, limit := range input.Limits {
		if _, err := tx.ExecContext(ctx, `INSERT INTO budget_limits
			(owner_id, plan_id, category_id, amount_minor, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?)`,
			ownerID, id, limit.CategoryID, limit.AmountMinor, nowString(now), nowString(now)); err != nil {
			return BudgetPlan{}, err
		}
	}
	if err := bumpMonthRevision(ctx, tx, ownerID, monthID, now); err != nil {
		return BudgetPlan{}, err
	}
	if err := tx.Commit(); err != nil {
		return BudgetPlan{}, err
	}
	return s.GetBudgetPlan(ctx, ownerID, monthID)
}

func (s *Store) DeleteBudgetPlan(ctx context.Context, ownerID, monthID string) error {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return err
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	result, err := tx.ExecContext(ctx, `DELETE FROM budget_plans WHERE owner_id = ? AND month_id = ?`,
		ownerID, monthID)
	if err != nil {
		return err
	}
	affected, _ := result.RowsAffected()
	if affected == 0 {
		return ErrNotFound
	}
	if err := bumpMonthRevision(ctx, tx, ownerID, monthID, time.Now().UTC()); err != nil {
		return err
	}
	return tx.Commit()
}
