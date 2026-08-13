#!/usr/bin/env bash

set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

fake_bin="$fixture_dir/bin"
report_dir="$fixture_dir/report"
fake_apk="$fixture_dir/staging.apk"
mkdir -p "$fake_bin" "$report_dir"
touch "$fake_apk"

cat > "$fake_bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${FAKE_ADB_MODE:-verifier}:$*" in
  "verifier:wait-for-device")
    exit 0
    ;;
  "verifier:shell getprop ro.kernel.qemu")
    printf '1\n'
    ;;
  "verifier:shell getprop debug.sf.luma_sampling")
    # The old verifier accepted this property without worker completion.
    printf '0\n'
    ;;
  "worker:start-server")
    exit 0
    ;;
  "worker:devices")
    printf 'List of devices attached\nemulator-9999\tdevice\n'
    ;;
  "worker:-s emulator-9999 wait-for-device")
    exit 0
    ;;
  "worker:-s emulator-9999 root")
    root_attempt="$(cat "$FAKE_ADB_ROOT_STATE" 2> /dev/null || printf '0')"
    printf '%s\n' "$((root_attempt + 1))" > "$FAKE_ADB_ROOT_STATE"
    [[ "$root_attempt" != "0" ]]
    ;;
  "worker:-s emulator-9999 shell id -u")
    if [[ "$(cat "$FAKE_ADB_ROOT_STATE")" -ge 2 ]]; then
      printf '0\n'
    else
      printf '2000\n'
    fi
    ;;
  "worker:-s emulator-9999 shell setprop debug.sf.luma_sampling 0")
    exit 0
    ;;
  "worker:-s emulator-9999 shell killall surfaceflinger")
    exit 0
    ;;
  "worker:-s emulator-9999 shell pidof surfaceflinger")
    pid_read_count="$(cat "$FAKE_ADB_STATE" 2> /dev/null || printf '0')"
    if [[ "$pid_read_count" == "0" ]]; then
      printf '100\n'
    else
      printf '200\n'
    fi
    printf '%s\n' "$((pid_read_count + 1))" > "$FAKE_ADB_STATE"
    ;;
  "worker:-s emulator-9999 shell getprop debug.sf.luma_sampling")
    printf '0\n'
    ;;
  *)
    printf 'unexpected fake adb command: %s\n' "$*" >&2
    exit 99
    ;;
esac
EOF
chmod +x "$fake_bin/adb"

run_token="regression-test-run"
printf '%s\n' "$run_token" > "$report_dir/android17-emulator-stabilize.request"

if output="$(
  CI=true \
    REQUIRE_ANDROID17_EMULATOR_STABILIZED=true \
    RELEASE_VERIFY_REPORT_DIR="$report_dir" \
    ANDROID17_STABILIZATION_WAIT_ATTEMPTS=1 \
    ANDROID17_STABILIZATION_POLL_SECONDS=0 \
    PATH="$fake_bin:$PATH" \
    "$repo_dir/scripts/verify-release-runtime.sh" "$fake_apk" dev.ipf.whitenoise.android.staging 2>&1
)"; then
  echo "error: verifier accepted luma_sampling=0 without a success marker" >&2
  exit 1
fi

if [[ "$output" != *"Android 17 emulator stabilization did not complete"* ]]; then
  printf 'error: verifier reported the wrong missing-marker failure:\n%s\n' "$output" >&2
  exit 1
fi

printf 'success stale-run\n' > "$report_dir/android17-emulator-stabilize.status"
if output="$(
  CI=true \
    REQUIRE_ANDROID17_EMULATOR_STABILIZED=true \
    RELEASE_VERIFY_REPORT_DIR="$report_dir" \
    ANDROID17_STABILIZATION_WAIT_ATTEMPTS=1 \
    ANDROID17_STABILIZATION_POLL_SECONDS=0 \
    PATH="$fake_bin:$PATH" \
    "$repo_dir/scripts/verify-release-runtime.sh" "$fake_apk" dev.ipf.whitenoise.android.staging 2>&1
)"; then
  echo "error: verifier accepted a stale success marker from another run" >&2
  exit 1
fi

if [[ "$output" != *"Android 17 emulator stabilization did not complete"* ]]; then
  printf 'error: verifier reported the wrong stale-marker failure:\n%s\n' "$output" >&2
  exit 1
fi

printf 'failure %s\n' "$run_token" > "$report_dir/android17-emulator-stabilize.status"
if output="$(
  CI=true \
    REQUIRE_ANDROID17_EMULATOR_STABILIZED=true \
    RELEASE_VERIFY_REPORT_DIR="$report_dir" \
    ANDROID17_STABILIZATION_WAIT_ATTEMPTS=10 \
    ANDROID17_STABILIZATION_POLL_SECONDS=0 \
    PATH="$fake_bin:$PATH" \
    "$repo_dir/scripts/verify-release-runtime.sh" "$fake_apk" dev.ipf.whitenoise.android.staging 2>&1
)"; then
  echo "error: verifier accepted an explicit worker failure" >&2
  exit 1
fi

if [[ "$output" != *"Android 17 emulator stabilization worker failed"* ]]; then
  printf 'error: verifier did not surface the worker failure:\n%s\n' "$output" >&2
  exit 1
fi

worker_report_dir="$fixture_dir/worker-report"
worker_run_token="worker-regression-test"
worker_state="$fixture_dir/worker-adb-state"
worker_root_state="$fixture_dir/worker-adb-root-state"
mkdir -p "$worker_report_dir"
printf '%s\n' "$worker_run_token" > "$worker_report_dir/android17-emulator-stabilize.request"
FAKE_ADB_MODE=worker \
  FAKE_ADB_STATE="$worker_state" \
  FAKE_ADB_ROOT_STATE="$worker_root_state" \
  PATH="$fake_bin:$PATH" \
  "$repo_dir/scripts/stabilize-android17-emulator.sh" \
  --worker "$worker_report_dir" "$worker_run_token" > /dev/null

if [[ "$(cat "$worker_report_dir/android17-emulator-stabilize.status")" != "success $worker_run_token" ]]; then
  echo "error: worker did not publish success after observing the replacement SurfaceFlinger PID" >&2
  exit 1
fi

printf 'test-android17-emulator-stabilization.sh passed\n'
