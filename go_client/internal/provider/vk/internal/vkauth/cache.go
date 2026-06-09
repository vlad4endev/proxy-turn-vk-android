package vkauth

import (
	"encoding/json"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// StreamCredentialsCache хранит TURN-реквизиты для группы потоков
// и счётчик ошибок для решений об инвалидации.
type StreamCredentialsCache struct {
	creds         TurnCredentials
	mutex         sync.RWMutex
	errorCount    atomic.Int32
	lastErrorTime atomic.Int64
}

// Store отображает cache-id (streamID / streamsPerCache) → StreamCredentialsCache.
type Store struct {
	mu              sync.RWMutex
	caches          map[int]*StreamCredentialsCache
	streamsPerCache int
	cacheFile       string // путь к файлу персистентного кэша, "" → без файла
}

// credCacheEntry — JSON-представление одного cacheID в файле.
type credCacheEntry struct {
	Username    string    `json:"username"`
	Password    string    `json:"password"`
	ServerAddrs []string  `json:"server_addrs"`
	ExpiresAt   time.Time `json:"expires_at"`
	Link        string    `json:"link"`
}

// credCacheFile — корневой JSON-объект файла.
type credCacheFile struct {
	Caches map[int]credCacheEntry `json:"caches"`
}

func NewStore(streamsPerCache int, cacheFile string) *Store {
	if streamsPerCache <= 0 {
		streamsPerCache = DefaultStreamsPerCache
	}
	s := &Store{
		caches:          make(map[int]*StreamCredentialsCache),
		streamsPerCache: streamsPerCache,
		cacheFile:       cacheFile,
	}
	if cacheFile != "" {
		s.loadFromFile()
	}
	return s
}

func (s *Store) CacheID(streamID int) int {
	return streamID / s.streamsPerCache
}

func (s *Store) Get(streamID int) *StreamCredentialsCache {
	cacheID := s.CacheID(streamID)

	s.mu.RLock()
	cache, exists := s.caches[cacheID]
	s.mu.RUnlock()
	if exists {
		return cache
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if cache, exists = s.caches[cacheID]; exists {
		return cache
	}
	cache = &StreamCredentialsCache{}
	s.caches[cacheID] = cache
	return cache
}

// SaveToFile сохраняет все не истёкшие creds на диск (no-op если cacheFile пустой).
func (s *Store) SaveToFile() {
	if s.cacheFile == "" {
		return
	}

	cf := credCacheFile{Caches: make(map[int]credCacheEntry)}

	s.mu.RLock()
	for cacheID, cache := range s.caches {
		cache.mutex.RLock()
		if time.Now().Before(cache.creds.ExpiresAt) && len(cache.creds.ServerAddrs) > 0 {
			cf.Caches[cacheID] = credCacheEntry{
				Username:    cache.creds.Username,
				Password:    cache.creds.Password,
				ServerAddrs: cache.creds.ServerAddrs,
				ExpiresAt:   cache.creds.ExpiresAt,
				Link:        cache.creds.Link,
			}
		}
		cache.mutex.RUnlock()
	}
	s.mu.RUnlock()

	if len(cf.Caches) == 0 {
		_ = os.Remove(s.cacheFile) // нечего хранить — убираем устаревший файл
		return
	}

	data, err := json.Marshal(cf)
	if err != nil {
		return
	}
	_ = os.WriteFile(s.cacheFile, data, 0600)
}

// loadFromFile читает файл кэша и заполняет store не истёкшими записями.
func (s *Store) loadFromFile() {
	data, err := os.ReadFile(s.cacheFile)
	if err != nil {
		return // файла нет — нормально при первом запуске
	}

	var cf credCacheFile
	if err := json.Unmarshal(data, &cf); err != nil {
		return
	}

	now := time.Now()
	for cacheID, entry := range cf.Caches {
		if now.After(entry.ExpiresAt) {
			continue // истёкшие не грузим
		}
		cache := &StreamCredentialsCache{}
		cache.creds = TurnCredentials{
			Username:    entry.Username,
			Password:    entry.Password,
			ServerAddrs: entry.ServerAddrs,
			ExpiresAt:   entry.ExpiresAt,
			Link:        entry.Link,
		}
		s.caches[cacheID] = cache
	}
}

// Invalidate сбрасывает реквизиты кэша потока и обнуляет счётчик ошибок.
func (c *StreamCredentialsCache) Invalidate() {
	c.mutex.Lock()
	c.creds = TurnCredentials{}
	c.mutex.Unlock()

	c.errorCount.Store(0)
	c.lastErrorTime.Store(0)
}

// IsAuthError проверяет ошибку по эвристике TURN-клиента:
// auth/401/stale-nonce — признак инвалидации кэша.
func IsAuthError(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	return strings.Contains(s, "401") ||
		strings.Contains(s, "Unauthorized") ||
		strings.Contains(s, "authentication") ||
		strings.Contains(s, "invalid credential") ||
		strings.Contains(s, "stale nonce")
}
