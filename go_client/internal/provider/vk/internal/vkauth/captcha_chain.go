package vkauth

import (
	"context"
	"fmt"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/captcha"
	"github.com/samosvalishe/free-turn-proxy/internal/randx"

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
		token, err := c.runGoAutoSolve(ctx, captchaErr, streamID, httpClient, profile, 2)
		if err == nil {
			return token, nil
		}
		if ctx.Err() != nil {
			return "", err
		}
		c.log.Warnf("[STREAM %d] [КАПЧА] RJS: ошибка, fallback на WBV Auto: %v", streamID, err)
		token, err = requestWebViewCaptcha(streamID, captchaErr, "auto", captchaAutoWebViewTimeout)
		if err == nil {
			return token, nil
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

	token, solveErr := c.runGoAutoSolve(ctx, captchaErr, streamID, httpClient, profile, 2)
	if solveErr == nil {
		c.log.Infof("[STREAM %d] [КАПЧА] AUTO: Go v2 решил капчу", streamID)
		return token, nil
	}
	if ctx.Err() != nil {
		return "", solveErr
	}
	lastErr := solveErr
	c.log.Warnf("[STREAM %d] [КАПЧА] AUTO: Go v2 не решил за 2 попытки: %v", streamID, solveErr)

	for wbvAttempt := 1; wbvAttempt <= 2; wbvAttempt++ {
		c.log.Infof("[STREAM %d] [КАПЧА] AUTO: WBV Auto попытка %d/2 (timeout %s)", streamID, wbvAttempt, captchaAutoWebViewTimeout)
		token, solveErr = requestWebViewCaptcha(streamID, captchaErr, "auto", captchaAutoWebViewTimeout)
		if solveErr == nil {
			c.log.Infof("[STREAM %d] [КАПЧА] AUTO: WBV Auto решил капчу", streamID)
			return token, nil
		}
		if ctx.Err() != nil {
			return "", solveErr
		}
		lastErr = solveErr
		if isWebViewCaptchaTimeout(solveErr) {
			c.log.Warnf("[STREAM %d] [КАПЧА] AUTO: WBV Auto timeout %d/2", streamID, wbvAttempt)
		} else {
			c.log.Warnf("[STREAM %d] [КАПЧА] AUTO: WBV Auto ошибка %d/2: %v", streamID, wbvAttempt, solveErr)
		}

		timer := time.NewTimer(time.Duration(250+randx.Intn(250)) * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return "", ctx.Err()
		case <-timer.C:
		}
	}

	c.log.Infof("[STREAM %d] [КАПЧА] AUTO: финальная Go v2 попытка после WBV", streamID)
	token, solveErr = c.runGoAutoSolve(ctx, captchaErr, streamID, httpClient, profile, 1)
	if solveErr == nil {
		c.log.Infof("[STREAM %d] [КАПЧА] AUTO: финальная Go v2 решила капчу", streamID)
		return token, nil
	}
	if ctx.Err() != nil {
		return "", solveErr
	}
	lastErr = solveErr
	c.log.Warnf("[STREAM %d] [КАПЧА] AUTO: финальная Go v2 ошибка: %v", streamID, solveErr)

	c.log.Infof("[STREAM %d] [КАПЧА] AUTO: автоцепочка не прошла, открыт ручной WebView", streamID)
	token, solveErr = requestWebViewCaptcha(streamID, captchaErr, "manual", captchaManualWebViewTimeout)
	if solveErr == nil {
		c.log.Infof("[STREAM %d] [КАПЧА] AUTO: ручной WebView решил капчу", streamID)
		return token, nil
	}

	return c.runProxyManualFallback(ctx, captchaErr, streamID, fmt.Errorf("automatic captcha chain failed: %w; manual webview failed: %v", lastErr, solveErr))
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
