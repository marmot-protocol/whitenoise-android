#!/usr/bin/env bash
# Build signed release APKs for Dark Matter Android.
#
# Pre-reqs:
#   - JAVA_HOME or a JBR pointing at JDK 17+
#   - Android SDK + NDK installed
#   - Signing creds in local.properties (see scripts/release.sh --help)
#   - Sibling checkout of marmot at $DARKMATTER_MARMOT_DIR (default: ../darkmatter)
#
# Outputs signed per-ABI and universal APKs in app/build/outputs/apk/release/.

set -euo pipefail

usage() {
  cat <<EOF
Usage: scripts/release.sh [--skip-bindings] [--abi <ABI>] [--help]

  --skip-bindings   Don't rebuild the Rust .so libs (use whatever's checked in)
  --abi <ABI>       After building, print the path to a specific ABI APK
                    (arm64-v8a | armeabi-v7a | x86 | x86_64 | universal)
  --help            Show this help

Signing creds (in local.properties or env):
  DARKMATTER_KEYSTORE_PATH       Path to .p12 / .jks keystore
  DARKMATTER_KEY_ALIAS           Key alias inside the keystore
  DARKMATTER_KEYSTORE_PASSWORD   Keystore password
  DARKMATTER_KEY_PASSWORD        Key password (same as keystore for PKCS12)

Optional env:
  DARKMATTER_MARMOT_DIR          Path to marmot workspace (default: ../darkmatter)
  ANDROID_ABIS                   Space-separated ABIs to build (default: all 4)
EOF
}

SKIP_BINDINGS=false
TARGET_ABI=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-bindings) SKIP_BINDINGS=true; shift ;;
    --abi) TARGET_ABI="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
  esac
done

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MARMOT_DIR="${DARKMATTER_MARMOT_DIR:-$(cd "$REPO_DIR/../darkmatter" 2>/dev/null && pwd || true)}"

# --- Java sanity ---
if ! command -v java >/dev/null 2>&1; then
  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME/Library/Application Support/JetBrains/Toolbox/apps/android-studio/Contents/jbr/Contents/Home"; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi
if ! command -v java >/dev/null 2>&1; then
  echo "error: java not found. Set JAVA_HOME to Android Studio's JBR." >&2
  exit 1
fi

# --- Signing sanity ---
LOCAL_PROPS="$REPO_DIR/local.properties"
required_props=(DARKMATTER_KEYSTORE_PATH DARKMATTER_KEY_ALIAS DARKMATTER_KEYSTORE_PASSWORD DARKMATTER_KEY_PASSWORD)
missing=()
for prop in "${required_props[@]}"; do
  if ! grep -q "^${prop}=" "$LOCAL_PROPS" 2>/dev/null && [[ -z "${!prop:-}" ]]; then
    missing+=("$prop")
  fi
done
if (( ${#missing[@]} > 0 )); then
  echo "error: missing signing config (in local.properties or env): ${missing[*]}" >&2
  echo "Run 'just keystore-gen' to create one." >&2
  exit 1
fi
keystore_path=$(grep "^DARKMATTER_KEYSTORE_PATH=" "$LOCAL_PROPS" 2>/dev/null | cut -d= -f2- || echo "${DARKMATTER_KEYSTORE_PATH:-}")
if [[ ! -f "$keystore_path" ]]; then
  echo "error: keystore not found at: $keystore_path" >&2
  exit 1
fi

# --- Rebuild Rust .so (smaller via strip=symbols) ---
if [[ "$SKIP_BINDINGS" == "false" ]]; then
  if [[ -z "$MARMOT_DIR" || ! -d "$MARMOT_DIR" ]]; then
    echo "error: marmot workspace not found. Set DARKMATTER_MARMOT_DIR." >&2
    exit 1
  fi
  echo "==> Rebuilding marmot bindings with RUSTFLAGS=-C strip=symbols"
  pushd "$MARMOT_DIR" >/dev/null
  RUSTFLAGS="-C strip=symbols" bash crates/marmot-uniffi/kotlin-bindings.sh
  popd >/dev/null

  echo "==> Copying generated bindings + native libs into Android repo"
  cp "$MARMOT_DIR/crates/marmot-uniffi/output/android/kotlin/dev/ipf/marmotkit/marmot_uniffi.kt" \
     "$REPO_DIR/app/src/main/java/dev/ipf/marmotkit/marmot_uniffi.kt"
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    cp "$MARMOT_DIR/crates/marmot-uniffi/output/android/jniLibs/$abi/libmarmot_uniffi.so" \
       "$REPO_DIR/app/src/main/jniLibs/$abi/libmarmot_uniffi.so"
  done
fi

# --- Gradle release build ---
echo "==> Assembling release APKs"
cd "$REPO_DIR"
./gradlew :app:assembleRelease

APK_DIR="$REPO_DIR/app/build/outputs/apk/release"
echo ""
echo "==> Release APKs:"
ls -lh "$APK_DIR"/*.apk

if [[ -n "$TARGET_ABI" ]]; then
  match=$(ls "$APK_DIR" | grep -E "app-${TARGET_ABI}-release.apk$" || true)
  if [[ -z "$match" ]]; then
    echo "error: no APK found for ABI: $TARGET_ABI" >&2
    exit 1
  fi
  echo ""
  echo "==> Selected: $APK_DIR/$match"
fi
