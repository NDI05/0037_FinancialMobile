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
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/crypto"
)

type Category struct {
	ID         string
	Code       string
	Label      string
	Kind       string
	Color      string
	IsBuiltin  bool
	Revision   int
	Attributes map[string]any
	CreatedAt  time.Time
	UpdatedAt  time.Time
}

type CategoryPage struct {
	Items      []Category
	NextCursor string
}

// ListCategories returns categories ordered by the stable (is_builtin DESC, code ASC, id ASC)
// tuple. q filters the sealed label/code after decryption, so the cursor walks that same total
// order instead of an index; a cursor is bound to owner+q and is rejected for another query.
func (s *Store) ListCategories(ctx context.Context, ownerID, q string, limit int, cursor string) (CategoryPage, error) {
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	scope := categoryCursorScope(ownerID, q)
	var after categoryCursor
	haveAfter := false
	if cursor != "" {
		decoded, err := decodeCategoryCursor(cursor)
		if err != nil || decoded.Scope != scope {
			return CategoryPage{}, ErrInvalidCursor
		}
		after, haveAfter = decoded, true
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT id, code, kind, label_ciphertext, label_iv, label_tag,
		color, is_builtin, attributes_ciphertext, attributes_iv, attributes_tag, key_version,
		revision, created_at, updated_at
		FROM categories WHERE owner_id = ? AND deleted_at IS NULL
		ORDER BY is_builtin DESC, code ASC, id ASC`, ownerID)
	if err != nil {
		return CategoryPage{}, err
	}
	defer rows.Close()
	var matches []Category
	for rows.Next() {
		category, err := scanCategory(ctx, rows, s, ownerID)
		if err != nil {
			return CategoryPage{}, err
		}
		if q != "" && !containsFold(category.Label, q) && !containsFold(category.Code, q) {
			continue
		}
		if haveAfter && !categoryAfter(category, after) {
			continue
		}
		matches = append(matches, category)
	}
	if err := rows.Err(); err != nil {
		return CategoryPage{}, err
	}
	page := CategoryPage{}
	if len(matches) > limit {
		page.Items = matches[:limit]
		last := page.Items[len(page.Items)-1]
		page.NextCursor = encodeCategoryCursor(categoryCursorFor(last, scope))
	} else {
		page.Items = matches
	}
	return page, nil
}

type categoryCursor struct {
	Builtin bool   `json:"b"`
	Code    string `json:"c"`
	ID      string `json:"i"`
	Scope   string `json:"s"`
}

func categoryCursorScope(ownerID, q string) string {
	sum := sha256.Sum256([]byte(ownerID + "\x00" + strings.ToLower(strings.TrimSpace(q))))
	return hex.EncodeToString(sum[:8])
}

func categoryCursorFor(category Category, scope string) categoryCursor {
	return categoryCursor{Builtin: category.IsBuiltin, Code: category.Code, ID: category.ID, Scope: scope}
}

func encodeCategoryCursor(cursor categoryCursor) string {
	raw, _ := json.Marshal(cursor)
	return base64.RawURLEncoding.EncodeToString(raw)
}

func decodeCategoryCursor(value string) (categoryCursor, error) {
	raw, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return categoryCursor{}, err
	}
	var cursor categoryCursor
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&cursor); err != nil || cursor.ID == "" || len(cursor.Scope) != 16 {
		return categoryCursor{}, fmt.Errorf("invalid cursor")
	}
	return cursor, nil
}

// categoryAfter reports whether category sorts strictly after the cursor position in the
// (built-in first, code, id) order.
func categoryAfter(category Category, cursor categoryCursor) bool {
	if category.IsBuiltin != cursor.Builtin {
		// Built-in rows (rank 0) sort before custom rows (rank 1).
		return !category.IsBuiltin
	}
	if category.Code != cursor.Code {
		return category.Code > cursor.Code
	}
	return category.ID > cursor.ID
}

func (s *Store) GetCategory(ctx context.Context, ownerID, id string) (Category, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT id, code, kind, label_ciphertext, label_iv, label_tag,
		color, is_builtin, attributes_ciphertext, attributes_iv, attributes_tag, key_version,
		revision, created_at, updated_at
		FROM categories WHERE owner_id = ? AND id = ? AND deleted_at IS NULL`, ownerID, id)
	return scanCategory(ctx, row, s, ownerID)
}

type CategoryInput struct {
	Code       string
	Label      string
	Kind       string
	Color      string
	Attributes map[string]any
}

func (s *Store) CreateCategory(ctx context.Context, ownerID string, input CategoryInput, now time.Time) (Category, error) {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return Category{}, err
	}
	if input.Label == "" {
		return Category{}, fmt.Errorf("label is required")
	}
	if !validKind(input.Kind) {
		return Category{}, fmt.Errorf("invalid kind")
	}
	id := NewID("cat")
	code := input.Code
	if code == "" {
		code = id
	}
	label, err := crypto.SealJSON(input.Label, s.DataKey, s.KeyVersion, ownerID, "category_label")
	if err != nil {
		return Category{}, err
	}
	attrs, err := s.sealAttributes(ctx, ownerID, "category_attributes", input.Attributes)
	if err != nil {
		return Category{}, err
	}
	_, err = s.DB.ExecContext(ctx, `INSERT INTO categories
		(id, owner_id, code, kind, label_ciphertext, label_iv, label_tag, color, is_builtin,
		 attributes_ciphertext, attributes_iv, attributes_tag, key_version, revision,
		 created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, 0, ?, ?)`,
		id, ownerID, code, input.Kind, label.Ciphertext, label.IV, label.Tag, input.Color,
		attrs.Ciphertext, attrs.IV, attrs.Tag, s.KeyVersion, nowString(now), nowString(now))
	if err != nil {
		if isUnique(err.Error()) {
			return Category{}, ErrConflict
		}
		return Category{}, err
	}
	return s.GetCategory(ctx, ownerID, id)
}

type CategoryPatch struct {
	Label      *string
	Color      *string
	Kind       *string
	Attributes *map[string]any
	Revision   int
}

func (s *Store) UpdateCategory(ctx context.Context, ownerID, id string, patch CategoryPatch, now time.Time) (Category, error) {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return Category{}, err
	}
	current, err := s.GetCategory(ctx, ownerID, id)
	if err != nil {
		return Category{}, err
	}
	if current.Revision != patch.Revision {
		return Category{}, ErrRevisionConflict
	}
	if current.IsBuiltin && patch.Kind != nil && *patch.Kind != current.Kind {
		return Category{}, fmt.Errorf("built-in kind cannot change")
	}
	label := current.Label
	if patch.Label != nil {
		label = *patch.Label
	}
	kind := current.Kind
	if patch.Kind != nil {
		if !validKind(*patch.Kind) {
			return Category{}, fmt.Errorf("invalid kind")
		}
		kind = *patch.Kind
	}
	color := current.Color
	if patch.Color != nil {
		color = *patch.Color
	}
	attributes := current.Attributes
	if patch.Attributes != nil {
		attributes = *patch.Attributes
	}
	sealedLabel, err := crypto.SealJSON(label, s.DataKey, s.KeyVersion, ownerID, "category_label")
	if err != nil {
		return Category{}, err
	}
	attrs, err := s.sealAttributes(ctx, ownerID, "category_attributes", attributes)
	if err != nil {
		return Category{}, err
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return Category{}, err
	}
	defer func() { _ = tx.Rollback() }()
	result, err := tx.ExecContext(ctx, `UPDATE categories SET
		kind = ?, label_ciphertext = ?, label_iv = ?, label_tag = ?, color = ?,
		attributes_ciphertext = ?, attributes_iv = ?, attributes_tag = ?, key_version = ?,
		revision = revision + 1, updated_at = ?
		WHERE owner_id = ? AND id = ? AND revision = ? AND deleted_at IS NULL`,
		kind, sealedLabel.Ciphertext, sealedLabel.IV, sealedLabel.Tag, color,
		attrs.Ciphertext, attrs.IV, attrs.Tag, s.KeyVersion, nowString(now),
		ownerID, id, patch.Revision)
	if err != nil {
		return Category{}, err
	}
	changed, _ := result.RowsAffected()
	if changed != 1 {
		return Category{}, ErrRevisionConflict
	}
	months, err := categoryMonthsTx(ctx, tx, ownerID, id)
	if err != nil {
		return Category{}, err
	}
	for _, month := range months {
		if err := bumpMonthRevision(ctx, tx, ownerID, month, now); err != nil {
			return Category{}, err
		}
	}
	if err := tx.Commit(); err != nil {
		return Category{}, err
	}
	return s.GetCategory(ctx, ownerID, id)
}

func (s *Store) DeleteCategory(ctx context.Context, ownerID, id, reassignTo string, now time.Time) error {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return err
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	var builtin int
	var deleted sql.NullString
	if err := tx.QueryRowContext(ctx, `SELECT is_builtin, deleted_at FROM categories
		WHERE owner_id = ? AND id = ?`, ownerID, id).Scan(&builtin, &deleted); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if deleted.Valid {
		return ErrNotFound
	}
	if builtin == 1 {
		return ErrBuiltinCategory
	}
	var refs int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM transactions
		WHERE owner_id = ? AND category_id = ? AND deleted_at IS NULL`, ownerID, id).Scan(&refs); err != nil {
		return err
	}
	if refs > 0 && reassignTo == "" {
		return ErrCategoryInUse
	}
	if reassignTo != "" {
		if reassignTo == id {
			return fmt.Errorf("reassign target must differ")
		}
		// Capture the months touched before the update so their summary revisions move.
		monthRows, err := tx.QueryContext(ctx, `SELECT DISTINCT month_id FROM transactions
			WHERE owner_id = ? AND category_id = ? AND deleted_at IS NULL`, ownerID, id)
		if err != nil {
			return err
		}
		var months []string
		for monthRows.Next() {
			var month string
			if err := monthRows.Scan(&month); err != nil {
				monthRows.Close()
				return err
			}
			months = append(months, month)
		}
		monthRows.Close()
		var target int
		if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM categories
			WHERE owner_id = ? AND id = ? AND deleted_at IS NULL`, ownerID, reassignTo).Scan(&target); err != nil {
			return err
		}
		if target != 1 {
			return ErrNotFound
		}
		if _, err := tx.ExecContext(ctx, `UPDATE transactions SET category_id = ?, updated_at = ?
			WHERE owner_id = ? AND category_id = ? AND deleted_at IS NULL`,
			reassignTo, nowString(now), ownerID, id); err != nil {
			return err
		}
		for _, month := range months {
			if err := bumpMonthRevision(ctx, tx, ownerID, month, now); err != nil {
				return err
			}
		}
		// Move existing limits to the replacement category. If a limit already exists there,
		// keep the larger envelope rather than silently dropping a user's plan.
		if _, err := tx.ExecContext(ctx, `INSERT INTO budget_limits
			(owner_id, plan_id, category_id, amount_minor, created_at, updated_at)
			SELECT owner_id, plan_id, ?, amount_minor, created_at, updated_at FROM budget_limits
			WHERE owner_id = ? AND category_id = ?
			ON CONFLICT(owner_id, plan_id, category_id) DO UPDATE SET
			  amount_minor = MAX(budget_limits.amount_minor, excluded.amount_minor),
			  updated_at = excluded.updated_at`,
			reassignTo, ownerID, id); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `UPDATE categories SET deleted_at = ?, revision = revision + 1,
		updated_at = ? WHERE owner_id = ? AND id = ?`, nowString(now), nowString(now), ownerID, id); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM budget_limits WHERE owner_id = ? AND category_id = ?`,
		ownerID, id); err != nil {
		return err
	}
	return tx.Commit()
}

// scanCategory accepts both *sql.Row and *sql.Rows through the common scanner interface.
type rowScanner interface {
	Scan(dest ...any) error
}

func scanCategory(ctx context.Context, scanner rowScanner, s *Store, ownerID string) (Category, error) {
	var c Category
	var ciphertext, iv, tag []byte
	var attrsCipher, attrsIV, attrsTag []byte
	var keyVersion int
	var created, updated string
	var color sql.NullString
	var builtin int
	if err := scanner.Scan(&c.ID, &c.Code, &c.Kind, &ciphertext, &iv, &tag, &color, &builtin,
		&attrsCipher, &attrsIV, &attrsTag, &keyVersion, &c.Revision, &created, &updated); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Category{}, ErrNotFound
		}
		return Category{}, err
	}
	c.Color = color.String
	c.IsBuiltin = builtin == 1
	c.CreatedAt, c.UpdatedAt = parseTime(created), parseTime(updated)
	if err := crypto.OpenJSON(crypto.Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag, KeyVersion: keyVersion},
		s.DataKey, ownerID, "category_label", &c.Label); err != nil {
		return Category{}, err
	}
	if len(attrsCipher) > 0 {
		if err := crypto.OpenJSON(crypto.Sealed{Ciphertext: attrsCipher, IV: attrsIV, Tag: attrsTag,
			KeyVersion: keyVersion}, s.DataKey, ownerID, "category_attributes", &c.Attributes); err != nil {
			return Category{}, err
		}
	}
	return c, nil
}

func categoryMonthsTx(ctx context.Context, tx *sql.Tx, ownerID, categoryID string) ([]string, error) {
	rows, err := tx.QueryContext(ctx, `SELECT DISTINCT month_id FROM transactions
		WHERE owner_id = ? AND category_id = ? AND deleted_at IS NULL`, ownerID, categoryID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var months []string
	for rows.Next() {
		var month string
		if err := rows.Scan(&month); err != nil {
			return nil, err
		}
		months = append(months, month)
	}
	return months, rows.Err()
}

func (s *Store) sealAttributes(ctx context.Context, ownerID, purpose string, attributes map[string]any) (crypto.Sealed, error) {
	if len(attributes) == 0 {
		return crypto.Sealed{}, nil
	}
	if err := validateAttributes(attributes); err != nil {
		return crypto.Sealed{}, err
	}
	return crypto.SealJSON(attributes, s.DataKey, s.KeyVersion, ownerID, purpose)
}

func validKind(kind string) bool {
	return kind == "INCOME" || kind == "EXPENSE" || kind == "TRANSFER" || kind == "UNCATEGORIZED"
}

func containsFold(haystack, needle string) bool {
	return strings.Contains(strings.ToLower(haystack), strings.ToLower(needle))
}
