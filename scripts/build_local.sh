#!/usr/bin/env bash
# Local ZDT-D build: module zip + APK (+ optional install), no GitHub Actions.
# Usage:
#   scripts/build_local.sh           # module zip + APK
#   scripts/build_local.sh --install # also adb install -r the APK
#   scripts/build_local.sh --module  # module zip only
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk17}"
GRADLE="${GRADLE:-$HOME/.local/opt/gradle-9.6.0/bin/gradle}"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
OUT_MODULE_DIR="out/module_build/module_root"
OUT_MODULE="out/module/zdt_module.zip"
GEN_ASSETS="application/app/build/generated/zdt-assets/main"

DO_MODULE=1
DO_APK=1
DO_INSTALL=0
for arg in "$@"; do
  case "$arg" in
    --module) DO_APK=0 ;;
    --install) DO_INSTALL=1 ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# Rust binaries the module ships. Rebuilt automatically when their sources
# are newer than the shipped prebuilt binary (mtime check) so a local build
# can never again pack a stale daemon — the exact bug that kept phantom
# hysteria2/dpitunnel entries in /api/programs after the sources were removed.
RUST_CRATES=(
  "zdtd:rust/zdtd:prebuilt/bin/arm64-v8a/zdtd"
  "dpi-detector:rust/dpi-detector:prebuilt/bin/arm64-v8a/dpi-detector"
  "nfqws-tester:rust/nfqws-tester:prebuilt/bin/arm64-v8a/nfqws-tester"
)
RUST_TARGET="aarch64-linux-android"

NDK_VERSION="27.2.12479018"
NDK_ROOT="${NDK_ROOT:-$SDK_DIR/ndk/$NDK_VERSION}"
if [[ ! -d "$NDK_ROOT" ]]; then
  NDK_ROOT="$SDK_DIR/ndk/$(ls "$SDK_DIR/ndk" 2>/dev/null | sort -V | tail -1)"
fi
NDK_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"

build_rust_binaries() {
  local rebuilt=0
  local bin src crate
  for spec in "${RUST_CRATES[@]}"; do
    crate="${spec%%:*}"
    rest="${spec#*:}"
    src="${rest%%:*}"
    bin="${rest#*:}"
    [[ -d "$src" ]] || { echo "skip $crate: $src not found"; continue; }
    [[ -f "$bin" ]] || { echo "rebuild $crate: $bin missing"; cargo_build "$crate" "$src" "$bin" && rebuilt=1; continue; }
    # stale = any crate file newer than the shipped binary
    if [[ -n "$(find "$src" -name '*.rs' -newer "$bin" -print -quit 2>/dev/null)" ]] ||
       [[ rust/Cargo.lock -nt "$bin" ]] || [[ rust/Cargo.toml -nt "$bin" ]]; then
      echo "rebuild $crate: sources newer than $bin"
      cargo_build "$crate" "$src" "$bin" && rebuilt=1
    fi
  done
  [[ "$rebuilt" == 1 ]] && echo "NOTE: rust binaries were rebuilt — commit the updated prebuilt/ files."
}

cargo_build() {
  local crate="$1" src="$2" bin="$3"
  test -x "$NDK_BIN/aarch64-linux-android21-clang" || { echo "ERROR: NDK clang not found at $NDK_BIN" >&2; exit 1; }
  echo "  building $crate (release, $RUST_TARGET)..."
  (cd rust && \
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android21-clang" \
    CC_aarch64_linux_android="$NDK_BIN/aarch64-linux-android21-clang" \
    AR_aarch64_linux_android="$NDK_BIN/llvm-ar" \
    RANLIB_aarch64_linux_android="$NDK_BIN/llvm-ranlib" \
    rustup run stable cargo build --manifest-path "${src#rust/}/Cargo.toml" --release --target "$RUST_TARGET") || return 1
  cp -f "rust/target/$RUST_TARGET/release/$crate" "$bin"
}

if [[ "$DO_MODULE" == 1 ]]; then
  echo "== rust binaries =="
  build_rust_binaries
fi

if [[ "$DO_MODULE" == 1 ]]; then
  echo "== module zip =="
  rm -rf out/module_build out/module
  mkdir -p "$OUT_MODULE_DIR" out/module
  cp -a module_template/. "$OUT_MODULE_DIR/"
  cp -f module.prop "$OUT_MODULE_DIR/module.prop"
  sed -i '/^buildType=/d; /^buildNumber=/d' "$OUT_MODULE_DIR/module.prop"
  { echo "buildType=local"; echo "buildNumber=0"; } >> "$OUT_MODULE_DIR/module.prop"
  cp -f module.prop out/module/module.prop
  rm -rf "$OUT_MODULE_DIR/zygisk" "$OUT_MODULE_DIR/bin" "$OUT_MODULE_DIR/prebuilt" "$OUT_MODULE_DIR/verify_sum"
  mkdir -p "$OUT_MODULE_DIR/prebuilt/bin/arm64-v8a"
  for f in prebuilt/bin/arm64-v8a/*; do
    [[ -f "$f" ]] || continue
    cp "$f" "$OUT_MODULE_DIR/prebuilt/bin/arm64-v8a/$(basename "$f")"
    chmod 755 "$OUT_MODULE_DIR/prebuilt/bin/arm64-v8a/$(basename "$f")"
  done
  (cd "$OUT_MODULE_DIR" && find prebuilt/bin -type f | sort | while read -r file; do
    mkdir -p "verify_sum/$(dirname "$file")"
    sha256sum "$file" > "verify_sum/$file.sha256"
  done && test -s verify_sum/prebuilt/bin/arm64-v8a/zdtd.sha256)
  (cd "$OUT_MODULE_DIR" && zip -1qr ../../../out/module/zdt_module.zip .)
  ls -lh "$OUT_MODULE"
fi

if [[ "$DO_APK" == 1 ]]; then
  echo "== helper assets =="
  mkdir -p "$GEN_ASSETS/dpi-detector/arm64-v8a" "$GEN_ASSETS/nfqws-tester/arm64-v8a"
  cp prebuilt/bin/arm64-v8a/dpi-detector "$GEN_ASSETS/dpi-detector/arm64-v8a/dpi-detector"
  cp prebuilt/bin/arm64-v8a/nfqws-tester "$GEN_ASSETS/nfqws-tester/arm64-v8a/nfqws_tester"
  chmod 755 "$GEN_ASSETS/dpi-detector/arm64-v8a/dpi-detector" "$GEN_ASSETS/nfqws-tester/arm64-v8a/nfqws_tester"

  [[ -f application/local.properties ]] || echo "sdk.dir=$SDK_DIR" > application/local.properties

  echo "== gradle assembleRelease =="
  # Isolated gradle home: ~/.gradle/init.d/maven-mirror.gradle adds aliyun repos
  # that conflict with FAIL_ON_PROJECT_REPOS in application/settings.gradle.
  (cd application && JAVA_HOME="$JAVA_HOME" NO_DASHBOARD=1 "$GRADLE" --no-daemon -Dgradle.user.home="$ROOT/out/gradle-home" -x lintVitalRelease assembleRelease)

  APK="application/app/build/outputs/apk/release/app-release.apk"
  emb=$(unzip -p "$APK" assets/zdt_module.zip > /dev/null 2>&1 && echo ok || echo missing)
  if [[ "$emb" != "ok" ]]; then
    echo "ERROR: $APK has no assets/zdt_module.zip" >&2
    exit 1
  fi
  emb_sha=$(unzip -p "$APK" assets/zdt_module.zip | sha256sum | cut -d' ' -f1)
  zip_sha=$(sha256sum "$OUT_MODULE" | cut -d' ' -f1)
  echo "APK: $(ls -lh "$APK" | awk '{print $5}')"
  if [[ "$emb_sha" != "$zip_sha" ]]; then
    echo "ERROR: embedded zdt_module.zip in APK is stale (sha mismatch: apk=$emb_sha zip=$zip_sha)" >&2
    echo "  The gradle prepareZdtGeneratedAssets task did not pick up the new zip." >&2
    echo "  Fix: rm -rf application/app/build/generated && re-run." >&2
    exit 1
  fi
  echo "embedded zdt_module.zip OK (sha ${zip_sha:0:16}...)"

  if [[ "$DO_INSTALL" == 1 ]]; then
    echo "== adb install =="
    adb install -r "$APK"
  fi
fi
