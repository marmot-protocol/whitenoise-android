#!/usr/bin/env bash
# Run bounded :fuzz campaigns via standalone Jazzer (jobs=2, workers=2 per target).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TARGETS=(
  fuzzZapstoreProtocol
  fuzzIdentityReference
  fuzzNip55SignerProtocol
)

METADATA_FILE="fuzz/build/fuzz-engine-metadata.properties"
LOG_DIR="fuzz/build/fuzz-campaign-logs"

FUZZ_RUNS="${FUZZ_RUNS:-}"
FUZZ_MAX_DURATION="${FUZZ_MAX_DURATION:-3m}"
FUZZ_MAX_HEAP="${FUZZ_MAX_HEAP:-2g}"
FUZZ_JOBS_APPLIED="${FUZZ_JOBS_APPLIED:--jobs=2}"
FUZZ_WORKERS_APPLIED="${FUZZ_WORKERS_APPLIED:--workers=2}"
GRADLE_FLAGS=(--no-daemon)

usage() {
  cat <<'EOF'
Usage:
  scripts/fuzz-run-campaign.sh [--self-check]
  scripts/fuzz-run-campaign.sh [--target <gradleTaskName>]...

Environment:
  FUZZ_RUNS            Fixed executions per target (-runs)
  FUZZ_MAX_DURATION    Duration budget per target when FUZZ_RUNS is unset (-max_total_time)
  FUZZ_MAX_HEAP        JVM heap cap (default 2g)
  FUZZ_JOBS_APPLIED    libFuzzer -jobs value (default -jobs=2)
  FUZZ_WORKERS_APPLIED libFuzzer -workers value (default -workers=2)

Each target runs one standalone Jazzer JavaExec with jobs/workers applied by libFuzzer.
Phase-1 targets run sequentially. Full engine output is written to fuzz/build/fuzz-campaign-logs/.
EOF
}

log() {
  printf '[fuzz-run] %s\n' "$1"
}

gradle_props() {
  local props=(
    "-PfuzzMaxHeap=$FUZZ_MAX_HEAP"
  )
  if [[ -n "$FUZZ_RUNS" ]]; then
    props+=("-PfuzzRuns=$FUZZ_RUNS")
  else
    props+=("-PfuzzMaxDuration=$FUZZ_MAX_DURATION")
  fi
  printf '%s\n' "${props[@]}"
}

write_metadata() {
  local jazzer_version
  jazzer_version="$(
    ./gradlew :fuzz:dependencies --configuration testRuntimeClasspath --quiet 2>/dev/null \
      | grep 'jazzer-junit:' \
      | head -1 \
      | sed -E 's/.*jazzer-junit:([^ ]*).*/\1/' \
      || echo 'unknown'
  )"
  mkdir -p "$(dirname "$METADATA_FILE")"
  cat >"$METADATA_FILE" <<EOF
engine=jazzer-junit:$jazzer_version
max_len_bytes=65536
max_collection_elements=64
max_depth=16
max_frames=32
max_heap=$FUZZ_MAX_HEAP
jobs_requested=$FUZZ_JOBS_APPLIED
workers_requested=$FUZZ_WORKERS_APPLIED
jobs_applied=$FUZZ_JOBS_APPLIED
workers_applied=$FUZZ_WORKERS_APPLIED
max_duration_per_target=$FUZZ_MAX_DURATION
fuzz_runs_per_target=${FUZZ_RUNS:-}
entry_points=$(IFS=,; echo "${TARGETS[*]}")
EOF
}

verify_engine_parallelism() {
  local log_file="$1"
  if ! grep -Eq '>fuzz-0\.log' "$log_file" || ! grep -Eq '>fuzz-1\.log' "$log_file"; then
    log "libfuzzer_jobs_missing log=$log_file"
    return 1
  fi
  if ! grep -Eq 'Job 0 exited' "$log_file" || ! grep -Eq 'Job 1 exited' "$log_file"; then
    log "libfuzzer_job_completion_missing log=$log_file"
    return 1
  fi
  if ! grep -Eq '#[0-9]+' "$log_file"; then
    log "engine_output_missing log=$log_file"
    return 1
  fi
  log "libfuzzer_jobs_verified log=$log_file"
  return 0
}

run_target() {
  local target="$1"
  local log_file="$LOG_DIR/${target}.log"
  mkdir -p "$LOG_DIR"
  date +%s >"$LOG_DIR/${target}.start"
  set +e
  # shellcheck disable=SC2046
  ./gradlew "${GRADLE_FLAGS[@]}" ":fuzz:${target}" $(gradle_props) >"$log_file" 2>&1
  local status=$?
  set -e
  date +%s >"$LOG_DIR/${target}.end"
  if [[ "$status" -ne 0 ]]; then
    log "target_failed name=$target status=$status log=$log_file"
    return "$status"
  fi
  if ! verify_engine_parallelism "$log_file"; then
    return 1
  fi
  log "target_passed name=$target log=$log_file"
  return 0
}

verify_targets_sequential() {
  local previous_end=0
  local target
  for target in "$@"; do
    local start
    start="$(cat "$LOG_DIR/${target}.start")"
    if [[ "$previous_end" -ne 0 && "$start" -lt "$previous_end" ]]; then
      log "target_overlap target=$target start=$start previous_end=$previous_end"
      return 1
    fi
    local end
    end="$(cat "$LOG_DIR/${target}.end")"
    previous_end="$end"
  done
  log "target_sequence_verified"
  return 0
}

self_check() {
  local saved_runs="$FUZZ_RUNS"
  FUZZ_RUNS=100
  rm -rf "$LOG_DIR"
  mkdir -p "$LOG_DIR"
  log "self_check=compile"
  ./gradlew "${GRADLE_FLAGS[@]}" :fuzz:compileKotlin :fuzz:compileTestKotlin
  local target="${TARGETS[0]}"
  log "self_check=standalone_jazzer target=$target runs=$FUZZ_RUNS"
  run_target "$target"
  FUZZ_RUNS="$saved_runs"
  log "self_check=passed"
}

main() {
  local selected_targets=()
  if [[ "${1:-}" == "--self-check" ]]; then
    self_check
    exit 0
  fi
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --target)
        shift
        selected_targets+=("${1:?--target requires a task name}")
        ;;
      -h | --help)
        usage
        exit 0
        ;;
      *)
        selected_targets+=("$1")
        ;;
    esac
    shift
  done
  if [[ "${#selected_targets[@]}" -eq 0 ]]; then
    selected_targets=("${TARGETS[@]}")
  fi

  log "compile_once"
  ./gradlew "${GRADLE_FLAGS[@]}" :fuzz:compileKotlin :fuzz:compileTestKotlin

  rm -rf "$LOG_DIR"
  mkdir -p "$LOG_DIR"

  local target
  local failed=0
  for target in "${selected_targets[@]}"; do
    log "target_start name=$target"
    if ! run_target "$target"; then
      failed=1
    fi
    log "target_done name=$target"
  done

  if [[ "${#selected_targets[@]}" -gt 1 ]]; then
    verify_targets_sequential "${selected_targets[@]}" || failed=1
  fi

  write_metadata
  if [[ "$failed" -ne 0 ]]; then
    log "campaign=failed"
    exit 1
  fi
  log "campaign=passed targets=${selected_targets[*]}"
}

main "$@"
