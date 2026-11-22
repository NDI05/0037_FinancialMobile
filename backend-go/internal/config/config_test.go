package config

import (
	"crypto/sha256"
	"encoding/base64"
	"strings"
	"testing"
)

func productionEnv() map[string]string {
	key := sha256.Sum256([]byte("synthetic-production-data-key"))
	return map[string]string{
		"APP_ENV": "production", "DATABASE_URL": "file:./data/prod-test.db",
		"REGISTRATION_INVITE_CODE": "production-invite",
		"JWT_SECRET":               "synthetic-production-jwt-secret-32-bytes",
		"REFRESH_PEPPER":           "synthetic-production-refresh-pepper-32",
		"DATA_ENCRYPTION_KEY":      base64.StdEncoding.EncodeToString(key[:]),
	}
}

func TestProductionRequiresRealEncryptionKey(t *testing.T) {
	valid := productionEnv()
	if _, err := Load(valid); err != nil {
		t.Fatalf("valid production config rejected: %v", err)
	}
	cases := []struct {
		name   string
		mutate func(map[string]string)
	}{
		{"missing", func(env map[string]string) { delete(env, "DATA_ENCRYPTION_KEY") }},
		{"bad base64", func(env map[string]string) { env["DATA_ENCRYPTION_KEY"] = "not-base64!" }},
		{"wrong length", func(env map[string]string) {
			env["DATA_ENCRYPTION_KEY"] = base64.StdEncoding.EncodeToString([]byte("short"))
		}},
		{"development key", func(env map[string]string) {
			sum := sha256.Sum256([]byte("pam-dev-data-key"))
			env["DATA_ENCRYPTION_KEY"] = base64.StdEncoding.EncodeToString(sum[:])
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			env := productionEnv()
			tc.mutate(env)
			_, err := Load(env)
			if err == nil {
				t.Fatal("production accepted an unsafe encryption key")
			}
			if !strings.Contains(err.Error(), "DATA_ENCRYPTION_KEY") {
				t.Fatalf("error must name the setting: %v", err)
			}
		})
	}
}

func TestProductionRejectsDevelopmentAuthConstants(t *testing.T) {
	cases := []struct {
		name  string
		key   string
		value string
	}{
		{"jwt default", "JWT_SECRET", defaultJWTSecret},
		{"jwt short", "JWT_SECRET", "short"},
		{"refresh default", "REFRESH_PEPPER", defaultRefreshPepper},
		{"invite default", "REGISTRATION_INVITE_CODE", defaultInviteCode},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			env := productionEnv()
			env[tc.key] = tc.value
			if _, err := Load(env); err == nil {
				t.Fatalf("production accepted unsafe %s", tc.key)
			}
		})
	}
}

func TestDevelopmentUsesExplicitDefaultsAndWarns(t *testing.T) {
	cfg, err := Load(map[string]string{"APP_ENV": "development"})
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.DataKey) != 32 {
		t.Fatalf("data key length = %d", len(cfg.DataKey))
	}
	if len(cfg.Warnings) == 0 {
		t.Fatal("development should warn about default secrets")
	}
	if cfg.Limits.UnknownCallUSD <= 0 {
		t.Fatal("unknown provider usage must have a conservative cost")
	}
}

func TestCostConfigurationRejectsInvalidValues(t *testing.T) {
	cases := []map[string]string{
		{"DAILY_AI_CEILING_USD": "0"},
		{"DAILY_AI_CEILING_USD": "NaN"},
		{"UNKNOWN_AI_CALL_COST_USD": "-1"},
		{"PRICE_PROMPT_PER_1K_USD": "+Inf"},
		{"LLM_MAX_INPUT_TOKENS": "0"},
		{"LLM_MAX_OUTPUT_TOKENS": "100001"},
	}
	for _, extra := range cases {
		env := map[string]string{"APP_ENV": "test"}
		for k, v := range extra {
			env[k] = v
		}
		if _, err := Load(env); err == nil {
			t.Fatalf("accepted invalid cost configuration %v", extra)
		}
	}
	cfg, err := Load(map[string]string{"APP_ENV": "test"})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Limits.MaxCallCostUSD <= 0 {
		t.Fatalf("max call cost = %f", cfg.Limits.MaxCallCostUSD)
	}
}

func TestCategoryLLMFailsClosedWithoutVerifiedPrices(t *testing.T) {
	env := map[string]string{"CATEGORY_LLM_ENABLED": "true", "AZURE_CLASSIFIER_BASE_URL": "https://example.test/openai/v1",
		"AZURE_CLASSIFIER_DEPLOYMENT": "d", "AZURE_CLASSIFIER_API_KEY": "k"}
	if _, err := Load(env); err == nil {
		t.Fatal("enabled category LLM accepted without verified prices")
	}
	env["CATEGORY_LLM_PRICE_PROMPT_PER_1K_USD"] = "0.001"
	env["CATEGORY_LLM_PRICE_COMPLETION_PER_1K_USD"] = "0.002"
	if _, err := Load(env); err != nil {
		t.Fatalf("valid category LLM config rejected: %v", err)
	}
}
