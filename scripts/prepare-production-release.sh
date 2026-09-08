#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/prepare-production-release.sh [options]

Build and verify the one production APK used by Zapstore/GitHub plus the Play
App Bundle, then collect immutable release inputs under build/production-release.

Options:
  --allow-dirty                Permit a non-clean worktree (never used in CI)
  --allow-incomplete-runtime   Permit missing production push/telemetry values
                               for a non-publishable local rehearsal
  --expected-version <name>    Require the canonical versionName to match
  --help                       Show this help
EOF
}

allow_dirty=false
allow_incomplete_runtime=false
expected_version=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --allow-dirty)
      allow_dirty=true
      shift
      ;;
    --allow-incomplete-runtime)
      allow_incomplete_runtime=true
      shift
      ;;
    --expected-version)
      if [[ $# -lt 2 || -z "$2" || "$2" == --* ]]; then
        echo "error: --expected-version requires a value" >&2
        exit 1
      fi
      expected_version="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
release_config="$repo_dir/config/android-release.properties"
gradle_file="$repo_dir/app/build.gradle.kts"
output_dir="$repo_dir/build/production-release"

release_property() {
  local property_name="$1"
  awk -F= -v requested="$property_name" '$1 == requested { print substr($0, index($0, "=") + 1); exit }' "$release_config"
}

android_build_tool() {
  local tool_name="$1"
  if command -v "$tool_name" >/dev/null 2>&1; then
    command -v "$tool_name"
    return 0
  fi

  local sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  if [[ -d "$sdk_dir/build-tools" ]]; then
    find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 -type f -name "$tool_name" | sort | tail -1
  fi
}

normalize_fingerprint() {
  tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'
}

configured_value() {
  local property_name="$1"
  local property_value=""
  if [[ -f "$repo_dir/local.properties" ]]; then
    property_value="$(grep "^${property_name}=" "$repo_dir/local.properties" 2>/dev/null | head -1 | cut -d= -f2- || true)"
  fi
  if [[ -z "$property_value" && -n "${!property_name:-}" ]]; then
    property_value="${!property_name}"
  fi
  printf '%s' "$property_value"
}

production_runtime_value() {
  local property_name="$1"
  local property_value
  property_value="$(configured_value "$property_name")"
  if [[ -n "$property_value" ]]; then
    printf '%s' "$property_value"
    return 0
  fi

  # Keep the release guard aligned with the canonical defaults compiled by
  # app/build.gradle.kts. These public identifiers are not credentials.
  case "$property_name" in
    WHITENOISE_PUSH_RELAY_HINT)
      printf '%s' 'wss://relay.eu.whitenoise.chat'
      ;;
  esac
}

application_id="$(release_property APPLICATION_ID)"
expected_app_signing_sha="$(release_property APP_SIGNING_SHA256)"
expected_play_upload_sha="$(release_property PLAY_UPLOAD_SHA256)"
zsp_version="$(release_property ZSP_VERSION)"
version_code="$(awk '/^[[:space:]]*versionCode = [0-9]+$/ { print $3; exit }' "$gradle_file")"
version_name="$(awk -F'"' '/^[[:space:]]*versionName = "/ { print $2; exit }' "$gradle_file")"
source_sha="$(git -C "$repo_dir" rev-parse HEAD)"
worktree_dirty=false
if [[ -n "$(git -C "$repo_dir" status --porcelain --untracked-files=normal)" ]]; then
  worktree_dirty=true
fi

if [[ -z "$version_code" || -z "$version_name" ]]; then
  echo "error: unable to read canonical version from app/build.gradle.kts" >&2
  exit 1
fi
if [[ -n "$expected_version" && "$version_name" != "$expected_version" ]]; then
  echo "error: versionName is $version_name, expected $expected_version" >&2
  exit 1
fi
if [[ "$allow_dirty" != true && "$worktree_dirty" == true ]]; then
  echo "error: production releases require a clean worktree (use --allow-dirty only for local rehearsal)" >&2
  exit 1
fi

runtime_missing=""
for runtime_name in \
  WHITENOISE_OTLP_ENDPOINT \
  WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN \
  WHITENOISE_AUDIT_LOG_ENDPOINT \
  WHITENOISE_AUDIT_LOG_AUTH_TOKEN \
  WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX \
  WHITENOISE_PUSH_RELAY_HINT; do
  if [[ -z "$(production_runtime_value "$runtime_name")" ]]; then
    runtime_missing="$runtime_missing $runtime_name"
  fi
done
push_pubkey="$(production_runtime_value WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX)"
if [[ -n "$push_pubkey" && ! "$push_pubkey" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "error: WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX must contain 64 hex characters" >&2
  exit 1
fi
if [[ -n "$runtime_missing" && "$allow_incomplete_runtime" != true ]]; then
  echo "error: missing publishable production runtime configuration:$runtime_missing" >&2
  echo "Use --allow-incomplete-runtime only for a non-publishable local rehearsal." >&2
  exit 1
fi
runtime_complete=true
if [[ -n "$runtime_missing" ]]; then
  runtime_complete=false
fi

"$repo_dir/scripts/check-release-metadata.py"
# Fail before building if this version already identifies another source commit.
python3 "$repo_dir/scripts/release_bundle.py" check-tag --source "$source_sha" --version "$version_name"
bundletool="$repo_dir/build/tools/bundletool.jar"
"$repo_dir/scripts/install-bundletool.sh" "$bundletool"

"$repo_dir/scripts/release.sh" --flavor production --abi arm64-v8a
(
  cd "$repo_dir"
  ./gradlew :app:bundleProductionPlayRelease -Pandroid.injected.testOnly=false
)

apk_source_dir="$repo_dir/app/build/outputs/apk/productionZapstore/release"
aab_source="$repo_dir/app/build/outputs/bundle/productionPlayRelease/app-production-play-release.aab"
apk_count="$(find "$apk_source_dir" -maxdepth 1 -type f -name 'whitenoise-production-v8a-release-*.apk' 2>/dev/null | wc -l | tr -d ' ')"
if [[ "$apk_count" != 1 ]]; then
  echo "error: expected one production arm64 APK, found $apk_count" >&2
  exit 1
fi
apk_source="$(find "$apk_source_dir" -maxdepth 1 -type f -name 'whitenoise-production-v8a-release-*.apk' -print)"
if [[ ! -f "$aab_source" ]]; then
  echo "error: missing Play App Bundle: $aab_source" >&2
  exit 1
fi

aapt_bin="$(android_build_tool aapt)"
apksigner_bin="$(android_build_tool apksigner)"
if [[ -z "$aapt_bin" || ! -x "$aapt_bin" || -z "$apksigner_bin" || ! -x "$apksigner_bin" ]]; then
  echo "error: aapt and apksigner are required to verify release artifacts" >&2
  exit 1
fi

badging="$("$aapt_bin" dump badging "$apk_source")"
actual_application_id="$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
actual_version_code="$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p")"
actual_version_name="$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p")"
if [[ "$actual_application_id" != "$application_id" || "$actual_version_code" != "$version_code" || "$actual_version_name" != "$version_name" ]]; then
  echo "error: APK identity/version does not match canonical release metadata" >&2
  exit 1
fi

"$apksigner_bin" verify --verbose "$apk_source" >/dev/null
actual_app_signing_sha="$($apksigner_bin verify --print-certs "$apk_source" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | normalize_fingerprint)"
if [[ "$actual_app_signing_sha" != "$expected_app_signing_sha" ]]; then
  echo "error: direct APK signer does not match the registered Play/Zapstore app-signing key" >&2
  exit 1
fi

apk_abis="$(unzip -Z1 "$apk_source" | awk -F/ '/^lib\/[^\/]+\/.*\.so$/ { print $2 }' | sort -u | paste -sd, -)"
if [[ "$apk_abis" != "arm64-v8a" ]]; then
  echo "error: direct APK must contain only arm64-v8a native libraries; found: $apk_abis" >&2
  exit 1
fi

jarsigner -verify "$aab_source" >/dev/null
actual_play_upload_sha="$(keytool -printcert -jarfile "$aab_source" | sed -n 's/^[[:space:]]*SHA256: //p' | normalize_fingerprint)"
if [[ "$actual_play_upload_sha" != "$expected_play_upload_sha" ]]; then
  echo "error: Play App Bundle signer does not match the registered upload key" >&2
  exit 1
fi

java -jar "$bundletool" validate --bundle="$aab_source" >/dev/null
for field in package android:versionCode android:versionName; do
  actual="$(java -jar "$bundletool" dump manifest --bundle="$aab_source" --xpath="/manifest/@$field")"
  case "$field" in
    package) expected="$application_id" ;;
    android:versionCode) expected="$version_code" ;;
    android:versionName) expected="$version_name" ;;
  esac
  [[ "$actual" == "$expected" ]] || { echo "error: AAB $field differs from canonical release identity" >&2; exit 1; }
done

aab_abis="$(unzip -Z1 "$aab_source" | awk -F/ '/^base\/lib\/[^\/]+\/.*\.so$/ { print $3 }' | sort -u | paste -sd, -)"
if [[ "$aab_abis" != "arm64-v8a,armeabi-v7a,x86,x86_64" ]]; then
  echo "error: Play App Bundle does not contain the expected four ABIs; found: $aab_abis" >&2
  exit 1
fi

[[ "$(git -C "$repo_dir" rev-parse HEAD)" == "$source_sha" ]] || {
  echo 'error: source commit changed during build' >&2; exit 1;
}
if [[ "$allow_dirty" != true && -n "$(git -C "$repo_dir" status --porcelain --untracked-files=normal)" ]]; then
  echo 'error: source worktree changed during build' >&2; exit 1
fi

rm -rf "$output_dir"
mkdir -p "$output_dir"
apk_output="$output_dir/whitenoise-android-$version_name-arm64-v8a.apk"
aab_output="$output_dir/whitenoise-android-$version_name-play.aab"
notes_output="$output_dir/release-notes-en-US.txt"
cp "$apk_source" "$apk_output"
cp "$aab_source" "$aab_output"
cp "$repo_dir/fastlane/metadata/android/en-US/changelogs/$version_code.txt" "$notes_output"

for distribution in Play Zapstore; do
  mapping_source="$repo_dir/app/build/outputs/mapping/production${distribution}Release/mapping.txt"
  [[ -s "$mapping_source" ]] || { echo "error: missing $distribution R8 mapping" >&2; exit 1; }
  mapping_suffix="$(printf '%s' "$distribution" | tr '[:upper:]' '[:lower:]')"
  cp "$mapping_source" "$output_dir/mapping-$version_name-$mapping_suffix.txt"
done

(
  cd "$repo_dir"
  zip -q -r "$output_dir/store-assets-$version_name.zip" fastlane/metadata/android/en-US zapstore.yaml
)

python3 - "$output_dir" "$application_id" "$version_name" "$version_code" "$source_sha" "$worktree_dirty" "$runtime_complete" "$runtime_missing" "$actual_app_signing_sha" "$actual_play_upload_sha" "$zsp_version" <<'PY'
import hashlib
import json
import os
import sys
from pathlib import Path

output_dir = Path(sys.argv[1])
files = {}
for artifact in sorted(output_dir.iterdir()):
    if not artifact.is_file() or artifact.name in {"release-manifest.json", "checksums-sha256.txt"}:
        continue
    files[artifact.name] = {
        "bytes": artifact.stat().st_size,
        "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
    }

manifest = {
    "schemaVersion": 2,
    "buildRunId": os.environ.get("GITHUB_RUN_ID", ""),
    "buildRunAttempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
    "applicationId": sys.argv[2],
    "versionName": sys.argv[3],
    "versionCode": int(sys.argv[4]),
    "sourceCommit": sys.argv[5],
    "worktreeDirty": sys.argv[6] == "true",
    "productionRuntimeConfigurationComplete": sys.argv[7] == "true",
    "missingProductionRuntimeConfiguration": sys.argv[8].split(),
    "appSigningCertificateSha256": sys.argv[9],
    "playUploadCertificateSha256": sys.argv[10],
    "zspVersion": sys.argv[11],
    "files": files,
}
(output_dir / "release-manifest.json").write_text(
    json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
)
PY

(
  cd "$output_dir"
  : > checksums-sha256.txt
  for release_file in ./*; do
    [[ "$release_file" == "./checksums-sha256.txt" ]] && continue
    shasum -a 256 "$release_file" >> checksums-sha256.txt
  done
)

echo "Prepared production release $version_name (versionCode $version_code)"
echo "Source: $source_sha"
echo "Output: $output_dir"
if [[ "$worktree_dirty" == true || "$runtime_complete" != true ]]; then
  echo "WARNING: local rehearsal only; this bundle is not publishable." >&2
  [[ "$worktree_dirty" == true ]] && echo "  - source worktree is dirty" >&2
  [[ "$runtime_complete" != true ]] && echo "  - production runtime configuration is incomplete:$runtime_missing" >&2
fi
