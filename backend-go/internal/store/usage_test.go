package store

import (
	"context"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/pam/recap/backend-go/internal/config"
)

func testUsageStore(t *testing.T) *Store {
	t.Helper()
	db, err := Open(context.Background(), config.Database{
		URL: "file:" + filepath.Join(t.TempDir(), "usage.db"),
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

func TestProviderReservationChecksUsedPlusNext(t *testing.T) {
	s := testUsageStore(t)
	now := time.Date(2026, 11, 22, 5, 0, 0, 0, time.UTC)
	ok, err := s.ReserveProviderCost(context.Background(), .001, .0006, now)
	if err != nil || !ok {
		t.Fatalf("first reservation: ok=%v err=%v", ok, err)
	}
	ok, err = s.ReserveProviderCost(context.Background(), .001, .0006, now)
	if err != nil || ok {
		t.Fatalf("second reservation should not fit: ok=%v err=%v", ok, err)
	}
	// Exactly equal to the ceiling is allowed; one cent more is not.
	s2 := testUsageStore(t)
	if ok, err := s2.ReserveProviderCost(context.Background(), .001, .001, now); err != nil || !ok {
		t.Fatalf("exact ceiling reservation: ok=%v err=%v", ok, err)
	}
	if ok, err := s2.ReserveProviderCost(context.Background(), .001, .000001, now); err != nil || ok {
		t.Fatalf("over-ceiling reservation: ok=%v err=%v", ok, err)
	}
}

func TestProviderReservationConcurrentBudget(t *testing.T) {
	s := testUsageStore(t)
	now := time.Date(2026, 11, 22, 5, 0, 0, 0, time.UTC)
	var wg sync.WaitGroup
	results := make(chan bool, 12)
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ok, err := s.ReserveProviderCost(context.Background(), .001, .0002, now)
			if err != nil {
				t.Errorf("reservation: %v", err)
			}
			results <- ok
		}()
	}
	wg.Wait()
	close(results)
	accepted := 0
	for ok := range results {
		if ok {
			accepted++
		}
	}
	if accepted != 5 {
		t.Fatalf("accepted reservations = %d, want 5", accepted)
	}
	day, _ := JakartaBuckets(now)
	snapshot, err := s.UsageSnapshot(context.Background(), "*", "day:"+day)
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.CostUSD < .000999 || snapshot.CostUSD > .001001 {
		t.Fatalf("reserved cost = %f, want .001", snapshot.CostUSD)
	}
}

func TestUsageDayBucketIsJakarta(t *testing.T) {
	day, hour := JakartaBuckets(time.Date(2026, 11, 21, 17, 30, 0, 0, time.UTC))
	if day != "2026-11-22" || hour != "2026-11-22T00" {
		t.Fatalf("bucket = %s/%s", day, hour)
	}
}
