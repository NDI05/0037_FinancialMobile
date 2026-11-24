// Package config loads the PAM backend configuration from the environment.
//
// Development/test has deterministic defaults so a local run needs no external service.
// Production refuses the development secrets instead of silently using them.
package config

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"math"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	DefaultPort           = 8080
	DefaultAccessTTL      = 15 * time.Minute
	DefaultRefreshTTLDays = 30
	DefaultDataKeyVersion = 1
	defaultInviteCode     = "pam-dev"
	defaultJWTSecret      = "dev-jwt-secret-not-for-production-use-32b"
	defaultRefreshPepper  = "dev-refresh-pepper-not-for-production-use"
	defaultDatabaseURL    = "file:./data/pam.db"
)

// Config is the complete runtime configuration. Secrets are kept only in this process.
type Config struct {
	Env                string
	Port               int
	LogLevel           string
	Database           Database
	InviteCode         string
	InternalSweepToken string
	JWT                JWT
	DataKey            []byte
	KeyVersion         int
	LLM                LLM
	CategoryLLM        CategoryLLM
	Limits             Limits
	Warnings           []string
}

type Database struct {
	URL       string
	AuthToken string
}

type JWT struct {
	Secret        []byte
	Issuer        string
	Audience      string
	AccessTTL     time.Duration
	RefreshTTL    time.Duration
	RefreshPepper string
}

type LLM struct {
	Provider       string
	BaseURL        string
	APIKey         string
	Model          string
	MaxTokens      int
	MaxInputTokens int
	Timeout        time.Duration
	FakeScenario   string
}

type CategoryLLM struct {
	Enabled          bool
	Provider         string
	BaseURL          string
	APIKey           string
	Deployment       string
	Model            string
	ModelVersion     string
	Region           string
	DeploymentType   string
	Timeout          time.Duration
	DailyCeilingUSD  float64
	MonthlyCeilingUSD float64
	OwnerAttemptsDay int
	InstallAttemptsDay int
	PromptPer1K      float64
	CompletionPer1K  float64
}

type Limits struct {
	AnalysesPerHour int
	AnalysesPerDay  int
	DailyCeilingUSD float64
	PromptPer1K     float64
	CompletionPer1K float64
	UnknownCallUSD  float64
	MaxCallCostUSD  float64
}

// Load parses env. It returns an error for invalid values and production-unsafe secrets.
func Load(env map[string]string) (*Config, error) {
	if env == nil {
		env = environ()
	}
	cfg := &Config{
		Env:                get(env, "APP_ENV", get(env, "NODE_ENV", "development")),
		LogLevel:           get(env, "LOG_LEVEL", "info"),
		InviteCode:         get(env, "REGISTRATION_INVITE_CODE", ""),
		InternalSweepToken: get(env, "INTERNAL_SWEEP_TOKEN", ""),
		Database: Database{
			URL:       normalizeDatabaseURL(get(env, "DATABASE_URL", defaultDatabaseURL)),
			AuthToken: get(env, "DATABASE_AUTH_TOKEN", ""),
		},
		JWT: JWT{
			Issuer:        get(env, "JWT_ISSUER", "pam-api"),
			Audience:      get(env, "JWT_AUDIENCE", "pam-android"),
			RefreshPepper: get(env, "REFRESH_PEPPER", ""),
		},
		KeyVersion: intValue(env, "DATA_KEY_VERSION", DefaultDataKeyVersion),
		LLM: LLM{
			Provider:       get(env, "LLM_PROVIDER", "fake"),
			BaseURL:        get(env, "LLM_BASE_URL", "https://api.openai.com/v1"),
			APIKey:         get(env, "LLM_API_KEY", ""),
			Model:          get(env, "LLM_MODEL", "gpt-4o-mini"),
			MaxTokens:      intValue(env, "LLM_MAX_OUTPUT_TOKENS", 800),
			MaxInputTokens: intValue(env, "LLM_MAX_INPUT_TOKENS", 4000),
			Timeout:        time.Duration(intValue(env, "LLM_TIMEOUT_MS", 45000)) * time.Millisecond,
			FakeScenario:   get(env, "FAKE_LLM_SCENARIO", "valid"),
		},
		CategoryLLM: CategoryLLM{
			Enabled:          boolValue(env, "CATEGORY_LLM_ENABLED", false),
			Provider:         get(env, "CATEGORY_LLM_PROVIDER", "azure"),
			BaseURL:          get(env, "AZURE_CLASSIFIER_BASE_URL", ""),
			APIKey:           get(env, "AZURE_CLASSIFIER_API_KEY", ""),
			Deployment:       get(env, "AZURE_CLASSIFIER_DEPLOYMENT", ""),
			Model:            get(env, "AZURE_CLASSIFIER_MODEL", ""),
			ModelVersion:     get(env, "AZURE_CLASSIFIER_MODEL_VERSION", ""),
			Region:           get(env, "AZURE_CLASSIFIER_REGION", ""),
			DeploymentType:   get(env, "AZURE_CLASSIFIER_DEPLOYMENT_TYPE", ""),
			Timeout:          time.Duration(intValue(env, "CATEGORY_LLM_TIMEOUT_MS", 20000)) * time.Millisecond,
			DailyCeilingUSD:  floatValue(env, "CATEGORY_LLM_DAILY_CEILING_USD", 0.10),
			MonthlyCeilingUSD: floatValue(env, "CATEGORY_LLM_MONTHLY_CEILING_USD", 2.00),
			OwnerAttemptsDay: intValue(env, "CATEGORY_LLM_OWNER_ATTEMPTS_DAY", 20),
			InstallAttemptsDay: intValue(env, "CATEGORY_LLM_INSTALL_ATTEMPTS_DAY", 100),
			PromptPer1K:      floatValue(env, "CATEGORY_LLM_PRICE_PROMPT_PER_1K_USD", 0),
			CompletionPer1K:  floatValue(env, "CATEGORY_LLM_PRICE_COMPLETION_PER_1K_USD", 0),
		},
		Limits: Limits{
			AnalysesPerHour: intValue(env, "RATE_LIMIT_ANALYSES_PER_HOUR", 10),
			AnalysesPerDay:  intValue(env, "RATE_LIMIT_ANALYSES_PER_DAY", 40),
			DailyCeilingUSD: floatValue(env, "DAILY_AI_CEILING_USD", 1),
			PromptPer1K:     floatValue(env, "PRICE_PROMPT_PER_1K_USD", 0.00015),
			CompletionPer1K: floatValue(env, "PRICE_COMPLETION_PER_1K_USD", 0.0006),
			UnknownCallUSD:  floatValue(env, "UNKNOWN_AI_CALL_COST_USD", 0.001),
		},
	}
	port, err := strconv.Atoi(get(env, "PORT", strconv.Itoa(DefaultPort)))
	if err != nil || port < 0 || port > 65535 {
		return nil, fmt.Errorf("PORT must be an integer 0..65535")
	}
	cfg.Port = port

	accessSeconds := intValue(env, "ACCESS_TOKEN_TTL_SECONDS", int(DefaultAccessTTL/time.Second))
	if accessSeconds < 60 || accessSeconds > 900 {
		return nil, fmt.Errorf("ACCESS_TOKEN_TTL_SECONDS must be 60..900 (target <=15 minutes)")
	}
	cfg.JWT.AccessTTL = time.Duration(accessSeconds) * time.Second
	refreshDays := intValue(env, "REFRESH_TOKEN_TTL_DAYS", DefaultRefreshTTLDays)
	if refreshDays < 1 || refreshDays > 365 {
		return nil, fmt.Errorf("REFRESH_TOKEN_TTL_DAYS must be 1..365")
	}
	cfg.JWT.RefreshTTL = time.Duration(refreshDays) * 24 * time.Hour

	rawJWT := get(env, "JWT_SECRET", "")
	rawRefresh := get(env, "REFRESH_PEPPER", "")
	rawDataKey := get(env, "DATA_ENCRYPTION_KEY", "")
	dataKey, dataKeyErr := decodeDataKeyStrict(rawDataKey)
	if rawDataKey != "" && dataKeyErr != nil {
		return nil, fmt.Errorf("DATA_ENCRYPTION_KEY is invalid: %w", dataKeyErr)
	}
	devKeySum := sha256.Sum256([]byte("pam-dev-data-key"))
	devKey := devKeySum[:]

	if cfg.CategoryLLM.Enabled {
		if cfg.CategoryLLM.Provider != "azure" || cfg.CategoryLLM.BaseURL == "" ||
			cfg.CategoryLLM.Deployment == "" || cfg.CategoryLLM.APIKey == "" ||
			cfg.CategoryLLM.PromptPer1K <= 0 || cfg.CategoryLLM.CompletionPer1K <= 0 {
			return nil, fmt.Errorf("invalid category LLM configuration: enabled requires azure endpoint/key and verified prices")
		}
	}
	if cfg.Env == "production" {
		problems := []string{}
		if cfg.InviteCode == "" || cfg.InviteCode == defaultInviteCode {
			problems = append(problems, "REGISTRATION_INVITE_CODE must be set to a non-development value")
		}
		if len(rawJWT) < 32 || rawJWT == defaultJWTSecret {
			problems = append(problems, "JWT_SECRET must be at least 32 bytes and not the development value")
		}
		if len(rawRefresh) < 32 || rawRefresh == defaultRefreshPepper {
			problems = append(problems, "REFRESH_PEPPER must be at least 32 bytes and not the development value")
		}
		if rawDataKey == "" || dataKeyErr != nil {
			problems = append(problems, "DATA_ENCRYPTION_KEY is required and must be base64 for exactly 32 bytes")
		} else if bytes.Equal(dataKey, devKey) {
			problems = append(problems, "DATA_ENCRYPTION_KEY must not be the known development key")
		}
		if len(cfg.Database.AuthToken) == 0 && strings.HasPrefix(cfg.Database.URL, "libsql://") {
			problems = append(problems, "DATABASE_AUTH_TOKEN is required for a libsql:// URL")
		}
		if len(problems) > 0 {
			return nil, fmt.Errorf("invalid production configuration: %s", strings.Join(problems, "; "))
		}
	}
	if cfg.InviteCode == "" {
		cfg.InviteCode = defaultInviteCode
		cfg.Warnings = append(cfg.Warnings, "REGISTRATION_INVITE_CODE missing: using the development invite code")
	}
	if cfg.Env != "production" {
		cfg.Warnings = append(cfg.Warnings, "development secrets are in use; do not run this configuration in production")
	}
	cfg.JWT.Secret = []byte(rawJWT)
	if rawJWT == "" {
		cfg.JWT.Secret = []byte(defaultJWTSecret)
	} else if len(rawJWT) < 32 {
		return nil, fmt.Errorf("JWT_SECRET must be at least 32 bytes")
	}
	cfg.JWT.RefreshPepper = rawRefresh
	if rawRefresh == "" {
		cfg.JWT.RefreshPepper = defaultRefreshPepper
	} else if len(rawRefresh) < 32 {
		return nil, fmt.Errorf("REFRESH_PEPPER must be at least 32 bytes")
	}
	if rawDataKey == "" {
		cfg.DataKey = devKey
		cfg.Warnings = append(cfg.Warnings, "DATA_ENCRYPTION_KEY missing: using the development key")
	} else {
		cfg.DataKey = dataKey
	}
	if cfg.KeyVersion < 1 {
		cfg.KeyVersion = 1
	}
	if cfg.LogLevel != "debug" && cfg.LogLevel != "info" && cfg.LogLevel != "warn" && cfg.LogLevel != "error" {
		return nil, fmt.Errorf("LOG_LEVEL must be debug|info|warn|error")
	}
	switch cfg.LLM.Provider {
	case "fake", "openai":
	case "azure":
		endpoint, err := url.Parse(cfg.LLM.BaseURL)
		if err != nil || endpoint.Scheme != "https" || endpoint.Hostname() == "" ||
			endpoint.User != nil || endpoint.RawQuery != "" || endpoint.Fragment != "" ||
			strings.TrimRight(endpoint.Path, "/") != "/openai/v1" ||
			strings.TrimSpace(cfg.LLM.APIKey) == "" || get(env, "LLM_MODEL", "") == "" ||
			get(env, "PRICE_PROMPT_PER_1K_USD", "") == "" || get(env, "PRICE_COMPLETION_PER_1K_USD", "") == "" ||
			cfg.Limits.PromptPer1K <= 0 || cfg.Limits.CompletionPer1K <= 0 {
			return nil, fmt.Errorf("invalid Azure analysis configuration: requires HTTPS /openai/v1 endpoint, API key, deployment and verified prices")
		}
	default:
		return nil, fmt.Errorf("LLM_PROVIDER must be fake|openai|azure")
	}
	if cfg.LLM.MaxTokens < 1 || cfg.LLM.MaxTokens > 100_000 {
		return nil, fmt.Errorf("LLM_MAX_OUTPUT_TOKENS must be 1..100000")
	}
	if cfg.LLM.MaxInputTokens < 1 || cfg.LLM.MaxInputTokens > 1_000_000 {
		return nil, fmt.Errorf("LLM_MAX_INPUT_TOKENS must be 1..1000000")
	}
	cfg.Limits.MaxCallCostUSD = float64(cfg.LLM.MaxInputTokens)/1000*cfg.Limits.PromptPer1K +
		float64(cfg.LLM.MaxTokens)/1000*cfg.Limits.CompletionPer1K
	for name, value := range map[string]float64{
		"DAILY_AI_CEILING_USD":        cfg.Limits.DailyCeilingUSD,
		"UNKNOWN_AI_CALL_COST_USD":    cfg.Limits.UnknownCallUSD,
		"PRICE_PROMPT_PER_1K_USD":     cfg.Limits.PromptPer1K,
		"PRICE_COMPLETION_PER_1K_USD": cfg.Limits.CompletionPer1K,
	} {
		if math.IsNaN(value) || math.IsInf(value, 0) || value < 0 {
			return nil, fmt.Errorf("%s must be a finite non-negative number", name)
		}
	}
	if cfg.Limits.DailyCeilingUSD <= 0 || cfg.Limits.UnknownCallUSD <= 0 ||
		cfg.Limits.MaxCallCostUSD <= 0 {
		return nil, fmt.Errorf("AI cost limits and prices must be positive")
	}
	return cfg, nil
}

func normalizeDatabaseURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if strings.HasPrefix(raw, "turso://") {
		return "libsql://" + strings.TrimPrefix(raw, "turso://")
	}
	return raw
}

func decodeDataKeyStrict(raw string) ([]byte, error) {
	decoded, err := base64.StdEncoding.DecodeString(raw)
	if err != nil {
		return nil, fmt.Errorf("must be base64")
	}
	if len(decoded) != 32 {
		return nil, fmt.Errorf("must decode to 32 bytes")
	}
	return decoded, nil
}

func environ() map[string]string {
	m := make(map[string]string, len(os.Environ()))
	for _, entry := range os.Environ() {
		key, value, ok := strings.Cut(entry, "=")
		if ok {
			m[key] = value
		}
	}
	return m
}

func get(env map[string]string, key, fallback string) string {
	if v, ok := env[key]; ok && strings.TrimSpace(v) != "" {
		return strings.TrimSpace(v)
	}
	return fallback
}

func boolValue(env map[string]string, key string, fallback bool) bool {
	value, ok := env[key]
	if !ok || value == "" {
		return fallback
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func intValue(env map[string]string, key string, fallback int) int {
	raw := get(env, key, "")
	if raw == "" {
		return fallback
	}
	v, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return v
}

func floatValue(env map[string]string, key string, fallback float64) float64 {
	raw := get(env, key, "")
	if raw == "" {
		return fallback
	}
	v, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return fallback
	}
	return v
}
