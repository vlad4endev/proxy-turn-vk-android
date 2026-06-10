#!/usr/bin/env bash
# build-native-ios.sh — SKYFLOW VPN iOS native libraries
#
# Builds:
#   1. SkyflowClient.xcframework  — Go client (gomobile, Whitelist mode)
#   2. SkyflowXray.xcframework    — Xray-core (Speed mode)
#   3. SkyflowHev.xcframework     — hev-socks5-tunnel TUN (Speed mode)
#
# Requirements:
#   - Go 1.22+       (brew install go)
#   - gomobile        (go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init)
#   - Xcode 15+       (with iOS SDK)
#   - cmake           (brew install cmake)
#
# Usage:
#   ./scripts/build-native-ios.sh
#   ./scripts/build-native-ios.sh --skip-xray   (skip Xray if already built)
#   ./scripts/build-native-ios.sh --skip-hev    (skip hev-socks5-tunnel if already built)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IOS_FRAMEWORKS="$ROOT/ios/SkyflowVPN/Frameworks"
HEV_TAG="${HEV_SOCKS5_TUNNEL_TAG:-2.15.0}"
XRAY_TAG="${XRAY_CORE_TAG:-v26.6.1}"
SKIP_XRAY=false
SKIP_HEV=false

for arg in "$@"; do
  case "$arg" in
    --skip-xray) SKIP_XRAY=true ;;
    --skip-hev)  SKIP_HEV=true ;;
  esac
done

mkdir -p "$IOS_FRAMEWORKS"

# ─── 1. SkyflowClient.xcframework (gomobile) ──────────────────────────────────

build_go_client() {
  echo "== Building SkyflowClient.xcframework (gomobile) =="

  if ! command -v gomobile &>/dev/null; then
    echo "Installing gomobile..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    gomobile init
  fi

  cd "$ROOT/shared/gomobile"
  gomobile bind \
    -target ios,iossimulator \
    -o "$IOS_FRAMEWORKS/SkyflowClient.xcframework" \
    -ldflags="-s -w" \
    -trimpath \
    .

  echo "Built: $IOS_FRAMEWORKS/SkyflowClient.xcframework"
}

# ─── 2. SkyflowXray.xcframework (Xray-core) ───────────────────────────────────

build_xray() {
  echo "== Building SkyflowXray.xcframework (Xray-core $XRAY_TAG) =="

  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  git clone --depth 1 --branch "$XRAY_TAG" \
    https://github.com/XTLS/Xray-core.git "$tmp/Xray-core"

  # Build XCFramework wrapper
  # Xray-core exposes a C API via gomobile bind for mobile platforms
  cd "$tmp/Xray-core"

  # arm64 device
  CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    CC="$(xcrun --sdk iphoneos --find clang) -arch arm64 -isysroot $(xcrun --sdk iphoneos --show-sdk-path)" \
    go build -trimpath -buildmode=c-archive \
    -ldflags="-s -w -checklinkname=0" \
    -o "$tmp/xray-arm64.a" ./main

  # arm64 simulator
  CGO_ENABLED=1 GOOS=ios GOARCH=arm64 \
    GOFLAGS="-tags=iphonesimulator" \
    CC="$(xcrun --sdk iphonesimulator --find clang) -arch arm64 -isysroot $(xcrun --sdk iphonesimulator --show-sdk-path)" \
    go build -trimpath -buildmode=c-archive \
    -ldflags="-s -w -checklinkname=0" \
    -o "$tmp/xray-arm64-sim.a" ./main

  # x86_64 simulator
  CGO_ENABLED=1 GOOS=ios GOARCH=amd64 \
    GOFLAGS="-tags=iphonesimulator" \
    CC="$(xcrun --sdk iphonesimulator --find clang) -arch x86_64 -isysroot $(xcrun --sdk iphonesimulator --show-sdk-path)" \
    go build -trimpath -buildmode=c-archive \
    -ldflags="-s -w -checklinkname=0" \
    -o "$tmp/xray-x86_64.a" ./main

  # Universal simulator slice
  lipo -create "$tmp/xray-arm64-sim.a" "$tmp/xray-x86_64.a" \
    -output "$tmp/xray-sim.a"

  # Pack into XCFramework
  xcodebuild -create-xcframework \
    -library "$tmp/xray-arm64.a" \
    -library "$tmp/xray-sim.a" \
    -output "$IOS_FRAMEWORKS/SkyflowXray.xcframework"

  echo "Built: $IOS_FRAMEWORKS/SkyflowXray.xcframework"
}

# ─── 3. SkyflowHev.xcframework (hev-socks5-tunnel) ────────────────────────────

build_hev() {
  echo "== Building SkyflowHev.xcframework (hev-socks5-tunnel $HEV_TAG) =="

  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  git clone --depth 1 --branch "$HEV_TAG" --recursive \
    https://github.com/heiher/hev-socks5-tunnel.git "$tmp/hev"

  cd "$tmp/hev"

  SDK_DEVICE="$(xcrun --sdk iphoneos --show-sdk-path)"
  SDK_SIM="$(xcrun --sdk iphonesimulator --show-sdk-path)"
  CLANG_DEVICE="$(xcrun --sdk iphoneos --find clang)"
  CLANG_SIM="$(xcrun --sdk iphonesimulator --find clang)"

  build_slice() {
    local arch="$1" sdk="$2" clang="$3" out="$4"
    mkdir -p "$tmp/build-$arch"
    cmake -S "$tmp/hev" -B "$tmp/build-$arch" \
      -DCMAKE_SYSTEM_NAME=iOS \
      -DCMAKE_OSX_ARCHITECTURES="$arch" \
      -DCMAKE_OSX_SYSROOT="$sdk" \
      -DCMAKE_C_COMPILER="$clang" \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF \
      -DPKGNAME=com/wdtt/client/xray \
      -DCLSNAME=Tun2SocksHelper
    cmake --build "$tmp/build-$arch" --config Release
    cp "$tmp/build-$arch/libhev-socks5-tunnel.a" "$out"
  }

  build_slice arm64      "$SDK_DEVICE" "$CLANG_DEVICE" "$tmp/hev-arm64.a"
  build_slice arm64      "$SDK_SIM"    "$CLANG_SIM"    "$tmp/hev-arm64-sim.a"
  build_slice x86_64     "$SDK_SIM"    "$CLANG_SIM"    "$tmp/hev-x86_64.a"

  lipo -create "$tmp/hev-arm64-sim.a" "$tmp/hev-x86_64.a" \
    -output "$tmp/hev-sim.a"

  # Copy header
  mkdir -p "$tmp/hev-headers"
  cp "$tmp/hev/include/hev-socks5-tunnel.h" "$tmp/hev-headers/"

  xcodebuild -create-xcframework \
    -library "$tmp/hev-arm64.a" \
    -headers "$tmp/hev-headers" \
    -library "$tmp/hev-sim.a" \
    -headers "$tmp/hev-headers" \
    -output "$IOS_FRAMEWORKS/SkyflowHev.xcframework"

  echo "Built: $IOS_FRAMEWORKS/SkyflowHev.xcframework"
}

# ─── Run ───────────────────────────────────────────────────────────────────────

build_go_client

if [[ "$SKIP_XRAY" == "false" ]]; then
  build_xray
else
  echo "Skipping Xray (--skip-xray)"
fi

if [[ "$SKIP_HEV" == "false" ]]; then
  build_hev
else
  echo "Skipping hev-socks5-tunnel (--skip-hev)"
fi

echo ""
echo "✅ All iOS native frameworks ready under ios/SkyflowVPN/Frameworks/"
ls -lh "$IOS_FRAMEWORKS"/
