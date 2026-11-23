// PAM admin provisioning — controlled ops CLI.
//
// There is intentionally no public bootstrap route: granting ADMIN from a client would be a role
// escalation hole. This command runs with the same database credentials as the API, is idempotent
// and appends an admin_audit row on every real promotion.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/pam/recap/backend-go/internal/config"
	"github.com/pam/recap/backend-go/internal/store"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	email := flag.String("promote", "", "email of the existing account to grant ADMIN")
	flag.Parse()
	if *email == "" {
		return errors.New("usage: pam-admin -promote <email>")
	}

	cfg, err := config.Load(nil)
	if err != nil {
		return fmt.Errorf("configuration: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	db, err := store.Open(ctx, cfg.Database)
	if err != nil {
		return fmt.Errorf("database: %w", err)
	}
	data := store.New(db, cfg.DataKey, cfg.KeyVersion)
	defer data.Close()
	if err := store.Migrate(ctx, db, time.Now); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}

	account, changed, err := data.BootstrapAdmin(ctx, *email, time.Now())
	if err != nil {
		return fmt.Errorf("promote: %w", err)
	}
	state := "already admin"
	if changed {
		state = "promoted"
	}
	fmt.Printf("owner=%s email=%s role=%s changed=%v (%s)\n",
		account.OwnerID, account.Email, account.Role, changed, state)
	return nil
}
