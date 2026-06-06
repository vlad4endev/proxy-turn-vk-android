package vkauth

import "testing"

func TestNormalizeCaptchaMode(t *testing.T) {
	t.Parallel()

	cases := map[string]string{
		"auto":  "auto",
		"AUTO":  "auto",
		" wv ":  "wv",
		"rjs":   "rjs",
		"":      "auto",
		"other": "auto",
	}
	for in, want := range cases {
		if got := normalizeCaptchaMode(in); got != want {
			t.Fatalf("normalizeCaptchaMode(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestIsWebViewCaptchaTimeout(t *testing.T) {
	t.Parallel()

	if !isWebViewCaptchaTimeout(fmtError("webview captcha timed out")) {
		t.Fatal("expected timeout detection")
	}
	if isWebViewCaptchaTimeout(fmtError("other error")) {
		t.Fatal("expected non-timeout")
	}
}

type fmtError string

func (e fmtError) Error() string { return string(e) }
