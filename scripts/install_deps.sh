#!/usr/bin/env bash
# ZDT-D build dependencies bootstrap (Arch/CachyOS + generic Linux fallback).
# Usage: scripts/install_deps.sh [--check]
#   --check  only verify what is present/missing, install nothing.
#
# Sets up everything scripts/build_local.sh needs:
#   - JDK 17 (Temurin), Gradle 9.6.0, Android SDK (platform 37, build-tools, NDK)
#   - Rust stable + aarch64-linux-android target
#   - zip/unzip, adb
# All user-local installs: no system packages touched on the generic path.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

LOCAL_OPT="$HOME/.local/opt"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
JAVA_HOME_TARGET="$LOCAL_OPT/jdk17"
GRADLE_TARGET="$LOCAL_OPT/gradle-9.6.0"

err()  { echo "ERROR: $*" >&2; }
ok()   { echo "  ok    $*"; }
miss() { echo "  MISS  $*"; }
todo() { echo "  TODO  $*"; }

# ---------- package manager helpers ----------
have_cmd() { command -v "$1" >/dev/null 2>&1; }

pkg_install() {
  # pkg_install <desc> <cmd...>
  local desc="$1"; shift
  if [[ "$CHECK_ONLY" == 1 ]]; then
    todo "$desc (would run: $*)"
    return 0
  fi
  echo "  installing: $*"
  "$@"
}

if have_cmd pacman; then
  PACMAN_INSTALL=(sudo pacman -S --needed --noconfirm)
elif have_cmd apt; then
  PACMAN_INSTALL=(sudo apt-get install -y)
elif have_cmd dnf; then
  PACMAN_INSTALL=(sudo dnf install -y)
else
  PACMAN_INSTALL=()
fi

# ---------- 1. system binaries ----------
echo "== system tools =="
for c in unzip zip; do
  if have_cmd "$c"; then ok "$c"; else
    miss "$c"
    [[ ${#PACMAN_INSTALL[@]} -gt 0 ]] && pkg_install "$c" "${PACMAN_INSTALL[@]}" "$c" || err "install $c manually"
  fi
done
if have_cmd adb; then
  ok "adb ($(adb --version 2>/dev/null | head -1 | grep -oE '[0-9.]+'))"
else
  miss "adb (platform-tools)"
  # Will be installed via sdkmanager below; on Arch also available as pacman pkg.
  if have_cmd pacman && [[ "$CHECK_ONLY" == 0 ]]; then
    pkg_install "adb" "${PACMAN_INSTALL[@]}" android-tools 2>/dev/null || true
  fi
fi
if have_cmd rustup && have_cmd cargo; then
  ok "rustup"
else
  miss "rustup"
  pkg_install "rustup (rustup.rs installer)" bash -c 'curl -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable' || err "install rustup from https://rustup.rs"
  # shellcheck disable=SC1091
  [[ -f "$HOME/.cargo/env" ]] && source "$HOME/.cargo/env"
fi
if have_cmd rustup; then
  # default toolchain is unconfigured on this machine; always probe via stable
  if rustup run stable rustup target list --installed 2>/dev/null | grep -q aarch64-linux-android; then
    ok "rust target aarch64-linux-android"
  else
    pkg_install "rust target" rustup run stable rustup target add aarch64-linux-android
  fi
fi

# ---------- 2. JDK 17 ----------
echo "== JDK 17 =="
if [[ -x "$JAVA_HOME_TARGET/bin/java" ]]; then
  ok "jdk17 at $JAVA_HOME_TARGET ($("$JAVA_HOME_TARGET/bin/java" -version 2>&1 | head -1))"
elif have_cmd java && java -version 2>&1 | head -1 | grep -q '"17'; then
  ok "system java 17"
else
  miss "JDK 17"
  if [[ "$CHECK_ONLY" == 1 ]]; then
    todo "install Temurin 17 to $JAVA_HOME_TARGET"
  else
    mkdir -p "$LOCAL_OPT"
    case "$(uname -m)" in
      x86_64) JDK_ARCH=x64 ;;
      aarch64) JDK_ARCH=aarch64 ;;
      *) JDK_ARCH=x64 ;;
    esac
    JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse"
    TMP_TAR="$(mktemp -d)/jdk17.tar.gz"
    echo "  downloading Temurin 17..."
    curl -fSL -o "$TMP_TAR" "$JDK_URL"
    mkdir -p "$LOCAL_OPT/jdk17-extract"
    tar -xzf "$TMP_TAR" -C "$LOCAL_OPT/jdk17-extract" --strip-components=1
    rm -rf "$JAVA_HOME_TARGET"
    mv "$LOCAL_OPT/jdk17-extract" "$JAVA_HOME_TARGET"
    ok "installed jdk17 -> $JAVA_HOME_TARGET"
  fi
fi

# ---------- 3. Gradle 9.6.0 ----------
echo "== Gradle =="
GRADLE_BIN="$GRADLE_TARGET/bin/gradle"
if [[ -x "$GRADLE_BIN" ]]; then
  ok "gradle at $GRADLE_BIN"
else
  miss "gradle 9.6.0"
  if [[ "$CHECK_ONLY" == 1 ]]; then
    todo "download Gradle 9.6.0 to $GRADLE_TARGET"
  else
    mkdir -p "$LOCAL_OPT"
    GRADLE_URL="https://services.gradle.org/distributions/gradle-9.6.0-bin.zip"
    TMP_ZIP="$(mktemp -d)/gradle.zip"
    echo "  downloading Gradle 9.6.0..."
    curl -fSL -o "$TMP_ZIP" "$GRADLE_URL"
    unzip -q "$TMP_ZIP" -d "$LOCAL_OPT"
    rm -rf "$GRADLE_TARGET"
    mv "$LOCAL_OPT/gradle-9.6.0" "$GRADLE_TARGET"
    ok "installed gradle -> $GRADLE_BIN"
  fi
fi

# ---------- 4. Android SDK ----------
echo "== Android SDK ($SDK_DIR) =="
sdkmanager() {
  local latest_bt
  latest_bt=$(ls "$SDK_DIR/build-tools" 2>/dev/null | sort -V | tail -1)
  "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" "$@"
}

if [[ -d "$SDK_DIR/cmdline-tools/latest" ]]; then
  ok "cmdline-tools"
else
  miss "cmdline-tools"
  if [[ "$CHECK_ONLY" == 1 ]]; then
    todo "install cmdline-tools into $SDK_DIR/cmdline-tools/latest"
  else
    mkdir -p "$SDK_DIR"
    CLT_ZIP="$(mktemp -d)/clt.zip"
    echo "  downloading commandline tools..."
    # URL pinned by CI conventions (tools dir name varies by SDK revision)
    curl -fSL -o "$CLT_ZIP" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    unzip -q "$CLT_ZIP" -d "$SDK_DIR/cmdline-tools-tmp"
    mkdir -p "$SDK_DIR/cmdline-tools"
    mv "$SDK_DIR/cmdline-tools-tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -rf "$SDK_DIR/cmdline-tools-tmp"
    yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
    ok "installed cmdline-tools"
  fi
fi

if [[ -d "$SDK_DIR/cmdline-tools/latest" ]]; then
  # platform 37.0 (not 37) per machine setup notes; fall back to 37 on failure
  if ls "$SDK_DIR/platforms" 2>/dev/null | grep -q 'android-37'; then
    ok "platform android-37"
  elif [[ "$CHECK_ONLY" == 1 ]]; then
    todo "sdkmanager 'platforms;android-37.0' (or platforms;android-37)"
  else
    echo "  installing platform android-37.0..."
    sdkmanager "platforms;android-37.0" >/dev/null 2>&1 || sdkmanager "platforms;android-37" >/dev/null 2>&1 || err "platform install failed"
  fi
  if ls "$SDK_DIR/build-tools" 2>/dev/null | grep -q 37; then
    ok "build-tools 37.x"
  elif [[ "$CHECK_ONLY" == 1 ]]; then
    todo "sdkmanager 'build-tools;37.0.0'"
  else
    echo "  installing build-tools 37.0.0..."
    sdkmanager "build-tools;37.0.0" >/dev/null 2>&1 || err "build-tools install failed"
  fi
  if [[ -d "$SDK_DIR/platform-tools" ]]; then
    ok "platform-tools (adb)"
  elif [[ "$CHECK_ONLY" == 1 ]]; then
    todo "sdkmanager 'platform-tools'"
  else
    echo "  installing platform-tools..."
    sdkmanager "platform-tools" >/dev/null 2>&1 || err "platform-tools install failed"
  fi
  NDK_DIR="$SDK_DIR/ndk/27.2.12479018"
  if [[ -d "$NDK_DIR" ]]; then
    ok "NDK 27.2.12479018"
  elif [[ "$CHECK_ONLY" == 1 ]]; then
    todo "sdkmanager 'ndk;27.2.12479018'"
  else
    echo "  installing NDK 27.2.12479018..."
    sdkmanager "ndk;27.2.12479018" >/dev/null 2>&1 || err "NDK install failed (large download ~600MB)"
  fi
fi

# ---------- 5. app local.properties ----------
echo "== app config =="
if [[ "$CHECK_ONLY" == 0 ]]; then
  if [[ -f "$ROOT/application/local.properties" ]]; then
    ok "application/local.properties exists"
  else
    echo "sdk.dir=$SDK_DIR" > "$ROOT/application/local.properties"
    ok "wrote application/local.properties"
  fi
fi

echo
if [[ "$CHECK_ONLY" == 1 ]]; then
  echo "Check complete. Re-run without --check to install missing items."
else
  echo "Done. Build with: bash scripts/build_local.sh"
fi
