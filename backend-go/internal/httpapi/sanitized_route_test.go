package httpapi

import "testing"

// Route labels feed request logs and dashboards; an id-shaped label for a job-wide endpoint hides
// every automatic run, so each queue route keeps its own name.
func TestSanitizedRouteKeepsQueueEndpoints(t *testing.T) {
	cases := map[string]string{
		"/v1/category-jobs/enqueue":        "/v1/category-jobs/enqueue",
		"/v1/category-jobs/drain":          "/v1/category-jobs/drain",
		"/v1/category-jobs/cj_123":         "/v1/category-jobs/{id}",
		"/v1/category-jobs":                "/v1/category-jobs",
		"/v1/category-jobs/":               "/v1/category-jobs",
	}
	for path, want := range cases {
		if got := sanitizedRoute(path); got != want {
			t.Errorf("sanitizedRoute(%q) = %q, want %q", path, got, want)
		}
	}
}
