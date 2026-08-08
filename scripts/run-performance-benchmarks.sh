#!/usr/bin/env bash
set -euo pipefail

group_name="${1:?usage: scripts/run-performance-benchmarks.sh <group-name>}"
target_package="dev.ipf.whitenoise.android.dev"
test_package="dev.ipf.whitenoise.android.benchmark"
runner="$test_package/androidx.test.runner.AndroidJUnitRunner"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required host command: $1" >&2
    exit 1
  fi
}

require_command jq
require_command rg

if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  adb_bin="$ANDROID_HOME/platform-tools/adb"
else
  require_command adb
  adb_bin="$(command -v adb)"
fi

adb_args=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  adb_args=(-s "$ANDROID_SERIAL")
fi

adb_cmd() {
  "$adb_bin" "${adb_args[@]}" "$@"
}

if ! adb_cmd shell pm path "$target_package" >/dev/null 2>&1; then
  echo "The authenticated dev app is not installed: $target_package" >&2
  echo "Install :app:installDevZapstoreDebug and prepare the fixture first." >&2
  exit 1
fi

device_abi="$(adb_cmd shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$device_abi" in
  arm64-v8a | armeabi-v7a | x86 | x86_64) ;;
  *)
    echo "Unsupported or empty device ABI: $device_abi" >&2
    exit 1
    ;;
esac

./gradlew \
  :app:assembleDevZapstoreDebug \
  :app:assembleDevZapstoreBenchmarkRelease \
  :benchmark:assembleDevZapstoreBenchmarkRelease \
  -Pandroid.injected.build.abi="$device_abi" \
  --no-daemon

resolve_apk() {
  local module_dir="$1"
  local application_id="$2"
  local variant_name="$3"
  local apk_root metadata output_file candidate

  # AGP writes regular assembly outputs under outputs/apk. Device-targeted
  # builds created with android.injected.build.abi may instead use
  # intermediates/apk, so resolve the artifact from AGP's metadata in either
  # location rather than depending on a generated filename or directory shape.
  for apk_root in "$module_dir/build/outputs/apk" "$module_dir/build/intermediates/apk"; do
    [[ -d "$apk_root" ]] || continue
    while IFS= read -r metadata; do
      if [[ "$(jq -r '.applicationId' "$metadata")" != "$application_id" ]] ||
        [[ "$(jq -r '.variantName' "$metadata")" != "$variant_name" ]]; then
        continue
      fi

      output_file="$(
        jq -er --arg abi "$device_abi" '
          .elements
          | map(
              select(
                ([.filters[]? | select(.filterType == "ABI") | .value]) as $abis
                | ($abis | length == 0) or ($abis | index($abi) != null)
              )
            )
          | if length == 1 then .[0].outputFile else empty end
        ' "$metadata"
      )" || continue
      candidate="$(dirname "$metadata")/$output_file"
      if [[ -f "$candidate" ]]; then
        printf '%s\n' "$candidate"
        return 0
      fi
    done < <(find "$apk_root" -type f -name output-metadata.json -print | sort)
  done

  echo "Could not resolve $application_id ($variant_name) from AGP output metadata." >&2
  return 1
}

dev_app_apk="$(resolve_apk app "$target_package" devZapstoreDebug)"
app_apk="$(resolve_apk app "$target_package" devZapstoreBenchmarkRelease)"
test_apk="$(resolve_apk benchmark "$test_package" devZapstoreBenchmarkRelease)"

for apk in "$dev_app_apk" "$app_apk" "$test_apk"; do
  if [[ ! -f "$apk" ]]; then
    echo "Expected APK was not built: $apk" >&2
    exit 1
  fi
done

result_file="$(mktemp)"
cleanup() {
  local status=$?
  trap - EXIT
  adb_cmd uninstall "$test_package" >/dev/null 2>&1 || true
  if ! adb_cmd install -r -d -t "$dev_app_apk"; then
    echo "Failed to restore the normal dev app: $dev_app_apk" >&2
    if ((status == 0)); then status=1; fi
  fi
  rm -f "$result_file"
  exit "$status"
}
trap cleanup EXIT

# Replacing the target APK with the same application ID and debug certificate
# preserves its authenticated data. The exit trap restores the normal dev debug
# APK and uninstalls the self-instrumenting package even when the run fails.
adb_cmd uninstall "$test_package" >/dev/null 2>&1 || true
adb_cmd install -r -d -t "$app_apk"
adb_cmd install -r -d -t "$test_apk"

default_benchmark_classes="dev.ipf.whitenoise.android.benchmark.StartupBenchmark#coldStartupNoCompilation,\
dev.ipf.whitenoise.android.benchmark.StartupBenchmark#coldStartupBaselineProfile,\
dev.ipf.whitenoise.android.benchmark.GroupFlowsBenchmark#openGroupMembersNoCompilation,\
dev.ipf.whitenoise.android.benchmark.GroupFlowsBenchmark#openGroupMembersBaselineProfile"
benchmark_classes="${BENCHMARK_CLASS_FILTER:-$default_benchmark_classes}"

adb_cmd shell am instrument -w -r \
  -e class "$benchmark_classes" \
  -e groupName "$group_name" \
  -e androidx.benchmark.output.enable true \
  "$runner" | tee "$result_file"

if rg -q "FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed" "$result_file"; then
  echo "Instrumentation reported a failure." >&2
  exit 1
fi

device_output="/sdcard/Android/media/$test_package"
local_output="benchmark/build/outputs/manual/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$local_output"
if adb_cmd shell test -d "$device_output"; then
  adb_cmd pull "$device_output" "$local_output"
fi

echo "Benchmark artifacts: $local_output"
