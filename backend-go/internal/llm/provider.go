// Package llm holds the model-provider boundary. Only aggregate payloads reach this package.
package llm

import (
	"context"
	"fmt"

	"github.com/pam/recap/backend-go/internal/store"
)

// Provider is the replaceable model adapter (Ollama/OpenAI-compatible/fake).
type Provider interface {
	ID() string
	Model() string
	Analyze(ctx context.Context, payload map[string]any) (store.AnalysisResponse, error)
}

type Fake struct {
	Scenario string
}

func (f Fake) ID() string    { return "fake" }
func (f Fake) Model() string { return "deterministic-v1" }

func (f Fake) Analyze(_ context.Context, payload map[string]any) (store.AnalysisResponse, error) {
	if f.Scenario == "malformed" {
		return store.AnalysisResponse{}, fmt.Errorf("fake_malformed")
	}
	outflow, _ := payload["grossOutflowMinor"].(float64)
	return store.AnalysisResponse{
		PromptVersion: "analysis-v1",
		Observations: []store.Observation{
			{Text: "Arus kas keluar bulan ini tercatat pada ringkasan.", MetricKeys: []string{"summary.grossOutflow"}},
			{Text: fmt.Sprintf("Total pengeluaran adalah %d unit minor.", int64(outflow)), MetricKeys: []string{"summary.grossOutflow"}},
		},
		Suggestions: []store.Suggestion{
			{Text: "Pertahankan batas anggaran per kategori agar pengeluaran tetap terkendali.", MetricKeys: []string{"summary.grossOutflow"}},
		},
		Limitations: []string{"Analisis memakai proyeksi agregat, bukan isi email atau catatan pribadi."},
	}, nil
}
