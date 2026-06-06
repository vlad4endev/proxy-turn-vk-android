package vkauth

import (
	"context"
	"fmt"
	neturl "net/url"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/browserprofile"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk/internal/captcha"

	tlsclient "github.com/bogdanfinn/tls-client"
)

// fetchCallToken — шаг 2 цепочки: вызывает calls.getAnonymousToken и ведёт
// цикл retry captcha до получения call-токена или исчерпания всех режимов решения.
func (c *Client) fetchCallToken(
	ctx context.Context,
	httpClient tlsclient.HttpClient,
	profile browserprofile.Profile,
	streamID int,
	link, escapedName, token1 string,
	creds VKCredentials,
) (string, error) {
	urlAddr := fmt.Sprintf("https://api.vk.ru/method/calls.getAnonymousToken?v=5.275&client_id=%s", creds.ClientID)
	data := fmt.Sprintf("vk_join_link=https://vk.com/call/join/%s&name=%s&access_token=%s",
		link, escapedName, token1)

	for attempt := 0; ; attempt++ {
		resp, err := c.doRequest(ctx, httpClient, profile, data, urlAddr)
		if err != nil {
			return "", err
		}

		if errObj, hasErr := resp["error"].(map[string]any); hasErr {
			captchaErr := captcha.ParseError(errObj)
			if captchaErr != nil && captchaErr.IsCaptcha() {
				retryData, err := c.solveCaptcha(ctx, httpClient, profile, streamID, attempt, link, escapedName, token1, captchaErr)
				if err != nil {
					return "", err
				}
				data = retryData
				continue
			}
			return "", fmt.Errorf("VK API error: %v", errObj)
		}

		respMap, ok := resp["response"].(map[string]any)
		if !ok {
			return "", fmt.Errorf("unexpected getAnonymousToken response: %v", resp)
		}
		token2, ok := respMap["token"].(string)
		if !ok {
			return "", fmt.Errorf("missing token in response: %v", resp)
		}
		return token2, nil
	}
}

// solveCaptcha выполняет цепочку решения captcha и возвращает тело POST
// для следующего retry или ошибку при исчерпании всех режимов.
func (c *Client) solveCaptcha(
	ctx context.Context,
	httpClient tlsclient.HttpClient,
	profile browserprofile.Profile,
	streamID, attempt int,
	link, escapedName, token1 string,
	captchaErr *captcha.Error,
) (retryData string, err error) {
	if attempt > 0 {
		c.log.Warnf("[STREAM %d] [Captcha] No more solve modes available (attempt %d)", streamID, attempt+1)
		c.engageLockout(60 * time.Second)
		if c.streamsFn() == 0 {
			c.log.Errorf("[STREAM %d] [Captcha] FATAL: 0 connected streams and solve modes exhausted", streamID)
			return "", ErrFatalCaptchaNoStreams
		}
		return "", ErrCaptchaWaitRequired
	}

	c.log.Infof("[STREAM %d] [Captcha] Solving captcha...", streamID)
	successToken, solveErr := c.runCaptchaChain(ctx, httpClient, profile, streamID, captchaErr)

	if solveErr != nil {
		c.log.Warnf("[STREAM %d] [Captcha] captcha chain failed (attempt %d): %v", streamID, attempt+1, solveErr)
		c.engageLockout(60 * time.Second)
		if c.streamsFn() == 0 {
			c.log.Errorf("[STREAM %d] [Captcha] FATAL: 0 connected streams and captcha chain failed", streamID)
			return "", ErrFatalCaptchaNoStreams
		}
		return "", ErrCaptchaWaitRequired
	}

	if successToken == "" {
		c.engageLockout(60 * time.Second)
		if c.streamsFn() == 0 {
			return "", ErrFatalCaptchaNoStreams
		}
		return "", ErrCaptchaWaitRequired
	}

	c.log.Infof("[STREAM %d] [Captcha] solver succeeded", streamID)
	if captchaErr.CaptchaAttempt == "0" || captchaErr.CaptchaAttempt == "" {
		captchaErr.CaptchaAttempt = "1"
	}
	return buildCaptchaRetryData(link, escapedName, token1, captchaErr, successToken, ""), nil
}

// buildCaptchaRetryData формирует тело POST для следующей попытки captcha.
func buildCaptchaRetryData(link, escapedName, token1 string, captchaErr *captcha.Error, successToken, captchaKey string) string {
	if captchaKey != "" {
		return fmt.Sprintf(
			"vk_join_link=https://vk.com/call/join/%s&name=%s&captcha_key=%s&captcha_sid=%s&access_token=%s",
			link, escapedName, neturl.QueryEscape(captchaKey), captchaErr.CaptchaSid, token1,
		)
	}
	return fmt.Sprintf(
		"vk_join_link=https://vk.com/call/join/%s&name=%s&captcha_key=&captcha_sid=%s&is_sound_captcha=0&success_token=%s&captcha_ts=%s&captcha_attempt=%s&access_token=%s",
		link, escapedName, captchaErr.CaptchaSid, neturl.QueryEscape(successToken),
		captchaErr.CaptchaTs, captchaErr.CaptchaAttempt, token1,
	)
}
