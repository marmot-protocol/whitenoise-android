#!/usr/bin/env bash
set -euo pipefail

if (($# != 5)); then
  echo "usage: $0 <launch-output> <startup-log> <device-state> <apk-sha256> <report-json>" >&2
  exit 2
fi

launch_output="$1"
startup_log="$2"
device_state="$3"
apk_sha256="$4"
report_json="$5"

for required_file in "$launch_output" "$startup_log" "$device_state"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Missing package-replacement evidence file: $required_file" >&2
    exit 1
  fi
done

if ! command -v jq >/dev/null 2>&1; then
  echo "Missing required host command: jq" >&2
  exit 1
fi

read_device_value() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key {value = substr($0, length(key) + 2)} END {print value}' "$device_state"
}

read_launch_value() {
  local key="$1"
  awk -F': ' -v key="$key" '$1 == key {value = $2} END {gsub(/\r$/, "", value); print value}' "$launch_output"
}

read_phase_value() {
  local phase="$1"
  local key="$2"
  awk -v target_op="op=app_start" -v target_phase="phase=$phase" -v value_prefix="$key=" '
    {
      op_matches = 0
      phase_matches = 0
      for (field_index = 1; field_index <= NF; field_index += 1) {
        if ($field_index == target_op) op_matches += 1
        if ($field_index == target_phase) phase_matches += 1
      }
      if (op_matches != 1 || phase_matches != 1) next
      for (field_index = 1; field_index <= NF; field_index += 1) {
        if (index($field_index, value_prefix) == 1 &&
          substr($field_index, length(value_prefix) + 1) ~ /^[0-9]+$/) {
          split($field_index, parts, "=")
          value = parts[2]
          matches += 1
        }
      }
    }
    END {
      if (matches != 1) exit 1
      print value
    }
  ' "$startup_log"
}

status="$(read_launch_value Status)"
launch_state="$(read_launch_value LaunchState)"
activity="$(read_launch_value Activity)"
total_time_ms="$(read_launch_value TotalTime)"
launch_started_uptime_ms="$(read_launch_value DeviceUptimeBeforeLaunchMs)"
if [[ "$status" != "ok" ]]; then
  echo "Package-replacement launch did not report Status: ok." >&2
  exit 1
fi
if [[ "$launch_state" != "COLD" ]]; then
  echo "Package-replacement launch was not cold: ${launch_state:-missing}" >&2
  exit 1
fi
if [[ "$activity" != \
  "dev.ipf.whitenoise.android.dev/dev.ipf.whitenoise.android.MainActivity" ]]; then
  echo "Package-replacement launch reported the wrong Activity: ${activity:-missing}" >&2
  exit 1
fi
if [[ ! "$total_time_ms" =~ ^[0-9]+$ ]]; then
  echo "Package-replacement launch is missing a numeric TotalTime." >&2
  exit 1
fi
if [[ ! "$launch_started_uptime_ms" =~ ^[0-9]+$ ]]; then
  echo "Package-replacement launch is missing device uptime at launch." >&2
  exit 1
fi

if ! splash_handoff_ms="$(read_phase_value system_splash_handoff elapsed_ms)"; then
  echo "Expected exactly one system-splash-handoff startup marker." >&2
  exit 1
fi
if ! ready_ms="$(read_phase_value first_local_frame elapsed_ms)"; then
  echo "Expected exactly one first-local-frame startup marker." >&2
  exit 1
fi
# Activity TotalTime includes launch work before Application/AppState exists,
# while WNPerf elapsed time begins with AppState. Use the conservative later
# value so pre-AppState work cannot be hidden from the splash bound.
if ((total_time_ms > splash_handoff_ms)); then
  first_compose_ui_ms="$total_time_ms"
else
  first_compose_ui_ms="$splash_handoff_ms"
fi
if ((first_compose_ui_ms >= 2000)); then
  echo "App-owned Compose UI missed the 2 second handoff bound: ${first_compose_ui_ms}ms" >&2
  exit 1
fi

model="$(read_device_value model)"
android_release="$(read_device_value android_release)"
api_level="$(read_device_value api_level)"
build_fingerprint="$(read_device_value build_fingerprint)"
serial="$(read_device_value serial)"
for required_value in "$model" "$android_release" "$api_level" "$build_fingerprint" "$serial"; do
  if [[ -z "$required_value" ]]; then
    echo "Package-replacement device evidence is incomplete." >&2
    exit 1
  fi
done
if [[ "$serial" == "default" || "$serial" == "unknown" ]]; then
  echo "Package-replacement evidence does not identify the physical device." >&2
  exit 1
fi
if [[ ! "$api_level" =~ ^[0-9]+$ ]]; then
  echo "Device API level is not numeric: $api_level" >&2
  exit 1
fi
if [[ ! "$apk_sha256" =~ ^[0-9A-Fa-f]{64}$ ]]; then
  echo "Release-like APK SHA-256 is malformed." >&2
  exit 1
fi
normalized_apk_sha256="$(printf '%s' "$apk_sha256" | tr '[:upper:]' '[:lower:]')"

mkdir -p "$(dirname "$report_json")"
jq -n \
  --arg capturedAtUtc "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg packageName "dev.ipf.whitenoise.android.dev" \
  --arg apkSha256 "$normalized_apk_sha256" \
  --arg serial "$serial" \
  --arg model "$model" \
  --arg androidRelease "$android_release" \
  --argjson apiLevel "$api_level" \
  --arg buildFingerprint "$build_fingerprint" \
  --argjson activityTotalTimeMs "$total_time_ms" \
  --argjson systemSplashHandoffMs "$splash_handoff_ms" \
  --argjson timeToFirstComposeUiMs "$first_compose_ui_ms" \
  --argjson timeToReadyMs "$ready_ms" \
  '{
    schemaVersion: 1,
    journey: "in-place-package-replacement-cold-start",
    capturedAtUtc: $capturedAtUtc,
    packageName: $packageName,
    releaseLikeApkSha256: $apkSha256,
    device: {
      serial: $serial,
      model: $model,
      androidRelease: $androidRelease,
      apiLevel: $apiLevel,
      buildFingerprint: $buildFingerprint
    },
    measurements: {
      activityTotalTimeMs: $activityTotalTimeMs,
      systemSplashHandoffMs: $systemSplashHandoffMs,
      timeToFirstComposeUiMs: $timeToFirstComposeUiMs,
      timeToReadyMs: $timeToReadyMs
    }
  }' >"$report_json"

jq -e '
  .schemaVersion == 1 and
  .journey == "in-place-package-replacement-cold-start" and
  .measurements.timeToFirstComposeUiMs < 2000 and
  .measurements.timeToReadyMs >= 0
' "$report_json" >/dev/null

echo "Package-replacement startup report: $report_json"
