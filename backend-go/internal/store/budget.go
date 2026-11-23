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
	TotalLimitMinor     int64          `json:"totalLimitMinor"`
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

type BudgetPlanPage struct {
	Plans      []BudgetPlan
	NextCursor string
}

func (s *Store) GetBudgetPlan(ctx context.Context, ownerID, monthID string) (BudgetPlan, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, expected_income_minor, planned_savings_minor,
		ciphertext, iv, tag, key_version, revision, created_at, updated_at
		FROM budget_plans WHERE owner_id = ? AND month_id = ?`, ownerID, monthID)
	var id string
	var plan BudgetPlan
	var sealed crypto.Sealed
	var created, updated string
	if err := row.Scan(&id, &plan.ExpectedIncomeMinor, &plan.PlannedSavingsMinor,
		&sealed.Ciphertext, &sealed.IV, &sealed.Tag, &sealed.KeyVersion, &plan.Revision,
		&created, &updated); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return BudgetPlan{}, ErrNotFound
		}
		return BudgetPlan{}, err
	}
	plan.MonthID = monthID
	plan.CreatedAt, plan.UpdatedAt = parseTime(created), parseTime(updated)
	if err := s.hydrateBudgetPlan(ctx, ownerID, id, sealed, &plan); err != nil {
		return BudgetPlan{}, err
	}
	return plan, nil
}

// hydrateBudgetPlan completes a plan whose scalar columns were already scanned: it decrypts the
// sealed attributes, loads the limits and derives the totals/gap. Keeping this in one place means
// GET detail and the month-range listing always agree on the derived fields.
func (s *Store) hydrateBudgetPlan(ctx context.Context, ownerID, planID string, sealed crypto.Sealed,
	plan *BudgetPlan) error {
	if err := crypto.OpenJSON(sealed, s.DataKey, ownerID, "budget_plan", &plan.Attributes); err != nil {
		return err
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT bl.category_id, COALESCE(c.code, ''), bl.amount_minor
		FROM budget_limits bl LEFT JOIN categories c
		  ON c.owner_id = bl.owner_id AND c.id = bl.category_id
		WHERE bl.owner_id = ? AND bl.plan_id = ? ORDER BY bl.amount_minor DESC`, ownerID, planID)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var limit BudgetLimit
		if err := rows.Scan(&limit.CategoryID, &limit.CategoryCode, &limit.AmountMinor); err != nil {
			return err
		}
		plan.Limits = append(plan.Limits, limit)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	for _, limit := range plan.Limits {
		plan.TotalLimitMinor += limit.AmountMinor
	}
	plan.GapMinor = plan.ExpectedIncomeMinor - plan.PlannedSavingsMinor - plan.TotalLimitMinor
	return nil
}

// ListBudgetPlans returns the owner's plans for the inclusive month range from..to, newest month
// first. from/to are YYYY-MM and must be supplied together; an empty range lists every plan. The
// cursor is bound to owner+range so it cannot be replayed against another filter.
func (s *Store) ListBudgetPlans(ctx context.Context, ownerID, from, to string, limit int,
	cursor string) (BudgetPlanPage, error) {
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	from, to = NormalizeSearchTerm(from), NormalizeSearchTerm(to)
	if (from == "") != (to == "") {
		return BudgetPlanPage{}, fmt.Errorf("from and to must be provided together")
	}
	if from != "" {
		if _, _, err := ParseMonth(from); err != nil {
			return BudgetPlanPage{}, fmt.Errorf("from must be YYYY-MM")
		}
		if _, _, err := ParseMonth(to); err != nil {
			return BudgetPlanPage{}, fmt.Errorf("to must be YYYY-MM")
		}
		if from > to {
			return BudgetPlanPage{}, fmt.Errorf("from must be <= to")
		}
	}
	scope := budgetPlanListScope(ownerID, from, to)
	sqlText := `SELECT id, month_id, expected_income_minor, planned_savings_minor, ciphertext, iv,
		tag, key_version, revision, created_at, updated_at FROM budget_plans WHERE owner_id = ?`
	args := []any{ownerID}
	if from != "" {
		sqlText += ` AND month_id >= ? AND month_id <= ?`
		args = append(args, from, to)
	}
	if cursor != "" {
		_, monthID, cursorScope, err := decodeCursor(cursor)
		if err != nil || cursorScope != scope {
			return BudgetPlanPage{}, ErrInvalidCursor
		}
		// month_id is unique per owner and YYYY-MM sorts chronologically as text.
		sqlText += ` AND month_id < ?`
		args = append(args, monthID)
	}
	sqlText += ` ORDER BY month_id DESC LIMIT ?`
	args = append(args, limit+1)
	rows, err := s.DB.QueryContext(ctx, sqlText, args...)
	if err != nil {
		return BudgetPlanPage{}, err
	}
	type pendingPlan struct {
		id     string
		sealed crypto.Sealed
		plan   BudgetPlan
	}
	var pending []pendingPlan
	for rows.Next() {
		var item pendingPlan
		var created, updated string
		if err := rows.Scan(&item.id, &item.plan.MonthID, &item.plan.ExpectedIncomeMinor,
			&item.plan.PlannedSavingsMinor, &item.sealed.Ciphertext, &item.sealed.IV,
			&item.sealed.Tag, &item.sealed.KeyVersion, &item.plan.Revision, &created,
			&updated); err != nil {
			rows.Close()
			return BudgetPlanPage{}, err
		}
		item.plan.CreatedAt, item.plan.UpdatedAt = parseTime(created), parseTime(updated)
		pending = append(pending, item)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return BudgetPlanPage{}, err
	}
	// Close before hydrating: hydration issues its own queries and the SQLite pool may hand out a
	// single connection, so iterating rows and querying on top of them would deadlock.
	rows.Close()
	page := BudgetPlanPage{}
	for _, item := range pending {
		if err := s.hydrateBudgetPlan(ctx, ownerID, item.id, item.sealed, &item.plan); err != nil {
			return BudgetPlanPage{}, err
		}
		page.Plans = append(page.Plans, item.plan)
	}
	if len(page.Plans) > limit {
		page.Plans = page.Plans[:limit]
		last := page.Plans[len(page.Plans)-1]
		page.NextCursor = encodeCursor(0, last.MonthID, scope)
	}
	return page, nil
}

func budgetPlanListScope(ownerID, from, to string) string {
	sum := sha256.Sum256([]byte(strings.Join([]string{ownerID, from, to}, "\x00")))
	return hex.EncodeToString(sum[:8])
}

func (s *Store) PutBudgetPlan(ctx context.Context, ownerID, monthID string, input BudgetInput, now time.Time) (BudgetPlan, error) {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return BudgetPlan{}, err
	}
	if input.ExpectedIncomeMinor < 0 || input.ExpectedIncomeMinor > 1_000_000_000_000_000 ||
		input.PlannedSavingsMinor < 0 || input.PlannedSavingsMinor > 1_000_000_000_000_000 {
		return BudgetPlan{}, ErrConflict
	}
	seenLimits := make(map[string]bool, len(input.Limits))
	for _, limit := range input.Limits {
		if limit.AmountMinor < 0 || limit.AmountMinor > 1_000_000_000_000_000 {
			return BudgetPlan{}, ErrConflict
		}
		if seenLimits[limit.CategoryID] {
			return BudgetPlan{}, fmt.Errorf("invalid budget: duplicate categoryId in limits")
		}
		seenLimits[limit.CategoryID] = true
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
