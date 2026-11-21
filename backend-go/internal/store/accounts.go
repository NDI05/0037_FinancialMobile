package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/pam/recap/backend-go/internal/crypto"
)

type Account struct {
	OwnerID      string
	Email        string
	DisplayName  string
	Salt         []byte
	PasswordHash []byte
	KDF          string
	Role         string
	Status       string
	AuthVersion  int
	Revision     int
	CreatedAt    time.Time
}

type User struct {
	OwnerID     string
	Email       string
	DisplayName string
	Role        string
	Status      string
	AuthVersion int
	Revision    int
}

type PasswordDigest struct {
	Salt []byte
	Hash []byte
	KDF  string
}

type builtinCategory struct {
	Code  string
	Label string
	Kind  string
	Color string
}

var builtinCategories = []builtinCategory{
	{"income", "Pemasukan", "INCOME", "income_mint"},
	{"housing", "Tempat tinggal", "EXPENSE", "violet_deep"},
	{"food", "Makanan", "EXPENSE", "amber"},
	{"transport", "Transportasi", "EXPENSE", "brand_magenta"},
	{"utilities", "Utilitas", "EXPENSE", "violet_soft"},
	{"shopping", "Belanja", "EXPENSE", "brand_pink"},
	{"health", "Kesehatan", "EXPENSE", "rose"},
	{"education", "Pendidikan", "EXPENSE", "amber_deep"},
	{"other_expense", "Pengeluaran lain", "EXPENSE", "neutral"},
	{"transfer", "Transfer internal", "TRANSFER", "slate"},
	{"uncategorized", "Belum berkategori", "UNCATEGORIZED", "warning"},
}

// CreateAccount inserts the account, its 11 built-in categories and the per-account fingerprint
// key in one transaction. A duplicate email returns ErrEmailExists without leaking details.
func (s *Store) CreateAccount(ctx context.Context, email, displayName string, digest PasswordDigest, now time.Time) (Account, error) {
	ownerID := OwnerIDForEmail(email)
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return Account{}, err
	}
	defer func() { _ = tx.Rollback() }()

	_, err = tx.ExecContext(ctx, `INSERT INTO users
		(owner_id, email, display_name, salt, password_hash, kdf, created_at, last_seen_at,
		 role, status, auth_version, revision, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'USER', 'ACTIVE', 0, 0, ?)`,
		ownerID, NormalizeEmail(email), nullString(displayName), digest.Salt, digest.Hash,
		digest.KDF, nowString(now), nowString(now), nowString(now))
	if err != nil {
		if isUnique(err.Error()) {
			return Account{}, ErrEmailExists
		}
		return Account{}, fmt.Errorf("insert account: %w", err)
	}

	for _, category := range builtinCategories {
		label, err := crypto.SealJSON(category.Label, s.DataKey, s.KeyVersion, ownerID, "category_label")
		if err != nil {
			return Account{}, err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO categories
			(id, owner_id, code, kind, label_ciphertext, label_iv, label_tag, color,
			 is_builtin, key_version, revision, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 0, ?, ?)`,
			NewID("cat"), ownerID, category.Code, category.Kind, label.Ciphertext, label.IV,
			label.Tag, category.Color, s.KeyVersion, nowString(now), nowString(now)); err != nil {
			return Account{}, fmt.Errorf("seed category %s: %w", category.Code, err)
		}
	}

	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return Account{}, err
	}
	sealed, err := crypto.Seal(key, s.DataKey, s.KeyVersion, ownerID, "fingerprint")
	if err != nil {
		return Account{}, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO account_keys
		(owner_id, key_version, fingerprint_ciphertext, fingerprint_iv, fingerprint_tag, created_at)
		VALUES (?, ?, ?, ?, ?, ?)`,
		ownerID, s.KeyVersion, sealed.Ciphertext, sealed.IV, sealed.Tag, nowString(now)); err != nil {
		return Account{}, err
	}

	if err := tx.Commit(); err != nil {
		return Account{}, err
	}
	return s.FindAccountByEmail(ctx, email)
}

func (s *Store) FindAccountByEmail(ctx context.Context, email string) (Account, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT owner_id, email, display_name, salt, password_hash, kdf,
		role, status, auth_version, revision, created_at
		FROM users WHERE email = ?`, NormalizeEmail(email))
	return scanAccount(row)
}

func (s *Store) GetUser(ctx context.Context, ownerID string) (User, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT owner_id, email, display_name, role, status,
		auth_version, revision FROM users WHERE owner_id = ?`, ownerID)
	var user User
	var display sql.NullString
	err := row.Scan(&user.OwnerID, &user.Email, &display, &user.Role, &user.Status,
		&user.AuthVersion, &user.Revision)
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrNotFound
	}
	if err != nil {
		return User{}, err
	}
	user.DisplayName = display.String
	return user, nil
}

func (s *Store) TouchAccount(ctx context.Context, ownerID string, at time.Time) error {
	_, err := s.DB.ExecContext(ctx, `UPDATE users SET last_seen_at = ? WHERE owner_id = ?`, nowString(at), ownerID)
	return err
}

// FingerprintKey returns the per-account HMAC key after login. The raw key is never stored.
func (s *Store) FingerprintKey(ctx context.Context, ownerID string) ([]byte, int, error) {
	row := s.DB.QueryRowContext(ctx, `SELECT key_version, fingerprint_ciphertext, fingerprint_iv,
		fingerprint_tag FROM account_keys WHERE owner_id = ?`, ownerID)
	var keyVersion int
	var ciphertext, iv, tag []byte
	if err := row.Scan(&keyVersion, &ciphertext, &iv, &tag); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, 0, ErrNotFound
		}
		return nil, 0, err
	}
	key, err := crypto.Open(crypto.Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag, KeyVersion: keyVersion},
		s.DataKey, ownerID, "fingerprint")
	if err != nil {
		return nil, 0, err
	}
	return key, keyVersion, nil
}

func scanAccount(row *sql.Row) (Account, error) {
	var account Account
	var display sql.NullString
	var createdAt string
	err := row.Scan(&account.OwnerID, &account.Email, &display, &account.Salt,
		&account.PasswordHash, &account.KDF, &account.Role, &account.Status,
		&account.AuthVersion, &account.Revision, &createdAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Account{}, ErrNotFound
	}
	if err != nil {
		return Account{}, err
	}
	account.DisplayName = display.String
	account.CreatedAt = parseTime(createdAt)
	return account, nil
}

func nullString(value string) any {
	if value == "" {
		return nil
	}
	return value
}
