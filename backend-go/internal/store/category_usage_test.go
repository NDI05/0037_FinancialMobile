package store

import (
	"context"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/pam/recap/backend-go/internal/config"
)

func openCategoryStore(t *testing.T) (*Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "cat.db")
	db, err := Open(context.Background(), config.Database{URL: "file:" + path})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return New(db, make([]byte, 32), 1), path
}

func TestCategoryBudgetIsGlobalAcrossOwnersAndSurvivesRestart(t *testing.T) {
	s, path := openCategoryStore(t)
	ctx := context.Background()
	now := time.Date(2026, 11, 22, 12, 0, 0, 0, time.UTC)
	id, ok, err := s.ReserveCategoryCall(ctx, "owner-a", 20, 100, 100, 0.10, 0.06, now)
	if err != nil || !ok || id == "" {
		t.Fatalf("first reserve ok=%v err=%v", ok, err)
	}
	if _, ok, _ := s.ReserveCategoryCall(ctx, "owner-b", 20, 100, 100, 0.10, 0.06, now); ok {
		t.Fatal("global monthly cap was bypassed by another owner")
	}
	if err := s.SettleCategoryReservation(ctx, "owner-a", id, 0.05, 0.05, true, now); err != nil {
		t.Fatal(err)
	}
	// A second owner can use the remaining global monthly budget, not the full cap.
	if _, ok, _ := s.ReserveCategoryCall(ctx, "owner-b", 20, 100, 100, 0.10, 0.06, now); ok {
		t.Fatal("reservation ignored prior global actual spend")
	}
	s.Close()
	db, err := Open(ctx, config.Database{URL: "file:" + path})
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	reopened := New(db, make([]byte, 32), 1)
	if _, ok, _ := reopened.ReserveCategoryCall(ctx, "owner-c", 20, 100, 100, 0.10, 0.06, now); ok {
		t.Fatal("restart reset global budget")
	}
}

func TestCategoryBudgetConcurrentReservationsCannotOverspend(t *testing.T) {
	s, _ := openCategoryStore(t)
	ctx := context.Background()
	now := time.Date(2026, 11, 22, 12, 0, 0, 0, time.UTC)
	var wg sync.WaitGroup
	var mu sync.Mutex
	allowed := 0
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			_, ok, err := s.ReserveCategoryCall(ctx, "owner-"+string(rune('a'+i)), 20, 100, 100, 0.10, 0.03, now)
			if err != nil {
				t.Errorf("reserve: %v", err)
				return
			}
			if ok {
				mu.Lock()
				allowed++
				mu.Unlock()
			}
		}(i)
	}
	wg.Wait()
	if allowed != 3 {
		t.Fatalf("allowed=%d, expected exactly 3 within 0.10", allowed)
	}
}
