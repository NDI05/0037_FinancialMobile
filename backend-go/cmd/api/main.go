// PAM API — Go service.
//
// One modular service: net/http + database/sql + remote libSQL. It replaces the Node backend for
// the v0.4 target; the Node tree remains the behavioural reference until parity is accepted.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/pam/recap/backend-go/internal/auth"
	"github.com/pam/recap/backend-go/internal/categoryllm"
	"github.com/pam/recap/backend-go/internal/config"
	"github.com/pam/recap/backend-go/internal/httpapi"
	"github.com/pam/recap/backend-go/internal/llm"
	"github.com/pam/recap/backend-go/internal/store"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	level := slog.LevelInfo
	if raw := os.Getenv("LOG_LEVEL"); raw == "debug" {
		level = slog.LevelDebug
	}
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level}))
	slog.SetDefault(logger)

	cfg, err := config.Load(nil)
	if err != nil {
		return fmt.Errorf("configuration: %w", err)
	}
	for _, warning := range cfg.Warnings {
		logger.Warn("config", "warning", warning)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	db, err := store.Open(ctx, cfg.Database)
	if err != nil {
		return fmt.Errorf("database: %w", err)
	}
	storeLayer := store.New(db, cfg.DataKey, cfg.KeyVersion)
	defer storeLayer.Close()

	issuer := auth.NewIssuer(cfg.JWT.Secret, cfg.JWT.Issuer, cfg.JWT.Audience, cfg.JWT.AccessTTL)
	// The deterministic provider keeps local runs network-free; the OpenAI-compatible adapter is
	// used only when explicitly configured (Ollama/OpenAI/OpenRouter).
	var provider llm.Provider = &llm.Fake{Scenario: cfg.LLM.FakeScenario}
	if cfg.LLM.Provider == "openai" {
		provider = llm.NewOpenAI(cfg.LLM.BaseURL, cfg.LLM.APIKey, cfg.LLM.Model,
			cfg.LLM.MaxTokens, cfg.LLM.Timeout)
	}
	api := httpapi.New(storeLayer, cfg, issuer, logger, provider)
	if cfg.CategoryLLM.Enabled {
		api.CategoryClassifier = categoryllm.NewAzure(cfg.CategoryLLM.BaseURL, cfg.CategoryLLM.APIKey,
			cfg.CategoryLLM.Deployment, cfg.CategoryLLM.Model, cfg.CategoryLLM.Timeout)
	}
	// Durable recovery worker: reclaims jobs whose owning request/process died, applies the
	// bounded transient-retry policy and sweeps orphaned reservations.
	worker := httpapi.NewCategoryWorker(api)
	if cfg.CategoryLLM.Enabled {
		worker.Start()
	}
	server := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.Port),
		Handler:           api.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      35 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errs := make(chan error, 1)
	go func() {
		logger.Info("started", "port", cfg.Port, "env", cfg.Env)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errs <- err
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	select {
	case err := <-errs:
		return err
	case <-stop:
		logger.Info("shutdown_requested")
	}
	// STOP the worker first so it cannot grab provider slots while HTTP drains; jobs it held are
	// reclaimed by the next process after their lease expires (recovery path, not data loss).
	if cfg.CategoryLLM.Enabled {
		worker.Stop()
	}
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	return server.Shutdown(shutdownCtx)
}
