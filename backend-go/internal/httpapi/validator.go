package httpapi

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"github.com/pam/recap/backend-go/internal/analysis"
	"github.com/pam/recap/backend-go/internal/store"
)

var knownMetricKeys = map[string]bool{
	"summary.grossInflow": true, "summary.grossOutflow": true, "summary.netCashFlow": true,
	"summary.classifiedIncome": true, "summary.classifiedExpense": true,
	"summary.uncategorizedCount": true, "summary.manualEntryCount": true,
	"summary.nonIdrExcludedCount": true, "summary.transactionCount": true,
	"summary.coverage": true,
	"category.income":  true, "category.housing": true, "category.food": true,
	"category.transport": true, "category.utilities": true, "category.shopping": true,
	"category.health": true, "category.education": true, "category.other_expense": true,
	"category.transfer": true, "category.uncategorized": true,
}

var instructionMarkers = []string{
	"abaikan", "instruksi sebelumnya", "ignore previous", "ignore all previous",
	"ignore the above", "disregard previous", "system:", "assistant:", "you are now",
	"kamu sekarang", "lupakan aturan", "override the", "tool_calls", "jalankan alat", "panggil fungsi",
}

var rupiahPattern = regexp.MustCompile(`Rp\s?([0-9][0-9.,]*)`)
var bareMoneyPattern = regexp.MustCompile(`\d{1,3}(?:\.\d{3})+(?:,\d{1,2})?|\d{6,}(?:,\d{1,2})?`)
var minorFlagPattern = regexp.MustCompile(`(?i)\b(minor|satuan minor|minor units?)\b`)

func validateAnalysisResponse(response store.AnalysisResponse, payload analysis.Payload) error {
	if response.PromptVersion != analysis.PromptVersion {
		return fmt.Errorf("prompt_version_mismatch")
	}
	if len(response.Observations) == 0 {
		return fmt.Errorf("no_observations")
	}
	if len(response.Limitations) == 0 {
		return fmt.Errorf("missing_limitations")
	}
	if len(response.Observations) > 12 || len(response.Suggestions) > 8 || len(response.Limitations) > 8 {
		return fmt.Errorf("schema_invalid")
	}
	allowed := allowedAmounts(payload)
	checkText := func(text string) error {
		if strings.TrimSpace(text) == "" {
			return fmt.Errorf("blank_text")
		}
		if len(text) > 600 {
			return fmt.Errorf("schema_invalid")
		}
		if containsInstruction(text) {
			return fmt.Errorf("untrusted_instruction")
		}
		if issue := inspectAmounts(text, allowed); issue != "" {
			return fmt.Errorf("%s", issue)
		}
		return nil
	}
	for _, observation := range response.Observations {
		if err := checkMetricKeys(observation.MetricKeys); err != nil {
			return err
		}
		if err := checkText(observation.Text); err != nil {
			return err
		}
	}
	for _, suggestion := range response.Suggestions {
		if err := checkMetricKeys(suggestion.MetricKeys); err != nil {
			return err
		}
		if err := checkText(suggestion.Text); err != nil {
			return err
		}
		if suggestion.SuggestedAmountMinor != nil {
			amount := *suggestion.SuggestedAmountMinor
			exceeds := payload.GrossInflowMinor > 0 && amount > payload.GrossInflowMinor
			if amount <= 0 || exceeds {
				return fmt.Errorf("suggestion_amount_invalid")
			}
		}
		if suggestion.CategoryCode != "" && !hasCategory(payload, suggestion.CategoryCode) {
			return fmt.Errorf("unknown_metric_key")
		}
	}
	return nil
}

func checkMetricKeys(keys []string) error {
	if len(keys) == 0 {
		return fmt.Errorf("missing_evidence")
	}
	for _, key := range keys {
		if !knownMetricKeys[key] {
			return fmt.Errorf("unknown_metric_key")
		}
	}
	return nil
}

func hasCategory(payload analysis.Payload, code string) bool {
	for _, total := range payload.CategoryTotals {
		if total.CategoryCode == code {
			return true
		}
	}
	return false
}

func containsInstruction(text string) bool {
	lower := strings.ToLower(text)
	for _, marker := range instructionMarkers {
		if strings.Contains(lower, marker) {
			return true
		}
	}
	return false
}

// inspectAmounts ports the Kotlin/Node FR-37 checks. Rupiah figures are parsed as major units into
// minor units; a bare grouped/display figure is accepted, while a bare raw minor value is ambiguous.
func inspectAmounts(text string, allowed map[int64]bool) string {
	rupiahSpans := rupiahPattern.FindAllStringSubmatchIndex(text, -1)
	for _, span := range rupiahSpans {
		token := text[span[2]:span[3]]
		value, ok := parseRupiahMinor(token)
		if !ok || !allowed[absInt64(value)] {
			return "invented_amount"
		}
	}
	masked := []byte(text)
	for _, span := range rupiahSpans {
		for i := span[0]; i < span[1]; i++ {
			masked[i] = ' '
		}
	}
	minorFlagged := minorFlagPattern.MatchString(text)
	for _, match := range bareMoneyPattern.FindAllString(string(masked), -1) {
		if regexp.MustCompile(`^\d{1,2}\.\d{2}$`).MatchString(match) ||
			regexp.MustCompile(`^\d{4}\.\d{1,2}$`).MatchString(match) {
			continue
		}
		display, displayOK := parseRupiahMinor(match)
		if displayOK && allowed[absInt64(display)] {
			continue
		}
		raw, err := strconv.ParseInt(strings.NewReplacer(".", "", ",", "").Replace(match), 10, 64)
		if err != nil {
			return "invented_amount"
		}
		if allowed[absInt64(raw)] {
			if !minorFlagged {
				return "ambiguous_minor_amount"
			}
			continue
		}
		return "invented_amount"
	}
	return ""
}

func parseRupiahMinor(token string) (int64, bool) {
	compact := strings.TrimSpace(token)
	if compact == "" {
		return 0, false
	}
	comma := strings.LastIndex(compact, ",")
	majorPart, centsPart := compact, "00"
	if comma >= 0 {
		majorPart = compact[:comma]
		centsPart = compact[comma+1:]
	}
	majorDigits := digitsOnly(majorPart)
	if majorDigits == "" {
		return 0, false
	}
	major, err := strconv.ParseInt(majorDigits, 10, 64)
	if err != nil || major > 9_000_000_000_000_000 {
		return 0, false
	}
	centsDigits := digitsOnly(centsPart)
	for len(centsDigits) < 2 {
		centsDigits += "0"
	}
	if len(centsDigits) > 2 {
		centsDigits = centsDigits[:2]
	}
	cents, err := strconv.ParseInt(centsDigits, 10, 64)
	if err != nil || cents < 0 || cents > 99 {
		return 0, false
	}
	return major*100 + cents, true
}

func digitsOnly(value string) string {
	var out strings.Builder
	for _, r := range value {
		if r >= '0' && r <= '9' {
			out.WriteRune(r)
		}
	}
	return out.String()
}

func absInt64(value int64) int64 {
	if value < 0 {
		return -value
	}
	return value
}

func allowedAmounts(payload analysis.Payload) map[int64]bool {
	allowed := map[int64]bool{0: true}
	add := func(value int64) {
		allowed[absInt64(value)] = true
	}
	add(payload.GrossInflowMinor)
	add(payload.GrossOutflowMinor)
	add(payload.NetCashFlowMinor)
	add(payload.ClassifiedIncomeMinor)
	add(payload.ClassifiedExpenseMinor)
	for _, total := range payload.CategoryTotals {
		add(total.AmountMinor)
	}
	if payload.PriorMonth != nil {
		add(payload.PriorMonth.ExpenseMinor)
		add(payload.PriorMonth.IncomeMinor)
	}
	if payload.Plan != nil {
		add(payload.Plan.ExpectedIncomeMinor)
		add(payload.Plan.PlannedSavingsMinor)
		for _, total := range payload.Plan.CategoryLimitTotals {
			add(total.AmountMinor)
		}
	}
	return allowed
}
