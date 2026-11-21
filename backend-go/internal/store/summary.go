package store

import (
	"context"
	"database/sql"
	"errors"
	"sort"
	"time"

	"github.com/pam/recap/backend-go/internal/crypto"
	"github.com/pam/recap/backend-go/internal/finance"
)

type Summary struct {
	MonthID                string                  `json:"monthId"`
	InputRevision          int                     `json:"inputRevision"`
	Coverage               string                  `json:"coverage"`
	GrossInflowMinor       int64                   `json:"grossInflowMinor"`
	GrossOutflowMinor      int64                   `json:"grossOutflowMinor"`
	NetMinor               int64                   `json:"netMinor"`
	ClassifiedIncomeMinor  int64                   `json:"classifiedIncomeMinor"`
	ClassifiedExpenseMinor int64                   `json:"classifiedExpenseMinor"`
	TransactionCount       int                     `json:"transactionCount"`
	ManualEntryCount       int                     `json:"manualEntryCount"`
	UncategorizedCount     int                     `json:"uncategorizedCount"`
	CategoryTotals         []finance.CategoryTotal `json:"categoryTotals"`
	GeneratedAt            time.Time               `json:"generatedAt"`
	EmailDerived           bool                    `json:"emailDerived"`
}

type StoredSummary struct {
	MonthID       string
	InputRevision int
	Coverage      string
	Payload       map[string]any
	CreatedAt     time.Time
	UpdatedAt     time.Time
}

// ComputeSummary reads only active rows for one owner/month and applies the deterministic rules.
func (s *Store) ComputeSummary(ctx context.Context, ownerID, monthID string, now time.Time) (Summary, error) {
	start, end, err := ParseMonth(monthID)
	if err != nil {
		return Summary{}, err
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT t.id, COALESCE(t.category_id, ''),
		COALESCE(c.code, ''), COALESCE(c.kind, 'UNCATEGORIZED'), t.direction, t.source,
		t.amount_minor, t.is_internal_transfer, COALESCE(t.linked_refund_of, '')
		FROM transactions t LEFT JOIN categories c
		  ON c.owner_id = t.owner_id AND c.id = t.category_id
		WHERE t.owner_id = ? AND t.deleted_at IS NULL
		  AND t.event_at_ms >= ? AND t.event_at_ms < ?
		ORDER BY t.event_at_ms ASC, t.id ASC`, ownerID, start.UnixMilli(), end.UnixMilli())
	if err != nil {
		return Summary{}, err
	}
	defer rows.Close()
	var entries []finance.Entry
	for rows.Next() {
		var entry finance.Entry
		var internal int
		var categoryID string
		if err := rows.Scan(&entry.ID, &categoryID, &entry.CategoryCode,
			&entry.CategoryKind, &entry.Direction, &entry.Source, &entry.AmountMinor,
			&internal, &entry.LinkedRefundOf); err != nil {
			return Summary{}, err
		}
		entry.IsInternalTransfer = internal == 1
		entries = append(entries, entry)
	}
	if err := rows.Err(); err != nil {
		return Summary{}, err
	}
	totals := finance.Compute(entries)
	categoryTotals := make([]finance.CategoryTotal, 0, len(totals.CategoryTotals))
	// Labels come from the category table; decrypt them only for the categories that appear.
	labels, err := s.categoryLabels(ctx, ownerID)
	if err != nil {
		return Summary{}, err
	}
	for code, amount := range totals.CategoryTotals {
		categoryTotals = append(categoryTotals, finance.CategoryTotal{
			CategoryCode: code, Label: labels[code], AmountMinor: amount,
			Count: totals.CategoryCounts[code],
		})
	}
	sort.Slice(categoryTotals, func(i, j int) bool {
		if categoryTotals[i].AmountMinor == categoryTotals[j].AmountMinor {
			return categoryTotals[i].CategoryCode < categoryTotals[j].CategoryCode
		}
		return categoryTotals[i].AmountMinor > categoryTotals[j].AmountMinor
	})
	revision, err := s.MonthRevision(ctx, ownerID, monthID)
	if err != nil {
		return Summary{}, err
	}
	coverage, err := s.coverage(ctx, ownerID, monthID, totals.TransactionCount)
	if err != nil {
		return Summary{}, err
	}
	return Summary{
		MonthID: monthID, InputRevision: revision, Coverage: coverage,
		GrossInflowMinor: totals.GrossInflowMinor, GrossOutflowMinor: totals.GrossOutflowMinor,
		NetMinor: totals.NetMinor, ClassifiedIncomeMinor: totals.ClassifiedIncomeMinor,
		ClassifiedExpenseMinor: totals.ClassifiedExpenseMinor,
		TransactionCount:       totals.TransactionCount, ManualEntryCount: totals.ManualCount,
		UncategorizedCount: totals.UncategorizedCount, CategoryTotals: categoryTotals,
		GeneratedAt: now.UTC(), EmailDerived: true,
	}, nil
}

func (s *Store) MonthRevision(ctx context.Context, ownerID, monthID string) (int, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT revision FROM month_revisions
		WHERE owner_id = ? AND month_id = ?`, ownerID, monthID)
	var revision int
	if err := row.Scan(&revision); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, nil
		}
		return 0, err
	}
	return revision, nil
}

func (s *Store) coverage(ctx context.Context, ownerID, monthID string, transactionCount int) (string, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT state FROM import_jobs
		WHERE owner_id = ? AND month_id = ? ORDER BY started_at DESC, rowid DESC LIMIT 1`,
		ownerID, monthID)
	var state string
	if err := row.Scan(&state); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			if transactionCount > 0 {
				return "PARTIAL", nil
			}
			return "EMPTY", nil
		}
		return "", err
	}
	switch state {
	case "COMPLETE":
		if transactionCount > 0 {
			return "COMPLETE", nil
		}
		return "EMPTY", nil
	case "EMPTY":
		return "EMPTY", nil
	case "FAILED", "PERMISSION_REQUIRED":
		if transactionCount > 0 {
			return "PARTIAL", nil
		}
		return "FAILED", nil
	default:
		return "PARTIAL", nil
	}
}

func (s *Store) categoryLabels(ctx context.Context, ownerID string) (map[string]string, error) {
	rows, err := s.DB.QueryContext(ctx, `SELECT code, label_ciphertext, label_iv, label_tag, key_version
		FROM categories WHERE owner_id = ? AND deleted_at IS NULL`, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	labels := map[string]string{}
	for rows.Next() {
		var code string
		var ciphertext, iv, tagBytes []byte
		var keyVersion int
		if err := rows.Scan(&code, &ciphertext, &iv, &tagBytes, &keyVersion); err != nil {
			return nil, err
		}
		var label string
		if err := crypto.OpenJSON(crypto.Sealed{Ciphertext: ciphertext, IV: iv, Tag: tagBytes,
			KeyVersion: keyVersion}, s.DataKey, ownerID, "category_label", &label); err != nil {
			return nil, err
		}
		labels[code] = label
	}
	return labels, rows.Err()
}

// UpsertSummary stores the client-supplied aggregate used by the v0.3 Android sync.
func (s *Store) UpsertSummary(ctx context.Context, ownerID, monthID string, inputRevision int, payload map[string]any, now time.Time) (StoredSummary, error) {
	sealed, err := crypto.SealJSON(payload, s.DataKey, s.KeyVersion, ownerID, "summary")
	if err != nil {
		return StoredSummary{}, err
	}
	coverage, _ := payload["coverage"].(string)
	if coverage == "" {
		coverage = "PARTIAL"
	}
	_, err = s.DB.ExecContext(ctx, `INSERT INTO summaries
		(id, owner_id, month_id, input_revision, coverage, ciphertext, iv, tag, key_version, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(owner_id, month_id) DO UPDATE SET input_revision = excluded.input_revision,
			coverage = excluded.coverage, ciphertext = excluded.ciphertext, iv = excluded.iv,
			tag = excluded.tag, key_version = excluded.key_version, updated_at = excluded.updated_at`,
		NewID("sum"), ownerID, monthID, inputRevision, coverage, sealed.Ciphertext, sealed.IV,
		sealed.Tag, s.KeyVersion, nowString(now), nowString(now))
	if err != nil {
		return StoredSummary{}, err
	}
	return s.GetStoredSummary(ctx, ownerID, monthID)
}

func (s *Store) GetStoredSummary(ctx context.Context, ownerID, monthID string) (StoredSummary, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT input_revision, coverage, ciphertext, iv, tag,
		key_version, created_at, updated_at FROM summaries WHERE owner_id = ? AND month_id = ?`,
		ownerID, monthID)
	var stored StoredSummary
	var ciphertext, iv, tag []byte
	var keyVersion int
	var created, updated string
	if err := row.Scan(&stored.InputRevision, &stored.Coverage, &ciphertext, &iv, &tag,
		&keyVersion, &created, &updated); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return StoredSummary{}, ErrNotFound
		}
		return StoredSummary{}, err
	}
	stored.MonthID = monthID
	stored.CreatedAt, stored.UpdatedAt = parseTime(created), parseTime(updated)
	if err := crypto.OpenJSON(crypto.Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag,
		KeyVersion: keyVersion}, s.DataKey, ownerID, "summary", &stored.Payload); err != nil {
		return StoredSummary{}, err
	}
	return stored, nil
}
