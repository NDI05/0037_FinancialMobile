package store

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/pam/recap/backend-go/internal/crypto"
)

type Observation struct {
	Text       string   `json:"text"`
	MetricKeys []string `json:"metricKeys"`
}

type Suggestion struct {
	Text                 string   `json:"text"`
	MetricKeys           []string `json:"metricKeys"`
	CategoryCode         string   `json:"categoryCode,omitempty"`
	SuggestedAmountMinor *int64   `json:"suggestedAmountMinor,omitempty"`
}

type AnalysisResponse struct {
	PromptVersion    string        `json:"promptVersion"`
	Observations     []Observation `json:"observations"`
	Suggestions      []Suggestion  `json:"suggestions"`
	Limitations      []string      `json:"limitations"`
	PromptTokens     *int64        `json:"promptTokens,omitempty"`
	CompletionTokens *int64        `json:"completionTokens,omitempty"`
}

type AnalysisRecord struct {
	ID                string
	MonthID           string
	InputRevision     int
	Provider          string
	Model             string
	PromptVersion     string
	Response          AnalysisResponse
	Status            string
	RegenerationCount int
	CreatedAt         time.Time
}

func (s *Store) FindAnalysisByCache(ctx context.Context, ownerID, monthID string, revision int,
	promptVersion, provider, model string) (AnalysisRecord, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, month_id, input_revision, provider, model,
		prompt_version, ciphertext, iv, tag, key_version, status, regeneration_count, created_at
		FROM analyses WHERE owner_id = ? AND month_id = ? AND input_revision = ?
		  AND prompt_version = ? AND provider = ? AND model = ?
		ORDER BY created_at DESC LIMIT 1`, ownerID, monthID, revision, promptVersion, provider, model)
	record, _, err := s.scanAnalysis(ctx, row, ownerID, false)
	return record, err
}

func (s *Store) FindAnalysisByIdempotency(ctx context.Context, ownerID, key string) (AnalysisRecord, string, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, month_id, input_revision, provider, model,
		prompt_version, ciphertext, iv, tag, key_version, status, regeneration_count, created_at,
		request_hash FROM analyses WHERE owner_id = ? AND idempotency_key = ?`, ownerID, key)
	return s.scanAnalysis(ctx, row, ownerID, true)
}

func (s *Store) LatestAnalysis(ctx context.Context, ownerID, monthID string) (AnalysisRecord, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, month_id, input_revision, provider, model,
		prompt_version, ciphertext, iv, tag, key_version, status, regeneration_count, created_at
		FROM analyses WHERE owner_id = ? AND month_id = ? ORDER BY created_at DESC LIMIT 1`,
		ownerID, monthID)
	record, _, err := s.scanAnalysis(ctx, row, ownerID, false)
	return record, err
}

func (s *Store) InsertAnalysis(ctx context.Context, ownerID, monthID string, inputRevision int,
	consentID, provider, model, promptVersion, idempotencyKey, requestHash string,
	response AnalysisResponse, status string, regenerationCount int, now time.Time) (AnalysisRecord, error) {
	sealed, err := crypto.SealJSON(response, s.DataKey, s.KeyVersion, ownerID, "analysis")
	if err != nil {
		return AnalysisRecord{}, err
	}
	id := NewID("analysis")
	_, err = s.DB.ExecContext(ctx, `INSERT INTO analyses
		(id, owner_id, month_id, summary_id, input_revision, consent_id, provider, model,
		 prompt_version, ciphertext, iv, tag, key_version, status, validation_issues,
		 regeneration_count, prompt_tokens, completion_tokens, idempotency_key, request_hash, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '[]', ?, ?, ?, ?, ?, ?)`,
		id, ownerID, monthID, "server:"+monthID, inputRevision, consentID, provider, model,
		promptVersion, sealed.Ciphertext, sealed.IV, sealed.Tag, s.KeyVersion, status,
		regenerationCount, nullableInt64(response.PromptTokens), nullableInt64(response.CompletionTokens),
		idempotencyKey, requestHash, nowString(now))
	if err != nil {
		if isUnique(err.Error()) {
			return AnalysisRecord{}, ErrConflict
		}
		return AnalysisRecord{}, err
	}
	record, _, err := s.FindAnalysisByIdempotency(ctx, ownerID, idempotencyKey)
	return record, err
}

func nullableInt64(value *int64) any {
	if value == nil {
		return nil
	}
	return *value
}

func (s *Store) scanAnalysis(ctx context.Context, row *sql.Row, ownerID string,
	withHash bool) (AnalysisRecord, string, error) {
	var record AnalysisRecord
	var ciphertext, iv, tag []byte
	var keyVersion int
	var created, hash string
	dest := []any{&record.ID, &record.MonthID, &record.InputRevision, &record.Provider,
		&record.Model, &record.PromptVersion, &ciphertext, &iv, &tag, &keyVersion,
		&record.Status, &record.RegenerationCount, &created}
	if withHash {
		dest = append(dest, &hash)
	}
	if err := row.Scan(dest...); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return AnalysisRecord{}, "", ErrNotFound
		}
		return AnalysisRecord{}, "", err
	}
	record.CreatedAt = parseTime(created)
	if err := crypto.OpenJSON(crypto.Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag,
		KeyVersion: keyVersion}, s.DataKey, ownerID, "analysis", &record.Response); err != nil {
		return AnalysisRecord{}, "", err
	}
	return record, hash, nil
}
