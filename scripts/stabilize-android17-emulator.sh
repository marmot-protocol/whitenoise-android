#!/usr/bin/env bash

# Emulator 37.1.11's API 37 image can abort SurfaceFlinger's RegionSampling
# thread while reading a DMA-backed color buffer. Start a detached worker before
# the emulator launches, disable luma sampling as soon as ADB is available, and
# restart SurfaceFlinger once so it reads the property during initialization.

set -euo pipefail

atomic_write_status() {
  local path="$1"
  local value="$2"
  local temporary_path="${path}.tmp.$$"
  printf '%s\n' "$value" > "$temporary_path"
  mv -f "$temporary_path" "$path"
}

stabilize_emulator() {
  trap 'echo "error: emulator stabilization failed at line $LINENO" >&2' ERR
  adb start-server

  local attempt emulator_serial=""
  for attempt in {1..180}; do
    emulator_serial="$({ adb devices 2> /dev/null || true; } | awk '$1 ~ /^emulator-/ { print $1; exit }')"
    [[ -n "$emulator_serial" ]] && break
    sleep 1
  done
  if [[ -z "$emulator_serial" ]]; then
    echo "error: API 37 emulator did not appear in ADB" >&2
    return 1
  fi

  local adb_uid=""
  for attempt in {1..30}; do
    adb -s "$emulator_serial" wait-for-device
    # The first root request can close the transport while adbd restarts.
    adb -s "$emulator_serial" root || true
    adb -s "$emulator_serial" wait-for-device
    adb_uid="$({ adb -s "$emulator_serial" shell id -u 2> /dev/null || true; } | tr -d '\r')"
    [[ "$adb_uid" == "0" ]] && break
    sleep 1
  done
  if [[ "$adb_uid" != "0" ]]; then
    echo "error: API 37 emulator ADB did not become root" >&2
    return 1
  fi

  adb -s "$emulator_serial" shell setprop debug.sf.luma_sampling 0

  local old_surfaceflinger_pid=""
  for attempt in {1..60}; do
    old_surfaceflinger_pid="$({ adb -s "$emulator_serial" shell pidof surfaceflinger 2> /dev/null || true; } | tr -d '\r')"
    [[ -n "$old_surfaceflinger_pid" ]] && break
    sleep 1
  done
  if [[ -z "$old_surfaceflinger_pid" ]]; then
    echo "error: SurfaceFlinger did not become available for stabilization" >&2
    return 1
  fi

  # The shell transport may close while init restarts SurfaceFlinger.
  adb -s "$emulator_serial" shell killall surfaceflinger || true

  local new_surfaceflinger_pid=""
  for attempt in {1..60}; do
    new_surfaceflinger_pid="$({ adb -s "$emulator_serial" shell pidof surfaceflinger 2> /dev/null || true; } | tr -d '\r')"
    if [[ -n "$new_surfaceflinger_pid" && "$new_surfaceflinger_pid" != "$old_surfaceflinger_pid" ]]; then
      break
    fi
    sleep 1
  done
  if [[ -z "$new_surfaceflinger_pid" || "$new_surfaceflinger_pid" == "$old_surfaceflinger_pid" ]]; then
    echo "error: SurfaceFlinger did not restart with luma sampling disabled" >&2
    return 1
  fi

  local luma_sampling
  luma_sampling="$(adb -s "$emulator_serial" shell getprop debug.sf.luma_sampling | tr -d '\r')"
  if [[ "$luma_sampling" != "0" ]]; then
    echo "error: SurfaceFlinger luma sampling was not disabled" >&2
    return 1
  fi

  printf 'Android 17 emulator stabilized: serial=%s surfaceflinger=%s luma_sampling=%s\n' \
    "$emulator_serial" \
    "$new_surfaceflinger_pid" \
    "$luma_sampling"
}

if [[ "${1:-}" == "--worker" ]]; then
  if [[ $# -ne 3 ]]; then
    echo "usage: $0 --worker <report-dir> <run-token>" >&2
    exit 64
  fi
  report_dir="$2"
  run_token="$3"
  request_path="$report_dir/android17-emulator-stabilize.request"
  status_path="$report_dir/android17-emulator-stabilize.status"

  publish_failure() {
    local exit_status=$?
    trap - EXIT
    if ((exit_status != 0)) &&
      [[ "$(cat "$request_path" 2> /dev/null || true)" == "$run_token" ]]; then
      atomic_write_status "$status_path" "failure $run_token" || true
    fi
    exit "$exit_status"
  }
  trap publish_failure EXIT

  stabilize_emulator
  if [[ "$(cat "$request_path" 2> /dev/null || true)" != "$run_token" ]]; then
    echo "error: emulator stabilization run was superseded" >&2
    exit 1
  fi
  atomic_write_status "$status_path" "success $run_token"
  trap - EXIT
  exit
fi

report_dir="${RELEASE_VERIFY_REPORT_DIR:-app/build/reports/release-runtime-verify}"
mkdir -p "$report_dir"
request_path="$report_dir/android17-emulator-stabilize.request"
status_path="$report_dir/android17-emulator-stabilize.status"
run_token="${GITHUB_RUN_ID:-local}.${GITHUB_RUN_ATTEMPT:-0}.$$.$RANDOM"
atomic_write_status "$request_path" "$run_token"
rm -f "$status_path"
script_path="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
nohup "$script_path" --worker "$report_dir" "$run_token" \
  > "$report_dir/android17-emulator-stabilize.txt" 2>&1 < /dev/null &
printf 'Started Android 17 emulator stabilization worker (pid %s, run %s).\n' "$!" "$run_token"
