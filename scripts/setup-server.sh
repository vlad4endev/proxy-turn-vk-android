#!/usr/bin/env bash
# ============================================================
# WDTT Server — установка на VPS (Ubuntu/Debian)
# Запускать от root: WDTT_PASSWORD=tunnel2026 bash setup-server.sh
# ============================================================
set -euo pipefail

BINARY_PATH="/usr/local/bin/wdtt-server"
CONFIG_DIR="/etc/wdtt"
SERVICE_NAME="wdtt"
LISTEN_ADDR="0.0.0.0:56000"

# ── Пароль сервера (должен совпадать с ServerConfig.kt → PASSWORD) ──
if [[ -z "${WDTT_PASSWORD:-}" ]]; then
    echo "Введите пароль wdtt-server (ServerConfig.kt → PASSWORD):"
    read -r WDTT_PASSWORD
fi
if [[ -z "$WDTT_PASSWORD" ]]; then
    echo "ERROR: пароль не может быть пустым" >&2; exit 1
fi

# ── WireGuard ключи (должны совпадать с ServerConfig.kt → WG_CONFIG) ──
# Server keypair (сгенерировано вместе с APK):
WG_SERVER_PRIVATE="cKLkugA79iIDgtSA2Ifm+O2k9bINR+BOyVcuNTnJ5EE="
WG_SERVER_PUBLIC="3TnSzSokv43REGxreadx6RL/YPwt/1hR+MaOUxRyTxs="
# Default client keypair (ServerConfig.kt → WG_CONFIG → PrivateKey):
WG_CLIENT_PRIVATE="cFKlz2G2UU3j03vwe2jugrIhJTFjLmu1MSZ41Lpg024="
WG_CLIENT_PUBLIC="bHpIcQHCXsi0WdpWC8H/zOe+T+jIoFHQ5j0v0mAA/VM="

echo ""
echo "══════════════════════════════════════════"
echo "   WDTT Server — установка"
echo "   Бинарь:  $BINARY_PATH"
echo "   Конфиг:  $CONFIG_DIR"
echo "   Порт:    $LISTEN_ADDR"
echo "══════════════════════════════════════════"
echo ""

# ── 1. Проверяем бинарь ───────────────────────────────────────
if [[ ! -f "$BINARY_PATH" ]]; then
    echo "ERROR: бинарь $BINARY_PATH не найден!"
    echo ""
    echo "Скопируйте сначала:"
    echo "  scp wdtt-server-linux-amd64 root@$(hostname -I | awk '{print $1}'):/usr/local/bin/wdtt-server"
    echo "  chmod +x /usr/local/bin/wdtt-server"
    exit 1
fi
chmod +x "$BINARY_PATH"
echo "[OK] Бинарь: $(ls -lh $BINARY_PATH)"

# ── 2. IP forwarding ──────────────────────────────────────────
echo "[*] IP forwarding..."
sysctl -w net.ipv4.ip_forward=1 > /dev/null
echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-wdtt-forward.conf
echo "[OK] net.ipv4.ip_forward=1"

# ── 3. Firewall ───────────────────────────────────────────────
echo "[*] Открытие UDP-портов 56000, 56001..."
if command -v ufw &>/dev/null && ufw status 2>/dev/null | grep -q "^Status: active"; then
    ufw allow 56000/udp comment "wdtt DTLS" || true
    ufw allow 56001/udp comment "wdtt WireGuard" || true
    echo "[OK] ufw правила добавлены"
fi
if command -v iptables &>/dev/null; then
    iptables -C INPUT -p udp --dport 56000 -j ACCEPT 2>/dev/null \
        || iptables -I INPUT -p udp --dport 56000 -j ACCEPT
    iptables -C INPUT -p udp --dport 56001 -j ACCEPT 2>/dev/null \
        || iptables -I INPUT -p udp --dport 56001 -j ACCEPT
    echo "[OK] iptables правила добавлены"
fi

# ── 4. Конфиг директория и WireGuard ключи ───────────────────
echo "[*] Настройка WireGuard ключей..."
mkdir -p "$CONFIG_DIR"

# Записываем ключи ТОЧНО совпадающие с ServerConfig.kt → WG_CONFIG
# Формат wg-keys.dat: serverPrivate / serverPublic / clientPrivate / clientPublic
cat > "${CONFIG_DIR}/wg-keys.dat" << EOF
${WG_SERVER_PRIVATE}
${WG_SERVER_PUBLIC}
${WG_CLIENT_PRIVATE}
${WG_CLIENT_PUBLIC}
EOF
chmod 600 "${CONFIG_DIR}/wg-keys.dat"
echo "[OK] wg-keys.dat создан"

# ── 5. Регистрируем дефолтное устройство (Android клиент) ─────
# Сервер использует базу devices для авторизации WireGuard пиров.
# Без регистрации WireGuard handshake не пройдёт.
echo "[*] Регистрация Android клиента в базе..."
DEVICE_ID="android-default"
cat > "${CONFIG_DIR}/passwords.json" << EOF
{
  "main_password": "${WDTT_PASSWORD}",
  "admin_id": "",
  "bot_token": "",
  "passwords": {},
  "devices": {
    "${DEVICE_ID}": {
      "device_id": "${DEVICE_ID}",
      "ip": "10.66.66.2",
      "priv_key": "${WG_CLIENT_PRIVATE}",
      "pub_key": "${WG_CLIENT_PUBLIC}"
    }
  }
}
EOF
chmod 600 "${CONFIG_DIR}/passwords.json"
echo "[OK] passwords.json создан (устройство: $DEVICE_ID, IP: 10.66.66.2)"

# ── 6. Systemd service ────────────────────────────────────────
echo "[*] Создание systemd сервиса..."
cat > /etc/systemd/system/${SERVICE_NAME}.service << EOF
[Unit]
Description=WDTT VPN Server (VK TURN + WireGuard)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=${BINARY_PATH} \\
    -password ${WDTT_PASSWORD} \\
    -listen ${LISTEN_ADDR} \\
    -config-dir ${CONFIG_DIR}
Restart=always
RestartSec=5
User=root
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal
SyslogIdentifier=wdtt-server

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"
systemctl restart "${SERVICE_NAME}"
sleep 2

# ── 7. Статус ─────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════"
echo "   Статус:"
echo "══════════════════════════════════════════"
systemctl status "${SERVICE_NAME}" --no-pager -l || true
echo ""
if command -v ss &>/dev/null; then
    echo "[UDP порты]:"
    ss -ulnp | grep -E "56000|56001" || echo "  Порты ещё не открыты (проверьте логи)"
fi
echo ""
echo "══════════════════════════════════════════"
echo "   Готово!"
echo ""
echo "   Логи:  journalctl -u wdtt -f"
echo "   Стоп:  systemctl stop wdtt"
echo "   Старт: systemctl start wdtt"
echo "══════════════════════════════════════════"
