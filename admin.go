package main

import (
	_ "embed"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

//go:embed admin_ui.html
var adminUIBytes []byte

// adminFcmKey is set from --fcm-key flag in main()
var adminFcmKey string

// ─── Ring log buffer ────────────────────────────────────────────────────────

type logLine struct {
	TS   int64  `json:"ts"`
	Text string `json:"text"`
}

type ringLog struct {
	mu   sync.Mutex
	buf  []logLine
	pos  int
	full bool
}

func newRingLog(n int) *ringLog { return &ringLog{buf: make([]logLine, n)} }

func (r *ringLog) Write(p []byte) (int, error) {
	line := strings.TrimRight(string(p), "\n\r")
	if line == "" {
		return len(p), nil
	}
	r.mu.Lock()
	r.buf[r.pos] = logLine{TS: time.Now().UnixMilli(), Text: line}
	r.pos = (r.pos + 1) % len(r.buf)
	if r.pos == 0 {
		r.full = true
	}
	r.mu.Unlock()
	sseHub.send(line)
	return len(p), nil
}

func (r *ringLog) Recent(n int) []logLine {
	r.mu.Lock()
	defer r.mu.Unlock()
	size := len(r.buf)
	count := size
	if !r.full {
		count = r.pos
	}
	if n < count {
		count = n
	}
	if count == 0 {
		return []logLine{}
	}
	result := make([]logLine, count)
	start := (r.pos - count + size) % size
	for i := 0; i < count; i++ {
		result[i] = r.buf[(start+i)%size]
	}
	return result
}

var adminLogBuf = newRingLog(500)

// ─── SSE broadcast hub ──────────────────────────────────────────────────────

type sseHubT struct {
	mu      sync.Mutex
	clients map[chan string]struct{}
}

func newSSEHub() *sseHubT { return &sseHubT{clients: make(map[chan string]struct{})} }

func (h *sseHubT) subscribe() chan string {
	ch := make(chan string, 64)
	h.mu.Lock()
	h.clients[ch] = struct{}{}
	h.mu.Unlock()
	return ch
}

func (h *sseHubT) unsubscribe(ch chan string) {
	h.mu.Lock()
	delete(h.clients, ch)
	h.mu.Unlock()
}

func (h *sseHubT) send(msg string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.clients {
		select {
		case ch <- msg:
		default:
		}
	}
}

var sseHub = newSSEHub()

// ─── Auth ───────────────────────────────────────────────────────────────────

func makeAdminToken(password string) string {
	sum := sha256.Sum256([]byte("skyflow-admin-v1:" + password))
	return hex.EncodeToString(sum[:])
}

func adminAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Support Bearer header and ?auth= query param (needed for SSE/EventSource)
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if tok == "" {
			tok = r.URL.Query().Get("auth")
		}
		dbMutex.Lock()
		expected := makeAdminToken(db.MainPassword)
		dbMutex.Unlock()
		if tok == "" || tok != expected {
			jsonResp(w, 401, map[string]string{"error": "unauthorized"})
			return
		}
		next(w, r)
	}
}

// ─── Helpers ────────────────────────────────────────────────────────────────

func jsonResp(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func fmtBytes(b int64) string {
	switch {
	case b >= 1<<30:
		return fmt.Sprintf("%.2f GB", float64(b)/float64(1<<30))
	case b >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(b)/float64(1<<20))
	case b >= 1<<10:
		return fmt.Sprintf("%.1f KB", float64(b)/float64(1<<10))
	default:
		return fmt.Sprintf("%d B", b)
	}
}

func cors(w http.ResponseWriter) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
}

// ─── API: Auth ──────────────────────────────────────────────────────────────

// POST /admin/api/auth
func handleAdminAuth(w http.ResponseWriter, r *http.Request) {
	cors(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(204)
		return
	}
	var req struct {
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Password == "" {
		jsonResp(w, 400, map[string]string{"error": "password required"})
		return
	}
	dbMutex.Lock()
	ok := req.Password == db.MainPassword
	token := makeAdminToken(db.MainPassword)
	dbMutex.Unlock()
	if !ok {
		jsonResp(w, 401, map[string]string{"error": "wrong password"})
		return
	}
	jsonResp(w, 200, map[string]string{"token": token})
}

// ─── API: Overview ──────────────────────────────────────────────────────────

// GET /admin/api/overview
func handleAdminOverview(w http.ResponseWriter, r *http.Request) {
	cors(w)
	fromC := atomic.LoadInt64(&totalBytesFromClient)
	toC := atomic.LoadInt64(&totalBytesToClient)
	active := atomic.LoadInt32(&activeConns)
	total := atomic.LoadInt64(&totalConns)
	uptime := time.Since(serverStartTime)

	dbMutex.Lock()
	activeSubs := 0
	for _, e := range db.Passwords {
		if !isPasswordExpired(e) && !e.IsDeactivated {
			activeSubs++
		}
	}
	numPw := len(db.Passwords)
	numDev := len(db.Devices)
	dbMutex.Unlock()

	jsonResp(w, 200, map[string]any{
		"active_conns": active,
		"total_conns":  total,
		"passwords":    numPw,
		"devices":      numDev,
		"active_subs":  activeSubs,
		"down_bytes":   toC,
		"up_bytes":     fromC,
		"down_fmt":     fmtBytes(toC),
		"up_fmt":       fmtBytes(fromC),
		"uptime":       formatUptime(uptime),
		"nat":          natType,
		"server_ip":    getPublicIP(),
	})
}

// ─── API: Licenses ──────────────────────────────────────────────────────────

type licenseItem struct {
	Password      string `json:"password"`
	DeviceID      string `json:"device_id"`
	ExpiresAt     int64  `json:"expires_at"`
	DownBytes     int64  `json:"down_bytes"`
	UpBytes       int64  `json:"up_bytes"`
	DownFmt       string `json:"down_fmt"`
	UpFmt         string `json:"up_fmt"`
	VkHash        string `json:"vk_hash"`
	Ports         string `json:"ports"`
	IsDeactivated bool   `json:"is_deactivated"`
	IsExpired     bool   `json:"is_expired"`
	LastSeenAt    int64  `json:"last_seen_at"`
	DeviceIP      string `json:"device_ip"`
	AppVersion    string `json:"app_version"`
	Link          string `json:"link"`
}

// GET /admin/api/licenses
func handleGetLicenses(w http.ResponseWriter, r *http.Request) {
	cors(w)
	srvIP := getPublicIP()
	dbMutex.Lock()
	defer dbMutex.Unlock()

	items := make([]licenseItem, 0, len(db.Passwords))
	for pass, entry := range db.Passwords {
		if entry == nil {
			continue
		}
		item := licenseItem{
			Password:      pass,
			DeviceID:      entry.DeviceID,
			ExpiresAt:     entry.ExpiresAt,
			DownBytes:     entry.DownBytes,
			UpBytes:       entry.UpBytes,
			DownFmt:       fmtBytes(entry.DownBytes),
			UpFmt:         fmtBytes(entry.UpBytes),
			VkHash:        entry.VkHash,
			Ports:         entry.Ports,
			IsDeactivated: entry.IsDeactivated,
			IsExpired:     isPasswordExpired(entry),
			LastSeenAt:    entry.LastSeenAt,
		}
		if dev, ok := db.Devices[entry.DeviceID]; ok && dev != nil {
			item.DeviceIP = dev.IP
			item.AppVersion = dev.AppVersion
		}
		// Build quick-link
		pts := strings.Split(entry.Ports, ",")
		if len(pts) == 3 && srvIP != "" {
			item.Link = fmt.Sprintf("wdtt://%s:%s:%s:%s:%s:%s", srvIP, pts[0], pts[1], pts[2], pass, entry.VkHash)
		}
		items = append(items, item)
	}
	jsonResp(w, 200, items)
}

// POST /admin/api/licenses
func handleCreateLicense(wgDev *device.Device) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cors(w)
		var req struct {
			Days   int    `json:"days"`
			VkHash string `json:"vk_hash"`
			Ports  string `json:"ports"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			jsonResp(w, 400, map[string]string{"error": "invalid json"})
			return
		}
		if req.Days <= 0 || req.Days > 365 {
			jsonResp(w, 400, map[string]string{"error": "days must be 1-365"})
			return
		}
		if req.VkHash == "" {
			jsonResp(w, 400, map[string]string{"error": "vk_hash required"})
			return
		}
		if req.Ports == "" {
			req.Ports = "56000,56001,9000"
		}
		pts := strings.Split(req.Ports, ",")
		if len(pts) != 3 {
			jsonResp(w, 400, map[string]string{"error": "ports must be 3 comma-separated values"})
			return
		}

		dbMutex.Lock()
		if len(db.Passwords) >= maxGeneratedPasswords {
			dbMutex.Unlock()
			jsonResp(w, 400, map[string]string{"error": fmt.Sprintf("limit %d licenses reached", maxGeneratedPasswords)})
			return
		}
		newPass := generatePassword()
		for i := 0; i < 100 && db.Passwords[newPass] != nil; i++ {
			newPass = generatePassword()
		}
		expiresAt := time.Now().Add(time.Duration(req.Days) * 24 * time.Hour).Unix()
		entry := &PasswordEntry{
			ExpiresAt: expiresAt,
			VkHash:    req.VkHash,
			Ports:     req.Ports,
		}
		db.Passwords[newPass] = entry
		if err := serverWrapKeys.AddPassword(newPass); err != nil {
			delete(db.Passwords, newPass)
			dbMutex.Unlock()
			jsonResp(w, 500, map[string]string{"error": "wrap key error: " + err.Error()})
			return
		}
		saveDB()
		srvIP := getPublicIP()
		dbMutex.Unlock()

		link := ""
		if srvIP != "" {
			link = fmt.Sprintf("wdtt://%s:%s:%s:%s:%s:%s", srvIP, pts[0], pts[1], pts[2], newPass, req.VkHash)
		}
		log.Printf("[ADMIN] Создана лицензия: %s, %d дн.", maskPassword(newPass), req.Days)
		jsonResp(w, 201, map[string]any{
			"password":   newPass,
			"expires_at": expiresAt,
			"link":       link,
		})
	}
}

// PATCH /admin/api/licenses/{pass}
func handleUpdateLicense(wgDev *device.Device) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cors(w)
		pass := r.PathValue("pass")
		var req struct {
			Action string `json:"action"` // extend | deactivate | activate | reset_device
			Days   int    `json:"days"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			jsonResp(w, 400, map[string]string{"error": "invalid json"})
			return
		}

		dbMutex.Lock()
		entry := db.Passwords[pass]
		if entry == nil {
			dbMutex.Unlock()
			jsonResp(w, 404, map[string]string{"error": "license not found"})
			return
		}

		switch req.Action {
		case "extend":
			if req.Days <= 0 || req.Days > 365 {
				dbMutex.Unlock()
				jsonResp(w, 400, map[string]string{"error": "days must be 1-365"})
				return
			}
			base := entry.ExpiresAt
			if base < time.Now().Unix() {
				base = time.Now().Unix()
			}
			entry.ExpiresAt = base + int64(req.Days)*86400
			log.Printf("[ADMIN] Продлена лицензия: %s на %d дн.", maskPassword(pass), req.Days)
		case "deactivate":
			entry.IsDeactivated = true
			log.Printf("[ADMIN] Деактивирована лицензия: %s", maskPassword(pass))
		case "activate":
			entry.IsDeactivated = false
			log.Printf("[ADMIN] Активирована лицензия: %s", maskPassword(pass))
		case "reset_device":
			if entry.DeviceID != "" {
				dev := db.Devices[entry.DeviceID]
				if dev != nil {
					removePeerFromWG(wgDev, dev)
				}
				delete(db.Devices, entry.DeviceID)
				entry.DeviceID = ""
				log.Printf("[ADMIN] Сброшено устройство с лицензии: %s", maskPassword(pass))
			}
		default:
			dbMutex.Unlock()
			jsonResp(w, 400, map[string]string{"error": "unknown action"})
			return
		}

		saveDB()
		dbMutex.Unlock()
		jsonResp(w, 200, map[string]string{"ok": "updated"})
	}
}

// DELETE /admin/api/licenses/{pass}
func handleDeleteLicense(wgDev *device.Device) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cors(w)
		pass := r.PathValue("pass")
		dbMutex.Lock()
		entry := db.Passwords[pass]
		if entry == nil {
			dbMutex.Unlock()
			jsonResp(w, 404, map[string]string{"error": "not found"})
			return
		}
		if entry.DeviceID != "" {
			dev := db.Devices[entry.DeviceID]
			if dev != nil {
				removePeerFromWG(wgDev, dev)
			}
			delete(db.Devices, entry.DeviceID)
		}
		delete(db.Passwords, pass)
		serverWrapKeys.RemovePassword(pass)
		saveDB()
		dbMutex.Unlock()
		log.Printf("[ADMIN] Удалена лицензия: %s", maskPassword(pass))
		jsonResp(w, 200, map[string]string{"ok": "deleted"})
	}
}

// ─── API: Devices ───────────────────────────────────────────────────────────

type deviceItem struct {
	DeviceID   string `json:"device_id"`
	Password   string `json:"password"`
	IP         string `json:"ip"`
	FcmToken   string `json:"fcm_token"`
	LastSeenAt int64  `json:"last_seen_at"`
	AppVersion string `json:"app_version"`
}

// GET /admin/api/devices
func handleGetDevices(w http.ResponseWriter, r *http.Request) {
	cors(w)
	dbMutex.Lock()
	defer dbMutex.Unlock()

	// Build reverse map: deviceID → password
	devToPass := make(map[string]string, len(db.Passwords))
	for pass, entry := range db.Passwords {
		if entry != nil && entry.DeviceID != "" {
			devToPass[entry.DeviceID] = pass
		}
	}

	items := make([]deviceItem, 0, len(db.Devices))
	for _, dev := range db.Devices {
		if dev == nil {
			continue
		}
		items = append(items, deviceItem{
			DeviceID:   dev.DeviceID,
			Password:   devToPass[dev.DeviceID],
			IP:         dev.IP,
			FcmToken:   dev.FcmToken,
			LastSeenAt: dev.LastSeenAt,
			AppVersion: dev.AppVersion,
		})
	}
	jsonResp(w, 200, items)
}

// DELETE /admin/api/devices/{id}
func handleDeleteDevice(wgDev *device.Device) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cors(w)
		id := r.PathValue("id")
		dbMutex.Lock()
		dev := db.Devices[id]
		if dev == nil {
			dbMutex.Unlock()
			jsonResp(w, 404, map[string]string{"error": "not found"})
			return
		}
		removePeerFromWG(wgDev, dev)
		// Detach from password entry
		for _, entry := range db.Passwords {
			if entry != nil && entry.DeviceID == id {
				entry.DeviceID = ""
			}
		}
		delete(db.Devices, id)
		saveDB()
		dbMutex.Unlock()
		log.Printf("[ADMIN] Удалено устройство: %s", id)
		jsonResp(w, 200, map[string]string{"ok": "deleted"})
	}
}

// ─── API: Heartbeat (device registration) ──────────────────────────────────

// POST /admin/api/heartbeat — called by Android app, authenticated by password
func handleHeartbeat(w http.ResponseWriter, r *http.Request) {
	cors(w)
	var req struct {
		DeviceID   string `json:"device_id"`
		Password   string `json:"password"`
		FcmToken   string `json:"fcm_token"`
		AppVersion string `json:"app_version"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.DeviceID == "" || req.Password == "" {
		jsonResp(w, 400, map[string]string{"error": "invalid"})
		return
	}

	dbMutex.Lock()
	entry := db.Passwords[req.Password]
	if entry == nil || isPasswordExpired(entry) || entry.IsDeactivated {
		dbMutex.Unlock()
		jsonResp(w, 403, map[string]string{"error": "invalid license"})
		return
	}
	now := time.Now().Unix()
	entry.LastSeenAt = now
	dev := db.Devices[req.DeviceID]
	if dev != nil {
		if req.FcmToken != "" {
			dev.FcmToken = req.FcmToken
		}
		dev.LastSeenAt = now
		dev.AppVersion = req.AppVersion
	}
	saveDB()
	dbMutex.Unlock()

	jsonResp(w, 200, map[string]string{"ok": "ok"})
}

// ─── API: Logs ──────────────────────────────────────────────────────────────

// GET /admin/api/logs/recent
func handleRecentLogs(w http.ResponseWriter, r *http.Request) {
	cors(w)
	lines := adminLogBuf.Recent(200)
	jsonResp(w, 200, lines)
}

// GET /admin/api/logs/stream  — SSE
func handleLogStream(w http.ResponseWriter, r *http.Request) {
	cors(w)
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(200)

	// Flush recent lines first
	for _, line := range adminLogBuf.Recent(50) {
		fmt.Fprintf(w, "data: %s\n\n", jsonMarshalStr(line.Text))
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
	}

	ch := sseHub.subscribe()
	defer sseHub.unsubscribe(ch)

	for {
		select {
		case <-r.Context().Done():
			return
		case msg := <-ch:
			fmt.Fprintf(w, "data: %s\n\n", jsonMarshalStr(msg))
			if f, ok := w.(http.Flusher); ok {
				f.Flush()
			}
		}
	}
}

func jsonMarshalStr(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// ─── API: Push notifications ────────────────────────────────────────────────

// POST /admin/api/push
func handlePush(w http.ResponseWriter, r *http.Request) {
	cors(w)
	var req struct {
		Target string `json:"target"` // "all" or device_id
		Title  string `json:"title"`
		Body   string `json:"body"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonResp(w, 400, map[string]string{"error": "invalid json"})
		return
	}
	if req.Title == "" || req.Body == "" {
		jsonResp(w, 400, map[string]string{"error": "title and body required"})
		return
	}
	if adminFcmKey == "" {
		jsonResp(w, 503, map[string]string{"error": "FCM not configured (--fcm-key missing)"})
		return
	}

	dbMutex.Lock()
	var tokens []string
	if req.Target == "all" || req.Target == "" {
		for _, dev := range db.Devices {
			if dev != nil && dev.FcmToken != "" {
				tokens = append(tokens, dev.FcmToken)
			}
		}
	} else {
		if dev := db.Devices[req.Target]; dev != nil && dev.FcmToken != "" {
			tokens = append(tokens, dev.FcmToken)
		}
	}
	dbMutex.Unlock()

	if len(tokens) == 0 {
		jsonResp(w, 404, map[string]string{"error": "no FCM tokens found for target"})
		return
	}

	sent := 0
	errs := []string{}
	for _, tok := range tokens {
		if err := sendFCMPush(tok, req.Title, req.Body, adminFcmKey); err != nil {
			errs = append(errs, err.Error())
		} else {
			sent++
		}
	}

	log.Printf("[ADMIN] Push отправлен: %d/%d, title=%q", sent, len(tokens), req.Title)
	jsonResp(w, 200, map[string]any{
		"sent":   sent,
		"total":  len(tokens),
		"errors": errs,
	})
}

// ─── FCM ────────────────────────────────────────────────────────────────────

func sendFCMPush(token, title, body, serverKey string) error {
	payload := map[string]any{
		"to": token,
		"notification": map[string]string{
			"title": title,
			"body":  body,
			"sound": "default",
		},
		"android": map[string]any{
			"priority": "high",
			"notification": map[string]any{
				"channel_id": "skyflow_updates",
				"priority":   "high",
			},
		},
		"priority": "high",
	}
	data, _ := json.Marshal(payload)
	req, err := http.NewRequest("POST", "https://fcm.googleapis.com/fcm/send", bytes.NewReader(data))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "key="+serverKey)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		respBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("FCM HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(respBody)))
	}
	return nil
}

// ─── Admin server startup ───────────────────────────────────────────────────

func startAdminServer(ctx context.Context, addr string, wgDev *device.Device) {
	// Capture log output into ring buffer (also keep stderr)
	log.SetOutput(io.MultiWriter(log.Writer(), adminLogBuf))

	mux := http.NewServeMux()

	// OPTIONS preflight for all routes
	mux.HandleFunc("OPTIONS /", func(w http.ResponseWriter, r *http.Request) {
		cors(w)
		w.WriteHeader(204)
	})

	// Auth (no middleware)
	mux.HandleFunc("POST /admin/api/auth", handleAdminAuth)

	// Heartbeat (authenticated by password, not admin token)
	mux.HandleFunc("POST /admin/api/heartbeat", handleHeartbeat)

	// Protected endpoints
	mux.HandleFunc("GET /admin/api/overview", adminAuth(handleAdminOverview))
	mux.HandleFunc("GET /admin/api/licenses", adminAuth(handleGetLicenses))
	mux.HandleFunc("POST /admin/api/licenses", adminAuth(handleCreateLicense(wgDev)))
	mux.HandleFunc("PATCH /admin/api/licenses/{pass}", adminAuth(handleUpdateLicense(wgDev)))
	mux.HandleFunc("DELETE /admin/api/licenses/{pass}", adminAuth(handleDeleteLicense(wgDev)))
	mux.HandleFunc("GET /admin/api/devices", adminAuth(handleGetDevices))
	mux.HandleFunc("DELETE /admin/api/devices/{id}", adminAuth(handleDeleteDevice(wgDev)))
	mux.HandleFunc("GET /admin/api/logs/recent", adminAuth(handleRecentLogs))
	mux.HandleFunc("GET /admin/api/logs/stream", adminAuth(handleLogStream))
	mux.HandleFunc("POST /admin/api/push", adminAuth(handlePush))

	// Static UI
	mux.HandleFunc("/admin", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(adminUIBytes)
	})
	mux.HandleFunc("/admin/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(adminUIBytes)
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			http.Redirect(w, r, "/admin", http.StatusFound)
			return
		}
		http.NotFound(w, r)
	})

	srv := &http.Server{Addr: addr, Handler: mux}
	go func() {
		log.Printf("[ADMIN] Панель управления: http://%s/admin", addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("[ADMIN] Ошибка: %v", err)
		}
	}()
	go func() {
		<-ctx.Done()
		srv.Close()
	}()
}
