package yandex

import "testing"

func TestNormalizeLink(t *testing.T) {
	tests := []struct {
		in   string
		want string
	}{
		{"https://telemost.yandex.ru/j/AbCdEfGhIj", "AbCdEfGhIj"},
		{"https://telemost.yandex.ru/j/AbCdEfGhIj?lang=ru", "AbCdEfGhIj"},
		{"https://ya.ru/telemost/j/RoomHash1234", "RoomHash1234"},
		{"https://ya.ru/calls/RoomHash1234", "RoomHash1234"},
		{"https://ya.ru/calls/RoomHash1234?lang=ru", "RoomHash1234"},
		{"https://telemost.yandex.ru/calls/RoomHash1234", "RoomHash1234"},
		{"https://vk.com/call/join/abc", ""},
	}
	for _, tc := range tests {
		if got := NormalizeLink(tc.in); got != tc.want {
			t.Fatalf("NormalizeLink(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestIsRealCaptchaChallenge(t *testing.T) {
	jsOnly := `<html><script>window.smartCaptcha={recaptcha:true}</script></html>`
	if IsRealCaptchaChallenge(jsOnly) {
		t.Fatal("expected JS-only captcha mention to be ignored")
	}
	if !IsRealCaptchaChallenge(`<div>Докажите, что вы не робот</div>`) {
		t.Fatal("expected robot challenge text to match")
	}
	if !IsRealCaptchaChallenge(`<form><input type="checkbox" name="robot"/></form>`) {
		t.Fatal("expected checkbox form to match")
	}
}
