package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/pam/recap/backend-go/internal/crypto"
)

type TransactionCreateResult struct {
	Transaction Transaction
	Replayed    bool
}

// CreateTransactionIdempotent creates a manual transaction and records the HTTP idempotency result
// in the same transaction. A retry returns the original row, including a soft-deleted one; it never
// resurrects deleted data. Concurrent writers are serialized by the unique idempotency key.
func (s *Store) CreateTransactionIdempotent(ctx context.Context, ownerID, key, requestHash string,
	input TransactionInput, now time.Time) (TransactionCreateResult, error) {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return TransactionCreateResult{}, err
	}
	if input.AmountMinor <= 0 || input.AmountMinor > 1_000_000_000_000_000 {
		return TransactionCreateResult{}, fmt.Errorf("amountMinor must be >0 and <=1e15")
	}
	if input.Currency == "" {
		input.Currency = "IDR"
	}
	if input.Currency != "IDR" {
		return TransactionCreateResult{}, fmt.Errorf("only IDR is supported")
	}
	if input.Direction != "CREDIT" && input.Direction != "DEBIT" {
		return TransactionCreateResult{}, fmt.Errorf("direction must be CREDIT or DEBIT")
	}
	if input.EventAt.IsZero() {
		return TransactionCreateResult{}, fmt.Errorf("eventAt is required")
	}
	if input.CategoryID != "" {
		if err := s.ensureCategory(ctx, ownerID, input.CategoryID); err != nil {
			return TransactionCreateResult{}, err
		}
	}
	if input.Source == "" {
		input.Source = "MANUAL"
	}
	if input.Source != "MANUAL" {
		return TransactionCreateResult{}, fmt.Errorf("idempotent create is only for manual transactions")
	}
	if input.CategoryOrigin == "" {
		input.CategoryOrigin = "USER"
	}
	if err := validateAttributes(input.Attributes); err != nil {
		return TransactionCreateResult{}, err
	}
	fingerprintSum := sha256.Sum256([]byte("manual-idempotency|" + ownerID + "|" + key))
	fingerprint := hex.EncodeToString(fingerprintSum[:])[:32]
	sealed, err := crypto.SealJSON(TransactionSensitive{
		Description: input.Description, MerchantLabel: input.MerchantLabel,
		Note: input.Note, Attributes: input.Attributes,
	}, s.DataKey, s.KeyVersion, ownerID, "transaction")
	if err != nil {
		return TransactionCreateResult{}, err
	}

	for attempt := 0; attempt < 2; attempt++ {
		result, retry, err := s.tryCreateIdempotent(ctx, ownerID, key, requestHash, fingerprint,
			input, sealed, now)
		if err != nil {
			return TransactionCreateResult{}, err
		}
		if retry {
			continue
		}
		return result, nil
	}
	return TransactionCreateResult{}, ErrConflict
}

func (s *Store) tryCreateIdempotent(ctx context.Context, ownerID, key, requestHash, fingerprint string,
	input TransactionInput, sealed crypto.Sealed, now time.Time) (TransactionCreateResult, bool, error) {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return TransactionCreateResult{}, false, err
	}
	defer func() { _ = tx.Rollback() }()
	if err := ensureNoDeletionTx(ctx, tx, ownerID); err != nil {
		return TransactionCreateResult{}, false, err
	}

	var existingID, existingHash string
	err = tx.QueryRowContext(ctx, `SELECT result_id, request_hash FROM http_idempotency
		WHERE owner_id = ? AND operation = 'transaction.create' AND idem_key = ?`,
		ownerID, key).Scan(&existingID, &existingHash)
	if err == nil {
		if existingHash != requestHash {
			return TransactionCreateResult{}, false, ErrIdempotencyConflict
		}
		transaction, err := getTransactionIncludingDeleted(ctx, tx, s, ownerID, existingID)
		if err != nil {
			return TransactionCreateResult{}, false, err
		}
		return TransactionCreateResult{Transaction: transaction, Replayed: true}, false, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return TransactionCreateResult{}, false, err
	}

	id := NewID("tx")
	_, err = tx.ExecContext(ctx, `INSERT INTO transactions
		(id, owner_id, category_id, direction, source, amount_minor, currency, event_at_ms,
		 month_id, fingerprint, parser_version, parser_confidence, category_origin,
		 sensitive_ciphertext, sensitive_iv, sensitive_tag, key_version,
		 is_internal_transfer, linked_refund_of, revision, created_at, updated_at)
		VALUES (?, ?, ?, ?, 'MANUAL', ?, ?, ?, ?, ?, NULL, 0, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)`,
		id, ownerID, nullableString(input.CategoryID), input.Direction, input.AmountMinor,
		input.Currency, input.EventAt.UnixMilli(), monthIDFor(input.EventAt), fingerprint,
		input.CategoryOrigin, sealed.Ciphertext, sealed.IV, sealed.Tag, s.KeyVersion,
		boolInt(input.IsInternalTransfer), nullableString(input.LinkedRefundOf),
		nowString(now), nowString(now))
	if err != nil {
		if isUnique(err.Error()) {
			// Another writer may have committed the same key between our SELECT and INSERT.
			return TransactionCreateResult{}, true, nil
		}
		return TransactionCreateResult{}, false, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO http_idempotency
		(id, owner_id, operation, idem_key, request_hash, result_id, created_at)
		VALUES (?, ?, 'transaction.create', ?, ?, ?, ?)`,
		NewID("idem"), ownerID, key, requestHash, id, nowString(now)); err != nil {
		if isUnique(err.Error()) {
			return TransactionCreateResult{}, true, nil
		}
		return TransactionCreateResult{}, false, err
	}
	if err := bumpMonthRevision(ctx, tx, ownerID, monthIDFor(input.EventAt), now); err != nil {
		return TransactionCreateResult{}, false, err
	}
	if err := tx.Commit(); err != nil {
		return TransactionCreateResult{}, false, err
	}
	transaction, err := s.getTransactionIncludingDeleted(ctx, ownerID, id)
	if err != nil {
		return TransactionCreateResult{}, false, err
	}
	return TransactionCreateResult{Transaction: transaction, Replayed: false}, false, nil
}

func (s *Store) getTransactionIncludingDeleted(ctx context.Context, ownerID, id string) (Transaction, error) {
	row := s.DB.QueryRowContext(ctx, transactionSelect+` WHERE t.owner_id = ? AND t.id = ?`, ownerID, id)
	return scanTransactionWithDeleted(ctx, row, s, ownerID)
}

func getTransactionIncludingDeleted(ctx context.Context, tx *sql.Tx, s *Store, ownerID, id string) (Transaction, error) {
	row := tx.QueryRowContext(ctx, transactionSelect+` WHERE t.owner_id = ? AND t.id = ?`, ownerID, id)
	return scanTransactionWithDeleted(ctx, row, s, ownerID)
}
