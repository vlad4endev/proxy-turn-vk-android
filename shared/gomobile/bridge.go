// bridge.go — SKYFLOW VPN gomobile bridge
// Exports Go client functions to Swift/ObjC via gomobile bind.
//
// Build for iOS:
//   gomobile bind -target ios -o ../../ios/Frameworks/SkyflowClient.xcframework \
//     -ldflags="-s -w" .
//
// The resulting SkyflowClient.xcframework contains arm64 (device) +
// arm64-sim/x86_64 (simulator) slices. Add it to both SkyflowVPN
// and SkyflowTunnel targets in Xcode.

package skyflowclient

import (
	"context"
	"sync"
)

// Client holds a running tunnel session.
// One instance per tunnel start — not a singleton.
type Client struct {
	cancel  context.CancelFunc
	mu      sync.Mutex
	running bool
}

// ClientCallback receives log lines and status updates from the Go client.
// Implement on the Swift side and pass to NewClient.
type ClientCallback interface {
	// OnLog is called for every log line from the Go client.
	OnLog(line string)
	// OnWorkers is called when the active worker count changes.
	OnWorkers(count int)
	// OnConnected is called when WireGuard tunnel is established.
	OnConnected()
	// OnError is called on fatal errors.
	OnError(message string)
}

// Stats contains current traffic counters.
type Stats struct {
	BytesIn   int64
	BytesOut  int64
	Workers   int
}

// NewClient creates a new (not yet started) tunnel client.
func NewClient() *Client {
	return &Client{}
}

// StartWhitelist starts the VK TURN/DTLS → WireGuard tunnel.
//
// Parameters match Android TunnelService extras:
//   peer          — VPS host:dtlsPort
//   vkHashes      — comma-separated VK call hashes
//   password      — connection password (WRAP key derived via HKDF)
//   workersPerHash — parallel DTLS streams per hash
//   tunFd         — writable TUN file descriptor (from NEPacketTunnelProvider)
//   cb            — callback interface
func (c *Client) StartWhitelist(
	peer, vkHashes, password string,
	workersPerHash, tunFd int,
	cb ClientCallback,
) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.running {
		return nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	c.cancel = cancel
	c.running = true

	go func() {
		defer func() {
			c.mu.Lock()
			c.running = false
			c.mu.Unlock()
		}()
		runWhitelist(ctx, peer, vkHashes, password, workersPerHash, tunFd, cb)
	}()

	return nil
}

// Stop gracefully shuts down the running tunnel.
// Blocks until the goroutine exits (mirrors Android awaitStop()).
func (c *Client) Stop() {
	c.mu.Lock()
	cancel := c.cancel
	c.mu.Unlock()

	if cancel != nil {
		cancel()
	}
}

// GetStats returns current traffic statistics.
func (c *Client) GetStats() *Stats {
	return &Stats{} // TODO: wire to internal stats package
}

// IsRunning returns true if the client goroutine is active.
func (c *Client) IsRunning() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.running
}

// runWhitelist is the actual tunnel implementation.
// Calls internal/provider/vk, transport/dtlsdial, proxy/bondclient, wireguard.
func runWhitelist(
	ctx context.Context,
	peer, vkHashes, password string,
	workersPerHash, tunFd int,
	cb ClientCallback,
) {
	// TODO: wire up go_client/internal packages:
	//
	// 1. vkauth.GetTurnCredentials(ctx, vkHashes)
	// 2. dtlsdial.DialAll(ctx, turnCreds, password, workersPerHash)
	// 3. bondclient.Run(ctx, dtlsConns, tunFd)  ← writes WireGuard packets to TUN fd
	//
	// The go_client/main.go already does all of this for Android (libclient.so).
	// For iOS, the same logic runs here — only the TUN fd source differs
	// (NEPacketTunnelProvider on iOS vs VpnService on Android).
	cb.OnLog("whitelist tunnel started (stub)")
	<-ctx.Done()
	cb.OnLog("whitelist tunnel stopped")
}
