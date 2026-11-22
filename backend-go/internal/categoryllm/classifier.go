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
	MaxCandidates   = 5
	MaxMerchantRunes = 120
)

var (
	ErrDisabled      = errors.New("category_llm_disabled")
	ErrInvalidInput  = errors.New("category_llm_invalid_input")
	ErrInvalidOutput = errors.New("category_llm_invalid_output")
	ErrTruncated     = errors.New("category_llm_truncated")
	ErrInputTooLarge = errors.New("category_llm_input_too_large")
)

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
func caseIDIndex(id string) int {
	if len(id) < 2 || id[0] != 'c' { return -1 }
	n, err := strconv.Atoi(id[1:]); if err != nil { return -1 }
	return n
}
