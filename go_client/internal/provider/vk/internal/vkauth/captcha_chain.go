package vkauth

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/captcha"

	tlsclient "github.com/bogdanfinn/tls-client"
)

// runCaptchaChain выполняет полную цепочку решения captcha согласно CaptchaMode.
func (c *Client) runCaptchaChain(
	ctx context.Context,
	httpClient tlsclient.HttpClient,
	profile browserprofile.Profile,
	streamID int,
	captchaErr *captcha.Error,
) (string, error) {
	if c.manualOnly {
		c.log.Infof("[STREAM %d] [КАПЧА] manual-only: WebView manual", streamID)
		token, err := requestWebViewCaptcha(streamID, captchaErr, "manual", captchaManualWebViewTimeout)
		if err == nil {
			return token, nil
		}
		return c.runProxyManualFallback(ctx, captchaErr, streamID, err)
	}

	switch c.captchaMode {
	case "wv":
		c.log.Infof("[STREAM %d] [КАПЧА] WBV: режим из настроек Android", streamID)
		token, err := requestWebViewCaptcha(streamID, captchaErr, "selected", captchaSelectedWebViewTimeout)
		if err == nil {
			return token, nil
		}
		return c.runProxyManualFallback(ctx, captchaErr, streamID, err)

	case "rjs":
		c.log.Infof("[STREAM %d] [КАПЧА] RJS: Go v2 выбран в настройках", streamID)
		token, err := c.runGoAutoSolve(ctx, captchaErr, streamID, httpClient, profile, 1)
		if err == nil {
			return token, nil
		}
		if ctx.Err() != nil {
			return "", err
		}
		if errors.Is(err, captcha.ErrCaptchaRateLimit) {
			return c.abortOnRateLimit(streamID, err)
		}
		c.log.Warnf("[STREAM %d] [КАПЧА] RJS: ошибка, fallback на WBV Auto: %v", streamID, err)
		token, err = requestWebViewCaptcha(streamID, captchaErr, "auto", captchaAutoWebViewTimeout)
		if err == nil {
			return token, nil
		}
		if errors.Is(err, captcha.ErrCaptchaRateLimit) {
			return c.abortOnRateLimit(streamID, err)
		}
		return c.runProxyManualFallback(ctx, captchaErr, streamID, err)

	default: // auto
		return c.runAutoCaptchaChain(ctx, httpClient, profile, streamID, captchaErr)
	}
}

func (c *Client) runAutoCaptchaChain(
	ctx context.Context,
	httpClient tlsclient.HttpClient,
	profile browserprofile.Profile,
	streamID int,
	captchaErr *captcha.Error,
) (string, error) {
	c.log.Infof("[STREAM %d] [КАПЧА] AUTO: старт цепочки", streamID)

	// Принцип: ОДНА попытка на метод, аборт при VK rate-limit. Раньше цепочка
	// делала Go×2 → WBV×2 (2-я отменяла 1-ю) → Go×1 → … — ~5 check-запросов по
	// одной сессии за ~13с, из-за чего VK банил сессию (error_limit) и всё
	// последующее (включая прокси) было бесполезно.

	// 1) Одна тихая Go-v2 попытка.
	token, solveErr := c.runGoAutoSolve(ctx, captchaErr, streamID, httpClient, profile, 1)
	if solveErr == nil {
		c.log.Infof("[STREAM %d] [КАПЧА] AUTO: Go v2 решил капчу", streamID)
		return token, nil
	}
	if ctx.Err() != nil {
		return "", solveErr
	}
	if errors.Is(solveErr, captcha.ErrCaptchaRateLimit) {
		return c.abortOnRateLimit(streamID, solveErr)
	}
	lastErr := solveErr
	c.log.Warnf("[STREAM %d] [КАПЧА] AUTO: Go v2 не решил: %v", streamID, solveErr)

	// 2) Одна авто-WebView попытка (щедрый таймаут, без цикла и само-отмены).
	c.log.Infof("[STREAM %d] [КАПЧА] AUTO: WBV Auto (timeout %s)", streamID, captchaAutoWebViewTimeout)
	token, solveErr = requestWebViewCaptcha(streamID, captchaErr, "auto", captchaAutoWebViewTimeout)
	if solveErr == nil {
		c.log.Infof("[STREAM %d] [КАПЧА] AUTO: WBV Auto решил капчу", streamID)
		return token, nil
	}
	if ctx.Err() != nil {
		return "", solveErr
	}
	if errors.Is(solveErr, captcha.ErrCaptchaRateLimit) {
		return c.abortOnRateLimit(streamID, solveErr)
	}
	lastErr = solveErr
	if isWebViewCaptchaTimeout(solveErr) {
		c.log.Warnf("[STREAM %d] [КАПЧА] AUTO: WBV Auto timeout", streamID)
	} else {
		c.log.Warnf("[STREAM %d] [КАПЧА] AUTO: WBV Auto не решил: %v", streamID, solveErr)
	}

	// 3) Ручной интерактивный WebView — пользователь решает сам.
	c.log.Infof("[STREAM %d] [КАПЧА] AUTO: открыт ручной WebView", streamID)
	token, solveErr = requestWebViewCaptcha(streamID, captchaErr, "manual", captchaManualWebViewTimeout)
	if solveErr == nil {
		c.log.Infof("[STREAM %d] [КАПЧА] AUTO: ручной WebView решил капчу", streamID)
		return token, nil
	}
	if errors.Is(solveErr, captcha.ErrCaptchaRateLimit) {
		return c.abortOnRateLimit(streamID, solveErr)
	}

	// 4) Локальный прокси :8765 — последний резерв (пропустит rate-limited сессию).
	return c.runProxyManualFallback(ctx, captchaErr, streamID, fmt.Errorf("automatic captcha chain failed: %w; manual webview failed: %v", lastErr, solveErr))
}

// abortOnRateLimit вызывается, когда VK ответил error_limit по текущей сессии
// капчи. Дальнейшие попытки по той же сессии (WBV/прокси) лишь усугубляют лимит,
// поэтому ставим lockout — стрим отступит и на следующем цикле получит свежий
// not_robot challenge вместо долбёжки отравленной сессии.
func (c *Client) abortOnRateLimit(streamID int, err error) (string, error) {
	c.log.Warnf("[STREAM %d] [КАПЧА] VK ограничил проверку (rate limit) — отступаем, свежая капча позже", streamID)
	c.engageLockout(90 * time.Second)
	return "", err
}

func (c *Client) runGoAutoSolve(
	ctx context.Context,
	captchaErr *captcha.Error,
	streamID int,
	httpClient tlsclient.HttpClient,
	profile browserprofile.Profile,
	maxAttempts int,
) (string, error) {
	if maxAttempts < 1 {
		maxAttempts = 1
	}
	solveFn := c.autoSolver
	if solveFn == nil {
		return AutoSolveWithMaxAttempts(ctx, captchaErr, streamID, httpClient, profile, maxAttempts)
	}
	return solveFn(ctx, captchaErr, streamID, httpClient, profile)
}

func (c *Client) runProxyManualFallback(
	ctx context.Context,
	captchaErr *captcha.Error,
	streamID int,
	priorErr error,
) (string, error) {
	// Сессия уже отравлена VK rate-limit — прокси по той же сессии не поможет,
	// только усилит лимит. Отступаем.
	if errors.Is(priorErr, captcha.ErrCaptchaRateLimit) {
		return c.abortOnRateLimit(streamID, priorErr)
	}
	if c.manualSolve == nil {
		return "", priorErr
	}
	c.log.Infof("[STREAM %d] [КАПЧА] AUTO: fallback на локальный прокси :8765", streamID)
	manualCtx, manualCancel := context.WithTimeout(ctx, 3*time.Minute)
	defer manualCancel()

	token, _, err := c.manualSolve(manualCtx, captchaErr, c.dialer)
	if token != "" {
		c.log.Infof("[STREAM %d] [Captcha] Got token from browser", streamID)
		return token, nil
	}
	if err != nil {
		return "", fmt.Errorf("%w; proxy fallback failed: %v", priorErr, err)
	}
	return "", priorErr
}
