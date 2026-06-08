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
  --abi <ABI>       Build only a specific ABI APK, then print its path
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
    --abi)
      if [[ $# -lt 2 || "$2" == --* ]]; then
        echo "error: --abi requires a value" >&2
        usage
        exit 1
      fi
      TARGET_ABI="$2"
      shift 2
      ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
  esac
done

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MARMOT_DIR="${DARKMATTER_MARMOT_DIR:-$(cd "$REPO_DIR/../darkmatter" 2>/dev/null && pwd || true)}"

if [[ -n "$TARGET_ABI" ]]; then
  case "$TARGET_ABI" in
    arm64-v8a|armeabi-v7a|x86|x86_64|universal) ;;
    *)
      echo "error: unsupported ABI: $TARGET_ABI" >&2
      usage
      exit 1
      ;;
  esac
fi

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
cd "$REPO_DIR"

APK_DIR="$REPO_DIR/app/build/outputs/apk/release"
INTERMEDIATE_APK_DIR="$REPO_DIR/app/build/intermediates/apk/release"
mkdir -p "$APK_DIR"

if [[ -n "$TARGET_ABI" && "$TARGET_ABI" != "universal" ]]; then
  echo "==> Assembling release APK for $TARGET_ABI"
  rm -f "$APK_DIR"/*.apk
  ./gradlew :app:assembleRelease -Pandroid.injected.build.abi="$TARGET_ABI"

  gradle_apk_name="app-${TARGET_ABI}-release.apk"
  selected_apk="$APK_DIR/$gradle_apk_name"
  intermediate_apk="$INTERMEDIATE_APK_DIR/$gradle_apk_name"
  if [[ ! -f "$selected_apk" && -f "$intermediate_apk" ]]; then
    cp "$intermediate_apk" "$selected_apk"
  fi
  if [[ ! -f "$selected_apk" ]]; then
    echo "error: no APK found for ABI: $TARGET_ABI" >&2
    exit 1
  fi

  if [[ "$TARGET_ABI" == "arm64-v8a" ]]; then
    selected_apk="$APK_DIR/darkmatter-v8a-release-$(date +%F).apk"
    mv "$APK_DIR/$gradle_apk_name" "$selected_apk"
  fi
else
  echo "==> Assembling release APKs"
  ./gradlew :app:assembleRelease
fi

echo ""
echo "==> Release APKs:"
ls -lh "$APK_DIR"/*.apk

if [[ -n "$TARGET_ABI" ]]; then
  echo ""
  echo "==> Selected: $selected_apk"
fi
