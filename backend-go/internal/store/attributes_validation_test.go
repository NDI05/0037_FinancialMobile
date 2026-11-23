package store

import (
	"strings"
	"testing"
)

func TestValidateAttributesBounds(t *testing.T) {
	tests := []struct {
		name       string
		attributes map[string]any
		wantError  bool
	}{
		{name: "empty is valid", attributes: nil},
		{name: "ten pairs are valid", attributes: tenPairs()},
		{name: "eleven pairs overflow", attributes: elevenPairs(), wantError: true},
		{name: "key of forty is valid", attributes: map[string]any{strings.Repeat("k", 40): "v"}},
		{name: "key over forty", attributes: map[string]any{strings.Repeat("k", 41): "v"}, wantError: true},
		{name: "blank key", attributes: map[string]any{"   ": "v"}, wantError: true},
		{name: "value of two hundred runes is valid", attributes: map[string]any{"catatan": strings.Repeat("é", 200)}},
		{name: "value over two hundred runes", attributes: map[string]any{"catatan": strings.Repeat("é", 201)}, wantError: true},
		{name: "encoded payload over two KB", attributes: map[string]any{"catatan": strings.Repeat("x", 2040)}, wantError: true},
		{name: "non-string values keep their JSON type", attributes: map[string]any{"jumlah": float64(3)}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := validateAttributes(test.attributes)
			if test.wantError && err == nil {
				t.Fatalf("expected a validation error")
			}
			if !test.wantError && err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
		})
	}
}

func tenPairs() map[string]any {
	attributes := make(map[string]any, 10)
	for i := 0; i < 10; i++ {
		attributes["k"+string(rune('a'+i))] = "v"
	}
	return attributes
}

func elevenPairs() map[string]any {
	attributes := tenPairs()
	attributes["extra"] = "v"
	return attributes
}
