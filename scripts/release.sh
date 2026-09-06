#!/usr/bin/env bash
# Build signed release APKs for White Noise Android.
#
# Pre-reqs:
#   - JAVA_HOME or a JBR pointing at JDK 17+
#   - Android SDK installed
#   - Signing creds in local.properties (see scripts/release.sh --help)
#
# Gradle downloads and verifies the immutable MarmotKit artifact pinned in
# app/src/main/marmotkit/MARMOT_VERSION. No local MDK checkout is required.
#
# Outputs signed per-ABI and universal APKs under app/build/outputs/apk/<flavor>/release/.

set -euo pipefail

usage() {
  cat <<EOF
Usage: scripts/release.sh [--abi <ABI>] [--flavor <name>] [--gradle-init-script <path>] [--help]

  --abi <ABI>       Build only a specific ABI APK, then print its path
                    (arm64-v8a | armeabi-v7a | x86 | x86_64 | universal)
  --flavor <name>   Build one release flavor, or both with "all"
                    (production | staging | all; default: production)
  --gradle-init-script <path>
                    Apply one regular, non-symlink Gradle init script to the
                    release build. Intended for trusted CI packaging policy.
  --help            Show this help

Production signing creds (in local.properties or env):
  WHITENOISE_PRODUCTION_KEYSTORE_PATH       Path to .p12 / .jks keystore
  WHITENOISE_PRODUCTION_KEY_ALIAS           Key alias inside the keystore
  WHITENOISE_PRODUCTION_KEYSTORE_PASSWORD   Keystore password
  WHITENOISE_PRODUCTION_KEY_PASSWORD        Key password (same as keystore for PKCS12)

Production also accepts WHITENOISE_KEYSTORE_* names as fallbacks.

Production MIP-05 push config (in local.properties or env):
  WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX  64-character server public key
  WHITENOISE_PRODUCTION_PUSH_RELAY_HINT         Valid wss:// relay URI

Production also accepts WHITENOISE_PUSH_* names as fallbacks.

Staging signing creds:
  WHITENOISE_STAGING_KEYSTORE_PATH
  WHITENOISE_STAGING_KEY_ALIAS
  WHITENOISE_STAGING_KEYSTORE_PASSWORD
  WHITENOISE_STAGING_KEY_PASSWORD

Optional env:
  WHITENOISE_MARMOTKIT_CACHE_DIR      Content-addressed MarmotKit artifact cache
  WHITENOISE_MARMOTKIT_ARTIFACT_FILE  Pre-downloaded artifact for offline builds
EOF
}

TARGET_ABI=""
FLAVOR="production"
GRADLE_INIT_SCRIPT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --abi)
      if [[ $# -lt 2 || -z "$2" || "$2" == --* ]]; then
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
    --gradle-init-script)
      if [[ $# -lt 2 || -z "$2" || "$2" == --* ]]; then
        echo "error: --gradle-init-script requires a path" >&2
        usage
        exit 1
      fi
      GRADLE_INIT_SCRIPT="$2"
      shift 2
      ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
  esac
done

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

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

assert_production_firebase_resources() {
  local apk="$1"
  local aapt_path resources
  local missing=()
  aapt_path="$(android_build_tool aapt)"
  if [[ -z "$aapt_path" || ! -x "$aapt_path" ]]; then
    echo "error: aapt not found; cannot verify release APK Firebase resources" >&2
    exit 1
  fi
  if ! resources="$("$aapt_path" dump resources "$apk")"; then
    echo "error: unable to inspect release APK resources: $apk" >&2
    exit 1
  fi

  grep -q 'google_app_id' <<< "$resources" || missing+=(google_app_id)
  grep -q 'gcm_defaultSenderId' <<< "$resources" || missing+=(gcm_defaultSenderId)
  if (( ${#missing[@]} > 0 )); then
    echo "error: production release APK is missing Firebase resources (${missing[*]}): $apk" >&2
    exit 1
  fi
}

assert_production_push_config() {
  local apk="$1"
  local dex_strings

  if ! command -v unzip >/dev/null 2>&1 || ! command -v strings >/dev/null 2>&1; then
    echo "error: unzip and strings are required to verify production push configuration" >&2
    exit 1
  fi
  if ! dex_strings="$(unzip -p "$apk" 'classes*.dex' | strings -a)"; then
    echo "error: unable to inspect release APK bytecode for production push configuration: $apk" >&2
    exit 1
  fi
  if ! grep -Fq -- "$PRODUCTION_PUSH_SERVER_PUBKEY_HEX" <<< "$dex_strings"; then
    echo "error: production release APK does not contain the configured MIP-05 push server public key: $apk" >&2
    exit 1
  fi
  if ! grep -Fq -- "$PRODUCTION_PUSH_RELAY_HINT" <<< "$dex_strings"; then
    echo "error: production release APK does not contain the configured MIP-05 push relay hint: $apk" >&2
    exit 1
  fi
}

assert_release_apk() {
  local apk="$1"
  local flavor="$2"
  assert_not_test_only "$apk"
  if [[ "$flavor" == "production" ]]; then
    assert_production_firebase_resources "$apk"
    assert_production_push_config "$apk"
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

GRADLE_EXTRA_ARGS=()
if [[ -n "$GRADLE_INIT_SCRIPT" ]]; then
  if [[ ! -f "$GRADLE_INIT_SCRIPT" || -L "$GRADLE_INIT_SCRIPT" ]]; then
    echo "error: Gradle init script must be a regular non-symlink file: $GRADLE_INIT_SCRIPT" >&2
    exit 1
  fi
  GRADLE_EXTRA_ARGS=(-I "$GRADLE_INIT_SCRIPT")
fi

if [[ "$FLAVOR" == "all" ]]; then
  BUILD_FLAVORS=(production staging)
else
  BUILD_FLAVORS=("$FLAVOR")
fi

flavor_task_name() {
  # Release APKs are the direct-distribution (Zapstore) channel, so each
  # environment flavor combines with the zapstore distribution flavor.
  case "$1" in
    production) printf 'ProductionZapstore' ;;
    staging) printf 'StagingZapstore' ;;
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

require_production_firebase_config() {
  local config_path="$REPO_DIR/app/google-services.json"
  local application_id="dev.ipf.whitenoise.android"

  if [[ ! -f "$config_path" ]]; then
    echo "error: production release requires app/google-services.json with an Android client for $application_id" >&2
    exit 1
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    echo "error: python3 is required to validate app/google-services.json" >&2
    exit 1
  fi

  python3 - "$config_path" "$application_id" <<'PY'
import json
import sys
from pathlib import Path

config_path = Path(sys.argv[1])
application_id = sys.argv[2]

try:
    payload = json.loads(config_path.read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    print(f"error: unable to parse app/google-services.json: {error}", file=sys.stderr)
    raise SystemExit(1)

clients = payload.get("client", []) if isinstance(payload, dict) else []
package_names = set()
for client in clients:
    if not isinstance(client, dict):
        continue
    client_info = client.get("client_info")
    if not isinstance(client_info, dict):
        continue
    android_client_info = client_info.get("android_client_info")
    if isinstance(android_client_info, dict):
        package_names.add(android_client_info.get("package_name"))
if application_id not in package_names:
    print(
        "error: app/google-services.json does not contain an Android client for "
        f"{application_id}",
        file=sys.stderr,
    )
    raise SystemExit(1)
PY
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

runtime_prop_value() {
  local key value
  for key in "$@"; do
    if grep -q "^${key}=" "$LOCAL_PROPS" 2>/dev/null; then
      grep "^${key}=" "$LOCAL_PROPS" | head -1 | cut -d= -f2-
      return 0
    fi
    if printenv "$key" >/dev/null 2>&1; then
      printenv "$key"
      return 0
    fi
  done
  return 1
}

production_push_value() {
  local suffix="$1"
  local default_value="${2:-}"
  if runtime_prop_value "WHITENOISE_PRODUCTION_${suffix}" "WHITENOISE_${suffix}"; then
    return 0
  fi
  printf '%s\n' "$default_value"
}

PRODUCTION_PUSH_SERVER_PUBKEY_HEX=""
PRODUCTION_PUSH_RELAY_HINT=""

require_production_push_config() {
  PRODUCTION_PUSH_SERVER_PUBKEY_HEX="$(production_push_value PUSH_SERVER_PUBKEY_HEX)"
  PRODUCTION_PUSH_RELAY_HINT="$(production_push_value PUSH_RELAY_HINT 'wss://relay.eu.whitenoise.chat')"

  if [[ ! "$PRODUCTION_PUSH_SERVER_PUBKEY_HEX" =~ ^[[:xdigit:]]{64}$ ]]; then
    echo "error: WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX (or WHITENOISE_PUSH_SERVER_PUBKEY_HEX) must be exactly 64 hexadecimal characters" >&2
    exit 1
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    echo "error: python3 is required to validate the production push relay hint" >&2
    exit 1
  fi
  if ! python3 - "$PRODUCTION_PUSH_RELAY_HINT" <<'PY'
import sys
from urllib.parse import urlparse

relay = urlparse(sys.argv[1])
try:
    relay.port
except ValueError:
    valid = False
else:
    valid = (
        relay.scheme.lower() == "wss"
        and bool(relay.hostname)
        and relay.username is None
        and relay.password is None
        and not relay.fragment
    )
raise SystemExit(0 if valid else 1)
PY
  then
    echo "error: WHITENOISE_PRODUCTION_PUSH_RELAY_HINT (or WHITENOISE_PUSH_RELAY_HINT) must be a valid wss:// URI" >&2
    exit 1
  fi
}

flavor_signing_value() {
  local flavor="$1"
  local suffix="$2"
  case "$flavor" in
    production) prop_value "WHITENOISE_PRODUCTION_${suffix}" "WHITENOISE_${suffix}" ;;
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
  if [[ "$flavor" == "production" ]]; then
    require_production_firebase_config
    require_production_push_config
  fi
  require_flavor_signing "$flavor"
done

# --- Gradle release build ---
cd "$REPO_DIR"

selected_apks=()

for flavor in "${BUILD_FLAVORS[@]}"; do
  flavor_task="$(flavor_task_name "$flavor")"
  APK_DIR="$REPO_DIR/app/build/outputs/apk/${flavor}Zapstore/release"
  INTERMEDIATE_APK_DIR="$REPO_DIR/app/build/intermediates/apk/${flavor}Zapstore/release"
  mkdir -p "$APK_DIR"

  if [[ -n "$TARGET_ABI" ]]; then
    echo "==> Assembling $flavor release APK for $TARGET_ABI"
    rm -f "$APK_DIR"/*.apk
    if [[ "$TARGET_ABI" == "universal" ]]; then
      ./gradlew ":app:assemble${flavor_task}Release" \
        "${GRADLE_EXTRA_ARGS[@]}" \
        -Pandroid.injected.testOnly=false
    else
      ./gradlew ":app:assemble${flavor_task}Release" \
        "${GRADLE_EXTRA_ARGS[@]}" \
        -Pandroid.injected.build.abi="$TARGET_ABI" \
        -Pandroid.injected.testOnly=false
    fi

    selected_apk="$(select_release_apk "$APK_DIR" "$INTERMEDIATE_APK_DIR" "$TARGET_ABI")"
    if [[ -z "$selected_apk" || ! -f "$selected_apk" ]]; then
      echo "error: no APK found for ABI: $TARGET_ABI ($flavor)" >&2
      exit 1
    fi

    if [[ "$TARGET_ABI" == "arm64-v8a" ]]; then
      # Keep the short SHA in the final name: this rename is the terminal
      # naming step, and a date-only name makes same-day builds collide
      # (issue #992).
      short_sha="${GITHUB_SHA:-}"
      short_sha="${short_sha:0:7}"
      if [[ -z "$short_sha" ]]; then
        short_sha="$(git rev-parse --short=7 HEAD 2>/dev/null || echo local)"
      fi
      renamed_apk="$APK_DIR/whitenoise-${flavor}-v8a-release-$(date +%F)-${short_sha}.apk"
      mkdir -p "$APK_DIR"
      mv "$selected_apk" "$renamed_apk"
      selected_apk="$renamed_apk"
    fi

    assert_release_apk "$selected_apk" "$flavor"
    selected_apks+=("$selected_apk")
  else
    echo "==> Assembling $flavor release APKs"
    ./gradlew ":app:assemble${flavor_task}Release" "${GRADLE_EXTRA_ARGS[@]}"

    verified_apk_count=0
    for built_apk in "$APK_DIR"/*.apk; do
      [[ -f "$built_apk" ]] || continue
      assert_release_apk "$built_apk" "$flavor"
      verified_apk_count=$((verified_apk_count + 1))
    done
    if (( verified_apk_count == 0 )); then
      echo "error: no release APKs found after assembling $flavor" >&2
      exit 1
    fi
  fi
done

echo ""
echo "==> Release APKs:"
for flavor in "${BUILD_FLAVORS[@]}"; do
  APK_DIR="$REPO_DIR/app/build/outputs/apk/${flavor}Zapstore/release"
  if compgen -G "$APK_DIR/*.apk" >/dev/null; then
    ls -lh "$APK_DIR"/*.apk
  fi
done

if [[ -n "$TARGET_ABI" ]]; then
  echo ""
  printf '==> Selected: %s\n' "${selected_apks[@]}"
fi
