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

func usageValue(ctx context.Context, tx *sql.Tx, scope, bucket string) (int, float64, error) {
	var calls int
	var cost float64
	err := tx.QueryRowContext(ctx, `SELECT analyses, cost_usd FROM usage_counters
		WHERE scope = ? AND bucket = ?`, scope, bucket).Scan(&calls, &cost)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, 0, nil
	}
	return calls, cost, err
}

// TryReserveAnalysis atomically checks the per-owner hour/day analysis limit. Provider cost is
// reserved per call by ReserveProviderCost so a regeneration or invalid output is still billed.
func (s *Store) TryReserveAnalysis(ctx context.Context, ownerID string, perHour, perDay int, now time.Time) (string, error) {
	day, hour := JakartaBuckets(now)
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()
	hourUsed, _, err := usageValue(ctx, tx, ownerID, "hour:"+hour)
	if err != nil {
		return "", err
	}
	dayUsed, _, err := usageValue(ctx, tx, ownerID, "day:"+day)
	if err != nil {
		return "", err
	}
	if hourUsed >= perHour || dayUsed >= perDay {
		return "rate_limited", nil
	}
	for _, bucket := range []string{"hour:" + hour, "day:" + day} {
		if _, err := tx.ExecContext(ctx, `INSERT INTO usage_counters
			(scope, bucket, analyses, tokens_prompt, tokens_completion, cost_usd, provider_calls, usage_unknown)
			VALUES (?, ?, 1, 0, 0, 0, 0, 0)
			ON CONFLICT(scope, bucket) DO UPDATE SET analyses = usage_counters.analyses + 1`,
			ownerID, bucket); err != nil {
			return "", err
		}
	}
	return "", tx.Commit()
}

// ReserveProviderCost checks the global daily USD ceiling and places a conservative reservation for
// one provider call. It is safe against concurrent writers.
func (s *Store) ReserveProviderCost(ctx context.Context, ceilingUSD, reservationUSD float64, now time.Time) (bool, error) {
	if reservationUSD <= 0 || ceilingUSD <= 0 {
		return false, nil
	}
	day, _ := JakartaBuckets(now)
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer func() { _ = tx.Rollback() }()
	_, cost, err := usageValue(ctx, tx, "*", "day:"+day)
	if err != nil {
		return false, err
	}
	// Check spent + outstanding reservations + this reservation. A reservation that cannot fit
	// the remaining budget is rejected before any provider call.
	if cost+reservationUSD > ceilingUSD+1e-12 {
		return false, nil
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO usage_counters
		(scope, bucket, analyses, tokens_prompt, tokens_completion, cost_usd, provider_calls, usage_unknown)
		VALUES ('*', ?, 0, 0, 0, ?, 0, 0)
		ON CONFLICT(scope, bucket) DO UPDATE SET cost_usd = usage_counters.cost_usd + excluded.cost_usd`,
		"day:"+day, reservationUSD); err != nil {
		return false, err
	}
	return true, tx.Commit()
}

// RecordUsage records one billed provider call. It removes the conservative reservation and adds
// the actual cost when the provider supplied usage; unknown usage keeps the conservative cost and
// increments the unknown counter rather than pretending it cost zero.
func (s *Store) RecordUsage(ctx context.Context, ownerID string, promptTokens, completionTokens int64,
	actualCost, reservedCost float64, usageKnown bool, now time.Time) error {
	day, _ := JakartaBuckets(now)
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	delta := actualCost - reservedCost
	if _, err := tx.ExecContext(ctx, `INSERT INTO usage_counters
		(scope, bucket, analyses, tokens_prompt, tokens_completion, cost_usd, provider_calls, usage_unknown)
		VALUES ('*', ?, 0, ?, ?, ?, 1, ?)
		ON CONFLICT(scope, bucket) DO UPDATE SET
		  tokens_prompt = usage_counters.tokens_prompt + excluded.tokens_prompt,
		  tokens_completion = usage_counters.tokens_completion + excluded.tokens_completion,
		  cost_usd = MAX(0, usage_counters.cost_usd + ?),
		  provider_calls = usage_counters.provider_calls + 1,
		  usage_unknown = usage_counters.usage_unknown + excluded.usage_unknown`,
		"day:"+day, promptTokens, completionTokens, actualCost, boolInt(!usageKnown), delta); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO usage_counters
		(scope, bucket, analyses, tokens_prompt, tokens_completion, cost_usd, provider_calls, usage_unknown)
		VALUES (?, ?, 0, ?, ?, ?, 1, ?)
		ON CONFLICT(scope, bucket) DO UPDATE SET
		  tokens_prompt = usage_counters.tokens_prompt + excluded.tokens_prompt,
		  tokens_completion = usage_counters.tokens_completion + excluded.tokens_completion,
		  cost_usd = MAX(0, usage_counters.cost_usd + ?),
		  provider_calls = usage_counters.provider_calls + 1,
		  usage_unknown = usage_counters.usage_unknown + excluded.usage_unknown`,
		ownerID, "day:"+day, promptTokens, completionTokens, actualCost, boolInt(!usageKnown), actualCost); err != nil {
		return err
	}
	return tx.Commit()
}

// UsageSnapshot is used by tests and diagnostics; it never exposes prompt/email content.
type UsageSnapshot struct {
	Analyses         int
	PromptTokens     int64
	CompletionTokens int64
	CostUSD          float64
	ProviderCalls    int
	UsageUnknown     int
}

func (s *Store) UsageSnapshot(ctx context.Context, scope, bucket string) (UsageSnapshot, error) {
	var snapshot UsageSnapshot
	err := s.DB.QueryRowContext(ctx, `SELECT analyses, tokens_prompt, tokens_completion, cost_usd,
		provider_calls, usage_unknown FROM usage_counters WHERE scope = ? AND bucket = ?`,
		scope, bucket).Scan(&snapshot.Analyses, &snapshot.PromptTokens,
		&snapshot.CompletionTokens, &snapshot.CostUSD, &snapshot.ProviderCalls, &snapshot.UsageUnknown)
	if errors.Is(err, sql.ErrNoRows) {
		return UsageSnapshot{}, nil
	}
	return snapshot, err
}
