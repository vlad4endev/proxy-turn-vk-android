package vkauth

import (
	"errors"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/provider"
)

// VKCredentials — пара app_id/app_secret для получения анонимных токенов.
type VKCredentials struct {
	ClientID     string
	ClientSecret string
}

// TurnCredentials — разрешённые TURN-реквизиты для группы потоков.
type TurnCredentials struct {
	Username    string
	Password    string
	ServerAddrs []string
	ExpiresAt   time.Time
	Link        string
}

// DefaultCredentials — два проверенных app_id из VK Calls (v1.1.8).
// Остальные client_id вызывают капчу, error_code 9008/3 и «Unknown method passed».
//
//nolint:gosec // public VK SDK app credentials, not user secrets
var DefaultCredentials = []VKCredentials{
	{ClientID: "6287487", ClientSecret: "MuAxFaKDYDOICzGnEOhp"},
	{ClientID: "8202606", ClientSecret: "lMRsTiMCyPnp5vfoldmn"},
}

const (
	CredentialLifetime = 10 * time.Minute
	CacheSafetyMargin  = 60 * time.Second
	MaxCacheErrors     = 3
	ErrorWindow        = 10 * time.Second

	DefaultStreamsPerCache = 10
)

// Sentinel-ошибки auth-потока. Строковые формы стабильны (используются в логах).
//
// ErrCaptchaWaitRequired и ErrFatalCaptchaNoStreams также матчатся через
// provider.ErrBackoffActive / provider.ErrFatalNoStreams — pipeline проверяет
// generic-sentinels, vkauth-внутренний код может проверять и старые.
var (
	ErrCaptchaWaitRequired   = errors.Join(provider.ErrBackoffActive, errors.New("CAPTCHA_WAIT_REQUIRED"))
	ErrFatalCaptchaNoStreams = errors.Join(provider.ErrFatalNoStreams, errors.New("FATAL_CAPTCHA_FAILED_NO_STREAMS"))
	ErrLockoutActive         = errors.New("global lockout active")
)
