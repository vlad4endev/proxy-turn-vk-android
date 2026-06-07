#!/usr/bin/env bash
# Builds native binaries for speed mode (Xray + hev-socks5-tunnel).
# Requires: ANDROID_NDK_HOME or NDK_HOME, Go 1.22+
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT/app/src/main/jniLibs"
MIN_API="${ANDROID_MIN_API:-29}"
HEV_TAG="${HEV_SOCKS5_TUNNEL_TAG:-2.15.0}"
XRAY_TAG="${XRAY_CORE_TAG:-v26.6.1}"

NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "Set ANDROID_NDK_HOME or NDK_HOME to your Android NDK path" >&2
  exit 1
fi

HOST="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"
case "$HOST" in
  linux) NDK_HOST=linux-x86_64 ;;
  darwin)
    # On Apple Silicon, NDK may ship only darwin-x86_64 (runs via Rosetta 2).
    # Prefer darwin-arm64 if present, otherwise fall back to darwin-x86_64.
    if [[ "$ARCH" == "arm64" ]] && [[ -d "$NDK/toolchains/llvm/prebuilt/darwin-arm64" ]]; then
      NDK_HOST=darwin-arm64
    else
      NDK_HOST=darwin-x86_64
    fi
    ;;
  *) echo "Unsupported host OS: $HOST" >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "NDK toolchain not found: $TOOLCHAIN" >&2
  exit 1
fi

mkdir -p "$JNI_DIR/arm64-v8a" "$JNI_DIR/armeabi-v7a" "$JNI_DIR/x86_64"

build_hevtun() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  git clone --depth 1 --branch "$HEV_TAG" --recursive \
    https://github.com/heiher/hev-socks5-tunnel.git "$tmp/hev-socks5-tunnel"

  mkdir -p "$tmp/jni"
  echo 'include $(call all-subdir-makefiles)' > "$tmp/jni/Android.mk"
  ln -s "$tmp/hev-socks5-tunnel" "$tmp/jni/hev-socks5-tunnel"

  pushd "$tmp" >/dev/null
  "$NDK/ndk-build" \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=jni/Android.mk \
    "APP_ABI=armeabi-v7a arm64-v8a x86_64" \
    "APP_PLATFORM=android-${MIN_API}" \
    NDK_LIBS_OUT="$tmp/libs" \
    NDK_OUT="$tmp/obj" \
    "APP_CFLAGS=-O3 -DPKGNAME=com/wdtt/client/xray -DCLSNAME=Tun2SocksHelper" \
    "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"
  popd >/dev/null

  for abi in armeabi-v7a arm64-v8a x86_64; do
    cp "$tmp/libs/$abi/libhev-socks5-tunnel.so" "$JNI_DIR/$abi/libhev-socks5-tunnel.so"
    chmod +x "$JNI_DIR/$abi/libhev-socks5-tunnel.so"
  done

  echo "Built hev-socks5-tunnel ($HEV_TAG):"
  ls -lh "$JNI_DIR"/*/libhev-socks5-tunnel.so
}

build_xray() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  git clone --depth 1 --branch "$XRAY_TAG" \
    https://github.com/XTLS/Xray-core.git "$tmp/Xray-core"

  build_one() {
    local goarch="$1"
    local abi="$2"
    local cc="$3"

    echo "== Xray-core $XRAY_TAG android/$goarch -> $abi/libxray.so =="
    (
      cd "$tmp/Xray-core"
      if [[ "$goarch" == "arm" ]]; then
        GOARM=7 CGO_ENABLED=1 GOOS=android GOARCH="$goarch" CC="$cc" \
          go build -trimpath -buildvcs=false \
          -ldflags="-s -w -buildid= -checklinkname=0" \
          -o "$JNI_DIR/$abi/libxray.so" ./main
      else
        CGO_ENABLED=1 GOOS=android GOARCH="$goarch" CC="$cc" \
          go build -trimpath -buildvcs=false \
          -ldflags="-s -w -buildid= -checklinkname=0" \
          -o "$JNI_DIR/$abi/libxray.so" ./main
      fi
      chmod +x "$JNI_DIR/$abi/libxray.so"
    )
  }

  build_one arm64 arm64-v8a "$TOOLCHAIN/aarch64-linux-android${MIN_API}-clang"
  build_one arm armeabi-v7a "$TOOLCHAIN/armv7a-linux-androideabi${MIN_API}-clang"
  build_one amd64 x86_64 "$TOOLCHAIN/x86_64-linux-android${MIN_API}-clang"

  echo "Built Xray-core ($XRAY_TAG):"
  ls -lh "$JNI_DIR"/*/libxray.so
}

echo "== Building hev-socks5-tunnel =="
build_hevtun

echo "== Building Xray-core =="
build_xray

echo "Speed-mode native libraries ready under app/src/main/jniLibs/"
