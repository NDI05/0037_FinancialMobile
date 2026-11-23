// Package finance holds the deterministic cash-flow rules (BR-01..BR-06).
package finance

// Entry is the minimal transaction projection needed for the monthly summary.
type Entry struct {
	ID                 string
	AmountMinor        int64
	Direction          string
	CategoryCode       string
	CategoryKind       string
	IsInternalTransfer bool
	LinkedRefundOf     string
	Source             string
}

type CategoryTotal struct {
	CategoryCode string `json:"categoryCode"`
	Label        string `json:"label,omitempty"`
	AmountMinor  int64  `json:"amountMinor"`
	Count        int    `json:"count"`
}

type Totals struct {
	GrossInflowMinor       int64
	GrossOutflowMinor      int64
	NetMinor               int64
	ClassifiedIncomeMinor  int64
	ClassifiedExpenseMinor int64
	TransactionCount       int
	ManualCount            int
	UncategorizedCount     int
	CategorizedCount       int
	// RefundMinor/RefundCount cover credits explicitly linked to an expense row. They are also
	// reflected in gross inflow and in the linked category total, so clients must not add them
	// to an income total a second time.
	RefundMinor      int64
	RefundCount      int
	TransferInMinor  int64
	TransferOutMinor int64
	TransferCount    int
	CategoryTotals   map[string]int64
	CategoryCounts   map[string]int
}

// Compute implements BR-02/BR-03/BR-04:
// - gross includes every posted row, including internal transfers;
// - classified income/expense excludes internal transfers;
// - a linked refund reduces the net of the expense category it points at;
// - unlinked credits are income, never netted against unrelated expenses.
func Compute(entries []Entry) Totals {
	totals := Totals{
		CategoryTotals: map[string]int64{},
		CategoryCounts: map[string]int{},
	}
	byID := make(map[string]Entry, len(entries))
	for _, entry := range entries {
		byID[entry.ID] = entry
	}
	for _, entry := range entries {
		if entry.AmountMinor <= 0 {
			continue
		}
		totals.TransactionCount++
		if entry.Source == "MANUAL" {
			totals.ManualCount++
		}
		if entry.CategoryKind == "UNCATEGORIZED" || entry.CategoryCode == "" {
			totals.UncategorizedCount++
		} else {
			totals.CategorizedCount++
		}
		if entry.IsInternalTransfer {
			totals.TransferCount++
			if entry.Direction == "CREDIT" {
				totals.TransferInMinor += entry.AmountMinor
			} else {
				totals.TransferOutMinor += entry.AmountMinor
			}
		}
		switch entry.Direction {
		case "CREDIT":
			totals.GrossInflowMinor += entry.AmountMinor
			if !entry.IsInternalTransfer && entry.CategoryKind == "INCOME" {
				totals.ClassifiedIncomeMinor += entry.AmountMinor
			}
			if entry.LinkedRefundOf != "" {
				if linked, ok := byID[entry.LinkedRefundOf]; ok && linked.CategoryKind == "EXPENSE" {
					totals.CategoryTotals[linked.CategoryCode] -= entry.AmountMinor
					totals.RefundMinor += entry.AmountMinor
					totals.RefundCount++
				}
			}
		case "DEBIT":
			totals.GrossOutflowMinor += entry.AmountMinor
			if !entry.IsInternalTransfer && entry.CategoryKind == "EXPENSE" {
				totals.ClassifiedExpenseMinor += entry.AmountMinor
				totals.CategoryTotals[entry.CategoryCode] += entry.AmountMinor
				totals.CategoryCounts[entry.CategoryCode]++
			}
		}
	}
	totals.NetMinor = totals.GrossInflowMinor - totals.GrossOutflowMinor
	return totals
}
