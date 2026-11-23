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
	if count != 13 {
		t.Fatalf("migrations = %d", count)
	}
	// 012 is additive: existing rows get the empty mapping version and an empty lease token, which
	// is exactly what makes them a cache miss and an invalid lease holder. 013 adds the automatic
	// queue clock and coalescing key with the same additive property.
	var legacyMapping, legacyToken string
	if err := db.QueryRowContext(ctx, `SELECT COALESCE((SELECT mapping_version FROM category_cache LIMIT 1),''),
		COALESCE((SELECT lease_token FROM category_jobs LIMIT 1),'')`).Scan(&legacyMapping, &legacyToken); err != nil {
		t.Fatalf("012 columns missing: %v", err)
	}
	var legacyQueueKey string
	if err := db.QueryRowContext(ctx, `SELECT COALESCE((SELECT merchant_key FROM category_jobs LIMIT 1),'')`).Scan(&legacyQueueKey); err != nil {
		t.Fatalf("013 column missing: %v", err)
	}
	var queueStates int
	if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM category_queue_state`).Scan(&queueStates); err != nil {
		t.Fatalf("013 table missing: %v", err)
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
