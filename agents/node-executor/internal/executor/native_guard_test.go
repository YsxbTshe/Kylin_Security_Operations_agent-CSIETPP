package executor

import "testing"

func TestInterpretSecuritySwitchOutput(t *testing.T) {
	cases := map[string]string{
		"strict":           "strict",
		"standard":         "standard",
		"on":               "standard",
		"enabled":          "standard",
		"off":              "off",
		"disabled":         "off",
		"  STRICT mode  ":  "strict",
		"":                 "unknown",
		"custom-profile-a": "custom-profile-a",
	}
	for input, want := range cases {
		if got := interpretSecuritySwitchOutput(input); got != want {
			t.Fatalf("interpretSecuritySwitchOutput(%q)=%q, want %q", input, got, want)
		}
	}
}
