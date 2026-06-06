package yandex

import (
	"net/url"
	"regexp"
	"strings"
)

var (
	// Поддерживаемые формы room hash:
	//   .../j/HASH            (telemost.yandex.ru/j/, ya.ru/telemost/j/)
	//   .../calls/HASH        (новый короткий формат ya.ru/calls/HASH)
	// Хвост ?lang=ru/#... срезается в cleanTail.
	telemostJoinRE = regexp.MustCompile(`(?i)(?:telemost/j/|/j/|telemost/calls/|/calls/)([A-Za-z0-9_\-]+)`)
	checkboxRE     = regexp.MustCompile(`(?i)<input[^>]+type\s*=\s*["']checkbox["']`)
)

// NormalizeLink извлекает room hash из ссылки Телемост.
// Возвращает hash или пустую строку.
func NormalizeLink(raw string) string {
	s := strings.Trim(raw, " <>\"'")
	if s == "" {
		return ""
	}
	lower := strings.ToLower(s)

	if strings.HasPrefix(lower, "yandex://telemost") {
		path := strings.TrimPrefix(s[len("yandex://telemost"):], "/")
		if m := telemostJoinRE.FindStringSubmatch("telemost/" + path); len(m) > 1 {
			return cleanTail(m[1])
		}
		if strings.HasPrefix(strings.ToLower(path), "j/") {
			return cleanTail(path[2:])
		}
	}

	prefixes := []string{
		"https://telemost.yandex.ru/j/",
		"http://telemost.yandex.ru/j/",
		"telemost.yandex.ru/j/",
		"https://telemost.yandex.ru/calls/",
		"http://telemost.yandex.ru/calls/",
		"telemost.yandex.ru/calls/",
		"https://ya.ru/telemost/j/",
		"http://ya.ru/telemost/j/",
		"ya.ru/telemost/j/",
		"https://ya.ru/calls/",
		"http://ya.ru/calls/",
		"ya.ru/calls/",
	}
	for _, prefix := range prefixes {
		if strings.HasPrefix(lower, prefix) {
			return cleanTail(s[len(prefix):])
		}
	}

	if m := telemostJoinRE.FindStringSubmatch(s); len(m) > 1 {
		return cleanTail(m[1])
	}

	if u, err := url.Parse(s); err == nil && u.Path != "" {
		if m := telemostJoinRE.FindStringSubmatch(u.Path); len(m) > 1 {
			return cleanTail(m[1])
		}
	}

	return ""
}

// JoinURL возвращает каноническую join-ссылку для API Телемост.
func JoinURL(hash string) string {
	return "https://telemost.yandex.ru/j/" + hash
}

// IsRealCaptchaChallenge сообщает, есть ли в HTML явная капча Яндекса.
// Слова captcha/recaptcha в JS не считаются признаком капчи.
func IsRealCaptchaChallenge(body string) bool {
	if body == "" {
		return false
	}
	lower := strings.ToLower(body)
	if strings.Contains(lower, "докажите что вы не робот") ||
		strings.Contains(lower, "докажите, что вы не робот") {
		return true
	}
	return checkboxRE.MatchString(body)
}

func cleanTail(s string) string {
	out := s
	if i := strings.IndexAny(out, "?#"); i >= 0 {
		out = out[:i]
	}
	out = strings.Trim(out, "/ ")
	if strings.HasPrefix(out, ":") {
		out = strings.TrimSpace(strings.TrimPrefix(out, ":"))
	}
	return out
}
