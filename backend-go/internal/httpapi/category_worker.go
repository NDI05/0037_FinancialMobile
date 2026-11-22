package httpapi

import (
	"context"
	"sync"
	"time"

	"github.com/pam/recap/backend-go/internal/store"
)

// CategoryWorker is the durable recovery loop for classification jobs. It reclaims PENDING jobs
// and RUNNING jobs whose lease expired (the owning request/process died), runs them through the
// same pipeline as the synchronous API path, and applies the bounded transient-retry policy:
// a job that fails transiently while its retry_count is 0 is requeued exactly once; a second
// transient failure finishes the job FAILED. Orphaned RESERVED reservations (process died between
// reserve and settle) are swept back into the ledger so spend is never silently lost.
type CategoryWorker struct {
	server *Server
	stop   chan struct{}
	wg     sync.WaitGroup
	swept  time.Time
}

func NewCategoryWorker(s *Server) *CategoryWorker {
	return &CategoryWorker{server: s, stop: make(chan struct{})}
}

// Start launches the worker loop. Call Stop before process exit for a clean handoff.
func (w *CategoryWorker) Start() {
	w.wg.Add(1)
	go w.loop()
}

func (w *CategoryWorker) Stop() {
	if w.stop == nil {
		return
	}
	close(w.stop)
	w.wg.Wait()
}

func (w *CategoryWorker) loop() {
	defer w.wg.Done()
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-w.stop:
			return
		case <-ticker.C:
			w.tick()
		}
	}
}

func (w *CategoryWorker) tick() {
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	s := w.server
	now := s.Now()
	if now.Sub(w.swept) >= 15*time.Second {
		if n, err := s.Store.SweepOrphanCategoryReservations(ctx, now.Add(-2*store.CategoryLeaseDuration), now); err != nil {
			s.Log.Error("category worker orphan sweep failed", "error", err.Error())
		} else if n > 0 {
			s.Log.Info("category worker swept orphan reservations", "count", n)
		}
		w.swept = now
	}
	jobs, err := s.Store.ClaimCategoryJobsForRecovery(ctx, now, store.CategoryLeaseDuration, 8)
	if err != nil {
		s.Log.Error("category worker recovery claim failed", "error", err.Error())
		return
	}
	for _, job := range jobs {
		if err := w.processRecovered(ctx, job); err != nil {
			s.Log.Error("category worker job failed", "jobId", job.ID, "error", err.Error())
		}
	}
}

// processRecovered drives one reclaimed job to a terminal state or a bounded requeue. Job
// ownership is enforced by every owner-scoped store access inside the pipeline: an owner never
// observes or executes another owner's jobs.
func (w *CategoryWorker) processRecovered(ctx context.Context, job store.CategoryJob) error {
	s := w.server
	prepared, eligible, err := s.prepareForClassify(ctx, job.OwnerID, job)
	if err != nil {
		return err
	}
	if !eligible {
		return nil // job finished with a typed error code
	}
	_, _, err = s.classifyGroup(ctx, job.OwnerID, []preparedCategory{prepared})
	return err
}