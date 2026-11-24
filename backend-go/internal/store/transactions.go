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
	ID                      string
	OwnerID                 string
	CategoryID              string
	CategoryCode            string
	CategoryKind            string
	Direction               string
	Source                  string
	AmountMinor             int64
	Currency                string
	EventAt                 time.Time
	MonthID                 string
	Fingerprint             string
	ParserVersion           string
	ParserConfidence        float64
	CategoryOrigin          string
	ClassifierOrigin        string
	CategoryConfidence      float64
	CategoryRuleVersion     string
	CategoryReason          string
	CategoryTaxonomyVersion string
	Sensitive               TransactionSensitive
	IsInternalTransfer      bool
	LinkedRefundOf          string
	Revision                int
	CreatedAt               time.Time
	UpdatedAt               time.Time
	DeletedAt               *time.Time
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
	CategoryID    *string
	Description   *string
	MerchantLabel *string
	// ExpectedFingerprint is the client's compare-and-swap proof for a merchant-label repair: it
	// must equal the currently stored fingerprint, so a stale view can never overwrite a newer
	// value. The replacement fingerprint is derived server-side; a client never sends it.
	ExpectedFingerprint *string
	// SourceRef is the opaque device reference `UIDVALIDITY:UID` used only to adopt rows imported
	// before source references existed. It is ignored (and must match) when one is already stored.
	SourceRef          *string
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

// searchScanWindow bounds how many decrypted rows one search page may inspect. Plain listing is
// index-backed, but q filters plaintext that lives in the sealed payload, so it has to walk the
// owner's rows. When the window is exhausted the page still returns a cursor anchored at the last
// scanned row, so a caller can continue a long search instead of silently losing matches.
const searchScanWindow = 2000

func (s *Store) ListTransactions(ctx context.Context, ownerID, monthID, categoryID, direction, source, q string, limit int, cursor string) (TransactionPage, error) {
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	q = NormalizeSearchTerm(q)
	start, end, err := ParseMonth(monthID)
	if err != nil {
		return TransactionPage{}, err
	}
	scope := cursorScope(ownerID, monthID, categoryID, direction, source, q)
	args := []any{ownerID, start.UnixMilli(), end.UnixMilli()}
	sqlText := `SELECT t.id, t.owner_id, COALESCE(t.category_id, ''), COALESCE(c.code, ''),
		COALESCE(c.kind, 'UNCATEGORIZED'), t.direction, t.source, t.amount_minor, t.currency,
		t.event_at_ms, t.month_id, t.fingerprint, COALESCE(t.parser_version, ''),
		COALESCE(t.parser_confidence, 0), t.category_origin, COALESCE(t.classifier_origin,''),
		COALESCE(t.category_confidence,0), COALESCE(t.category_rule_version,''),
		COALESCE(t.category_reason,''), COALESCE(t.category_taxonomy_version,''), t.sensitive_ciphertext,
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
		ms, id, cursorScopeValue, err := decodeCursor(cursor)
		if err != nil {
			return TransactionPage{}, ErrInvalidCursor
		}
		// A cursor is issued for one owner + month + filter/query combination. Reusing it with a
		// different scope would skip or duplicate rows, so it is rejected instead of reinterpreted.
		if cursorScopeValue != scope {
			return TransactionPage{}, ErrInvalidCursor
		}
		sqlText += " AND (t.event_at_ms < ? OR (t.event_at_ms = ? AND t.id < ?))"
		args = append(args, ms, ms, id)
	}
	sqlText += " ORDER BY t.event_at_ms DESC, t.id DESC LIMIT ?"
	fetchLimit := limit + 1
	if q != "" {
		fetchLimit = searchScanWindow + 1
	}
	args = append(args, fetchLimit)
	rows, err := s.DB.QueryContext(ctx, sqlText, args...)
	if err != nil {
		return TransactionPage{}, err
	}
	defer rows.Close()
	page := TransactionPage{}
	var lastScanned Transaction
	full := false
	for rows.Next() {
		transaction, err := scanTransaction(ctx, rows, s, ownerID)
		if err != nil {
			return TransactionPage{}, err
		}
		page.Scanned++
		lastScanned = transaction
		if q != "" && !transactionMatches(transaction, q) {
			continue
		}
		page.Items = append(page.Items, transaction)
		if len(page.Items) > limit {
			page.Items = page.Items[:limit]
			full = true
			break
		}
	}
	if err := rows.Err(); err != nil {
		return TransactionPage{}, err
	}
	if q == "" {
		// Scanned > limit means the extra probe row exists: only then is there a next page.
		if full || page.Scanned > limit {
			last := page.Items[len(page.Items)-1]
			page.NextCursor = encodeCursor(last.EventAt.UnixMilli(), last.ID, scope)
		}
		return page, nil
	}
	// Search pages are filtered after decryption. A full page continues from the last returned
	// item; an exhausted scan window continues from the last row that was actually inspected, so
	// a match deeper in the month is still reachable on the next request.
	if full {
		last := page.Items[len(page.Items)-1]
		page.NextCursor = encodeCursor(last.EventAt.UnixMilli(), last.ID, scope)
	} else if page.Scanned > searchScanWindow {
		page.Truncated = true
		page.NextCursor = encodeCursor(lastScanned.EventAt.UnixMilli(), lastScanned.ID, scope)
	}
	return page, nil
}

func (s *Store) GetTransaction(ctx context.Context, ownerID, id string) (Transaction, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT t.id, t.owner_id, COALESCE(t.category_id, ''),
		COALESCE(c.code, ''), COALESCE(c.kind, 'UNCATEGORIZED'), t.direction, t.source,
		t.amount_minor, t.currency, t.event_at_ms, t.month_id, t.fingerprint,
		COALESCE(t.parser_version, ''), COALESCE(t.parser_confidence, 0), t.category_origin,
		COALESCE(t.classifier_origin,''), COALESCE(t.category_confidence,0),
		COALESCE(t.category_rule_version,''), COALESCE(t.category_reason,''),
		COALESCE(t.category_taxonomy_version,''), t.sensitive_ciphertext, t.sensitive_iv, t.sensitive_tag, t.key_version,
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
	// Merchant-label repair path (P0: PAN stored instead of the Penerima business name). It has
	// stricter preconditions than an ordinary edit: the row must still be the email row the client
	// believes it is, and the identity of that row must be provable from stored data.
	var repair *merchantRepair
	if patch.MerchantLabel != nil {
		repair, err = s.prepareMerchantRepair(ctx, ownerID, current, patch)
		if err != nil {
			return Transaction{}, err
		}
		if repair.noop {
			return current, nil
		}
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
		sensitive.MerchantLabel = repair.merchantLabel
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
	fingerprint := current.Fingerprint
	var refCipher, refIV, refTag, refVersion, refHash any
	if repair != nil {
		fingerprint = repair.fingerprint
		if repair.adopt {
			sealedRef, err := crypto.Seal([]byte(repair.sourceRef), s.DataKey, s.KeyVersion,
				ownerID, "source_ref")
			if err != nil {
				return Transaction{}, err
			}
			refCipher, refIV, refTag = sealedRef.Ciphertext, sealedRef.IV, sealedRef.Tag
			refVersion = s.KeyVersion
			refHash = s.sourceRefHash(repair.sourceRef)
		}
	}
	// A user edit that changes the category supersedes any classifier output: provenance switches
	// to USER and every classifier-derived attribute (origin, confidence, reason, rule and taxonomy
	// versions) is cleared so no stale "LLM" provenance survives the override.
	categoryChanged := patch.CategoryID != nil
	var result sql.Result
	if repair != nil && repair.adopt {
		result, err = tx.ExecContext(ctx, `UPDATE transactions SET category_id = ?,
			category_origin = 'USER',
			classifier_origin = CASE WHEN ? THEN '' ELSE classifier_origin END,
			category_confidence = CASE WHEN ? THEN NULL ELSE category_confidence END,
			category_reason = CASE WHEN ? THEN NULL ELSE category_reason END,
			category_rule_version = CASE WHEN ? THEN NULL ELSE category_rule_version END,
			category_taxonomy_version = CASE WHEN ? THEN NULL ELSE category_taxonomy_version END,
			sensitive_ciphertext = ?, sensitive_iv = ?, sensitive_tag = ?,
			key_version = ?, is_internal_transfer = ?, linked_refund_of = ?,
			fingerprint = ?,
			source_ref_ciphertext = ?, source_ref_iv = ?, source_ref_tag = ?,
			source_ref_key_version = ?, source_ref_hash = ?,
			revision = revision + 1, updated_at = ?
			WHERE owner_id = ? AND id = ? AND revision = ? AND fingerprint = ? AND deleted_at IS NULL`,
			nullableString(categoryID), boolInt(categoryChanged), boolInt(categoryChanged),
			boolInt(categoryChanged), boolInt(categoryChanged), boolInt(categoryChanged),
			sealed.Ciphertext, sealed.IV, sealed.Tag, s.KeyVersion,
			boolInt(internal), nullableString(linked), fingerprint,
			refCipher, refIV, refTag, refVersion, refHash,
			nowString(now), ownerID, id, patch.Revision, current.Fingerprint)
	} else {
		result, err = tx.ExecContext(ctx, `UPDATE transactions SET category_id = ?,
			category_origin = 'USER',
			classifier_origin = CASE WHEN ? THEN '' ELSE classifier_origin END,
			category_confidence = CASE WHEN ? THEN NULL ELSE category_confidence END,
			category_reason = CASE WHEN ? THEN NULL ELSE category_reason END,
			category_rule_version = CASE WHEN ? THEN NULL ELSE category_rule_version END,
			category_taxonomy_version = CASE WHEN ? THEN NULL ELSE category_taxonomy_version END,
			sensitive_ciphertext = ?, sensitive_iv = ?, sensitive_tag = ?,
			key_version = ?, is_internal_transfer = ?, linked_refund_of = ?,
			fingerprint = ?,
			revision = revision + 1, updated_at = ?
			WHERE owner_id = ? AND id = ? AND revision = ? AND fingerprint = ? AND deleted_at IS NULL`,
			nullableString(categoryID), boolInt(categoryChanged), boolInt(categoryChanged),
			boolInt(categoryChanged), boolInt(categoryChanged), boolInt(categoryChanged),
			sealed.Ciphertext, sealed.IV, sealed.Tag, s.KeyVersion,
			boolInt(internal), nullableString(linked), fingerprint,
			nowString(now), ownerID, id, patch.Revision, current.Fingerprint)
	}
	if err != nil {
		if isUnique(err.Error()) {
			return Transaction{}, ErrConflict
		}
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

// merchantRepair is the resolved, verified plan for replacing a stored merchant label.
type merchantRepair struct {
	merchantLabel string
	sourceRef     string
	fingerprint   string
	adopt         bool // true when the row predates stored source references
	noop          bool // the requested state is already stored (idempotent replay)
}

// prepareMerchantRepair enforces the repair preconditions:
//   - the caller proves it is patching the row it read (expectedFingerprint CAS);
//   - the notification identity is available: stored sealed source ref, or supplied once to adopt
//     a legacy row (fail closed when neither exists);
//   - the replacement label is a real business name, never another identifier;
//   - the replacement fingerprint is derived here from canonical stored fields and the label.
func (s *Store) prepareMerchantRepair(ctx context.Context, ownerID string, current Transaction,
	patch TransactionPatch) (*merchantRepair, error) {
	if current.Source == "MANUAL" {
		return nil, fmt.Errorf("merchant label of a manual transaction is not repairable")
	}
	expected := ""
	if patch.ExpectedFingerprint != nil {
		expected = strings.ToLower(strings.TrimSpace(*patch.ExpectedFingerprint))
	}
	if expected == "" {
		return nil, ErrFingerprintRequired
	}
	if expected != current.Fingerprint {
		return nil, ErrFingerprintMismatch
	}
	storedRef, err := s.storedSourceRef(ctx, ownerID, current.ID)
	if err != nil {
		return nil, err
	}
	sourceRef := storedRef
	adopt := false
	supplied := ""
	if patch.SourceRef != nil {
		supplied = strings.TrimSpace(*patch.SourceRef)
	}
	if sourceRef == "" {
		if supplied == "" || !validSourceRef(supplied) {
			return nil, ErrSourceRefRequired
		}
		sourceRef = supplied
		adopt = true
	} else if supplied != "" && supplied != sourceRef {
		// The row already carries a verified identity; a different claimed reference means the
		// client is looking at another notification.
		return nil, ErrIdentityMismatch
	}
	label := strings.TrimSpace(*patch.MerchantLabel)
	if label == "" || len(label) > 120 || isReferenceOnlyLabel(label) {
		return nil, ErrInvalidMerchantLabel
	}
	fingerprint := s.transactionFingerprint(sourceRef, current.AmountMinor,
		current.EventAt.UnixMilli(), current.Direction, label)
	if !adopt && current.Fingerprint == fingerprint &&
		strings.TrimSpace(current.Sensitive.MerchantLabel) == label {
		return &merchantRepair{noop: true}, nil
	}
	return &merchantRepair{merchantLabel: label, sourceRef: sourceRef,
		fingerprint: fingerprint, adopt: adopt}, nil
}

// storedSourceRef returns the decrypted source reference of a row, or "" for a legacy row.
func (s *Store) storedSourceRef(ctx context.Context, ownerID, id string) (string, error) {
	var ciphertext, iv, tag []byte
	var keyVersion sql.NullInt64
	err := s.DB.QueryRowContext(ctx, `SELECT source_ref_ciphertext, source_ref_iv,
		source_ref_tag, source_ref_key_version FROM transactions
		WHERE owner_id = ? AND id = ? AND deleted_at IS NULL`, ownerID, id).Scan(
		&ciphertext, &iv, &tag, &keyVersion)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrNotFound
	}
	if err != nil {
		return "", err
	}
	if len(ciphertext) == 0 || len(iv) == 0 || len(tag) == 0 || !keyVersion.Valid {
		return "", nil
	}
	plain, err := crypto.Open(crypto.Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag,
		KeyVersion: int(keyVersion.Int64)}, s.DataKey, ownerID, "source_ref")
	if err != nil {
		return "", err
	}
	return string(plain), nil
}

// DeleteTransaction soft-deletes one row and its attachment metadata. expectedRevision is
// optional: a negative value keeps the historical unconditional delete for older clients, while
// 0 or greater makes the delete compare-and-swap so a stale client cannot delete a row it never
// saw.
func (s *Store) DeleteTransaction(ctx context.Context, ownerID, id string, expectedRevision int, now time.Time) error {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return err
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	var monthID string
	var revision int
	if err := tx.QueryRowContext(ctx, `SELECT month_id, revision FROM transactions
		WHERE owner_id = ? AND id = ? AND deleted_at IS NULL`, ownerID, id).Scan(&monthID, &revision); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if expectedRevision >= 0 && revision != expectedRevision {
		return ErrRevisionConflict
	}
	if _, err := tx.ExecContext(ctx, `UPDATE transactions SET deleted_at = ?, revision = revision + 1,
		updated_at = ? WHERE owner_id = ? AND id = ? AND deleted_at IS NULL`,
		nowString(now), nowString(now), ownerID, id); err != nil {
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
	var classifierOrigin, ruleVersion, categoryReason, taxonomyVersion string
	var confidence, categoryConfidence float64
	var ciphertext, iv, tag []byte
	var keyVersion int
	var internal int
	var eventAtMs int64
	var created, updated string
	if err := scanner.Scan(&t.ID, &t.OwnerID, &categoryID, &categoryCode, &categoryKind,
		&t.Direction, &t.Source, &t.AmountMinor, &t.Currency, &eventAtMs, &t.MonthID,
		&t.Fingerprint, &parserVersion, &confidence, &t.CategoryOrigin, &classifierOrigin,
		&categoryConfidence, &ruleVersion, &categoryReason, &taxonomyVersion, &ciphertext, &iv, &tag,
		&keyVersion, &internal, &linked, &t.Revision, &created, &updated); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Transaction{}, ErrNotFound
		}
		return Transaction{}, err
	}
	t.CategoryID, t.CategoryCode, t.CategoryKind = categoryID, categoryCode, categoryKind
	t.ParserVersion, t.ParserConfidence, t.LinkedRefundOf = parserVersion, confidence, linked
	t.ClassifierOrigin, t.CategoryConfidence = classifierOrigin, categoryConfidence
	t.CategoryRuleVersion, t.CategoryReason, t.CategoryTaxonomyVersion = ruleVersion, categoryReason, taxonomyVersion
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
	needle := strings.ToLower(NormalizeSearchTerm(q))
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

// cursorScope binds a cursor to the exact query that produced it: owner, month and every filter
// that changes the result set. The q text is hashed, not embedded, so a cursor never echoes a
// private search term.
func cursorScope(ownerID, monthID, categoryID, direction, source, q string) string {
	sum := sha256.Sum256([]byte(strings.Join([]string{ownerID, monthID, categoryID,
		direction, source, strings.ToLower(strings.TrimSpace(q))}, "\x00")))
	return hex.EncodeToString(sum[:8])
}

func encodeCursor(ms int64, id, scope string) string {
	raw := strconv.FormatInt(ms, 10) + "|" + id + "|" + scope
	return base64.RawURLEncoding.EncodeToString([]byte(raw))
}

func decodeCursor(cursor string) (int64, string, string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return 0, "", "", fmt.Errorf("invalid cursor")
	}
	// Exactly three fields are required. A legacy two-field cursor is not silently upgraded
	// because it carries no scope and could otherwise be replayed against different filters.
	parts := strings.Split(string(raw), "|")
	if len(parts) != 3 || len(parts[1]) == 0 || len(parts[2]) != 16 {
		return 0, "", "", fmt.Errorf("invalid cursor")
	}
	ms, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		return 0, "", "", fmt.Errorf("invalid cursor")
	}
	return ms, parts[1], parts[2], nil
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
	COALESCE(t.classifier_origin,''), COALESCE(t.category_confidence,0),
	COALESCE(t.category_rule_version,''), COALESCE(t.category_reason,''),
	COALESCE(t.category_taxonomy_version,''), t.sensitive_ciphertext, t.sensitive_iv, t.sensitive_tag, t.key_version,
	t.is_internal_transfer, COALESCE(t.linked_refund_of, ''), t.revision,
	t.created_at, t.updated_at, COALESCE(t.deleted_at, '')
	FROM transactions t LEFT JOIN categories c
	  ON c.owner_id = t.owner_id AND c.id = t.category_id`

func scanTransactionWithDeleted(ctx context.Context, scanner rowScanner, s *Store, ownerID string) (Transaction, error) {
	var t Transaction
	var categoryID, categoryCode, categoryKind, parserVersion, linked, deleted string
	var classifierOrigin, ruleVersion, categoryReason, taxonomyVersion string
	var confidence, categoryConfidence float64
	var ciphertext, iv, tag []byte
	var keyVersion int
	var internal int
	var eventAtMs int64
	var created, updated string
	if err := scanner.Scan(&t.ID, &t.OwnerID, &categoryID, &categoryCode, &categoryKind,
		&t.Direction, &t.Source, &t.AmountMinor, &t.Currency, &eventAtMs, &t.MonthID,
		&t.Fingerprint, &parserVersion, &confidence, &t.CategoryOrigin, &classifierOrigin,
		&categoryConfidence, &ruleVersion, &categoryReason, &taxonomyVersion, &ciphertext, &iv, &tag,
		&keyVersion, &internal, &linked, &t.Revision, &created, &updated, &deleted); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Transaction{}, ErrNotFound
		}
		return Transaction{}, err
	}
	t.CategoryID, t.CategoryCode, t.CategoryKind = categoryID, categoryCode, categoryKind
	t.ParserVersion, t.ParserConfidence, t.LinkedRefundOf = parserVersion, confidence, linked
	t.ClassifierOrigin, t.CategoryConfidence = classifierOrigin, categoryConfidence
	t.CategoryRuleVersion, t.CategoryReason, t.CategoryTaxonomyVersion = ruleVersion, categoryReason, taxonomyVersion
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
