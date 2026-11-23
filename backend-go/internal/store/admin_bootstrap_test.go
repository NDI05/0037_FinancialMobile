package store

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/pam/recap/backend-go/internal/config"
)

func testAdminStore(t *testing.T) *Store {
	t.Helper()
	db, err := Open(context.Background(), config.Database{
		URL: "file:" + filepath.Join(t.TempDir(), "admin.db"),
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	if err := Migrate(context.Background(), db, time.Now); err != nil {
		t.Fatal(err)
	}
	return New(db, make([]byte, 32), 1)
}

func TestBootstrapAdminIsIdempotentAndAudited(t *testing.T) {
	ctx := context.Background()
	s := testAdminStore(t)
	now := time.Date(2026, 11, 23, 8, 0, 0, 0, time.UTC)
	account, err := s.CreateAccount(ctx, "ops@example.id", "Ops", PasswordDigest{Salt: []byte("s"), Hash: []byte("h"), KDF: "test"}, now)
	if err != nil {
		t.Fatal(err)
	}
	if account.Role != "USER" {
		t.Fatalf("new accounts must be USER, got %q", account.Role)
	}

	promoted, changed, err := s.BootstrapAdmin(ctx, "ops@example.id", now)
	if err != nil {
		t.Fatal(err)
	}
	if !changed || promoted.Role != "ADMIN" {
		t.Fatalf("first bootstrap must promote: changed=%v role=%q", changed, promoted.Role)
	}

	again, changed, err := s.BootstrapAdmin(ctx, "OPS@example.id", now)
	if err != nil {
		t.Fatal(err)
	}
	if changed || again.Role != "ADMIN" {
		t.Fatalf("second bootstrap must be a no-op: changed=%v role=%q", changed, again.Role)
	}

	page, err := s.ListAudit(ctx, account.OwnerID, "bootstrap_admin", 10, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 {
		t.Fatalf("want exactly one bootstrap audit row, got %d", len(page.Items))
	}
	if page.Items[0].Metadata["source"] != "ops-cli" {
		t.Fatalf("audit must record the operator source, got %#v", page.Items[0].Metadata)
	}

	if _, _, err := s.BootstrapAdmin(ctx, "missing@example.id", now); err == nil {
		t.Fatal("unknown email must fail rather than creating an account")
	}
}
