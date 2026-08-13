#!/usr/bin/env bash

# Runtime-verifies a minified APK on the CI emulator. ART verification is the
# protection that a successful R8/D8 build cannot provide: malformed release
# bytecode can compile and package successfully, then fail only when a class is
# loaded on a device.

set -euo pipefail

if [[ "${CI:-}" != "true" ]]; then
  echo "error: release runtime verification may install only on an ephemeral CI emulator" >&2
  exit 1
fi

if [[ $# -ne 2 ]]; then
  echo "usage: scripts/verify-release-runtime.sh <apk> <application-id>" >&2
  exit 1
fi

apk_path="$1"
application_id="$2"
report_dir="${RELEASE_VERIFY_REPORT_DIR:-app/build/reports/release-runtime-verify}"
log_path="$report_dir/logcat.txt"
install_log_path="$report_dir/install.txt"
launcher_log_path="$report_dir/launcher-resolve.txt"

if [[ ! -f "$apk_path" ]]; then
  echo "error: APK not found: $apk_path" >&2
  exit 1
fi
if [[ -z "$application_id" ]]; then
  echo "error: application id must not be empty" >&2
  exit 1
fi

mkdir -p "$report_dir"
adb wait-for-device
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "error: refusing to install the release verifier APK on a physical device" >&2
  exit 1
fi

wait_for_android17_emulator_stabilization() {
  local request_path="$report_dir/android17-emulator-stabilize.request"
  local status_path="$report_dir/android17-emulator-stabilize.status"
  local max_attempts="${ANDROID17_STABILIZATION_WAIT_ATTEMPTS:-120}"
  local poll_seconds="${ANDROID17_STABILIZATION_POLL_SECONDS:-1}"
  local attempt expected_token marker_state marker_token marker_line luma_sampling

  if [[ ! "$max_attempts" =~ ^[1-9][0-9]*$ ]]; then
    echo "error: invalid Android 17 stabilization wait attempts: $max_attempts" >&2
    return 1
  fi

  expected_token="$(cat "$request_path" 2> /dev/null || true)"
  if [[ -z "$expected_token" ]]; then
    echo "error: Android 17 emulator stabilization request marker is missing" >&2
    return 1
  fi

  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    marker_line="$(cat "$status_path" 2> /dev/null || true)"
    marker_state=""
    marker_token=""
    read -r marker_state marker_token _ <<< "$marker_line"
    if [[ "$marker_token" == "$expected_token" ]]; then
      case "$marker_state" in
        success)
          luma_sampling="$(adb shell getprop debug.sf.luma_sampling | tr -d '\r')"
          if [[ "$luma_sampling" == "0" ]]; then
            printf 'Android 17 emulator stabilization confirmed (run %s).\n' "$expected_token"
            return 0
          fi
          echo "error: Android 17 emulator stabilization marker exists but luma sampling is enabled" >&2
          return 1
          ;;
        failure)
          cat "$report_dir/android17-emulator-stabilize.txt" >&2 2> /dev/null || true
          echo "error: Android 17 emulator stabilization worker failed" >&2
          return 1
          ;;
      esac
    fi
    sleep "$poll_seconds"
  done

  cat "$report_dir/android17-emulator-stabilize.txt" >&2 2> /dev/null || true
  echo "error: Android 17 emulator stabilization did not complete" >&2
  return 1
}

if [[ "${REQUIRE_ANDROID17_EMULATOR_STABILIZED:-false}" == "true" ]] &&
  ! wait_for_android17_emulator_stabilization; then
  exit 1
fi

# Android 17 can publish sys.boot_completed before Package Manager and the
# system Settings provider are ready to accept an APK. A transient package
# service failure can also restart system_server, so repeat this readiness gate
# before retrying an install.
wait_for_android_services() {
  local max_attempts="$1"
  local readiness_attempt stable_observations=0
  for ((readiness_attempt = 1; readiness_attempt <= max_attempts; readiness_attempt++)); do
    if [[ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" == "1" ]] &&
      adb shell cmd package list packages android > /dev/null 2>&1 &&
      adb shell settings get global device_provisioned > /dev/null 2>&1; then
      ((stable_observations += 1))
      # The API 37 image can briefly publish working package/settings binders,
      # then restart system_server. Require several observations across a
      # settling window before allowing a streamed APK install.
      if ((stable_observations >= 5)); then
        return 0
      fi
    else
      stable_observations=0
    fi
    sleep 2
  done
  return 1
}

if ! wait_for_android_services 60; then
  echo "error: Android package and settings services did not become ready" >&2
  exit 1
fi

: > "$install_log_path"
installed=false
for attempt in {1..10}; do
  set +e
  install_output="$(adb install -r "$apk_path" 2>&1)"
  install_status=$?
  set -e
  printf 'Attempt %d:\n%s\n' "$attempt" "$install_output" | tee -a "$install_log_path"
  if [[ $install_status -eq 0 ]]; then
    installed=true
    break
  fi
  if [[ "$install_output" != *"Broken pipe"* &&
        "$install_output" != *"Failure calling service package"* &&
        "$install_output" != *"Can't find service: package"* &&
        "$install_output" != *"before system providers are installed"* &&
        "$install_output" != *"PackageManagerInternal.freeStorage"* &&
        "$install_output" != *"device offline"* &&
        "$install_output" != *"error: closed"* ]]; then
    echo "error: APK installation failed with a non-transient error" >&2
    exit "$install_status"
  fi
  adb wait-for-device
  if ! wait_for_android_services 30; then
    echo "error: Android services did not recover after a transient install failure" >&2
    exit 1
  fi
done
if [[ "$installed" != true ]]; then
  echo "error: APK installation did not recover from transient startup failures" >&2
  exit 1
fi

compile_output="$(adb shell cmd package compile -f -m verify "$application_id")"
printf '%s\n' "$compile_output" | tee "$report_dir/package-compile.txt"
if [[ "$compile_output" != *Success* ]]; then
  echo "error: ART rejected the minified APK during package verification" >&2
  exit 1
fi

adb logcat -c
: > "$launcher_log_path"
wait_for_launcher() {
  local max_attempts="$1"
  local resolve_attempt resolve_status launcher_output
  launcher_component=""
  for ((resolve_attempt = 1; resolve_attempt <= max_attempts; resolve_attempt++)); do
    set +e
    launcher_output="$(
      adb shell cmd package resolve-activity \
        --brief \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER \
        "$application_id" 2>&1
    )"
    resolve_status=$?
    set -e
    printf 'Attempt %d:\n%s\n' "$resolve_attempt" "$launcher_output" | tee -a "$launcher_log_path"
    launcher_component="$(printf '%s\n' "$launcher_output" | tr -d '\r' | tail -n 1)"
    if [[ $resolve_status -eq 0 && "$launcher_component" == "$application_id/"* ]]; then
      return 0
    fi
    sleep 2
  done
  return 1
}

if ! wait_for_launcher 30; then
  echo "error: could not resolve launcher activity for $application_id" >&2
  exit 1
fi
adb shell am force-stop "$application_id"

launch_log_path="$report_dir/launch.txt"
: > "$launch_log_path"
launched=false
for launch_attempt in {1..10}; do
  set +e
  launch_output="$(adb shell am start -W -n "$launcher_component" 2>&1)"
  launch_status=$?
  set -e
  printf 'Attempt %d:\n%s\n' "$launch_attempt" "$launch_output" | tee -a "$launch_log_path"
  if [[ $launch_status -eq 0 ]]; then
    launched=true
    break
  fi
  if [[ "$launch_output" != *"Broken pipe"* &&
        "$launch_output" != *"Failure calling service activity"* &&
        "$launch_output" != *"Can't find service: activity"* &&
        "$launch_output" != *"device offline"* &&
        "$launch_output" != *"error: closed"* ]]; then
    adb logcat -d -v threadtime > "$log_path"
    echo "error: release app launcher activity failed with a non-transient error" >&2
    exit "$launch_status"
  fi
  adb wait-for-device
  if ! wait_for_android_services 30 || ! wait_for_launcher 30; then
    adb logcat -d -v threadtime > "$log_path"
    echo "error: Android services did not recover after a transient launch failure" >&2
    exit 1
  fi
done
if [[ "$launched" != true ]]; then
  adb logcat -d -v threadtime > "$log_path"
  echo "error: release app launch did not recover from transient startup failures" >&2
  exit 1
fi

app_pid=""
for _ in {1..20}; do
  # pidof exits non-zero while the launched process is still starting. Do not
  # let errexit bypass this bounded readiness loop on API 37.
  app_pid="$(adb shell pidof "$application_id" 2> /dev/null | tr -d '\r' || true)"
  [[ -n "$app_pid" ]] && break
  sleep 1
done

if [[ -z "$app_pid" ]]; then
  adb logcat -d -v threadtime > "$log_path"
  echo "error: release app did not remain alive after launch" >&2
  exit 1
fi

sleep 5
stable_pid="$(adb shell pidof "$application_id" 2> /dev/null | tr -d '\r' || true)"
if [[ "$stable_pid" != "$app_pid" ]]; then
  adb logcat -d -v threadtime > "$log_path"
  echo "error: release app process did not remain stable after launch" >&2
  exit 1
fi

adb logcat -d -v threadtime --pid="$app_pid" > "$log_path"

if grep -En "VerifyError|Verifier rejected class|FATAL EXCEPTION" "$log_path"; then
  echo "error: release runtime verification found an app fatal runtime or class-verifier failure" >&2
  exit 1
fi

printf 'Release runtime verification passed for %s (pid %s).\n' "$application_id" "$app_pid"
