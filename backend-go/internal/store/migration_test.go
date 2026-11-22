package store

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/pam/recap/backend-go/internal/config"
)

func TestMigrateTwiceAndLegacyRows(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pam.db")
	ctx := context.Background()
	db, err := Open(ctx, config.Database{URL: "file:" + path})
	if err != nil {
		t.Fatal(err)
	}
	db.Close()
	db, err = Open(ctx, config.Database{URL: "file:" + path})
	if err != nil {
		t.Fatalf("second boot must be a no-op: %v", err)
	}
	defer db.Close()
	var count int
	if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM schema_migrations`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 10 {
		t.Fatalf("migrations = %d", count)
	}
	// The last-active-admin trigger must exist on the local engine.
	s := New(db, make([]byte, 32), 1)
	now := time.Now()
	_, err = s.CreateAccount(ctx, "admin@example.test", "Admin",
		PasswordDigest{Salt: []byte("salt"), Hash: []byte("hash"), KDF: "scrypt:N=32768,r=8,p=1"}, now)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.ExecContext(ctx, `UPDATE users SET role = 'ADMIN' WHERE email = ?`, "admin@example.test"); err != nil {
		t.Fatal(err)
	}
	if _, err := db.ExecContext(ctx, `UPDATE users SET role = 'USER' WHERE email = ?`, "admin@example.test"); err == nil {
		t.Fatal("last active admin was demoted")
	}
}
