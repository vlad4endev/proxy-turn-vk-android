#!/usr/bin/env bash
# ============================================================
# WDTT — сборка сервера и деплой на VPS
# Запускать локально: ./scripts/deploy-to-vps.sh
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VPS_HOST="${VPS_HOST:-192.145.30.132}"
VPS_USER="${VPS_USER:-root}"
VPS_SSH_PORT="${VPS_SSH_PORT:-22}"
WDTT_PASSWORD="${WDTT_PASSWORD:-tunnel2026}"

echo "══════════════════════════════════════════"
echo "   WDTT Server — деплой на VPS"
echo "   VPS: ${VPS_USER}@${VPS_HOST}:${VPS_SSH_PORT}"
echo "══════════════════════════════════════════"
echo ""

# ── 1. Сборка сервера (Linux amd64) ──────────────────────────
echo "[1/4] Сборка wdtt-server для Linux amd64..."
mkdir -p "$ROOT/build/server"
cd "$ROOT"

CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build \
        -trimpath \
        -ldflags="-s -w -checklinkname=0" \
        -o build/server/wdtt-server-linux-amd64 \
        ./server.go

chmod +x build/server/wdtt-server-linux-amd64
echo "[OK] Собран: $(ls -lh build/server/wdtt-server-linux-amd64)"

# ── 2. Копирование бинаря на VPS ─────────────────────────────
echo ""
echo "[2/4] Копирование бинаря на VPS..."
scp -P "$VPS_SSH_PORT" \
    build/server/wdtt-server-linux-amd64 \
    "${VPS_USER}@${VPS_HOST}:/usr/local/bin/wdtt-server"
echo "[OK] Скопирован в /usr/local/bin/wdtt-server"

# ── 3. Копирование скрипта установки ─────────────────────────
echo ""
echo "[3/4] Копирование скрипта установки..."
scp -P "$VPS_SSH_PORT" \
    "$ROOT/scripts/setup-server.sh" \
    "${VPS_USER}@${VPS_HOST}:/tmp/setup-server.sh"
echo "[OK] Скопирован в /tmp/setup-server.sh"

# ── 4. Запуск установки на VPS ────────────────────────────────
echo ""
echo "[4/4] Запуск установки на VPS..."
ssh -p "$VPS_SSH_PORT" "${VPS_USER}@${VPS_HOST}" \
    "WDTT_PASSWORD='${WDTT_PASSWORD}' bash /tmp/setup-server.sh"

echo ""
echo "══════════════════════════════════════════"
echo "   Деплой завершён!"
echo ""
echo "   Проверка:"
echo "   ssh ${VPS_USER}@${VPS_HOST} 'systemctl status wdtt'"
echo "   ssh ${VPS_USER}@${VPS_HOST} 'journalctl -u wdtt -f'"
echo "══════════════════════════════════════════"
