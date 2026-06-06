// Package yandex — провайдер TURN-реквизитов через Яндекс Телемост.
// Не использует VK Smart Captcha; капча определяется только по явной HTML-форме.
package yandex

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/provider"
)

const (
	credentialLifetime = 10 * time.Minute
	defaultStreamsPer  = 10
	maxCacheErrors     = 3
)

// Config — параметры Yandex-провайдера.
type Config struct {
	Link            string
	StreamsPerCache int
	Log             logx.Logger
}

type cachedCreds struct {
	user string
	pass string
	addr string
	at   time.Time
}

type cacheEntry struct {
	creds      cachedCreds
	errorCount atomic.Int32
	mu         sync.Mutex
}

// Provider реализует provider.Provider для Яндекс Телемост.
type Provider struct {
	hash            string
	streamsPerCache int
	log             logx.Logger
	mu              sync.Mutex
	caches          map[int]*cacheEntry
}

// New создаёт Yandex-провайдер.
func New(cfg Config) (*Provider, error) {
	hash := NormalizeLink(cfg.Link)
	if hash == "" {
		return nil, fmt.Errorf("yandex: invalid telemost link %q", cfg.Link)
	}
	streamsPer := cfg.StreamsPerCache
	if streamsPer <= 0 {
		streamsPer = defaultStreamsPer
	}
	return &Provider{
		hash:            hash,
		streamsPerCache: streamsPer,
		log:             logx.OrNop(cfg.Log),
		caches:          make(map[int]*cacheEntry),
	}, nil
}

func (p *Provider) cacheID(streamID int) int { return streamID / p.streamsPerCache }

func (p *Provider) entry(streamID int) *cacheEntry {
	id := p.cacheID(streamID)
	p.mu.Lock()
	defer p.mu.Unlock()
	e, ok := p.caches[id]
	if !ok {
		e = &cacheEntry{}
		p.caches[id] = e
	}
	return e
}

// GetCredentials реализует provider.Provider.
func (p *Provider) GetCredentials(ctx context.Context, streamID int) (provider.Credentials, error) {
	entry := p.entry(streamID)
	entry.mu.Lock()
	defer entry.mu.Unlock()

	if c := entry.creds; c.user != "" && time.Since(c.at) < credentialLifetime {
		return provider.Credentials{User: c.user, Pass: c.pass, ServerAddr: c.addr, ExpiresAt: c.at.Add(credentialLifetime)}, nil
	}

	user, pass, addr, err := fetchTurnCreds(ctx, p.hash, streamID, p.log)
	if err != nil {
		return provider.Credentials{}, err
	}
	entry.creds = cachedCreds{user: user, pass: pass, addr: addr, at: time.Now()}
	entry.errorCount.Store(0)
	return provider.Credentials{
		User:       user,
		Pass:       pass,
		ServerAddr: addr,
		ExpiresAt:  time.Now().Add(credentialLifetime),
	}, nil
}

func (p *Provider) IsAuthError(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	return strings.Contains(s, "401") ||
		strings.Contains(s, "Unauthorized") ||
		strings.Contains(s, "authentication") ||
		strings.Contains(s, "invalid credential")
}

func (p *Provider) HandleAuthError(streamID int) bool {
	entry := p.entry(streamID)
	n := entry.errorCount.Add(1)
	if int(n) >= maxCacheErrors {
		entry.mu.Lock()
		entry.creds = cachedCreds{}
		entry.mu.Unlock()
		entry.errorCount.Store(0)
		return true
	}
	return false
}

func (p *Provider) ResetErrors(streamID int) { p.entry(streamID).errorCount.Store(0) }

func (p *Provider) BackoffUntilUnix() int64 { return 0 }

func (p *Provider) Name() string { return "yandex" }
