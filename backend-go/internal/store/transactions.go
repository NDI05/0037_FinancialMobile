package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/crypto"
)

type TransactionSensitive struct {
	Description   string         `json:"description,omitempty"`
	MerchantLabel string         `json:"merchantLabel,omitempty"`
	Note          string         `json:"note,omitempty"`
	Attributes    map[string]any `json:"attributes,omitempty"`
}

type Transaction struct {
	ID                 string
	OwnerID            string
	CategoryID         string
	CategoryCode       string
	CategoryKind       string
	Direction          string
	Source             string
	AmountMinor        int64
	Currency           string
	EventAt            time.Time
	MonthID            string
	Fingerprint        string
	ParserVersion      string
	ParserConfidence   float64
	CategoryOrigin     string
	Sensitive          TransactionSensitive
	IsInternalTransfer bool
	LinkedRefundOf     string
	Revision           int
	CreatedAt          time.Time
	UpdatedAt          time.Time
	DeletedAt          *time.Time
}

type TransactionInput struct {
	CategoryID         string
	Direction          string
	Source             string
	AmountMinor        int64
	Currency           string
	EventAt            time.Time
	Fingerprint        string
	ParserVersion      string
	ParserConfidence   float64
	CategoryOrigin     string
	Description        string
	MerchantLabel      string
	Note               string
	Attributes         map[string]any
	IsInternalTransfer bool
	LinkedRefundOf     string
}

type TransactionPatch struct {
	CategoryID         *string
	Description        *string
	MerchantLabel      *string
	Note               *string
	Attributes         *map[string]any
	IsInternalTransfer *bool
	LinkedRefundOf     *string
	Revision           int
}

func (s *Store) CreateTransaction(ctx context.Context, ownerID string, input TransactionInput, now time.Time) (Transaction, error) {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return Transaction{}, err
	}
	if input.AmountMinor <= 0 || input.AmountMinor > 1_000_000_000_000_000 {
		return Transaction{}, fmt.Errorf("amountMinor must be >0 and <=1e15")
	}
	if input.Currency == "" {
		input.Currency = "IDR"
	}
	if input.Currency != "IDR" {
		return Transaction{}, fmt.Errorf("only IDR is supported")
	}
	if input.Direction != "CREDIT" && input.Direction != "DEBIT" {
		return Transaction{}, fmt.Errorf("direction must be CREDIT or DEBIT")
	}
	if input.Source == "" {
		input.Source = "MANUAL"
	}
	if input.Source != "MANUAL" && input.Source != "EMAIL" {
		return Transaction{}, fmt.Errorf("invalid source")
	}
	if input.CategoryOrigin == "" {
		input.CategoryOrigin = "USER"
	}
	if input.EventAt.IsZero() {
		return Transaction{}, fmt.Errorf("eventAt is required")
	}
	id := NewID("tx")
	fingerprint := strings.ToLower(strings.TrimSpace(input.Fingerprint))
	if fingerprint == "" {
		sum := sha256.Sum256([]byte(fmt.Sprintf("%s|%s|%d|%d", ownerID, id, input.AmountMinor, input.EventAt.UnixMilli())))
		fingerprint = hex.EncodeToString(sum[:])[:32]
	}
	if len(fingerprint) != 32 {
		return Transaction{}, fmt.Errorf("fingerprint must be 32 hex characters")
	}
	if input.CategoryID != "" {
		if err := s.ensureCategory(ctx, ownerID, input.CategoryID); err != nil {
			return Transaction{}, err
		}
	}
	if err := validateAttributes(input.Attributes); err != nil {
		return Transaction{}, err
	}
	sealed, err := crypto.SealJSON(TransactionSensitive{
		Description: input.Description, MerchantLabel: input.MerchantLabel,
		Note: input.Note, Attributes: input.Attributes,
	}, s.DataKey, s.KeyVersion, ownerID, "transaction")
	if err != nil {
		return Transaction{}, err
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return Transaction{}, err
	}
	defer func() { _ = tx.Rollback() }()
	if err := ensureNoDeletionTx(ctx, tx, ownerID); err != nil {
		return Transaction{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO transactions
		(id, owner_id, category_id, direction, source, amount_minor, currency, event_at_ms,
		 month_id, fingerprint, parser_version, parser_confidence, category_origin,
		 sensitive_ciphertext, sensitive_iv, sensitive_tag, key_version,
		 is_internal_transfer, linked_refund_of, revision, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)`,
		id, ownerID, nullableString(input.CategoryID), input.Direction, input.Source,
		input.AmountMinor, input.Currency, input.EventAt.UnixMilli(), monthIDFor(input.EventAt),
		fingerprint, nullString(input.ParserVersion), input.ParserConfidence, input.CategoryOrigin,
		sealed.Ciphertext, sealed.IV, sealed.Tag, s.KeyVersion, boolInt(input.IsInternalTransfer),
		nullableString(input.LinkedRefundOf), nowString(now), nowString(now))
	if err != nil {
		if isUnique(err.Error()) {
			return Transaction{}, ErrConflict
		}
		return Transaction{}, err
	}
	if err := bumpMonthRevision(ctx, tx, ownerID, monthIDFor(input.EventAt), now); err != nil {
		return Transaction{}, err
	}
	if err := tx.Commit(); err != nil {
		return Transaction{}, err
	}
	return s.GetTransaction(ctx, ownerID, id)
}

type TransactionPage struct {
	Items      []Transaction
	NextCursor string
	Scanned    int
	Truncated  bool
}

func (s *Store) ListTransactions(ctx context.Context, ownerID, monthID, categoryID, direction, source, q string, limit int, cursor string) (TransactionPage, error) {
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	start, end, err := ParseMonth(monthID)
	if err != nil {
		return TransactionPage{}, err
	}
	args := []any{ownerID, start.UnixMilli(), end.UnixMilli()}
	sqlText := `SELECT t.id, t.owner_id, COALESCE(t.category_id, ''), COALESCE(c.code, ''),
		COALESCE(c.kind, 'UNCATEGORIZED'), t.direction, t.source, t.amount_minor, t.currency,
		t.event_at_ms, t.month_id, t.fingerprint, COALESCE(t.parser_version, ''),
		COALESCE(t.parser_confidence, 0), t.category_origin, t.sensitive_ciphertext,
		t.sensitive_iv, t.sensitive_tag, t.key_version, t.is_internal_transfer,
		COALESCE(t.linked_refund_of, ''), t.revision, t.created_at, t.updated_at
		FROM transactions t LEFT JOIN categories c
		  ON c.owner_id = t.owner_id AND c.id = t.category_id
		WHERE t.owner_id = ? AND t.deleted_at IS NULL
		  AND t.event_at_ms >= ? AND t.event_at_ms < ?`
	if categoryID != "" {
		sqlText += " AND t.category_id = ?"
		args = append(args, categoryID)
	}
	if direction != "" {
		sqlText += " AND t.direction = ?"
		args = append(args, direction)
	}
	if source != "" {
		sqlText += " AND t.source = ?"
		args = append(args, source)
	}
	if cursor != "" {
		ms, id, err := decodeCursor(cursor)
		if err != nil {
			return TransactionPage{}, err
		}
		sqlText += " AND (t.event_at_ms < ? OR (t.event_at_ms = ? AND t.id < ?))"
		args = append(args, ms, ms, id)
	}
	sqlText += " ORDER BY t.event_at_ms DESC, t.id DESC LIMIT ?"
	fetchLimit := limit + 1
	if q != "" {
		fetchLimit = 2001
	}
	args = append(args, fetchLimit)
	rows, err := s.DB.QueryContext(ctx, sqlText, args...)
	if err != nil {
		return TransactionPage{}, err
	}
	defer rows.Close()
	page := TransactionPage{}
	for rows.Next() {
		transaction, err := scanTransaction(ctx, rows, s, ownerID)
		if err != nil {
			return TransactionPage{}, err
		}
		page.Scanned++
		if q != "" && !transactionMatches(transaction, q) {
			continue
		}
		page.Items = append(page.Items, transaction)
		if len(page.Items) > limit {
			page.Items = page.Items[:limit]
			break
		}
	}
	if err := rows.Err(); err != nil {
		return TransactionPage{}, err
	}
	if len(page.Items) == limit && q == "" {
		last := page.Items[len(page.Items)-1]
		page.NextCursor = encodeCursor(last.EventAt.UnixMilli(), last.ID)
	}
	if q != "" {
		page.Truncated = page.Scanned >= 2000
		if len(page.Items) == limit && !page.Truncated {
			last := page.Items[len(page.Items)-1]
			page.NextCursor = encodeCursor(last.EventAt.UnixMilli(), last.ID)
		}
	}
	return page, nil
}

func (s *Store) GetTransaction(ctx context.Context, ownerID, id string) (Transaction, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT t.id, t.owner_id, COALESCE(t.category_id, ''),
		COALESCE(c.code, ''), COALESCE(c.kind, 'UNCATEGORIZED'), t.direction, t.source,
		t.amount_minor, t.currency, t.event_at_ms, t.month_id, t.fingerprint,
		COALESCE(t.parser_version, ''), COALESCE(t.parser_confidence, 0), t.category_origin,
		t.sensitive_ciphertext, t.sensitive_iv, t.sensitive_tag, t.key_version,
		t.is_internal_transfer, COALESCE(t.linked_refund_of, ''), t.revision,
		t.created_at, t.updated_at
		FROM transactions t LEFT JOIN categories c
		  ON c.owner_id = t.owner_id AND c.id = t.category_id
		WHERE t.owner_id = ? AND t.id = ? AND t.deleted_at IS NULL`, ownerID, id)
	return scanTransaction(ctx, row, s, ownerID)
}

func (s *Store) UpdateTransaction(ctx context.Context, ownerID, id string, patch TransactionPatch, now time.Time) (Transaction, error) {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return Transaction{}, err
	}
	current, err := s.GetTransaction(ctx, ownerID, id)
	if err != nil {
		return Transaction{}, err
	}
	if current.Revision != patch.Revision {
		return Transaction{}, ErrRevisionConflict
	}
	categoryID := current.CategoryID
	if patch.CategoryID != nil {
		categoryID = *patch.CategoryID
		if categoryID != "" {
			if err := s.ensureCategory(ctx, ownerID, categoryID); err != nil {
				return Transaction{}, err
			}
		}
	}
	sensitive := current.Sensitive
	if patch.Description != nil {
		sensitive.Description = *patch.Description
	}
	if patch.MerchantLabel != nil {
		sensitive.MerchantLabel = *patch.MerchantLabel
	}
	if patch.Note != nil {
		sensitive.Note = *patch.Note
	}
	if patch.Attributes != nil {
		sensitive.Attributes = *patch.Attributes
	}
	internal := current.IsInternalTransfer
	if patch.IsInternalTransfer != nil {
		internal = *patch.IsInternalTransfer
	}
	linked := current.LinkedRefundOf
	if patch.LinkedRefundOf != nil {
		linked = *patch.LinkedRefundOf
	}
	if err := validateAttributes(sensitive.Attributes); err != nil {
		return Transaction{}, err
	}
	sealed, err := crypto.SealJSON(sensitive, s.DataKey, s.KeyVersion, ownerID, "transaction")
	if err != nil {
		return Transaction{}, err
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return Transaction{}, err
	}
	defer func() { _ = tx.Rollback() }()
	result, err := tx.ExecContext(ctx, `UPDATE transactions SET category_id = ?,
		category_origin = 'USER', sensitive_ciphertext = ?, sensitive_iv = ?, sensitive_tag = ?,
		key_version = ?, is_internal_transfer = ?, linked_refund_of = ?,
		revision = revision + 1, updated_at = ?
		WHERE owner_id = ? AND id = ? AND revision = ? AND deleted_at IS NULL`,
		nullableString(categoryID), sealed.Ciphertext, sealed.IV, sealed.Tag, s.KeyVersion,
		boolInt(internal), nullableString(linked), nowString(now), ownerID, id, patch.Revision)
	if err != nil {
		return Transaction{}, err
	}
	changed, _ := result.RowsAffected()
	if changed != 1 {
		return Transaction{}, ErrRevisionConflict
	}
	if err := bumpMonthRevision(ctx, tx, ownerID, current.MonthID, now); err != nil {
		return Transaction{}, err
	}
	if err := tx.Commit(); err != nil {
		return Transaction{}, err
	}
	return s.GetTransaction(ctx, ownerID, id)
}

func (s *Store) DeleteTransaction(ctx context.Context, ownerID, id string, now time.Time) error {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return err
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	var monthID string
	if err := tx.QueryRowContext(ctx, `SELECT month_id FROM transactions
		WHERE owner_id = ? AND id = ? AND deleted_at IS NULL`, ownerID, id).Scan(&monthID); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE transactions SET deleted_at = ?, revision = revision + 1,
		updated_at = ? WHERE owner_id = ? AND id = ?`, nowString(now), nowString(now), ownerID, id); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE attachments SET deleted_at = ?, revision = revision + 1,
		updated_at = ? WHERE owner_id = ? AND transaction_id = ? AND deleted_at IS NULL`,
		nowString(now), nowString(now), ownerID, id); err != nil {
		return err
	}
	if err := bumpMonthRevision(ctx, tx, ownerID, monthID, now); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) ensureCategory(ctx context.Context, ownerID, categoryID string) error {
	var count int
	if err := s.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM categories
		WHERE owner_id = ? AND id = ? AND deleted_at IS NULL`, ownerID, categoryID).Scan(&count); err != nil {
		return err
	}
	if count != 1 {
		return ErrNotFound
	}
	return nil
}

func bumpMonthRevision(ctx context.Context, tx *sql.Tx, ownerID, monthID string, now time.Time) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO month_revisions (owner_id, month_id, revision, updated_at)
		VALUES (?, ?, 1, ?)
		ON CONFLICT(owner_id, month_id) DO UPDATE SET revision = month_revisions.revision + 1,
			updated_at = excluded.updated_at`, ownerID, monthID, nowString(now))
	return err
}

func scanTransaction(ctx context.Context, scanner rowScanner, s *Store, ownerID string) (Transaction, error) {
	var t Transaction
	var categoryID, categoryCode, categoryKind, parserVersion, linked string
	var confidence float64
	var ciphertext, iv, tag []byte
	var keyVersion int
	var internal int
	var eventAtMs int64
	var created, updated string
	if err := scanner.Scan(&t.ID, &t.OwnerID, &categoryID, &categoryCode, &categoryKind,
		&t.Direction, &t.Source, &t.AmountMinor, &t.Currency, &eventAtMs, &t.MonthID,
		&t.Fingerprint, &parserVersion, &confidence, &t.CategoryOrigin, &ciphertext, &iv, &tag,
		&keyVersion, &internal, &linked, &t.Revision, &created, &updated); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Transaction{}, ErrNotFound
		}
		return Transaction{}, err
	}
	t.CategoryID, t.CategoryCode, t.CategoryKind = categoryID, categoryCode, categoryKind
	t.ParserVersion, t.ParserConfidence, t.LinkedRefundOf = parserVersion, confidence, linked
	t.IsInternalTransfer = internal == 1
	t.EventAt = time.UnixMilli(eventAtMs).UTC()
	t.CreatedAt, t.UpdatedAt = parseTime(created), parseTime(updated)
	if err := crypto.OpenJSON(crypto.Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag, KeyVersion: keyVersion},
		s.DataKey, ownerID, "transaction", &t.Sensitive); err != nil {
		return Transaction{}, err
	}
	return t, nil
}

func transactionMatches(t Transaction, q string) bool {
	needle := strings.ToLower(q)
	haystack := strings.ToLower(strings.Join([]string{
		t.Sensitive.Description, t.Sensitive.MerchantLabel, t.Sensitive.Note,
		t.CategoryCode, t.CategoryKind,
	}, " "))
	if strings.Contains(haystack, needle) {
		return true
	}
	attributes, _ := json.Marshal(t.Sensitive.Attributes)
	return strings.Contains(strings.ToLower(string(attributes)), needle)
}

func encodeCursor(ms int64, id string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(strconv.FormatInt(ms, 10) + "|" + id))
}

func decodeCursor(cursor string) (int64, string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return 0, "", fmt.Errorf("invalid cursor")
	}
	parts := strings.SplitN(string(raw), "|", 2)
	if len(parts) != 2 {
		return 0, "", fmt.Errorf("invalid cursor")
	}
	ms, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		return 0, "", fmt.Errorf("invalid cursor")
	}
	return ms, parts[1], nil
}

func nullableString(value string) any {
	if value == "" {
		return nil
	}
	return value
}

const transactionSelect = `SELECT t.id, t.owner_id, COALESCE(t.category_id, ''),
	COALESCE(c.code, ''), COALESCE(c.kind, 'UNCATEGORIZED'), t.direction, t.source,
	t.amount_minor, t.currency, t.event_at_ms, t.month_id, t.fingerprint,
	COALESCE(t.parser_version, ''), COALESCE(t.parser_confidence, 0), t.category_origin,
	t.sensitive_ciphertext, t.sensitive_iv, t.sensitive_tag, t.key_version,
	t.is_internal_transfer, COALESCE(t.linked_refund_of, ''), t.revision,
	t.created_at, t.updated_at, COALESCE(t.deleted_at, '')
	FROM transactions t LEFT JOIN categories c
	  ON c.owner_id = t.owner_id AND c.id = t.category_id`

func scanTransactionWithDeleted(ctx context.Context, scanner rowScanner, s *Store, ownerID string) (Transaction, error) {
	var t Transaction
	var categoryID, categoryCode, categoryKind, parserVersion, linked, deleted string
	var confidence float64
	var ciphertext, iv, tag []byte
	var keyVersion int
	var internal int
	var eventAtMs int64
	var created, updated string
	if err := scanner.Scan(&t.ID, &t.OwnerID, &categoryID, &categoryCode, &categoryKind,
		&t.Direction, &t.Source, &t.AmountMinor, &t.Currency, &eventAtMs, &t.MonthID,
		&t.Fingerprint, &parserVersion, &confidence, &t.CategoryOrigin, &ciphertext, &iv, &tag,
		&keyVersion, &internal, &linked, &t.Revision, &created, &updated, &deleted); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Transaction{}, ErrNotFound
		}
		return Transaction{}, err
	}
	t.CategoryID, t.CategoryCode, t.CategoryKind = categoryID, categoryCode, categoryKind
	t.ParserVersion, t.ParserConfidence, t.LinkedRefundOf = parserVersion, confidence, linked
	t.IsInternalTransfer = internal == 1
	t.EventAt = time.UnixMilli(eventAtMs).UTC()
	t.CreatedAt, t.UpdatedAt = parseTime(created), parseTime(updated)
	if deleted != "" {
		when := parseTime(deleted)
		t.DeletedAt = &when
	}
	if err := crypto.OpenJSON(crypto.Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag, KeyVersion: keyVersion},
		s.DataKey, ownerID, "transaction", &t.Sensitive); err != nil {
		return Transaction{}, err
	}
	return t, nil
}
