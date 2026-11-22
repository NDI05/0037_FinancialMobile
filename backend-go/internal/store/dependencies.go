package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"
)

// SnapshotDependencies is the minimum state identity needed to prove an analysis payload is still
// current. The hash covers the target and prior month revisions, category revisions/kinds, budget
// revision and limits, and the latest import coverage record. The payload hash alone is not enough.
type SnapshotDependencies struct {
	TargetRevision int
	PriorRevision  int
	CategoriesHash string
	BudgetHash     string
	CoverageHash   string
	Hash           string
}

func (s *Store) SnapshotDependencies(ctx context.Context, ownerID, monthID string) (SnapshotDependencies, error) {
	return dependenciesFor(ctx, s.DB, ownerID, monthID)
}

func (s *Store) DependencyHash(ctx context.Context, ownerID, monthID string) (string, int, error) {
	deps, err := s.SnapshotDependencies(ctx, ownerID, monthID)
	if err != nil {
		return "", 0, err
	}
	return deps.Hash, deps.TargetRevision, nil
}

type queryer interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func dependenciesFor(ctx context.Context, q queryer, ownerID, monthID string) (SnapshotDependencies, error) {
	previousID := previousMonthID(monthID)
	targetRev, err := revisionFor(ctx, q, ownerID, monthID)
	if err != nil {
		return SnapshotDependencies{}, err
	}
	priorRev, err := revisionFor(ctx, q, ownerID, previousID)
	if err != nil {
		return SnapshotDependencies{}, err
	}
	categories, err := queryStrings(ctx, q, `SELECT id || '|' || code || '|' || kind || '|' ||
		revision || '|' || COALESCE(deleted_at, '') FROM categories
		WHERE owner_id = ? ORDER BY id`, ownerID)
	if err != nil {
		return SnapshotDependencies{}, err
	}
	var budget stringsBuilder
	rows, err := q.QueryContext(ctx, `SELECT id, revision, expected_income_minor, planned_savings_minor
		FROM budget_plans WHERE owner_id = ? AND month_id = ?`, ownerID, monthID)
	if err != nil {
		return SnapshotDependencies{}, err
	}
	for rows.Next() {
		var id string
		var revision int
		var expected, savings int64
		if err := rows.Scan(&id, &revision, &expected, &savings); err != nil {
			rows.Close()
			return SnapshotDependencies{}, err
		}
		budget.WriteString(fmt.Sprintf("%s|%d|%d|%d;", id, revision, expected, savings))
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return SnapshotDependencies{}, err
	}
	limitRows, err := q.QueryContext(ctx, `SELECT bl.category_id, bl.amount_minor
		FROM budget_limits bl JOIN budget_plans bp
		  ON bp.owner_id = bl.owner_id AND bp.id = bl.plan_id
		WHERE bl.owner_id = ? AND bp.month_id = ? ORDER BY bl.category_id`, ownerID, monthID)
	if err != nil {
		return SnapshotDependencies{}, err
	}
	for limitRows.Next() {
		var categoryID string
		var amount int64
		if err := limitRows.Scan(&categoryID, &amount); err != nil {
			limitRows.Close()
			return SnapshotDependencies{}, err
		}
		budget.WriteString(fmt.Sprintf("L%s|%d;", categoryID, amount))
	}
	limitRows.Close()
	if err := limitRows.Err(); err != nil {
		return SnapshotDependencies{}, err
	}
	coverageRows, err := q.QueryContext(ctx, `SELECT id, state, pages_processed, matched_count,
		imported_count, duplicate_count, skipped_count, unresolved_count, COALESCE(finished_at,'')
		FROM import_jobs WHERE owner_id = ? AND month_id = ?
		ORDER BY started_at DESC, rowid DESC`, ownerID, monthID)
	if err != nil {
		return SnapshotDependencies{}, err
	}
	var coverage stringsBuilder
	for coverageRows.Next() {
		var id, state, finished string
		var pages, matched, imported, duplicates, skipped, unresolved int
		if err := coverageRows.Scan(&id, &state, &pages, &matched, &imported, &duplicates,
			&skipped, &unresolved, &finished); err != nil {
			coverageRows.Close()
			return SnapshotDependencies{}, err
		}
		coverage.WriteString(fmt.Sprintf("%s|%s|%d|%d|%d|%d|%d|%d|%s;", id, state, pages,
			matched, imported, duplicates, skipped, unresolved, finished))
	}
	coverageRows.Close()
	if err := coverageRows.Err(); err != nil {
		return SnapshotDependencies{}, err
	}
	deps := SnapshotDependencies{
		TargetRevision: targetRev, PriorRevision: priorRev,
		CategoriesHash: hashText(joinStrings(categories)),
		BudgetHash:     hashText(budget.String()),
		CoverageHash:   hashText(coverage.String()),
	}
	deps.Hash = hashText(fmt.Sprintf("target:%d|prior:%d|categories:%s|budget:%s|coverage:%s",
		deps.TargetRevision, deps.PriorRevision, deps.CategoriesHash, deps.BudgetHash, deps.CoverageHash))
	return deps, nil
}

func revisionFor(ctx context.Context, q queryer, ownerID, monthID string) (int, error) {
	var revision int
	err := q.QueryRowContext(ctx, `SELECT revision FROM month_revisions
		WHERE owner_id = ? AND month_id = ?`, ownerID, monthID).Scan(&revision)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	return revision, err
}

func queryStrings(ctx context.Context, q queryer, query string, args ...any) ([]string, error) {
	rows, err := q.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var values []string
	for rows.Next() {
		var value string
		if err := rows.Scan(&value); err != nil {
			return nil, err
		}
		values = append(values, value)
	}
	return values, rows.Err()
}

func hashText(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func joinStrings(values []string) string {
	out := ""
	for _, value := range values {
		out += value + ";"
	}
	return out
}

type stringsBuilder struct{ value string }

func (b *stringsBuilder) WriteString(value string) { b.value += value }
func (b *stringsBuilder) String() string           { return b.value }

func previousMonthID(monthID string) string {
	start, _, err := ParseMonth(monthID)
	if err != nil {
		return monthID
	}
	loc, err := time.LoadLocation("Asia/Jakarta")
	if err != nil {
		loc = time.FixedZone("Asia/Jakarta", 7*60*60)
	}
	return start.In(loc).AddDate(0, -1, 0).Format("2006-01")
}
