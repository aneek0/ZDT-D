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
  ls -lh "$APK"

  if [[ "$DO_INSTALL" == 1 ]]; then
    echo "== adb install =="
    adb install -r "$APK"
  fi
fi
