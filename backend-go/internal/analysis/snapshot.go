// Package analysis builds the canonical, aggregate-only provider payload from server data and
// validates the client's legacy aggregate envelope before it is ignored as a data source.
package analysis

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/store"
)

const PromptVersion = "analysis-v1"

var safeCategoryCodes = map[string]bool{
	"income": true, "housing": true, "food": true, "transport": true, "utilities": true,
	"shopping": true, "health": true, "education": true, "other_expense": true,
	"transfer": true, "uncategorized": true,
}

type CategoryTotal struct {
	CategoryCode     string `json:"categoryCode"`
	AmountMinor      int64  `json:"amountMinor"`
	TransactionCount int    `json:"transactionCount"`
}

type PriorMonth struct {
	MonthID      string `json:"monthId"`
	ExpenseMinor int64  `json:"expenseMinor"`
	IncomeMinor  int64  `json:"incomeMinor"`
}

type PlanSummary struct {
	ExpectedIncomeMinor int64           `json:"expectedIncomeMinor"`
	PlannedSavingsMinor int64           `json:"plannedSavingsMinor"`
	CategoryLimitTotals []CategoryTotal `json:"categoryLimitTotals"`
}

type Payload struct {
	SchemaVersion          int             `json:"schemaVersion"`
	MonthID                string          `json:"monthId"`
	Coverage               string          `json:"coverage"`
	GrossInflowMinor       int64           `json:"grossInflowMinor"`
	GrossOutflowMinor      int64           `json:"grossOutflowMinor"`
	NetCashFlowMinor       int64           `json:"netCashFlowMinor"`
	ClassifiedIncomeMinor  int64           `json:"classifiedIncomeMinor"`
	ClassifiedExpenseMinor int64           `json:"classifiedExpenseMinor"`
	TransactionCount       int             `json:"transactionCount"`
	UncategorizedCount     int             `json:"uncategorizedCount"`
	ManualEntryCount       int             `json:"manualEntryCount"`
	NonIDRExcludedCount    int             `json:"nonIdrExcludedCount"`
	CategoryTotals         []CategoryTotal `json:"categoryTotals"`
	PriorMonth             *PriorMonth     `json:"priorMonth"`
	Plan                   *PlanSummary    `json:"plan"`
}

type Snapshot struct {
	Payload        Payload
	Hash           string
	Revision       int
	DependencyHash string
	Dependencies   store.SnapshotDependencies
}

var ErrSnapshotUnstable = errors.New("snapshot dependencies changed while reading")

// BuildConsistent brackets the payload read with the same dependency token on both sides. A writer
// that commits between those reads changes the token and the read is retried, so a payload cannot
// be labelled consistent from a mix of pre- and post-commit state without holding a transaction
// open during the provider call.
func BuildConsistent(ctx context.Context, s *store.Store, ownerID, monthID string) (Snapshot, error) {
	for attempt := 0; attempt < 3; attempt++ {
		before, err := s.SnapshotDependencies(ctx, ownerID, monthID)
		if err != nil {
			return Snapshot{}, err
		}
		snapshot, err := Build(ctx, s, ownerID, monthID)
		if err != nil {
			return Snapshot{}, err
		}
		after, err := s.SnapshotDependencies(ctx, ownerID, monthID)
		if err != nil {
			return Snapshot{}, err
		}
		if before.Hash == after.Hash {
			snapshot.DependencyHash = after.Hash
			snapshot.Dependencies = after
			return snapshot, nil
		}
	}
	return Snapshot{}, ErrSnapshotUnstable
}

// Build reads the owner's active transactions, categories and budget and produces the only payload
// that may reach a provider. Client-supplied aggregates are never used here.
func Build(ctx context.Context, s *store.Store, ownerID, monthID string) (Snapshot, error) {
	summary, err := s.ComputeSummary(ctx, ownerID, monthID, time.Now())
	if err != nil {
		return Snapshot{}, err
	}
	var categories []store.Category
	cursor := ""
	for {
		page, err := s.ListCategories(ctx, ownerID, "", "", 100, cursor)
		if err != nil {
			return Snapshot{}, err
		}
		categories = append(categories, page.Items...)
		if page.NextCursor == "" {
			break
		}
		cursor = page.NextCursor
	}
	kinds := make(map[string]string, len(categories))
	for _, category := range categories {
		kinds[category.Code] = category.Kind
	}
	rollup := map[string]CategoryTotal{}
	addTotal := func(code, kind string, amount int64, count int) {
		safe := code
		if !safeCategoryCodes[safe] {
			switch kind {
			case "INCOME":
				safe = "income"
			case "TRANSFER":
				safe = "transfer"
			case "UNCATEGORIZED":
				safe = "uncategorized"
			default:
				safe = "other_expense"
			}
		}
		current := rollup[safe]
		current.CategoryCode = safe
		current.AmountMinor += amount
		current.TransactionCount += count
		rollup[safe] = current
	}
	for _, total := range summary.CategoryTotals {
		addTotal(total.CategoryCode, kinds[total.CategoryCode], total.AmountMinor, total.Count)
	}
	categoryTotals := make([]CategoryTotal, 0, len(rollup))
	for _, total := range rollup {
		categoryTotals = append(categoryTotals, total)
	}
	sortTotals(categoryTotals)

	payload := Payload{
		SchemaVersion: 1, MonthID: monthID, Coverage: summary.Coverage,
		GrossInflowMinor: summary.GrossInflowMinor, GrossOutflowMinor: summary.GrossOutflowMinor,
		NetCashFlowMinor: summary.NetMinor, ClassifiedIncomeMinor: summary.ClassifiedIncomeMinor,
		ClassifiedExpenseMinor: summary.ClassifiedExpenseMinor,
		TransactionCount:       summary.TransactionCount, UncategorizedCount: summary.UncategorizedCount,
		ManualEntryCount: summary.ManualEntryCount, NonIDRExcludedCount: 0,
		CategoryTotals: categoryTotals,
	}

	previousID := previousMonthID(monthID)
	previous, err := s.ComputeSummary(ctx, ownerID, previousID, time.Now())
	if err != nil {
		return Snapshot{}, err
	}
	if previous.TransactionCount > 0 {
		payload.PriorMonth = &PriorMonth{MonthID: previousID, ExpenseMinor: previous.ClassifiedExpenseMinor,
			IncomeMinor: previous.ClassifiedIncomeMinor}
	}
	plan, err := s.GetBudgetPlan(ctx, ownerID, monthID)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return Snapshot{}, err
	}
	if err == nil {
		limitTotals := map[string]CategoryTotal{}
		for _, limit := range plan.Limits {
			kind := kinds[limit.CategoryCode]
			safe := limit.CategoryCode
			if !safeCategoryCodes[safe] {
				switch kind {
				case "INCOME":
					safe = "income"
				case "TRANSFER":
					safe = "transfer"
				case "UNCATEGORIZED":
					safe = "uncategorized"
				default:
					safe = "other_expense"
				}
			}
			total := limitTotals[safe]
			total.CategoryCode = safe
			total.AmountMinor += limit.AmountMinor
			limitTotals[safe] = total
		}
		limits := make([]CategoryTotal, 0, len(limitTotals))
		for _, total := range limitTotals {
			limits = append(limits, total)
		}
		sortTotals(limits)
		payload.Plan = &PlanSummary{ExpectedIncomeMinor: plan.ExpectedIncomeMinor,
			PlannedSavingsMinor: plan.PlannedSavingsMinor, CategoryLimitTotals: limits}
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return Snapshot{}, err
	}
	sum := sha256.Sum256(raw)
	return Snapshot{Payload: payload, Hash: hex.EncodeToString(sum[:]), Revision: summary.InputRevision}, nil
}

func previousMonthID(monthID string) string {
	start, _, err := store.ParseMonth(monthID)
	if err != nil {
		return monthID
	}
	jakarta, err := time.LoadLocation("Asia/Jakarta")
	if err != nil {
		jakarta = time.FixedZone("Asia/Jakarta", 7*60*60)
	}
	prev := start.In(jakarta).AddDate(0, -1, 0)
	return prev.Format("2006-01")
}

func sortTotals(totals []CategoryTotal) {
	for i := 1; i < len(totals); i++ {
		for j := i; j > 0 && totals[j].CategoryCode < totals[j-1].CategoryCode; j-- {
			totals[j], totals[j-1] = totals[j-1], totals[j]
		}
	}
}

// ToMap is used by the provider boundary; it contains only the canonical aggregate.
func (p Payload) ToMap() map[string]any {
	raw, _ := json.Marshal(p)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return out
}

// ClientPayload is the strict legacy envelope accepted from Android v0.3. Its numeric values are
// validated but not trusted as a source; canonical values come from Build.
type ClientPayload struct {
	SchemaVersion          int                   `json:"schemaVersion"`
	MonthID                string                `json:"monthId"`
	Coverage               string                `json:"coverage"`
	GrossInflowMinor       int64                 `json:"grossInflowMinor"`
	GrossOutflowMinor      int64                 `json:"grossOutflowMinor"`
	NetCashFlowMinor       int64                 `json:"netCashFlowMinor"`
	ClassifiedIncomeMinor  int64                 `json:"classifiedIncomeMinor"`
	ClassifiedExpenseMinor int64                 `json:"classifiedExpenseMinor"`
	TransactionCount       int                   `json:"transactionCount"`
	UncategorizedCount     int                   `json:"uncategorizedCount"`
	ManualEntryCount       int                   `json:"manualEntryCount"`
	NonIDRExcludedCount    int                   `json:"nonIdrExcludedCount"`
	CategoryTotals         []ClientCategoryTotal `json:"categoryTotals"`
	PriorMonth             *ClientPriorMonth     `json:"priorMonth"`
	Plan                   *ClientPlan           `json:"plan"`
}

type ClientCategoryTotal struct {
	CategoryCode     string `json:"categoryCode"`
	AmountMinor      int64  `json:"amountMinor"`
	TransactionCount *int   `json:"transactionCount"`
}

type ClientPriorMonth struct {
	MonthID      string `json:"monthId"`
	ExpenseMinor int64  `json:"expenseMinor"`
	IncomeMinor  int64  `json:"incomeMinor"`
}

type ClientPlan struct {
	ExpectedIncomeMinor int64                 `json:"expectedIncomeMinor"`
	PlannedSavingsMinor int64                 `json:"plannedSavingsMinor"`
	CategoryLimitTotals []ClientCategoryTotal `json:"categoryLimitTotals"`
}

// ParseClientPayload rejects unknown keys, missing nested counts, unsafe category codes and values
// outside the shared numeric envelope. It returns typed data only; the caller still uses Build.
func ParseClientPayload(raw json.RawMessage) (ClientPayload, error) {
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.DisallowUnknownFields()
	var payload ClientPayload
	if err := decoder.Decode(&payload); err != nil {
		return ClientPayload{}, fmt.Errorf("payload schema: %w", err)
	}
	if payload.SchemaVersion != 1 {
		return ClientPayload{}, fmt.Errorf("payload.schemaVersion must be 1")
	}
	if payload.Coverage != "COMPLETE" && payload.Coverage != "PARTIAL" && payload.Coverage != "UNKNOWN" {
		return ClientPayload{}, fmt.Errorf("payload.coverage is invalid")
	}
	if payload.GrossInflowMinor < -1_000_000_000_000_000 || payload.GrossInflowMinor > 1_000_000_000_000_000 ||
		payload.GrossOutflowMinor < 0 || payload.GrossOutflowMinor > 1_000_000_000_000_000 ||
		payload.NetCashFlowMinor < -1_000_000_000_000_000 || payload.NetCashFlowMinor > 1_000_000_000_000_000 ||
		payload.ClassifiedIncomeMinor < 0 || payload.ClassifiedIncomeMinor > 1_000_000_000_000_000 ||
		payload.ClassifiedExpenseMinor < 0 || payload.ClassifiedExpenseMinor > 1_000_000_000_000_000 {
		return ClientPayload{}, fmt.Errorf("payload amount is outside the safe envelope")
	}
	if payload.TransactionCount < 0 || payload.TransactionCount > 1_000_000 ||
		payload.UncategorizedCount < 0 || payload.UncategorizedCount > 1_000_000 ||
		payload.ManualEntryCount < 0 || payload.ManualEntryCount > 1_000_000 ||
		payload.NonIDRExcludedCount < 0 || payload.NonIDRExcludedCount > 1_000_000 {
		return ClientPayload{}, fmt.Errorf("payload count is invalid")
	}
	if len(payload.CategoryTotals) > 50 {
		return ClientPayload{}, fmt.Errorf("payload.categoryTotals is too large")
	}
	for _, total := range payload.CategoryTotals {
		if err := validateClientTotal(total); err != nil {
			return ClientPayload{}, err
		}
	}
	if payload.PriorMonth != nil {
		if payload.PriorMonth.MonthID == "" || len(payload.PriorMonth.MonthID) != 7 {
			return ClientPayload{}, fmt.Errorf("payload.priorMonth.monthId is invalid")
		}
	}
	if payload.Plan != nil {
		if len(payload.Plan.CategoryLimitTotals) > 20 {
			return ClientPayload{}, fmt.Errorf("payload.plan.categoryLimitTotals is too large")
		}
		for _, total := range payload.Plan.CategoryLimitTotals {
			if err := validateClientTotal(total); err != nil {
				return ClientPayload{}, err
			}
		}
	}
	return payload, nil
}

func validateClientTotal(total ClientCategoryTotal) error {
	if !safeCategoryCodes[total.CategoryCode] {
		return fmt.Errorf("payload categoryCode is not an allow-listed projection code")
	}
	if total.TransactionCount == nil {
		return fmt.Errorf("payload category total must include transactionCount")
	}
	if *total.TransactionCount < 0 || *total.TransactionCount > 1_000_000 {
		return fmt.Errorf("payload category transactionCount is invalid")
	}
	if total.AmountMinor < -1_000_000_000_000_000 || total.AmountMinor > 1_000_000_000_000_000 {
		return fmt.Errorf("payload category amount is outside the safe envelope")
	}
	return nil
}
