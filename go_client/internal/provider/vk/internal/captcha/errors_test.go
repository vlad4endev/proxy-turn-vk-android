package captcha

import "testing"

// TestParseError_SmartCaptcha проверяет новый VK Smart Captcha (not_robot_captcha)
// флоу: error_code 14 приходит с redirect_uri + session_token, но БЕЗ legacy-полей
// captcha_sid / captcha_img. Раньше ParseError отбраковывал такой challenge
// ("missing captcha_sid"), из-за чего TURN-креды не получались и туннель не поднимался.
func TestParseError_SmartCaptcha(t *testing.T) {
	errData := map[string]any{
		"error_code":  float64(14),
		"error_msg":   "Captcha need",
		"redirect_uri": "https://id.vk.ru/not_robot_captcha?domain=vk.com&session_token=abc.def.ghi&variant=popup",
	}

	e := ParseError(errData)
	if e == nil {
		t.Fatal("ParseError returned nil for a valid Smart Captcha challenge")
	}
	if !e.IsCaptcha() {
		t.Fatalf("IsCaptcha() = false; want true (code=%d redirect=%q session=%q)",
			e.ErrorCode, e.RedirectURI, e.SessionToken)
	}
	if e.SessionToken != "abc.def.ghi" {
		t.Fatalf("SessionToken = %q; want %q", e.SessionToken, "abc.def.ghi")
	}
}

// TestParseError_SessionTokenTopLevel проверяет fallback, когда session_token
// приходит отдельным полем, а не в query redirect_uri.
func TestParseError_SessionTokenTopLevel(t *testing.T) {
	errData := map[string]any{
		"error_code":    float64(14),
		"redirect_uri":  "https://id.vk.ru/not_robot_captcha?domain=vk.com",
		"session_token": "top-level-token",
	}
	e := ParseError(errData)
	if e == nil || !e.IsCaptcha() {
		t.Fatalf("expected actionable captcha; got %+v", e)
	}
	if e.SessionToken != "top-level-token" {
		t.Fatalf("SessionToken = %q; want %q", e.SessionToken, "top-level-token")
	}
}

// TestParseError_LegacyImageCaptcha проверяет, что legacy image-captcha
// (captcha_sid + captcha_img) по-прежнему парсится.
func TestParseError_LegacyImageCaptcha(t *testing.T) {
	errData := map[string]any{
		"error_code":  float64(14),
		"error_msg":   "Captcha needed",
		"captcha_sid": "239847923",
		"captcha_img": "https://api.vk.com/captcha.php?sid=239847923",
	}
	e := ParseError(errData)
	if e == nil {
		t.Fatal("ParseError returned nil for legacy image captcha")
	}
	if e.CaptchaSid != "239847923" || e.CaptchaImg == "" {
		t.Fatalf("legacy fields not parsed: sid=%q img=%q", e.CaptchaSid, e.CaptchaImg)
	}
}

// TestParseError_NonCaptchaError проверяет, что не-captcha ошибка (например,
// rate limit 29) не считается actionable captcha.
func TestParseError_NonCaptchaError(t *testing.T) {
	errData := map[string]any{
		"error_code": float64(29),
		"error_msg":  "Rate limit reached",
	}
	e := ParseError(errData)
	if e != nil && e.IsCaptcha() {
		t.Fatalf("non-captcha error mis-detected as captcha: %+v", e)
	}
}

// TestParseError_MissingErrorCode: без error_code возвращаем nil.
func TestParseError_MissingErrorCode(t *testing.T) {
	if e := ParseError(map[string]any{"error_msg": "boom"}); e != nil {
		t.Fatalf("expected nil for missing error_code; got %+v", e)
	}
}
