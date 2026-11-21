package finance

import "testing"

// SRS B5 worked example. Amounts are minor units.
func TestB5WorkedExample(t *testing.T) {
	entries := []Entry{
		{ID: "salary", AmountMinor: 500_000_000, Direction: "CREDIT", CategoryCode: "income", CategoryKind: "INCOME"},
		{ID: "housing", AmountMinor: 100_000_000, Direction: "DEBIT", CategoryCode: "housing", CategoryKind: "EXPENSE"},
		{ID: "transfer", AmountMinor: 20_000_000, Direction: "DEBIT", CategoryCode: "transfer", CategoryKind: "TRANSFER", IsInternalTransfer: true},
		{ID: "refund", AmountMinor: 10_000_000, Direction: "CREDIT", CategoryCode: "housing", CategoryKind: "EXPENSE", LinkedRefundOf: "housing"},
	}
	totals := Compute(entries)
	if totals.GrossInflowMinor != 510_000_000 {
		t.Fatalf("gross inflow = %d", totals.GrossInflowMinor)
	}
	if totals.GrossOutflowMinor != 120_000_000 {
		t.Fatalf("gross outflow = %d", totals.GrossOutflowMinor)
	}
	if totals.NetMinor != 390_000_000 {
		t.Fatalf("net = %d", totals.NetMinor)
	}
	if totals.ClassifiedIncomeMinor != 500_000_000 {
		t.Fatalf("classified income = %d", totals.ClassifiedIncomeMinor)
	}
	if totals.ClassifiedExpenseMinor != 100_000_000 {
		t.Fatalf("classified expense = %d", totals.ClassifiedExpenseMinor)
	}
	if totals.CategoryTotals["housing"] != 90_000_000 {
		t.Fatalf("net housing = %d", totals.CategoryTotals["housing"])
	}
}

func TestUnlinkedCreditIsNotNettedAgainstExpense(t *testing.T) {
	entries := []Entry{
		{ID: "expense", AmountMinor: 100, Direction: "DEBIT", CategoryCode: "food", CategoryKind: "EXPENSE"},
		{ID: "credit", AmountMinor: 50, Direction: "CREDIT", CategoryCode: "income", CategoryKind: "INCOME"},
	}
	totals := Compute(entries)
	if totals.CategoryTotals["food"] != 100 {
		t.Fatalf("food = %d", totals.CategoryTotals["food"])
	}
	if totals.ClassifiedIncomeMinor != 50 || totals.ClassifiedExpenseMinor != 100 {
		t.Fatalf("classified = %d/%d", totals.ClassifiedIncomeMinor, totals.ClassifiedExpenseMinor)
	}
}
