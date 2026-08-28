#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
reporter="$repo_root/scripts/package-replacement-startup-report.sh"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

write_valid_fixtures() {
  cat >"$fixture_dir/launch.txt" <<'EOF'
DeviceUptimeBeforeLaunchMs: 900000
Starting: Intent { cmp=dev.ipf.whitenoise.android.dev/dev.ipf.whitenoise.android.MainActivity }
Status: ok
LaunchState: COLD
Activity: dev.ipf.whitenoise.android.dev/dev.ipf.whitenoise.android.MainActivity
TotalTime: 1634
WaitTime: 1640
Complete
EOF
  cat >"$fixture_dir/startup.log" <<'EOF'
I/WNPerf(1234): schema=1 session=p#1 op=app_start phase=ffi_return elapsed_ms=221 duration_ms=200 result=success layer=ffi
I/WNPerf(1234): schema=1 session=p#1 op=app_start phase=system_splash_handoff elapsed_ms=1498 result=success layer=android
I/WNPerf(1234): schema=1 session=p#1 op=app_start phase=first_local_frame elapsed_ms=2288 result=success layer=android
EOF
  cat >"$fixture_dir/device.txt" <<'EOF'
captured_at_utc=2026-08-14T03:00:00Z
serial=fixture-serial
model=Pixel 9 Pro XL
android_release=17
api_level=37
build_fingerprint=google/komodo/fixture
EOF
}

write_valid_fixtures
bash "$reporter" \
  "$fixture_dir/launch.txt" \
  "$fixture_dir/startup.log" \
  "$fixture_dir/device.txt" \
  0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  "$fixture_dir/report.json"

jq -e '
  .device.model == "Pixel 9 Pro XL" and
  .measurements.activityTotalTimeMs == 1634 and
  .measurements.systemSplashHandoffMs == 1498 and
  .measurements.timeToFirstComposeUiMs == 1634 and
  .measurements.timeToReadyMs == 2288
' "$fixture_dir/report.json" >/dev/null

cp "$fixture_dir/startup.log" "$fixture_dir/missing-marker.log"
sed -i.bak '/phase=first_local_frame/d' "$fixture_dir/missing-marker.log"
if bash "$reporter" \
  "$fixture_dir/launch.txt" \
  "$fixture_dir/missing-marker.log" \
  "$fixture_dir/device.txt" \
  0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  "$fixture_dir/missing.json" >/dev/null 2>&1; then
  echo "Reporter accepted evidence without a Ready marker." >&2
  exit 1
fi

sed 's/phase=first_local_frame/phase=first_local_frame_extra/' \
  "$fixture_dir/startup.log" >"$fixture_dir/lookalike-marker.log"
if bash "$reporter" \
  "$fixture_dir/launch.txt" \
  "$fixture_dir/lookalike-marker.log" \
  "$fixture_dir/device.txt" \
  0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  "$fixture_dir/lookalike.json" >/dev/null 2>&1; then
  echo "Reporter accepted a look-alike Ready marker." >&2
  exit 1
fi

cp "$fixture_dir/startup.log" "$fixture_dir/duplicate-marker.log"
sed -n '/phase=first_local_frame/p' "$fixture_dir/startup.log" >>"$fixture_dir/duplicate-marker.log"
if bash "$reporter" \
  "$fixture_dir/launch.txt" \
  "$fixture_dir/duplicate-marker.log" \
  "$fixture_dir/device.txt" \
  0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  "$fixture_dir/duplicate.json" >/dev/null 2>&1; then
  echo "Reporter accepted duplicate Ready markers." >&2
  exit 1
fi

sed 's/LaunchState: COLD/LaunchState: WARM/' "$fixture_dir/launch.txt" >"$fixture_dir/warm-launch.txt"
if bash "$reporter" \
  "$fixture_dir/warm-launch.txt" \
  "$fixture_dir/startup.log" \
  "$fixture_dir/device.txt" \
  0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  "$fixture_dir/warm.json" >/dev/null 2>&1; then
  echo "Reporter accepted a non-cold package-replacement launch." >&2
  exit 1
fi

sed 's/phase=system_splash_handoff elapsed_ms=1498/phase=system_splash_handoff elapsed_ms=2000/' \
  "$fixture_dir/startup.log" >"$fixture_dir/late-handoff.log"
if bash "$reporter" \
  "$fixture_dir/launch.txt" \
  "$fixture_dir/late-handoff.log" \
  "$fixture_dir/device.txt" \
  0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  "$fixture_dir/late.json" >/dev/null 2>&1; then
  echo "Reporter accepted a Compose handoff outside the 2 second bound." >&2
  exit 1
fi

sed 's/TotalTime: 1634/TotalTime: 2000/' "$fixture_dir/launch.txt" >"$fixture_dir/late-activity-launch.txt"
if bash "$reporter" \
  "$fixture_dir/late-activity-launch.txt" \
  "$fixture_dir/startup.log" \
  "$fixture_dir/device.txt" \
  0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  "$fixture_dir/late-activity.json" >/dev/null 2>&1; then
  echo "Reporter hid launch work before the startup trace began." >&2
  exit 1
fi

sed 's#Activity: dev.ipf.whitenoise.android.dev/dev.ipf.whitenoise.android.MainActivity#Activity: dev.ipf.whitenoise.android.dev/.OtherActivity#' \
  "$fixture_dir/launch.txt" >"$fixture_dir/wrong-activity-launch.txt"
if bash "$reporter" \
  "$fixture_dir/wrong-activity-launch.txt" \
  "$fixture_dir/startup.log" \
  "$fixture_dir/device.txt" \
  0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  "$fixture_dir/wrong-activity.json" >/dev/null 2>&1; then
  echo "Reporter accepted evidence from the wrong Activity." >&2
  exit 1
fi

sed 's/op=app_start phase=system_splash_handoff/op=chat_open phase=system_splash_handoff/' \
  "$fixture_dir/startup.log" >"$fixture_dir/wrong-operation.log"
if bash "$reporter" \
  "$fixture_dir/launch.txt" \
  "$fixture_dir/wrong-operation.log" \
  "$fixture_dir/device.txt" \
  0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  "$fixture_dir/wrong-operation.json" >/dev/null 2>&1; then
  echo "Reporter accepted a startup marker from another operation." >&2
  exit 1
fi

echo "Package-replacement startup reporter tests passed."
