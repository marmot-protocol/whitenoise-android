#!/usr/bin/env bash
set -euo pipefail

group_name="${1:?usage: scripts/run-performance-benchmarks.sh <group-name>}"
target_package="dev.ipf.whitenoise.android.dev"
test_package="dev.ipf.whitenoise.android.benchmark"
runner="$test_package/androidx.test.runner.AndroidJUnitRunner"

if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  adb_bin="$ANDROID_HOME/platform-tools/adb"
else
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
  :app:assembleDevZapstoreBenchmarkRelease \
  :benchmark:assembleDevZapstoreBenchmarkRelease \
  -Pandroid.injected.build.abi="$device_abi" \
  --no-daemon

app_apk="app/build/intermediates/apk/devZapstore/benchmarkRelease/app-dev-zapstore-$device_abi-benchmarkRelease.apk"
test_apk="benchmark/build/intermediates/apk/devZapstore/benchmarkRelease/benchmark-dev-zapstore-benchmarkRelease.apk"

for apk in "$app_apk" "$test_apk"; do
  if [[ ! -f "$apk" ]]; then
    echo "Expected APK was not built: $apk" >&2
    exit 1
  fi
done

result_file="$(mktemp)"
cleanup() {
  adb_cmd uninstall "$test_package" >/dev/null 2>&1 || true
  rm -f "$result_file"
}
trap cleanup EXIT

# Replacing the target APK with the same application ID and debug certificate
# preserves its authenticated data. Only the self-instrumenting test package is
# uninstalled, so stale trace output cannot contaminate this run.
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
