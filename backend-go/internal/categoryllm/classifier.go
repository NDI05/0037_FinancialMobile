// Package categoryllm is the typed merchant-category boundary. It is intentionally separate from
// llm.Provider.Analyze: aggregate analysis never receives merchant data.
package categoryllm

import (
	"context"
	"errors"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	PromptVersion   = "category-prompt/1"
	TaxonomyVersion = "builtin-categories/1"
	// MappingVersion identifies how provider results are attributed to candidates. The durable
	// cache is keyed by it so entries written while results were matched by array position are
	// never trusted again; a new version is a cache miss, not an overwrite of real data.
	MappingVersion = "caseid/1"
	MaxCandidates   = 5
	MaxMerchantRunes = 120
	// MaxInputTokens is the model input ceiling enforced by a deterministic conservative bound: the
	// token estimate of the whole request body (system prompt, user payload, strict JSON schema and
	// request overhead) computed as ceil(UTF-8 bytes / ConservativeBytesPerToken). GPT-OSS uses a
	// byte-level BPE with byte fallback, and representative measured usage (see the usage ledger)
	// runs >= 4 bytes per prompt token; the 2-bytes-per-token floor below keeps every estimate on
	// the hot side. The estimate is NOT a formal proof that real tokens <= 1024 (that would require
	// the vocabulary), so real prompt_tokens from every provider response are kept as the exact
	// ledger value; the estimate is only the deterministic pre-flight gate. Requests that would
	// exceed the cap are rejected before any HTTP call (ErrInputTooLarge) or split
	// (SplitCandidates); a single overlong candidate becomes NEEDS_REVIEW instead of a provider call.
	MaxInputTokens = 1024
	// ConservativeBytesPerToken is the deliberate floor for the byte/token ratio used by the
	// pre-flight estimate. It is half the ~4 bytes/token observed on measured usage, so estimates
	// run hot: a request that passes the gate never passes with a badly underestimated prompt.
	ConservativeBytesPerToken = 2
	// MaxCompletionTokens caps the model output; provider finish_reason == "length" is ErrTruncated.
	MaxCompletionTokens = 512
)

var (
	ErrDisabled      = errors.New("category_llm_disabled")
	ErrInvalidInput  = errors.New("category_llm_invalid_input")
	ErrInvalidOutput = errors.New("category_llm_invalid_output")
	ErrTruncated     = errors.New("category_llm_truncated")
	ErrInputTooLarge = errors.New("category_llm_input_too_large")
	// ErrProviderTransient marks retryable transport/provider failures: network errors, timeouts
	// and HTTP 408/429/5xx. Non-transient classification failures must not be retried.
	ErrProviderTransient = errors.New("category_provider_transient")
)

// IsProviderTransient reports whether err is a retryable provider failure.
func IsProviderTransient(err error) bool { return errors.Is(err, ErrProviderTransient) }

type Candidate struct {
	Merchant  string   `json:"merchant"`
	Direction string   `json:"direction"`
	Method    string   `json:"method,omitempty"`
	Categories []string `json:"categories"`
}

type Result struct {
	CaseID       string `json:"caseId"`
	CategoryCode string `json:"categoryCode"`
	NeedsReview  bool   `json:"needsReview"`
	ReasonCode   string `json:"reasonCode"`
}

type Response struct {
	Results          []Result
	PromptTokens     int64
	CompletionTokens int64
	ReasoningTokens  int64
	UsageKnown       bool
}

type Classifier interface {
	ID() string
	Model() string
	Classify(ctx context.Context, candidates []Candidate) (Response, error)
	// SplitCandidates partitions candidates into groups that each fit the model input cap
	// (MaxInputTokens) under the conservative bound. Callers issue one provider call per group.
	SplitCandidates(candidates []Candidate) [][]Candidate
	// FitsInputBudget reports whether the whole candidate set fits the model input cap, so callers
	// can refuse an oversized batch before any provider call or budget reservation.
	FitsInputBudget(candidates []Candidate) bool
}

// SanitizeMerchant accepts a business merchant label only. It rejects control characters, URLs,
// email/account-like strings and overlong values; callers turn a failure into NEEDS_REVIEW.
func SanitizeMerchant(value string) (string, error) {
	m := strings.TrimSpace(value)
	if m == "" || !utf8.ValidString(m) {
		return "", ErrInvalidInput
	}
	if utf8.RuneCountInString(m) > MaxMerchantRunes {
		return "", ErrInvalidInput
	}
	if strings.Contains(m, "@") || strings.Contains(m, "://") || strings.ContainsAny(m, "\r\n\t") {
		return "", ErrInvalidInput
	}
	digits := 0
	for _, r := range m {
		if unicode.IsControl(r) {
			return "", ErrInvalidInput
		}
		if unicode.IsDigit(r) {
			digits++
		}
		if !(unicode.IsLetter(r) || unicode.IsDigit(r) || unicode.IsSpace(r) ||
			unicode.IsPunct(r) || unicode.IsSymbol(r)) {
			return "", ErrInvalidInput
		}
	}
	if digits > 8 {
		return "", ErrInvalidInput
	}
	return m, nil
}

func ValidateCandidates(candidates []Candidate) error {
	if len(candidates) == 0 || len(candidates) > MaxCandidates {
		return ErrInvalidInput
	}
	for _, c := range candidates {
		if _, err := SanitizeMerchant(c.Merchant); err != nil {
			return err
		}
		if c.Direction != "CREDIT" && c.Direction != "DEBIT" {
			return ErrInvalidInput
		}
		if c.Method != "" && !allowedMethod(c.Method) {
			return ErrInvalidInput
		}
		if len(c.Categories) == 0 || len(c.Categories) > 32 {
			return ErrInvalidInput
		}
		seen := map[string]bool{}
		for _, code := range c.Categories {
			if !allowedCode(code) || seen[code] {
				return ErrInvalidInput
			}
			seen[code] = true
		}
	}
	return nil
}

func allowedMethod(m string) bool {
	switch m {
	case "QRIS", "TRANSFER", "CARD", "CASH", "OTHER":
		return true
	default:
		return false
	}
}

func allowedCode(code string) bool {
	switch code {
	case "housing", "utilities", "transport", "food", "health", "education",
		"shopping", "other_expense", "transfer", "income", "uncategorized":
		return true
	default:
		return false
	}
}

func ValidateResults(results []Result, candidates []Candidate) error {
	if len(results) != len(candidates) { return ErrInvalidOutput }
	seen := make([]bool, len(candidates))
	for _, r := range results {
		idx := caseIDIndex(r.CaseID)
		if idx < 0 || idx >= len(candidates) || seen[idx] { return ErrInvalidOutput }
		seen[idx] = true
		if !allowedCode(r.CategoryCode) { return ErrInvalidOutput }
		if len(r.ReasonCode) > 40 || strings.ContainsAny(r.ReasonCode, "\r\n") { return ErrInvalidOutput }
		if r.NeedsReview && r.CategoryCode != "uncategorized" { return ErrInvalidOutput }
	}
	for _, ok := range seen { if !ok { return ErrInvalidOutput } }
	return nil
}

func caseID(i int) string { return "c" + strconv.Itoa(i) }

// CaseIDForIndex is the stable case identifier a request sends for candidate i.
func CaseIDForIndex(i int) string { return caseID(i) }

// CaseIndex returns the candidate index encoded in a caseId, or -1 when the value is not a caseId.
// Callers must treat -1 (and out-of-range or duplicate indexes) as invalid provider output instead
// of falling back to array position.
func CaseIndex(id string) int { return caseIDIndex(id) }

func caseIDIndex(id string) int {
	if len(id) < 2 || id[0] != 'c' { return -1 }
	n, err := strconv.Atoi(id[1:]); if err != nil { return -1 }
	return n
}
