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
	DependencyHash    string
}

func (s *Store) FindAnalysisByCache(ctx context.Context, ownerID, monthID string, revision int,
	promptVersion, provider, model, snapshotHash, consentID, dependencyHash string) (AnalysisRecord, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, month_id, input_revision, provider, model,
		prompt_version, ciphertext, iv, tag, key_version, status, regeneration_count, created_at,
		dependency_hash
		FROM analyses WHERE owner_id = ? AND month_id = ? AND input_revision = ?
		  AND prompt_version = ? AND provider = ? AND model = ?
		  AND snapshot_hash = ? AND consent_id = ? AND dependency_hash = ? AND status = 'AVAILABLE'
		ORDER BY created_at DESC LIMIT 1`, ownerID, monthID, revision, promptVersion, provider, model,
		snapshotHash, consentID, dependencyHash)
	record, _, err := s.scanAnalysis(ctx, row, ownerID, false)
	return record, err
}

func (s *Store) FindAnalysisByIdempotency(ctx context.Context, ownerID, key string) (AnalysisRecord, string, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, month_id, input_revision, provider, model,
		prompt_version, ciphertext, iv, tag, key_version, status, regeneration_count, created_at,
		dependency_hash, request_hash FROM analyses WHERE owner_id = ? AND idempotency_key = ?`, ownerID, key)
	return s.scanAnalysis(ctx, row, ownerID, true)
}

func (s *Store) LatestAnalysis(ctx context.Context, ownerID, monthID string) (AnalysisRecord, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, month_id, input_revision, provider, model,
		prompt_version, ciphertext, iv, tag, key_version, status, regeneration_count, created_at,
		dependency_hash
		FROM analyses WHERE owner_id = ? AND month_id = ? ORDER BY created_at DESC LIMIT 1`,
		ownerID, monthID)
	record, _, err := s.scanAnalysis(ctx, row, ownerID, false)
	return record, err
}

type AnalysisFinalizeInput struct {
	OwnerID           string
	SessionID         string
	MonthID           string
	InputRevision     int
	SnapshotHash      string
	DependencyHash    string
	ConsentID         string
	Provider          string
	Model             string
	PromptVersion     string
	IdempotencyKey    string
	RequestHash       string
	Response          AnalysisResponse
	RegenerationCount int
	Now               time.Time
}

// InsertAnalysisGuarded finalizes an analysis inside one write transaction. The consent, deletion,
// session and revision checks are evaluated in that transaction, after the provider call and before
// the row is visible. A concurrent withdrawal/deletion/revision change therefore wins cleanly.
func (s *Store) InsertAnalysisGuarded(ctx context.Context, input AnalysisFinalizeInput) (AnalysisRecord, error) {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return AnalysisRecord{}, err
	}
	defer func() { _ = tx.Rollback() }()

	// Session + account state.
	var revoked sql.NullString
	var sessionExpires, status string
	var authVersion int
	if err := tx.QueryRowContext(ctx, `SELECT s.revoked_at, s.expires_at, u.status, u.auth_version
		FROM auth_sessions s JOIN users u ON u.owner_id = s.owner_id
		WHERE s.id = ? AND s.owner_id = ?`, input.SessionID, input.OwnerID).Scan(
		&revoked, &sessionExpires, &status, &authVersion); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return AnalysisRecord{}, ErrSessionStale
		}
		return AnalysisRecord{}, err
	}
	if revoked.Valid || status != "ACTIVE" || parseTime(sessionExpires).Before(input.Now) {
		return AnalysisRecord{}, ErrSessionStale
	}
	// The consent row that authorised this request must still be the latest decision and granted.
	var latestID, latestDecision string
	if err := tx.QueryRowContext(ctx, `SELECT id, decision FROM consents
		WHERE owner_id = ? AND purpose = 'AI_PROCESSING'
		ORDER BY created_at DESC, rowid DESC LIMIT 1`, input.OwnerID).Scan(&latestID, &latestDecision); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return AnalysisRecord{}, ErrConsentChanged
		}
		return AnalysisRecord{}, err
	}
	if latestID != input.ConsentID || latestDecision != "GRANTED" {
		return AnalysisRecord{}, ErrConsentChanged
	}
	var pending int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM deletion_requests
		WHERE owner_id = ? AND state = 'PENDING'`, input.OwnerID).Scan(&pending); err != nil {
		return AnalysisRecord{}, err
	}
	if pending > 0 {
		return AnalysisRecord{}, ErrDeletionInProgress
	}
	dependencies, err := dependenciesFor(ctx, tx, input.OwnerID, input.MonthID)
	if err != nil {
		return AnalysisRecord{}, err
	}
	if dependencies.TargetRevision != input.InputRevision || dependencies.Hash != input.DependencyHash {
		return AnalysisRecord{}, ErrRevisionConflict
	}
	// A concurrent request with the same idempotency key may have inserted first.
	var existingID, existingHash string
	if err := tx.QueryRowContext(ctx, `SELECT id, request_hash FROM analyses
		WHERE owner_id = ? AND idempotency_key = ?`, input.OwnerID, input.IdempotencyKey).Scan(
		&existingID, &existingHash); err == nil {
		if existingHash != input.RequestHash {
			return AnalysisRecord{}, ErrIdempotencyConflict
		}
		record, _, err := s.scanAnalysis(ctx, tx.QueryRowContext(ctx, `SELECT id, month_id,
			input_revision, provider, model, prompt_version, ciphertext, iv, tag, key_version,
			status, regeneration_count, created_at, dependency_hash FROM analyses WHERE id = ?`, existingID), input.OwnerID, false)
		if err != nil {
			return AnalysisRecord{}, err
		}
		return record, tx.Commit()
	} else if !errors.Is(err, sql.ErrNoRows) {
		return AnalysisRecord{}, err
	}

	sealed, err := crypto.SealJSON(input.Response, s.DataKey, s.KeyVersion, input.OwnerID, "analysis")
	if err != nil {
		return AnalysisRecord{}, err
	}
	id := NewID("analysis")
	_, err = tx.ExecContext(ctx, `INSERT INTO analyses
		(id, owner_id, month_id, summary_id, input_revision, consent_id, provider, model,
		 prompt_version, ciphertext, iv, tag, key_version, status, validation_issues,
		 regeneration_count, prompt_tokens, completion_tokens, idempotency_key, request_hash,
		 snapshot_hash, dependency_hash, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', '[]', ?, ?, ?, ?, ?, ?, ?, ?)`,
		id, input.OwnerID, input.MonthID, "server:"+input.MonthID, input.InputRevision,
		input.ConsentID, input.Provider, input.Model, input.PromptVersion, sealed.Ciphertext,
		sealed.IV, sealed.Tag, s.KeyVersion, input.RegenerationCount,
		nullableInt64(input.Response.PromptTokens), nullableInt64(input.Response.CompletionTokens),
		input.IdempotencyKey, input.RequestHash, input.SnapshotHash, input.DependencyHash, nowString(input.Now))
	if err != nil {
		if isUnique(err.Error()) {
			// Another writer committed the same idempotency key after our pre-check.
			_ = tx.Rollback()
			record, hash, lookupErr := s.FindAnalysisByIdempotency(ctx, input.OwnerID, input.IdempotencyKey)
			if lookupErr != nil {
				return AnalysisRecord{}, lookupErr
			}
			if hash != input.RequestHash {
				return AnalysisRecord{}, ErrIdempotencyConflict
			}
			return record, nil
		}
		return AnalysisRecord{}, err
	}
	record, _, err := s.scanAnalysis(ctx, tx.QueryRowContext(ctx, `SELECT id, month_id,
		input_revision, provider, model, prompt_version, ciphertext, iv, tag, key_version,
		status, regeneration_count, created_at, dependency_hash FROM analyses WHERE id = ?`, id), input.OwnerID, false)
	if err != nil {
		return AnalysisRecord{}, err
	}
	if err := tx.Commit(); err != nil {
		return AnalysisRecord{}, err
	}
	return record, nil
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
		&record.Status, &record.RegenerationCount, &created, &record.DependencyHash}
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
