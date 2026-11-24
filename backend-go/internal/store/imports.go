package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/crypto"
)

type ImportJob struct {
	ID              string
	MonthID         string
	State           string
	TemplateVersion string
	PagesProcessed  int
	Matched         int
	Imported        int
	Duplicates      int
	Skipped         int
	Unresolved      int
	StartedAt       time.Time
	FinishedAt      *time.Time
	ErrorCode       string
}

type ImportCandidate struct {
	Fingerprint string
	// SourceRef is the opaque device reference `UIDVALIDITY:UID` of the notification. It is
	// optional for older clients, sealed at rest, and is the repair identity for this row.
	SourceRef     string
	Status        string
	Reason        string
	Direction     string
	AmountMinor   int64
	EventAt       time.Time
	CategoryID    string
	ParserVersion string
	Confidence    float64
	Description   string
	MerchantLabel string
	Attributes    map[string]any
}

type ImportBatchCounts struct {
	Matched    int `json:"matched"`
	Imported   int `json:"imported"`
	Duplicates int `json:"duplicates"`
	Skipped    int `json:"skipped"`
	Unresolved int `json:"unresolved"`
}

func (s *Store) CreateImportJob(ctx context.Context, ownerID, monthID, templateVersion string, now time.Time) (ImportJob, error) {
	if _, _, err := ParseMonth(monthID); err != nil {
		return ImportJob{}, err
	}
	if templateVersion == "" {
		return ImportJob{}, fmt.Errorf("templateVersion is required")
	}
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return ImportJob{}, err
	}
	id := NewID("job")
	_, err := s.DB.ExecContext(ctx, `INSERT INTO import_jobs
		(id, owner_id, month_id, state, template_version, started_at)
		VALUES (?, ?, ?, 'CREATED', ?, ?)`,
		id, ownerID, monthID, templateVersion, nowString(now))
	if err != nil {
		return ImportJob{}, err
	}
	return s.GetImportJob(ctx, ownerID, id)
}

func (s *Store) GetImportJob(ctx context.Context, ownerID, id string) (ImportJob, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, month_id, state, template_version, pages_processed,
		matched_count, imported_count, duplicate_count, skipped_count, unresolved_count,
		started_at, finished_at, COALESCE(error_code, '')
		FROM import_jobs WHERE owner_id = ? AND id = ?`, ownerID, id)
	return scanImportJob(row)
}

func (s *Store) ImportBatch(ctx context.Context, ownerID, jobID, batchKey, requestHash string,
	pageOrdinal int, candidates []ImportCandidate, now time.Time) (ImportBatchCounts, bool, error) {
	if batchKey == "" || len(batchKey) > 200 {
		return ImportBatchCounts{}, false, fmt.Errorf("batchKey is required")
	}
	if pageOrdinal < 0 || len(candidates) > 100 {
		return ImportBatchCounts{}, false, fmt.Errorf("batch size is invalid")
	}
	if len(candidates) == 0 {
		return ImportBatchCounts{}, false, fmt.Errorf("candidates are empty")
	}
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return ImportBatchCounts{}, false, err
	}
	// Resolve the job month before validation so an event outside the job month rejects the whole
	// page. The job is re-read inside the write transaction after validation.
	var jobMonth, initialJobState string
	if err := s.DB.QueryRowContext(ctx, `SELECT month_id, state FROM import_jobs
		WHERE owner_id = ? AND id = ?`, ownerID, jobID).Scan(&jobMonth, &initialJobState); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ImportBatchCounts{}, false, ErrNotFound
		}
		return ImportBatchCounts{}, false, err
	}
	// Validate everything before opening a write transaction: an invalid candidate rejects the
	// entire batch instead of leaving a half-imported page.
	for _, candidate := range candidates {
		if err := validateImportCandidate(candidate); err != nil {
			return ImportBatchCounts{}, false, err
		}
		if err := validateAttributes(candidate.Attributes); err != nil {
			return ImportBatchCounts{}, false, err
		}
		if candidate.Status == "PARSED" && monthIDFor(candidate.EventAt) != jobMonth {
			return ImportBatchCounts{}, false, fmt.Errorf("invalid candidate eventAt: outside the import month %s", jobMonth)
		}
		if candidate.SourceRef != "" && !validSourceRef(candidate.SourceRef) {
			return ImportBatchCounts{}, false, fmt.Errorf("sourceRef must look like UIDVALIDITY:UID")
		}
		if candidate.CategoryID != "" {
			if err := s.ensureCategory(ctx, ownerID, candidate.CategoryID); err != nil {
				return ImportBatchCounts{}, false, err
			}
		}
	}

	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return ImportBatchCounts{}, false, err
	}
	defer func() { _ = tx.Rollback() }()
	if err := ensureNoDeletionTx(ctx, tx, ownerID); err != nil {
		return ImportBatchCounts{}, false, err
	}
	var monthID, state string
	if err := tx.QueryRowContext(ctx, `SELECT month_id, state FROM import_jobs
		WHERE owner_id = ? AND id = ?`, ownerID, jobID).Scan(&monthID, &state); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ImportBatchCounts{}, false, ErrNotFound
		}
		return ImportBatchCounts{}, false, err
	}
	var existingHash string
	var existingCounts string
	err = tx.QueryRowContext(ctx, `SELECT request_hash, counts_json FROM import_batches
		WHERE owner_id = ? AND job_id = ? AND batch_key = ?`, ownerID, jobID, batchKey).Scan(
		&existingHash, &existingCounts)
	if err == nil {
		if existingHash != requestHash {
			return ImportBatchCounts{}, false, ErrIdempotencyConflict
		}
		var counts ImportBatchCounts
		_ = json.Unmarshal([]byte(existingCounts), &counts)
		return counts, true, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return ImportBatchCounts{}, false, err
	}
	if state != "CREATED" && state != "RUNNING" {
		return ImportBatchCounts{}, false, fmt.Errorf("import job is already finished")
	}

	counts := ImportBatchCounts{Matched: len(candidates)}
	for _, candidate := range candidates {
		if candidate.Status == "UNRESOLVED" {
			if _, err := tx.ExecContext(ctx, `INSERT INTO import_skipped
				(id, owner_id, job_id, reason_code, page_ordinal, candidate_ordinal, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)`,
				NewID("skip"), ownerID, jobID, candidate.Reason, pageOrdinal,
				counts.Skipped, nowString(now)); err != nil {
				return ImportBatchCounts{}, false, err
			}
			counts.Skipped++
			continue
		}
		sealed, err := crypto.SealJSON(TransactionSensitive{
			Description: candidate.Description, MerchantLabel: candidate.MerchantLabel,
			Attributes: candidate.Attributes,
		}, s.DataKey, s.KeyVersion, ownerID, "transaction")
		if err != nil {
			return ImportBatchCounts{}, false, err
		}
		var refCipher, refIV, refTag any
		var refVersion any
		var refHash any
		if candidate.SourceRef != "" {
			sealedRef, err := crypto.Seal([]byte(candidate.SourceRef), s.DataKey, s.KeyVersion,
				ownerID, "source_ref")
			if err != nil {
				return ImportBatchCounts{}, false, err
			}
			refCipher, refIV, refTag = sealedRef.Ciphertext, sealedRef.IV, sealedRef.Tag
			refVersion = s.KeyVersion
			refHash = s.sourceRefHash(candidate.SourceRef)
		}
		// Two independent identity keys: the device fingerprint and the sealed source reference.
		// A reparsed notification changes the former (its merchant fields changed), so the latter
		// is what makes a post-repair re-import a duplicate instead of a new row.
		result, err := tx.ExecContext(ctx, `INSERT INTO transactions
			(id, owner_id, category_id, direction, source, amount_minor, currency, event_at_ms,
			 month_id, fingerprint, parser_version, parser_confidence, category_origin,
			 sensitive_ciphertext, sensitive_iv, sensitive_tag, key_version,
			 source_ref_ciphertext, source_ref_iv, source_ref_tag, source_ref_key_version,
			 source_ref_hash, is_internal_transfer, linked_refund_of, revision, created_at, updated_at)
			VALUES (?, ?, ?, ?, 'EMAIL', ?, 'IDR', ?, ?, ?, ?, ?, 'IMPORTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, 0, ?, ?)
			ON CONFLICT DO NOTHING`,
			NewID("tx"), ownerID, nullableString(candidate.CategoryID), candidate.Direction,
			candidate.AmountMinor, candidate.EventAt.UnixMilli(), monthID, candidate.Fingerprint,
			candidate.ParserVersion, candidate.Confidence, sealed.Ciphertext, sealed.IV, sealed.Tag,
			s.KeyVersion, refCipher, refIV, refTag, refVersion, refHash,
			nowString(now), nowString(now))
		if err != nil {
			return ImportBatchCounts{}, false, err
		}
		affected, _ := result.RowsAffected()
		if affected == 1 {
			counts.Imported++
		} else {
			counts.Duplicates++
		}
	}
	countsJSON, _ := json.Marshal(counts)
	if _, err := tx.ExecContext(ctx, `INSERT INTO import_batches
		(id, owner_id, job_id, batch_key, request_hash, counts_json, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		NewID("batch"), ownerID, jobID, batchKey, requestHash, string(countsJSON), nowString(now)); err != nil {
		if isUnique(err.Error()) {
			// Concurrent writer won; roll back our attempted rows and return its stored counts.
			_ = tx.Rollback()
			var hash, stored string
			lookup := s.DB.QueryRowContext(ctx, `SELECT request_hash, counts_json FROM import_batches
				WHERE owner_id = ? AND job_id = ? AND batch_key = ?`, ownerID, jobID, batchKey)
			if lookupErr := lookup.Scan(&hash, &stored); lookupErr != nil {
				return ImportBatchCounts{}, false, lookupErr
			}
			if hash != requestHash {
				return ImportBatchCounts{}, false, ErrIdempotencyConflict
			}
			var existing ImportBatchCounts
			_ = json.Unmarshal([]byte(stored), &existing)
			return existing, true, nil
		}
		return ImportBatchCounts{}, false, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE import_jobs SET
		state = 'RUNNING',
		pages_processed = MAX(pages_processed, ?),
		matched_count = matched_count + ?,
		imported_count = imported_count + ?,
		duplicate_count = duplicate_count + ?,
		skipped_count = skipped_count + ?,
		unresolved_count = unresolved_count + ?
		WHERE owner_id = ? AND id = ?`,
		pageOrdinal+1, counts.Matched, counts.Imported, counts.Duplicates, counts.Skipped,
		counts.Unresolved, ownerID, jobID); err != nil {
		return ImportBatchCounts{}, false, err
	}
	if counts.Imported > 0 {
		if err := bumpMonthRevision(ctx, tx, ownerID, monthID, now); err != nil {
			return ImportBatchCounts{}, false, err
		}
	}
	if err := tx.Commit(); err != nil {
		return ImportBatchCounts{}, false, err
	}
	return counts, false, nil
}

func (s *Store) FinalizeImportJob(ctx context.Context, ownerID, jobID, finalState, errorCode string, now time.Time) (ImportJob, error) {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return ImportJob{}, err
	}
	switch finalState {
	case "COMPLETE", "PARTIAL", "FAILED", "EMPTY", "PERMISSION_REQUIRED":
	default:
		return ImportJob{}, fmt.Errorf("invalid final state")
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return ImportJob{}, err
	}
	defer func() { _ = tx.Rollback() }()
	if err := ensureNoDeletionTx(ctx, tx, ownerID); err != nil {
		return ImportJob{}, err
	}
	var state string
	var pages, matched, imported, duplicates, skipped int
	if err := tx.QueryRowContext(ctx, `SELECT state, pages_processed, matched_count, imported_count,
		duplicate_count, skipped_count FROM import_jobs WHERE owner_id = ? AND id = ?`,
		ownerID, jobID).Scan(&state, &pages, &matched, &imported, &duplicates, &skipped); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ImportJob{}, ErrNotFound
		}
		return ImportJob{}, err
	}
	if state == "COMPLETE" || state == "PARTIAL" || state == "FAILED" || state == "EMPTY" || state == "PERMISSION_REQUIRED" {
		if state == finalState {
			// A retry after a lost finalization response is recoverable and must not change counts.
			_ = tx.Rollback()
			return s.GetImportJob(ctx, ownerID, jobID)
		}
		return ImportJob{}, fmt.Errorf("import job is already finished with state %s", state)
	}
	if finalState == "COMPLETE" && (pages == 0 || imported+duplicates+skipped != matched) {
		return ImportJob{}, fmt.Errorf("COMPLETE does not match the recorded counts")
	}
	if finalState == "EMPTY" && (matched != 0 || imported != 0) {
		return ImportJob{}, fmt.Errorf("EMPTY cannot hide imported rows")
	}
	if finalState == "COMPLETE" && matched == 0 {
		return ImportJob{}, fmt.Errorf("a job with no matching rows must be EMPTY")
	}
	if _, err := tx.ExecContext(ctx, `UPDATE import_jobs SET state = ?, finished_at = ?,
		error_code = ? WHERE owner_id = ? AND id = ?`,
		finalState, nowString(now), nullString(errorCode), ownerID, jobID); err != nil {
		return ImportJob{}, err
	}
	if err := tx.Commit(); err != nil {
		return ImportJob{}, err
	}
	return s.GetImportJob(ctx, ownerID, jobID)
}

func validateImportCandidate(candidate ImportCandidate) error {
	if len(candidate.Fingerprint) != 32 || strings.Trim(candidate.Fingerprint, "0123456789abcdef") != "" {
		return fmt.Errorf("candidate fingerprint must be 32 lowercase hex characters")
	}
	switch candidate.Status {
	case "UNRESOLVED":
		if candidate.Reason == "" || len(candidate.Reason) > 64 {
			return fmt.Errorf("unresolved candidate requires a reason code")
		}
		return nil
	case "PARSED":
	default:
		return fmt.Errorf("candidate status must be PARSED or UNRESOLVED")
	}
	if candidate.Direction != "CREDIT" && candidate.Direction != "DEBIT" {
		return fmt.Errorf("candidate direction is invalid")
	}
	if candidate.AmountMinor <= 0 || candidate.AmountMinor > 1_000_000_000_000_000 {
		return fmt.Errorf("candidate amountMinor is invalid")
	}
	if candidate.EventAt.IsZero() {
		return fmt.Errorf("candidate eventAt is required")
	}
	if candidate.ParserVersion == "" || len(candidate.ParserVersion) > 64 {
		return fmt.Errorf("candidate parserVersion is invalid")
	}
	return nil
}

func scanImportJob(row *sql.Row) (ImportJob, error) {
	var job ImportJob
	var started string
	var finished sql.NullString
	var errorCode string
	if err := row.Scan(&job.ID, &job.MonthID, &job.State, &job.TemplateVersion,
		&job.PagesProcessed, &job.Matched, &job.Imported, &job.Duplicates, &job.Skipped,
		&job.Unresolved, &started, &finished, &errorCode); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ImportJob{}, ErrNotFound
		}
		return ImportJob{}, err
	}
	job.StartedAt = parseTime(started)
	job.ErrorCode = errorCode
	if finished.Valid {
		at := parseTime(finished.String)
		job.FinishedAt = &at
	}
	return job, nil
}

// HashImportBatch canonicalizes candidate data for the batch replay check.
func HashImportBatch(monthID, batchKey string, pageOrdinal int, candidates []ImportCandidate) string {
	raw, _ := json.Marshal(struct {
		MonthID     string            `json:"monthId"`
		BatchKey    string            `json:"batchKey"`
		PageOrdinal int               `json:"pageOrdinal"`
		Candidates  []ImportCandidate `json:"candidates"`
	}{monthID, batchKey, pageOrdinal, candidates})
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}
