// Package store owns the libSQL schema and all owner-scoped persistence.
package store

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/config"
	"github.com/tursodatabase/libsql-client-go/libsql"
	_ "modernc.org/sqlite"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Open connects to libSQL (remote) or a local file through the same database/sql driver.
func Open(ctx context.Context, database config.Database) (*sql.DB, error) {
	url := database.URL
	if strings.HasPrefix(url, "file:") && !strings.Contains(url, "?") {
		// Foreign keys are off by default in SQLite. The migrations and acceptance tests rely on
		// them; busy_timeout keeps concurrent writers from failing immediately.
		url += "?_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)"
	}
	opts := []libsql.Option{}
	if database.AuthToken != "" {
		opts = append(opts, libsql.WithAuthToken(database.AuthToken))
	}
	connector, err := libsql.NewConnector(url, opts...)
	if err != nil {
		return nil, fmt.Errorf("open libsql connector: %w", err)
	}
	db := sql.OpenDB(connector)
	if strings.HasPrefix(url, "libsql://") || strings.HasPrefix(url, "https://") || strings.HasPrefix(url, "wss://") {
		db.SetMaxOpenConns(8)
	} else {
		db.SetMaxOpenConns(1)
	}
	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("ping database: %w", err)
	}
	if err := Migrate(ctx, db, time.Now); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

// Migration is one ordered schema version and its statements.
type Migration struct {
	Version    int
	Statements []string
}

// LoadMigrations reads the embedded SQL copied from the applied Node history plus the v0.4
// additions. Go becomes the single migration runner from this point; the Node files stay as
// historical reference and are not applied to a Go-owned database.
func LoadMigrations() ([]Migration, error) {
	entries, err := migrationFiles.ReadDir("migrations")
	if err != nil {
		return nil, err
	}
	byVersion := map[int][]string{}
	triggerByVersion := map[int][]string{}
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasSuffix(name, ".sql") {
			continue
		}
		version, err := strconv.Atoi(strings.SplitN(name, ".", 2)[0])
		if err != nil {
			return nil, fmt.Errorf("migration file %s: %w", name, err)
		}
		raw, err := migrationFiles.ReadFile("migrations/" + name)
		if err != nil {
			return nil, err
		}
		if strings.HasSuffix(name, ".triggers.sql") {
			// Trigger bodies contain semicolons, so these files are split explicitly by marker
			// rather than by the SQL splitter.
			for _, block := range strings.Split(string(raw), "-- @statement") {
				if trimmed := strings.TrimSpace(block); trimmed != "" {
					triggerByVersion[version] = append(triggerByVersion[version], trimmed)
				}
			}
			continue
		}
		byVersion[version] = append(byVersion[version], splitSQL(string(raw))...)
	}
	versions := make([]int, 0, len(byVersion))
	for version := range byVersion {
		versions = append(versions, version)
	}
	sort.Ints(versions)
	migrations := make([]Migration, 0, len(versions))
	for _, version := range versions {
		statements := append([]string{}, byVersion[version]...)
		statements = append(statements, triggerByVersion[version]...)
		migrations = append(migrations, Migration{Version: version, Statements: statements})
	}
	return migrations, nil
}

// Migrate applies missing versions in order, each in its own transaction.
func Migrate(ctx context.Context, db *sql.DB, now func() time.Time) error {
	if _, err := db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version    INTEGER PRIMARY KEY,
		applied_at TEXT NOT NULL
	)`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}
	applied, err := appliedVersions(ctx, db)
	if err != nil {
		return err
	}
	migrations, err := LoadMigrations()
	if err != nil {
		return err
	}
	for _, migration := range migrations {
		if applied[migration.Version] {
			continue
		}
		tx, err := db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		failed := true
		defer func() {
			if failed {
				_ = tx.Rollback()
			}
		}()
		for _, statement := range migration.Statements {
			if strings.TrimSpace(statement) == "" {
				continue
			}
			if _, err := tx.ExecContext(ctx, statement); err != nil {
				return fmt.Errorf("migration %d: %w", migration.Version, err)
			}
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)`,
			migration.Version, now().UTC().Format(time.RFC3339Nano)); err != nil {
			return fmt.Errorf("record migration %d: %w", migration.Version, err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit migration %d: %w", migration.Version, err)
		}
		failed = false
	}
	return nil
}

func appliedVersions(ctx context.Context, db *sql.DB) (map[int]bool, error) {
	rows, err := db.QueryContext(ctx, `SELECT version FROM schema_migrations ORDER BY version`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	applied := map[int]bool{}
	for rows.Next() {
		var version int
		if err := rows.Scan(&version); err != nil {
			return nil, err
		}
		applied[version] = true
	}
	return applied, rows.Err()
}

// splitSQL removes line comments, splits on semicolons, and keeps a CREATE TRIGGER block intact.
func splitSQL(sqlText string) []string {
	lines := strings.Split(sqlText, "\n")
	for i, line := range lines {
		if idx := strings.Index(line, "--"); idx >= 0 {
			lines[i] = line[:idx]
		}
	}
	raw := strings.Split(strings.Join(lines, "\n"), ";")
	var statements []string
	for i := 0; i < len(raw); i++ {
		part := strings.TrimSpace(raw[i])
		if part == "" {
			continue
		}
		upper := strings.ToUpper(part)
		if strings.HasPrefix(upper, "CREATE TRIGGER") && !strings.Contains(upper, "END") {
			for i+1 < len(raw) {
				i++
				part += ";" + raw[i]
				if strings.Contains(strings.ToUpper(raw[i]), "END") {
					break
				}
			}
		}
		statements = append(statements, strings.TrimSpace(part))
	}
	return statements
}
