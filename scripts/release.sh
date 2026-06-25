#!/usr/bin/env bash
# Build signed release APKs for White Noise Android.
#
# Pre-reqs:
#   - JAVA_HOME or a JBR pointing at JDK 17+
#   - Android SDK + NDK installed
#   - Signing creds in local.properties (see scripts/release.sh --help)
#   - Sibling checkout of marmot at $DARKMATTER_MARMOT_DIR (default: ../darkmatter)
#
# Outputs signed per-ABI and universal APKs under app/build/outputs/apk/<flavor>/release/.

set -euo pipefail

usage() {
  cat <<EOF
Usage: scripts/release.sh [--skip-bindings] [--abi <ABI>] [--help]

  --skip-bindings   Don't rebuild the Rust .so libs (use whatever's checked in)
  --abi <ABI>       Build only a specific ABI APK, then print its path
                    (arm64-v8a | armeabi-v7a | x86 | x86_64 | universal)
  --flavor <name>   Build one release flavor, or both with "all"
                    (production | staging | all; default: production)
  --help            Show this help

Production signing creds (in local.properties or env):
  WHITENOISE_PRODUCTION_KEYSTORE_PATH       Path to .p12 / .jks keystore
  WHITENOISE_PRODUCTION_KEY_ALIAS           Key alias inside the keystore
  WHITENOISE_PRODUCTION_KEYSTORE_PASSWORD   Keystore password
  WHITENOISE_PRODUCTION_KEY_PASSWORD        Key password (same as keystore for PKCS12)

Production also accepts WHITENOISE_KEYSTORE_* and legacy DARKMATTER_KEYSTORE_*
names as fallbacks.

Staging signing creds:
  WHITENOISE_STAGING_KEYSTORE_PATH
  WHITENOISE_STAGING_KEY_ALIAS
  WHITENOISE_STAGING_KEYSTORE_PASSWORD
  WHITENOISE_STAGING_KEY_PASSWORD

Optional env:
  DARKMATTER_MARMOT_DIR          Path to marmot workspace (default: ../darkmatter)
  ANDROID_ABIS                   Space-separated ABIs to build (default: all 4)
EOF
}

SKIP_BINDINGS=false
TARGET_ABI=""
FLAVOR="production"
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
    --flavor)
      if [[ $# -lt 2 || "$2" == --* ]]; then
        echo "error: --flavor requires a value" >&2
        usage
        exit 1
      fi
      FLAVOR="$2"
      shift 2
      ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
  esac
done

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MARMOT_DIR="${DARKMATTER_MARMOT_DIR:-$(cd "$REPO_DIR/../darkmatter" 2>/dev/null && pwd || true)}"

android_build_tool() {
  local tool="$1"
  if command -v "$tool" >/dev/null 2>&1; then
    command -v "$tool"
    return 0
  fi

  local sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  if [[ -d "$sdk_dir/build-tools" ]]; then
    find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 -type f -name "$tool" | sort | tail -1
  fi
}

assert_not_test_only() {
  local apk="$1"
  local aapt_path
  aapt_path="$(android_build_tool aapt)"
  if [[ -z "$aapt_path" || ! -x "$aapt_path" ]]; then
    echo "error: aapt not found; cannot verify release APK manifest" >&2
    exit 1
  fi

  if "$aapt_path" dump xmltree "$apk" AndroidManifest.xml | grep -q "android:testOnly.*0xffffffff"; then
    echo "error: release APK is marked android:testOnly=true: $apk" >&2
    exit 1
  fi
}

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

case "$FLAVOR" in
  production|staging|all) ;;
  *)
    echo "error: unsupported flavor: $FLAVOR" >&2
    usage
    exit 1
    ;;
esac

if [[ "$FLAVOR" == "all" ]]; then
  BUILD_FLAVORS=(production staging)
else
  BUILD_FLAVORS=("$FLAVOR")
fi

flavor_task_name() {
  case "$1" in
    production) printf 'Production' ;;
    staging) printf 'Staging' ;;
    *) return 1 ;;
  esac
}

apk_name_pattern_for_abi() {
  case "$1" in
    universal) printf '*universal*release*.apk' ;;
    *) printf '*%s*release*.apk' "$1" ;;
  esac
}

select_release_apk() {
  local apk_dir="$1"
  local intermediate_apk_dir="$2"
  local target_abi="$3"
  local pattern selected_apk

  pattern="$(apk_name_pattern_for_abi "$target_abi")"
  selected_apk="$(find "$apk_dir" -maxdepth 1 -type f -name "$pattern" 2>/dev/null | sort | head -1 || true)"
  if [[ -n "$selected_apk" ]]; then
    printf '%s\n' "$selected_apk"
    return 0
  fi

  find "$intermediate_apk_dir" -maxdepth 1 -type f -name "$pattern" 2>/dev/null | sort | head -1 || true
}

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
prop_value() {
  local key value
  for key in "$@"; do
    value="$(grep "^${key}=" "$LOCAL_PROPS" 2>/dev/null | head -1 | cut -d= -f2- || true)"
    if [[ -n "$value" ]]; then
      printf '%s\n' "$value"
      return 0
    fi
    if [[ -n "${!key:-}" ]]; then
      printf '%s\n' "${!key}"
      return 0
    fi
  done
  return 1
}

flavor_signing_value() {
  local flavor="$1"
  local suffix="$2"
  case "$flavor" in
    production) prop_value "WHITENOISE_PRODUCTION_${suffix}" "WHITENOISE_${suffix}" "DARKMATTER_${suffix}" ;;
    staging) prop_value "WHITENOISE_STAGING_${suffix}" ;;
    *) return 1 ;;
  esac
}

require_flavor_signing() {
  local flavor="$1"
  local missing=()
  flavor_signing_value "$flavor" KEYSTORE_PATH >/dev/null || missing+=("${flavor}:KEYSTORE_PATH")
  flavor_signing_value "$flavor" KEY_ALIAS >/dev/null || missing+=("${flavor}:KEY_ALIAS")
  flavor_signing_value "$flavor" KEYSTORE_PASSWORD >/dev/null || missing+=("${flavor}:KEYSTORE_PASSWORD")
  flavor_signing_value "$flavor" KEY_PASSWORD >/dev/null || missing+=("${flavor}:KEY_PASSWORD")
  if (( ${#missing[@]} > 0 )); then
    echo "error: missing signing config (in local.properties or env): ${missing[*]}" >&2
    if [[ "$flavor" == "staging" ]]; then
      echo "Set WHITENOISE_STAGING_KEYSTORE_* values for staging release builds." >&2
    else
      echo "Set WHITENOISE_PRODUCTION_KEYSTORE_* or WHITENOISE_KEYSTORE_* values for production release builds." >&2
    fi
    exit 1
  fi

  local keystore_path
  keystore_path="$(flavor_signing_value "$flavor" KEYSTORE_PATH)"
  if [[ ! -f "$keystore_path" ]]; then
    echo "error: $flavor keystore not found at: $keystore_path" >&2
    exit 1
  fi
}

for flavor in "${BUILD_FLAVORS[@]}"; do
  require_flavor_signing "$flavor"
done

# --- Rebuild Rust .so (smaller via strip=symbols) ---
if [[ "$SKIP_BINDINGS" == "false" ]]; then
  if [[ -z "$MARMOT_DIR" || ! -d "$MARMOT_DIR" ]]; then
    echo "error: marmot workspace not found. Set DARKMATTER_MARMOT_DIR." >&2
    exit 1
  fi
  # Android release binaries include the OTLP exporter; tokens remain runtime config.
  echo "==> Rebuilding marmot bindings with RUSTFLAGS=-C strip=symbols OTLP_EXPORT=1"
  pushd "$MARMOT_DIR" >/dev/null
  OTLP_EXPORT=1 RUSTFLAGS="-C strip=symbols" bash crates/marmot-uniffi/kotlin-bindings.sh
  popd >/dev/null

  echo "==> Copying generated bindings + native libs into Android repo"
  cp "$MARMOT_DIR/crates/marmot-uniffi/output/android/kotlin/dev/ipf/marmotkit/marmot_uniffi.kt" \
     "$REPO_DIR/app/src/main/java/dev/ipf/marmotkit/marmot_uniffi.kt"
  bash "$REPO_DIR/scripts/patch-marmot-kotlin-bindings.sh" \
    "$REPO_DIR/app/src/main/java/dev/ipf/marmotkit/marmot_uniffi.kt"
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    cp "$MARMOT_DIR/crates/marmot-uniffi/output/android/jniLibs/$abi/libmarmot_uniffi.so" \
       "$REPO_DIR/app/src/main/jniLibs/$abi/libmarmot_uniffi.so"
  done
fi

# --- Gradle release build ---
cd "$REPO_DIR"

selected_apks=()

for flavor in "${BUILD_FLAVORS[@]}"; do
  flavor_task="$(flavor_task_name "$flavor")"
  APK_DIR="$REPO_DIR/app/build/outputs/apk/$flavor/release"
  INTERMEDIATE_APK_DIR="$REPO_DIR/app/build/intermediates/apk/$flavor/release"
  mkdir -p "$APK_DIR"

  if [[ -n "$TARGET_ABI" ]]; then
    echo "==> Assembling $flavor release APK for $TARGET_ABI"
    rm -f "$APK_DIR"/*.apk
    if [[ "$TARGET_ABI" == "universal" ]]; then
      ./gradlew ":app:assemble${flavor_task}Release" \
        -Pandroid.injected.testOnly=false
    else
      ./gradlew ":app:assemble${flavor_task}Release" \
        -Pandroid.injected.build.abi="$TARGET_ABI" \
        -Pandroid.injected.testOnly=false
    fi

    selected_apk="$(select_release_apk "$APK_DIR" "$INTERMEDIATE_APK_DIR" "$TARGET_ABI")"
    if [[ -z "$selected_apk" || ! -f "$selected_apk" ]]; then
      echo "error: no APK found for ABI: $TARGET_ABI ($flavor)" >&2
      exit 1
    fi

    if [[ "$TARGET_ABI" == "arm64-v8a" ]]; then
      renamed_apk="$APK_DIR/whitenoise-${flavor}-v8a-release-$(date +%F).apk"
      mkdir -p "$APK_DIR"
      mv "$selected_apk" "$renamed_apk"
      selected_apk="$renamed_apk"
    fi

    assert_not_test_only "$selected_apk"
    selected_apks+=("$selected_apk")
  else
    echo "==> Assembling $flavor release APKs"
    ./gradlew ":app:assemble${flavor_task}Release"
  fi
done

echo ""
echo "==> Release APKs:"
for flavor in "${BUILD_FLAVORS[@]}"; do
  APK_DIR="$REPO_DIR/app/build/outputs/apk/$flavor/release"
  if compgen -G "$APK_DIR/*.apk" >/dev/null; then
    ls -lh "$APK_DIR"/*.apk
  fi
done

if [[ -n "$TARGET_ABI" ]]; then
  echo ""
  printf '==> Selected: %s\n' "${selected_apks[@]}"
fi
