package vkauth

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/captcha"
)

const (
	// 30с (было 10с): авто-WebView вызывается ОДИН раз, поэтому даём Android
	// достаточно времени на решение вместо серии быстрых таймаутов/само-отмен.
	captchaAutoWebViewTimeout     = 30 * time.Second
	captchaManualWebViewTimeout   = 60 * time.Second
	captchaSelectedWebViewTimeout = 120 * time.Second
)

// captchaResultCh принимает success_token от Android через stdin (CAPTCHA_RESULT|...).
var captchaResultCh chan string

// SetCaptchaResultChannel подключает канал результатов WebView-решателя.
func SetCaptchaResultChannel(ch chan string) {
	captchaResultCh = ch
}

func drainCaptchaResult() {
	if captchaResultCh == nil {
		return
	}
	select {
	case <-captchaResultCh:
	default:
	}
}

// requestWebViewCaptcha просит Android решить captcha через WebView.
// Печатает CAPTCHA_SOLVE|mode|redirect_uri|session_token в stdout.
func requestWebViewCaptcha(streamID int, captchaErr *captcha.Error, mode string, timeout time.Duration) (string, error) {
	if captchaResultCh == nil {
		return "", fmt.Errorf("webview captcha channel not configured")
	}
	if captchaErr == nil || captchaErr.RedirectURI == "" || captchaErr.SessionToken == "" {
		return "", fmt.Errorf("webview captcha data is incomplete")
	}
	mode = strings.ToLower(strings.TrimSpace(mode))
	if mode != "manual" && mode != "selected" {
		mode = "auto"
	}
	if timeout <= 0 {
		timeout = captchaAutoWebViewTimeout
	}

	drainCaptchaResult()
	fmt.Printf("CAPTCHA_SOLVE|%s|%s|%s\n", mode, captchaErr.RedirectURI, captchaErr.SessionToken)

	waitCtx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	select {
	case result := <-captchaResultCh:
		result = strings.TrimSpace(result)
		if result == "" {
			return "", fmt.Errorf("webview captcha returned empty result")
		}
		lowerResult := strings.ToLower(result)
		if lowerResult == "error:timeout" {
			return "", fmt.Errorf("webview captcha timed out")
		}
		if strings.HasPrefix(lowerResult, "error:") {
			return "", fmt.Errorf("webview captcha failed: %s", result)
		}
		captcha.Log.Infof("[STREAM %d] [КАПЧА] WBV: %s solve succeeded", streamID, mode)
		return result, nil
	case <-waitCtx.Done():
		return "", fmt.Errorf("webview captcha timed out")
	}
}

func isWebViewCaptchaTimeout(err error) bool {
	return err != nil && strings.Contains(strings.ToLower(err.Error()), "timed out")
}
