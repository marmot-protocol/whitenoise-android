#!/usr/bin/env bash
set -euo pipefail

group_name="${1:-}"
target_package="dev.ipf.whitenoise.android.dev"
test_package="dev.ipf.whitenoise.android.benchmark"
runner="$test_package/androidx.test.runner.AndroidJUnitRunner"
allow_network_toggle="${ALLOW_NETWORK_TOGGLE:-false}"
network_recovery_benchmark_class="dev.ipf.whitenoise.android.benchmark.NetworkRecoveryBenchmark"

if [[ "${BENCHMARK_CLASS_FILTER:-}" == *"$network_recovery_benchmark_class"* &&
  "$allow_network_toggle" != true ]]; then
  echo "Network recovery changes connectivity. Re-run with ALLOW_NETWORK_TOGGLE=true to authorize it." >&2
  exit 1
fi

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required host command: $1" >&2
    exit 1
  fi
}

require_command jq
require_command rg

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo "Missing required host command: sha256sum or shasum" >&2
    return 1
  fi
}

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

benchmark_user="$(adb_cmd shell am get-current-user | tr -d '\r')"
[[ "$benchmark_user" =~ ^[0-9]+$ ]] || { echo "Cannot resolve the current Android user." >&2; exit 1; }
if [[ -n "${QUALIFICATION_USER_ID:-}" ]]; then
  [[ "$QUALIFICATION_USER_ID" =~ ^[1-9][0-9]*$ && "$benchmark_user" == "$QUALIFICATION_USER_ID" ]] || {
    echo "Switch to the explicitly authorized disposable profile before qualification." >&2
    exit 1
  }
fi

require_fixture_foreground() {
  if [[ -n "${QUALIFICATION_USER_ID:-}" &&
    "$(adb_cmd shell am get-current-user | tr -d '\r')" != "$QUALIFICATION_USER_ID" ]]; then
    echo "The authorized fixture is no longer foreground; refusing device UI control." >&2
    return 1
  fi
}

# Package code is shared across Android users. Refuse a changed binary instead
# of overwriting another task's in-place update or attributing mixed code to a run.
installed_code_hash() {
  local path copy hash
  path="$(adb_cmd shell pm path --user "$benchmark_user" "$target_package" | tr -d '\r')" || return 1
  [[ "$path" == package:* && "$path" != *$'\n'* ]] || return 1
  copy="$(mktemp)" || return 1
  if ! adb_cmd pull "${path#package:}" "$copy" >/dev/null 2>&1; then
    rm -f "$copy"; return 1
  fi
  hash="$(sha256_file "$copy")" || { rm -f "$copy"; return 1; }
  rm -f "$copy"
  printf '%s\n' "$hash"
}

require_owned_candidate() {
  local installed
  installed="$(installed_code_hash)" || {
    echo "Cannot verify shared Dev APK ownership." >&2; return 1;
  }
  [[ "$installed" == "$candidate_sha256" ]] || {
    echo "Shared Dev APK changed during qualification; refusing mixed-code evidence or restoration overwrite." >&2
    return 1
  }
}

wait_for_package_update_ui_to_settle() {
  local resumed_activity stable_samples=0
  for _ in {1..50}; do
    resumed_activity="$(
      adb_cmd shell dumpsys activity activities |
        awk '/topResumedActivity=|mResumedActivity:/{print; exit}' | tr -d '\r'
    )"
    if [[ -n "$resumed_activity" &&
      "$resumed_activity" != *".packageupdate.PackageUpdateActivity"* ]]; then
      stable_samples=$((stable_samples + 1))
      if ((stable_samples >= 5)); then return 0; fi
    else
      stable_samples=0
    fi
    sleep 0.1
  done

  echo "Android's package-update UI did not settle before the measured launch." >&2
  return 1
}

# adb joins arguments following `shell` into a command string interpreted by
# the device shell. Quote every dynamic value so spaces stay intact and shell
# metacharacters remain data rather than becoming commands.
quote_device_shell_arg() {
  local escaped
  escaped="$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
  printf "'%s'" "$escaped"
}

if ! adb_cmd shell pm path --user "$benchmark_user" "$target_package" >/dev/null 2>&1; then
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

if [[ -z "${BENCHMARK_APK_DIR:-}" ]]; then
  ./gradlew \
    :app:assembleDevZapstoreDebug \
    :app:assembleDevZapstoreBenchmarkRelease \
    :benchmark:assembleDevZapstoreBenchmarkRelease \
    -Pandroid.injected.build.abi="$device_abi" \
    --no-daemon
fi

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

if [[ -n "${BENCHMARK_APK_DIR:-}" ]]; then
  [[ -n "${QUALIFICATION_USER_ID:-}" && -f "${ORIGINAL_DEV_APK:-}" ]] || {
    echo "Prebuilt qualification requires a disposable profile and the preserved original Dev APK." >&2
    exit 1
  }
  [[ -x "${APKSIGNER:-}" && -x "${AAPT:-}" ]] || {
    echo "Prebuilt qualification requires APKSIGNER and AAPT for binary verification." >&2
    exit 1
  }
  installed_apk="$(mktemp)"
  installed_path="$(adb_cmd shell pm path --user "$benchmark_user" "$target_package" | tr -d '\r')"
  [[ "$installed_path" == package:* && "$installed_path" != *$'\n'* ]] || {
    rm -f "$installed_apk"; echo "Require one installed Dev base APK." >&2; exit 1;
  }
  if ! adb_cmd pull "${installed_path#package:}" "$installed_apk" >/dev/null ||
    ! python3 scripts/background_fixture_artifacts.py verify-install \
      "$BENCHMARK_APK_DIR" "$(git rev-parse HEAD)" \
      --original-dev-apk "$ORIGINAL_DEV_APK" --installed-dev-apk "$installed_apk" \
      --apksigner "$APKSIGNER" --aapt "$AAPT"; then
    rm -f "$installed_apk"; exit 1
  fi
  rm -f "$installed_apk"
  dev_app_apk="$ORIGINAL_DEV_APK"
  app_apk="$BENCHMARK_APK_DIR/app-devZapstoreBenchmarkRelease.apk"
  test_apk="$BENCHMARK_APK_DIR/benchmark-devZapstoreBenchmarkRelease.apk"
else
  dev_app_apk="$(resolve_apk app "$target_package" devZapstoreDebug)"
  app_apk="$(resolve_apk app "$target_package" devZapstoreBenchmarkRelease)"
  test_apk="$(resolve_apk benchmark "$test_package" devZapstoreBenchmarkRelease)"
fi

for apk in "$dev_app_apk" "$app_apk" "$test_apk"; do
  if [[ ! -f "$apk" ]]; then
    echo "Expected APK was not built: $apk" >&2
    exit 1
  fi
done

candidate_sha256="$(sha256_file "$app_apk")"
result_file="$(mktemp)"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
device_output="/storage/emulated/$benchmark_user/Android/media/$test_package/$run_id"
local_output="benchmark/build/outputs/manual/$run_id"
mkdir -p "$local_output"
device_output_pulled=false
heads_up_setting_captured=false
original_heads_up_notifications_enabled=""
target_replaced=false
airplane_mode_captured=false
original_airplane_mode=""
wifi_state_captured=false
original_wifi_enabled=""
delivery_state_captured=false
original_delivery_state=""
protected_users_changed=false
original_protected_users=""
listener_state_captured=false
original_listener_granted=""
fixture_listener="$test_package/dev.ipf.whitenoise.android.benchmark.BackgroundDeliveryReceiptListener"

capture_device_state() {
  local destination="$1"
  local battery serial thermal
  battery="$(adb_cmd shell dumpsys battery)"
  serial="$(adb_cmd get-serialno | tr -d '\r')"
  thermal="$(adb_cmd shell dumpsys thermalservice)"
  {
    printf 'captured_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'serial=%s\n' "$serial"
    printf 'model=%s\n' "$(adb_cmd shell getprop ro.product.model | tr -d '\r')"
    printf 'android_release=%s\n' "$(adb_cmd shell getprop ro.build.version.release | tr -d '\r')"
    printf 'api_level=%s\n' "$(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')"
    printf 'build_fingerprint=%s\n' "$(adb_cmd shell getprop ro.build.fingerprint | tr -d '\r')"
    printf '%s\n' "$battery" | awk '/USB powered:|level:|temperature:/{gsub(/^ +/, ""); print "battery_" $0}'
    printf '%s\n' "$thermal" | awk '/^Thermal Status:/{print "thermal_status=" $3}'
  } >"$destination"
}

restore_heads_up_notifications() {
  local attempt restored_value
  for attempt in 1 2 3; do
    if [[ -z "$original_heads_up_notifications_enabled" ||
      "$original_heads_up_notifications_enabled" == "null" ]]; then
      adb_cmd shell settings delete global heads_up_notifications_enabled >/dev/null 2>&1 || true
      restored_value="$(
        adb_cmd shell settings get global heads_up_notifications_enabled 2>/dev/null | tr -d '\r'
      )" || restored_value=""
      if [[ "$restored_value" == "null" ]]; then
        return 0
      fi
    else
      adb_cmd shell settings put global heads_up_notifications_enabled \
        "$original_heads_up_notifications_enabled" >/dev/null 2>&1 || true
      restored_value="$(
        adb_cmd shell settings get global heads_up_notifications_enabled 2>/dev/null | tr -d '\r'
      )" || restored_value=""
      if [[ "$restored_value" == "$original_heads_up_notifications_enabled" ]]; then
        return 0
      fi
    fi

    if ((attempt < 3)); then sleep 1; fi
  done

  echo "Failed to restore global heads_up_notifications_enabled to its original value." >&2
  echo "Reconnect the device and restore it manually before relying on notification behavior." >&2
  return 1
}

# Maps the query's past-tense status to the setter's imperative action.
airplane_mode_action_for_status() {
  case "$1" in
    enabled) printf '%s\n' enable ;;
    disabled) printf '%s\n' disable ;;
    *) return 1 ;;
  esac
}

restore_airplane_mode() {
  local attempt restore_action restored_value
  restore_action="$(airplane_mode_action_for_status "$original_airplane_mode")" || {
    echo "Cannot map airplane-mode state for restoration: $original_airplane_mode." >&2
    return 1
  }
  for attempt in 1 2 3; do
    adb_cmd shell cmd connectivity airplane-mode "$restore_action" >/dev/null 2>&1 || true
    restored_value="$(adb_cmd shell cmd connectivity airplane-mode 2>/dev/null | tr -d '\r')" || restored_value=""
    if [[ "$restored_value" == "$original_airplane_mode" ]]; then
      return 0
    fi
    if ((attempt < 3)); then sleep 1; fi
  done

  echo "Failed to restore airplane mode to its original state: $original_airplane_mode." >&2
  echo "Reconnect the device and restore that state manually before relying on network behavior." >&2
  return 1
}

# Read only the switch line; cmd wifi status also contains private network details.
current_wifi_enabled() {
  local status
  status="$(adb_cmd shell cmd wifi status | tr -d '\r' | awk 'NR == 1 {print}')" || return 1
  case "$status" in
    "Wifi is enabled") printf '%s\n' true ;;
    "Wifi is disabled") printf '%s\n' false ;;
    *) return 1 ;;
  esac
}

fixture_delivery_state() {
  local dump state
  dump="$(mktemp)" || return 1
  if ! adb_cmd shell dumpsys package "$target_package" >"$dump"; then rm -f "$dump"; return 1; fi
  state="$(python3 scripts/background_fixture_state.py "$dump" "$benchmark_user" "$@")" || {
    rm -f "$dump"; return 1;
  }
  rm -f "$dump"
  printf '%s\n' "$state"
}

# Other users retain their data but must not bootstrap with fixture configuration.
# Keep adb shell from consuming the profile list on its inherited standard input.
protect_other_users() {
  local user action
  original_protected_users="$(fixture_delivery_state --protected-users)" || return 1
  printf '%s\n' "$original_protected_users" >"$local_output/protected-user-overrides.json"
  protected_users_changed=true
  while IFS=$'\t' read -r user action; do
    [[ "$user" =~ ^[0-9]+$ && "$user" != "$benchmark_user" ]] || return 1
    case "$action" in
      default-state | enable)
        adb_cmd shell pm disable-user --user "$user" "$target_package" </dev/null >/dev/null || return 1
        ;;
      disable | disable-user | disable-until-used) ;;
      *) return 1 ;;
    esac
  done < <(jq -r '.[] | [.user, .action] | @tsv' <<<"$original_protected_users")
  local expected observed
  expected="$(jq -c 'map(if .action == "default-state" or .action == "enable" then .action = "disable-user" else . end)' \
    <<<"$original_protected_users")"
  observed="$(fixture_delivery_state --protected-users)" || return 1
  [[ "$(jq -c . <<<"$observed")" == "$expected" ]] || {
    echo "Could not protect every non-fixture Dev profile before replacement." >&2
    return 1
  }
}

restore_other_users() {
  local user action observed
  # Do not restart personal accounts with test configuration or competing code.
  [[ "$(installed_code_hash)" == "$(sha256_file "$dev_app_apk")" ]] || {
    echo "Dev profiles remain protected until the exact original APK is restored; original overrides are retained." >&2
    return 1
  }
  while IFS=$'\t' read -r user action; do
    [[ "$user" =~ ^[0-9]+$ && "$user" != "$benchmark_user" ]] || return 1
    case "$action" in default-state | enable | disable | disable-user | disable-until-used) ;; *) return 1 ;; esac
    adb_cmd shell pm "$action" --user "$user" "$target_package" </dev/null >/dev/null || return 1
  done < <(jq -r '.[] | [.user, .action] | @tsv' <<<"$original_protected_users")
  observed="$(fixture_delivery_state --protected-users)" || return 1
  [[ "$observed" == "$original_protected_users" ]] || {
    echo "The original non-fixture Dev profile overrides were not restored." >&2
    return 1
  }
}

# A host fence survives instrumentation death while Kotlin finally blocks cannot.
restore_fixture_delivery_state() {
  local permission_action receiver_action restored
  if [[ "$(jq -r '.permission_granted' <<<"$original_delivery_state")" == true ]]; then
    permission_action=grant
  else
    permission_action=revoke
  fi
  receiver_action="$(jq -r '.receiver_action' <<<"$original_delivery_state")"
  case "$receiver_action" in default-state | enable | disable) ;; *) return 1 ;; esac
  adb_cmd shell pm "$permission_action" --user "$benchmark_user" "$target_package" \
    android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  adb_cmd shell pm "$receiver_action" --user "$benchmark_user" \
    "$target_package/com.google.firebase.iid.FirebaseInstanceIdReceiver" >/dev/null 2>&1 || true
  restored="$(fixture_delivery_state)" || return 1
  [[ "$restored" == "$original_delivery_state" ]] || {
    echo "The fixture permission/FCM receiver overrides were not restored." >&2
    return 1
  }
}

fixture_listener_granted() {
  adb_cmd shell settings --user "$benchmark_user" get secure enabled_notification_listeners |
    python3 scripts/background_fixture_state.py --listener-grant
}

restore_fixture_listener() {
  local action restored
  if [[ "$original_listener_granted" == true ]]; then action=allow_listener; else action=disallow_listener; fi
  adb_cmd shell cmd notification "$action" "$fixture_listener" "$benchmark_user" >/dev/null 2>&1 || true
  restored="$(fixture_listener_granted)" || return 1
  [[ "$restored" == "$original_listener_granted" ]] || {
    echo "The fixture receipt listener's original access was not restored." >&2
    return 1
  }
}

restore_wifi_state() {
  local action restored attempt
  if [[ "$original_wifi_enabled" == true ]]; then action=enabled; else action=disabled; fi
  adb_cmd shell cmd wifi set-wifi-enabled "$action" >/dev/null 2>&1 || true
  for attempt in {1..50}; do
    restored="$(current_wifi_enabled)" || restored=""
    if [[ "$restored" == "$original_wifi_enabled" ]]; then return 0; fi
    sleep 0.1
  done
  echo "Failed to restore the original Wi-Fi switch; reconnect and restore it before relying on network evidence." >&2
  return 1
}

cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$airplane_mode_captured" == true ]] && ! restore_airplane_mode; then
    if ((status == 0)); then status=1; fi
  fi
  if [[ "$wifi_state_captured" == true ]] && ! restore_wifi_state; then
    if ((status == 0)); then status=1; fi
  fi
  if [[ "$delivery_state_captured" == true ]] && ! restore_fixture_delivery_state; then
    if ((status == 0)); then status=1; fi
  fi
  if [[ "$listener_state_captured" == true ]] && ! restore_fixture_listener; then
    if ((status == 0)); then status=1; fi
  fi
  if [[ "$heads_up_setting_captured" == true ]] && ! restore_heads_up_notifications; then
    if ((status == 0)); then status=1; fi
  fi
  if [[ "$device_output_pulled" == true ]]; then
    adb_cmd shell rm -rf "$device_output" || true
  fi
  if [[ "$target_replaced" == true ]]; then
    if ! require_owned_candidate; then
      echo "Original Dev APK remains preserved; restoration requires resolving the concurrent install." >&2
      if ((status == 0)); then status=1; fi
    elif ! adb_cmd install --user "$benchmark_user" -r -d -t "$dev_app_apk"; then
      echo "Failed to restore the normal dev app: $dev_app_apk" >&2
      if ((status == 0)); then status=1; fi
    elif [[ "$(installed_code_hash)" != "$(sha256_file "$dev_app_apk")" ]]; then
      echo "The restored Dev APK does not match the preserved original." >&2
      if ((status == 0)); then status=1; fi
    fi
  fi
  if [[ "$protected_users_changed" == true ]] && ! restore_other_users; then
    if ((status == 0)); then status=1; fi
  fi
  rm -f "$result_file"
  exit "$status"
}
trap cleanup EXIT

if [[ -n "${QUALIFICATION_USER_ID:-}" ]]; then
  original_delivery_state="$(fixture_delivery_state)" || {
    echo "Cannot capture exact fixture delivery overrides before qualification." >&2
    exit 1
  }
  delivery_state_captured=true
  original_listener_granted="$(fixture_listener_granted)" || {
    echo "Cannot capture the fixture receipt listener's prior access." >&2
    exit 1
  }
  listener_state_captured=true
fi

if [[ "$allow_network_toggle" == true ]]; then
  original_airplane_mode="$(adb_cmd shell cmd connectivity airplane-mode | tr -d '\r')"
  case "$original_airplane_mode" in
    enabled | disabled) ;;
    *)
      echo "Could not capture the original airplane-mode state: $original_airplane_mode" >&2
      exit 1
      ;;
  esac
  airplane_mode_captured=true
  original_wifi_enabled="$(current_wifi_enabled)" || {
    echo "Cannot capture the original Wi-Fi switch; refusing connectivity changes." >&2
    exit 1
  }
  wifi_state_captured=true
fi

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
# preserves its authenticated data. Capture this exact package-replacement
# launch before Macrobenchmark resets compilation, then let the normal startup
# benchmarks measure their controlled iterations. The exit trap restores the
# normal dev debug APK even when either journey fails.
capture_device_state "$local_output/package-replacement-device.txt"
package_replacement_install_log="$local_output/package-replacement-install.log"
require_fixture_foreground
if [[ -n "${BENCHMARK_APK_DIR:-}" && "$(installed_code_hash)" != "$(sha256_file "$dev_app_apk")" ]]; then
  echo "Shared Dev APK changed before replacement; preserving the competing install." >&2
  exit 1
fi
if [[ -n "${BENCHMARK_APK_DIR:-}" ]]; then
  protect_other_users
  if [[ "$(installed_code_hash)" != "$(sha256_file "$dev_app_apk")" ]]; then
    echo "Shared Dev APK changed while protecting profiles; refusing the fixture replacement." >&2
    exit 1
  fi
fi
if adb_cmd install --user "$benchmark_user" -r -d -t "$app_apk" >"$package_replacement_install_log" 2>&1; then
  target_replaced=true
  cat "$package_replacement_install_log"
else
  cat "$package_replacement_install_log" >&2
  exit 1
fi
adb_cmd install --user "$benchmark_user" -r -d -t "$test_apk"

# Isolate this run from stale device output. Supplying the directory explicitly
# also makes every pulled report and trace attributable to this invocation.
adb_cmd shell rm -rf "$device_output"
adb_cmd shell mkdir -p "$device_output"

# Exercise the first launch after the in-place APK swap before Macrobenchmark
# starts resetting compilation. This catches a broken fixture early and keeps
# one-time package initialization out of the first measured iteration. A
# package-replaced/background receiver may recreate the process without opening
# an Activity, so force-stop immediately before the explicit launch to make the
# journey cold without clearing authenticated data.
main_activity="$target_package/dev.ipf.whitenoise.android.MainActivity"
require_fixture_foreground
adb_cmd shell am force-stop --user "$benchmark_user" "$target_package"
wait_for_package_update_ui_to_settle
# The transient package-update Activity may have forwarded the launch intent as
# it closed. Force-stop once more after it is gone so the measured process is
# unambiguously created by the following command.
adb_cmd shell am force-stop --user "$benchmark_user" "$target_package"
launch_started_uptime_ms="$(
  adb_cmd shell cat /proc/uptime | awk '{printf "%.0f\n", $1 * 1000}' | tr -d '\r'
)"
if [[ ! "$launch_started_uptime_ms" =~ ^[0-9]+$ ]]; then
  echo "Could not capture device uptime before the package-replacement launch." >&2
  exit 1
fi
preflight_output="$(adb_cmd shell am start --user "$benchmark_user" -W -n "$main_activity")"
{
  printf 'DeviceUptimeBeforeLaunchMs: %s\n' "$launch_started_uptime_ms"
  printf '%s\n' "$preflight_output"
} >"$local_output/package-replacement-launch.txt"
if ! rg -q '^Status: ok\r?$' <<<"$preflight_output"; then
  echo "Benchmark target preflight launch failed:" >&2
  echo "$preflight_output" >&2
  exit 1
fi

preflight_pid=""
for _ in {1..20}; do
  preflight_pid="$(adb_cmd shell ps -A -o UID,PID,NAME | tr -d '\r' |
    awk -v profile="$benchmark_user" -v package="$target_package" \
      '$3 == package && int($1 / 100000) == profile {print $2}')"
  if [[ "$preflight_pid" =~ ^[0-9]+$ ]]; then break; fi
  sleep 0.1
done
if [[ ! "$preflight_pid" =~ ^[0-9]+$ ]]; then
  echo "Could not resolve the package-replacement app process." >&2
  exit 1
fi

startup_log="$local_output/package-replacement-startup.log"
startup_markers_ready=false
for _ in {1..120}; do
  adb_cmd logcat -d --pid="$preflight_pid" -v brief WNPerf:I '*:S' >"$startup_log"
  if rg -q 'op=app_start phase=system_splash_handoff elapsed_ms=[0-9]+' "$startup_log" &&
    rg -q 'op=app_start phase=first_local_frame elapsed_ms=[0-9]+' "$startup_log"; then
    startup_markers_ready=true
    break
  fi
  sleep 0.25
done
if [[ "$startup_markers_ready" != true ]]; then
  if [[ "${BENCHMARK_CLASS_FILTER:-}" == *"$network_recovery_benchmark_class"* &&
    "${BENCHMARK_CLASS_FILTER:-}" != *"StartupBenchmark"* ]]; then
    echo "Startup milestones unavailable; continuing the recovery-only run with the UI fixture preflight." >&2
  elif [[ "${REQUIRE_STARTUP_MILESTONES:-true}" == "false" &&
    "${BENCHMARK_CLASS_FILTER:-}" != *"StartupBenchmark"* ]]; then
    # A journey that never measures startup (paging, scrolling) still needs the authenticated
    # chat list below, but not the cold-start report; the caller opted out of that gate.
    echo "Startup milestones unavailable; REQUIRE_STARTUP_MILESTONES=false, continuing with the UI fixture preflight." >&2
  else
    echo "Package-replacement launch did not emit both startup milestones." >&2
    echo "Captured log: $startup_log" >&2
    exit 1
  fi
else
  bash scripts/package-replacement-startup-report.sh \
    "$local_output/package-replacement-launch.txt" \
    "$startup_log" \
    "$local_output/package-replacement-device.txt" \
    "$(sha256_file "$app_apk")" \
    "$local_output/package-replacement-startup.json"
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
adb_cmd shell am force-stop --user "$benchmark_user" "$target_package"

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

instrument_command="am instrument --user $benchmark_user -w -r \
-e class $(quote_device_shell_arg "$benchmark_classes") \
-e androidx.benchmark.output.enable true \
-e additionalTestOutputDir $(quote_device_shell_arg "$device_output")"
if [[ -n "$group_name" ]]; then
  instrument_command="$instrument_command \
-e groupName $(quote_device_shell_arg "$group_name")"
fi
if [[ -n "${PAGING_DEEP_FLINGS:-}" ]]; then
  instrument_command="$instrument_command \
-e pagingDeepFlings $(quote_device_shell_arg "$PAGING_DEEP_FLINGS")"
fi
if [[ -n "${IDLE_WINDOW_MS:-}" ]]; then
  instrument_command="$instrument_command \
-e idleWindowMs $(quote_device_shell_arg "$IDLE_WINDOW_MS")"
fi
if [[ -n "${QUALIFICATION_USER_ID:-}" ]]; then
  instrument_command="$instrument_command \
-e qualificationUserId $(quote_device_shell_arg "$QUALIFICATION_USER_ID")"
fi
if [[ "${ALLOW_RECEIPT_LISTENER:-false}" == true ]]; then
  instrument_command="$instrument_command -e allowReceiptListener true"
fi
if [[ -n "${CREATED_GROUP_PREFIX:-}" ]]; then
  instrument_command="$instrument_command \
-e createdGroupPrefix $(quote_device_shell_arg "$CREATED_GROUP_PREFIX")"
fi
if [[ -n "${INVITE_NAME:-}" ]]; then
  instrument_command="$instrument_command \
-e inviteName $(quote_device_shell_arg "$INVITE_NAME")"
fi
if [[ -n "${NOTIFICATION_TEXTS:-}" ]]; then
  instrument_command="$instrument_command \
-e notificationTexts $(quote_device_shell_arg "$NOTIFICATION_TEXTS")"
fi
if [[ -n "${NOTIFICATION_CONVERSATION_TITLES:-}" ]]; then
  instrument_command="$instrument_command \
-e notificationConversationTitles $(quote_device_shell_arg "$NOTIFICATION_CONVERSATION_TITLES")"
fi
if [[ -n "${NOTIFICATION_SOURCE_ACCOUNT_REF:-}" ]]; then
  instrument_command="$instrument_command \
-e notificationSourceAccountRef $(quote_device_shell_arg "$NOTIFICATION_SOURCE_ACCOUNT_REF")"
fi
if [[ "$allow_network_toggle" == true ]]; then
  instrument_command="$instrument_command \
-e allowNetworkToggle true \
-e originalAirplaneMode $(quote_device_shell_arg "$original_airplane_mode") \
-e originalWifiEnabled $(quote_device_shell_arg "$original_wifi_enabled")"
fi
instrument_command="$instrument_command \
$(quote_device_shell_arg "$runner")"

require_owned_candidate
require_fixture_foreground
capture_device_state "$local_output/device-before.txt"
instrumentation_status=0
adb_cmd shell "$instrument_command" | tee "$result_file" || instrumentation_status=$?
cp "$result_file" "$local_output/instrumentation.log"
capture_device_state "$local_output/device-after.txt"
require_owned_candidate

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
