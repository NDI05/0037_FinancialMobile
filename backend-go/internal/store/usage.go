package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// JakartaBuckets returns stable hour/day keys (Asia/Jakarta is UTC+7, no DST).
func JakartaBuckets(now time.Time) (day, hour string) {
	shifted := now.UTC().Add(7 * time.Hour)
	return shifted.Format("2006-01-02"), shifted.Format("2006-01-02T15")
}

// TryReserveAnalysis atomically checks the per-owner hour/day limit and reserves one analysis.
// A read-then-increment outside a transaction would let concurrent requests exceed the limit.
func (s *Store) TryReserveAnalysis(ctx context.Context, ownerID string, perHour, perDay int, now time.Time) (string, error) {
	day, hour := JakartaBuckets(now)
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()
	count := func(bucket string) (int, error) {
		var value int
		err := tx.QueryRowContext(ctx, `SELECT analyses FROM usage_counters
			WHERE scope = ? AND bucket = ?`, ownerID, bucket).Scan(&value)
		if errors.Is(err, sql.ErrNoRows) {
			return 0, nil
		}
		return value, err
	}
	hourUsed, err := count("hour:" + hour)
	if err != nil {
		return "", err
	}
	dayUsed, err := count("day:" + day)
	if err != nil {
		return "", err
	}
	if hourUsed >= perHour || dayUsed >= perDay {
		return "rate_limited", nil
	}
	for _, bucket := range []string{"hour:" + hour, "day:" + day} {
		if _, err := tx.ExecContext(ctx, `INSERT INTO usage_counters
			(scope, bucket, analyses, tokens_prompt, tokens_completion, cost_usd)
			VALUES (?, ?, 1, 0, 0, 0)
			ON CONFLICT(scope, bucket) DO UPDATE SET analyses = usage_counters.analyses + 1`,
			ownerID, bucket); err != nil {
			return "", err
		}
	}
	if err := tx.Commit(); err != nil {
		return "", err
	}
	return "", nil
}

// RecordUsage adds token counts after a provider call; it never changes the analysis reservation.
func (s *Store) RecordUsage(ctx context.Context, ownerID string, promptTokens, completionTokens int64,
	costUSD float64, now time.Time) error {
	day, _ := JakartaBuckets(now)
	_, err := s.DB.ExecContext(ctx, `INSERT INTO usage_counters
		(scope, bucket, analyses, tokens_prompt, tokens_completion, cost_usd)
		VALUES (?, ?, 0, ?, ?, ?)
		ON CONFLICT(scope, bucket) DO UPDATE SET
		  tokens_prompt = usage_counters.tokens_prompt + excluded.tokens_prompt,
		  tokens_completion = usage_counters.tokens_completion + excluded.tokens_completion,
		  cost_usd = usage_counters.cost_usd + excluded.cost_usd`,
		ownerID, "day:"+day, promptTokens, completionTokens, costUSD)
	return err
}
