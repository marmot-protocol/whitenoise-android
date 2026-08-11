#!/usr/bin/env bash
set -euo pipefail

group_name="${1:-}"
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

# adb joins arguments following `shell` into a command string interpreted by
# the device shell. Quote every dynamic value so spaces stay intact and shell
# metacharacters remain data rather than becoming commands.
quote_device_shell_arg() {
  local escaped
  escaped="$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
  printf "'%s'" "$escaped"
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
  local apk_root metadata output_file candidate newest_candidate=""

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
      if [[ -f "$candidate" ]] &&
        { [[ -z "$newest_candidate" ]] || [[ "$candidate" -nt "$newest_candidate" ]]; }; then
        newest_candidate="$candidate"
      fi
    done < <(find "$apk_root" -type f -name output-metadata.json -print | sort)
  done

  if [[ -n "$newest_candidate" ]]; then
    printf '%s\n' "$newest_candidate"
    return 0
  fi

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
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
device_output="/sdcard/Android/media/$test_package/$run_id"
local_output="benchmark/build/outputs/manual/$run_id"
mkdir -p "$local_output"
device_output_pulled=false
heads_up_setting_captured=false
original_heads_up_notifications_enabled=""

capture_device_state() {
  local destination="$1"
  local battery thermal
  battery="$(adb_cmd shell dumpsys battery)"
  thermal="$(adb_cmd shell dumpsys thermalservice)"
  {
    printf 'captured_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'serial=%s\n' "${ANDROID_SERIAL:-default}"
    printf 'model=%s\n' "$(adb_cmd shell getprop ro.product.model | tr -d '\r')"
    printf 'android_release=%s\n' "$(adb_cmd shell getprop ro.build.version.release | tr -d '\r')"
    printf 'api_level=%s\n' "$(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')"
    printf 'build_fingerprint=%s\n' "$(adb_cmd shell getprop ro.build.fingerprint | tr -d '\r')"
    printf '%s\n' "$battery" | awk '/USB powered:|level:|temperature:/{gsub(/^ +/, ""); print "battery_" $0}'
    printf '%s\n' "$thermal" | awk '/^Thermal Status:/{print "thermal_status=" $3}'
  } >"$destination"
}

cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$heads_up_setting_captured" == true ]]; then
    if [[ -z "$original_heads_up_notifications_enabled" ||
      "$original_heads_up_notifications_enabled" == "null" ]]; then
      adb_cmd shell settings delete global heads_up_notifications_enabled >/dev/null || true
    else
      adb_cmd shell settings put global heads_up_notifications_enabled \
        "$original_heads_up_notifications_enabled" || true
    fi
  fi
  if [[ "$device_output_pulled" == true ]]; then
    adb_cmd shell rm -rf "$device_output" || true
  fi
  if ! adb_cmd install -r -d -t "$dev_app_apk"; then
    echo "Failed to restore the normal dev app: $dev_app_apk" >&2
    if ((status == 0)); then status=1; fi
  fi
  rm -f "$result_file"
  exit "$status"
}
trap cleanup EXIT

# A heads-up notification can cover a Compose target between UiAutomator
# resolving its bounds and injecting the tap. That contaminates the sample and
# can click through into another app. Disable only the overlay for this process
# lifetime; notifications are still delivered, and the exit trap restores the
# exact previous setting on success or failure.
original_heads_up_notifications_enabled="$(
  adb_cmd shell settings get global heads_up_notifications_enabled | tr -d '\r'
)"
heads_up_setting_captured=true
adb_cmd shell settings put global heads_up_notifications_enabled 0
adb_cmd shell cmd statusbar collapse

# Replacing the target APK with the same application ID and debug certificate
# preserves its authenticated data. The exit trap restores the normal dev debug
# APK even when the run fails. The benchmark package is also updated in place
# and left installed so this workflow never performs an uninstall on a personal
# physical device.
adb_cmd install -r -d -t "$app_apk"
adb_cmd install -r -d -t "$test_apk"

# Isolate this run from stale device output. Supplying the directory explicitly
# also makes every pulled report and trace attributable to this invocation.
adb_cmd shell rm -rf "$device_output"
adb_cmd shell mkdir -p "$device_output"

# Exercise the first launch after the in-place APK swap before Macrobenchmark
# starts resetting compilation. This catches a broken fixture early and keeps
# one-time package initialization out of the first measured iteration.
main_activity="$target_package/dev.ipf.whitenoise.android.MainActivity"
preflight_output="$(adb_cmd shell am start -W -n "$main_activity")"
if ! rg -q '^Status: ok\r?$' <<<"$preflight_output"; then
  echo "Benchmark target preflight launch failed:" >&2
  echo "$preflight_output" >&2
  exit 1
fi

preflight_dump="$device_output/preflight.xml"
preflight_ready=false
for _ in {1..15}; do
  if adb_cmd shell uiautomator dump "$preflight_dump" >/dev/null 2>&1 &&
    adb_cmd exec-out cat "$preflight_dump" | rg -q 'resource-id="performance.new_message"'; then
    preflight_ready=true
    break
  fi
  sleep 2
done
adb_cmd shell rm -f "$preflight_dump"
if [[ "$preflight_ready" != true ]]; then
  echo "Benchmark target did not reach the authenticated chat list during preflight." >&2
  exit 1
fi
adb_cmd shell am force-stop "$target_package"

default_benchmark_classes="dev.ipf.whitenoise.android.benchmark.StartupBenchmark#coldStartupNoCompilation,\
dev.ipf.whitenoise.android.benchmark.StartupBenchmark#coldStartupBaselineProfile,\
dev.ipf.whitenoise.android.benchmark.GroupFlowsBenchmark#openGroupMembersNoCompilation,\
dev.ipf.whitenoise.android.benchmark.GroupFlowsBenchmark#openGroupMembersBaselineProfile"
benchmark_classes="${BENCHMARK_CLASS_FILTER:-$default_benchmark_classes}"

if [[ -z "$group_name" && -z "${BENCHMARK_CLASS_FILTER:-}" ]]; then
  echo "usage: scripts/run-performance-benchmarks.sh <group-name>" >&2
  echo "A group name is required for the default startup + group-open suite." >&2
  exit 1
fi

instrument_command="am instrument -w -r \
-e class $(quote_device_shell_arg "$benchmark_classes") \
-e androidx.benchmark.output.enable true \
-e additionalTestOutputDir $(quote_device_shell_arg "$device_output")"
if [[ -n "$group_name" ]]; then
  instrument_command="$instrument_command \
-e groupName $(quote_device_shell_arg "$group_name")"
fi
if [[ -n "${CREATED_GROUP_PREFIX:-}" ]]; then
  instrument_command="$instrument_command \
-e createdGroupPrefix $(quote_device_shell_arg "$CREATED_GROUP_PREFIX")"
fi
if [[ -n "${INVITE_NAME:-}" ]]; then
  instrument_command="$instrument_command \
-e inviteName $(quote_device_shell_arg "$INVITE_NAME")"
fi
instrument_command="$instrument_command \
$(quote_device_shell_arg "$runner")"

capture_device_state "$local_output/device-before.txt"
instrumentation_status=0
adb_cmd shell "$instrument_command" | tee "$result_file" || instrumentation_status=$?
cp "$result_file" "$local_output/instrumentation.log"
capture_device_state "$local_output/device-after.txt"

if adb_cmd shell test -d "$device_output"; then
  if adb_cmd pull "$device_output/." "$local_output"; then
    device_output_pulled=true
  else
    echo "Failed to pull benchmark output from $device_output; leaving it on the device." >&2
  fi
fi

if ((instrumentation_status != 0)); then
  echo "Instrumentation command exited with status $instrumentation_status." >&2
  exit "$instrumentation_status"
fi

if rg -q "FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed" "$result_file"; then
  echo "Instrumentation reported a failure." >&2
  exit 1
fi

if ! rg -q '^INSTRUMENTATION_CODE: -1\r?$' "$result_file" ||
  ! rg -q 'OK \([1-9][0-9]* tests?\)' "$result_file"; then
  echo "Instrumentation did not report successful test completion." >&2
  exit 1
fi

if [[ "$device_output_pulled" != true ]]; then
  echo "Benchmark output directory was not created: $device_output" >&2
  exit 1
fi

benchmark_report_count=0
while IFS= read -r report; do
  if ! jq -e '.benchmarks | type == "array" and length > 0' "$report" >/dev/null; then
    echo "Benchmark report is missing measurement data: $report" >&2
    exit 1
  fi
  ((benchmark_report_count += 1))
done < <(find "$local_output" -type f -name '*-benchmarkData.json' -print)

benchmark_trace_count=0
while IFS= read -r trace; do
  ((benchmark_trace_count += 1))
done < <(find "$local_output" -type f \( -name '*.perfetto-trace' -o -name '*.trace' \) -print)

if ((benchmark_report_count == 0)); then
  echo "No fresh benchmark JSON report was pulled from $device_output." >&2
  exit 1
fi
if ((benchmark_trace_count == 0)); then
  echo "No fresh benchmark trace was pulled from $device_output." >&2
  exit 1
fi

echo "Benchmark artifacts: $local_output ($benchmark_report_count JSON, $benchmark_trace_count traces)"
