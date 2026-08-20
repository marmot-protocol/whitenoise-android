#!/usr/bin/env bash
# Privacy-safe triage for :fuzz Jazzer crashes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TARGET="${1:-}"
REPRO="${2:-}"
SUBTARGET="${3:-}"

SELF_CHECK_TARGET=":fuzz:fuzzTriageSelfCheck"
SELF_CHECK_ENTRY_METHOD="fuzzTriageSelfCheck"
SELF_CHECK_TEST_CLASS="dev.ipf.whitenoise.android.fuzz.FuzzTriageSelfCheck"
SELF_CHECK_MAGIC=$'\xfe\xed\xf0\x0d'
TRIAGE_METADATA_DIR="fuzz/build/fuzz-triage-metadata"

TRIAGE_STAGE="started"
TRIAGE_STATUS="unknown"
PRIVACY_STATUS="unknown"
REPRO_DIGEST=""
MINIMIZED_DIGEST=""
REPLAY_EXCEPTION_CLASS=""

usage() {
  cat <<'EOF'
Usage: scripts/fuzz-triage.sh <gradle-task> [reproducer-file] [subtarget-name]
       scripts/fuzz-triage.sh --self-check
       scripts/fuzz-triage.sh --self-check-passing-replay

Supported tasks:
  :fuzz:fuzzZapstoreProtocol
  :fuzz:fuzzIdentityReference
  :fuzz:fuzzNip55SignerProtocol
  :fuzz:fuzzTriageSelfCheck

Example:
  scripts/fuzz-triage.sh :fuzz:fuzzZapstoreProtocol fuzz/build/repro-review/crash-001

When a reproducer is supplied, the final byte is the subtarget selector. Pass an
optional subtarget name to validate the mapping; omit it to derive the name from
the artifact bytes.

Steps performed:
  1. Validate task/subtarget mapping
  2. Minimize the supplied crash artifact byte-for-byte
  3. Fresh JVM replay via :fuzz:replayFuzzRegression
  4. Privacy review, classification, and promotion guidance

Manual follow-up required before retention:
  - Add a deterministic app unit test covering the finding
  - Copy a reviewed input into fuzz/regression-corpus/<target>/ with trailing subtarget byte
  - Re-run ./gradlew :fuzz:replayAllFuzzRegression
EOF
}

log() {
  printf '[fuzz-triage] %s\n' "$1"
}

digest() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

read_subtarget_id() {
  local size
  size="$(wc -c <"$1" | tr -d ' ')"
  if [[ "$size" -eq 0 ]]; then
    return 1
  fi
  od -An -tu1 -j $((size - 1)) -N1 "$1" | tr -d ' \n'
}

privacy_check() {
  local file="$1"
  local patterns='nsec[0-9]|password|Bearer |api[_-]?key|@[a-z0-9.-]+\.(chat|app)|wss?://'
  if grep -Eiq "$patterns" "$file"; then
    log "PRIVACY_BLOCK: reproducer matches sensitive pattern; store digest only"
    log "digest=$(digest "$file")"
    PRIVACY_STATUS="blocked"
    return 1
  fi
  if LC_ALL=C grep -Pq '[^\x09\x0A\x0D\x20-\x7E]' "$file"; then
    log "NOTE: reproducer contains non-ASCII bytes; manual review required"
  fi
  log "privacy_check=passed digest=$(digest "$file")"
  PRIVACY_STATUS="passed"
  return 0
}

classify() {
  local exception_class="${1:-unknown}"
  log "classification=MANUAL_REVIEW default_private=true exception_class=$exception_class"
  log "classification_guidance=use private vulnerability reporting until a maintainer rules out security impact"
}

write_triage_metadata() {
  mkdir -p "$TRIAGE_METADATA_DIR"
  local metadata_key="${MINIMIZED_DIGEST:-$REPRO_DIGEST}"
  local metadata_target="${GRADLE_TASK:-unknown}"
  local elapsed_seconds=0
  if [[ -z "$metadata_key" ]]; then
    metadata_key="unknown"
  fi
  if [[ -n "${START_SECONDS:-}" ]]; then
    elapsed_seconds=$((SECONDS - START_SECONDS))
  fi
  local metadata_file="$TRIAGE_METADATA_DIR/${metadata_target}-${metadata_key}.properties"
  cat >"$metadata_file" <<EOF
target=$TARGET
gradle_task=${GRADLE_TASK:-unknown}
subtarget=${SUBTARGET:-}
input_digest=${REPRO_DIGEST:-unknown}
minimized_digest=${MINIMIZED_DIGEST:-}
exception_class=${REPLAY_EXCEPTION_CLASS:-unknown}
triage_stage=${TRIAGE_STAGE:-unknown}
triage_status=${TRIAGE_STATUS:-unknown}
privacy_check=${PRIVACY_STATUS:-unknown}
classification=MANUAL_REVIEW
engine=jazzer-junit:${ENGINE_VERSION:-unknown}
elapsed_seconds=$elapsed_seconds
EOF
  log "triage_metadata=$metadata_file"
}

subtarget_name_for_id() {
  local task="$1"
  local subtarget_id="$2"
  case "$task" in
    :fuzz:fuzzZapstoreProtocol)
      subtarget_id=$((subtarget_id % 3))
      case "$subtarget_id" in
        0) printf '%s' "NostrEventJson" ;;
        1) printf '%s' "RelayEnvelopeFrames" ;;
        2) printf '%s' "RelayEnvelopeSequence" ;;
        *) return 1 ;;
      esac
      ;;
    :fuzz:fuzzIdentityReference)
      subtarget_id=$((subtarget_id % 4))
      case "$subtarget_id" in
        0) printf '%s' "ProfileLink" ;;
        1) printf '%s' "RecipientNormalize" ;;
        2) printf '%s' "RecipientTokenize" ;;
        3) printf '%s' "PlausibleClipboard" ;;
        *) return 1 ;;
      esac
      ;;
    :fuzz:fuzzNip55SignerProtocol)
      subtarget_id=$((subtarget_id % 3))
      case "$subtarget_id" in
        0) printf '%s' "ParseContentRow" ;;
        1) printf '%s' "ParseActivityResult" ;;
        2) printf '%s' "SignedEventPubkeyHelpers" ;;
        *) return 1 ;;
      esac
      ;;
    *)
      return 1
      ;;
  esac
}

lookup_mapping() {
  local task="$1"
  local subtarget_name="$2"
  case "$task" in
    :fuzz:fuzzZapstoreProtocol)
      TEST_CLASS="dev.ipf.whitenoise.android.fuzz.ZapstoreProtocolFuzzTest"
      ENTRY_METHOD="fuzzZapstoreProtocol"
      GRADLE_TASK="fuzzZapstoreProtocol"
      ;;
    :fuzz:fuzzIdentityReference)
      TEST_CLASS="dev.ipf.whitenoise.android.fuzz.IdentityReferenceFuzzTest"
      ENTRY_METHOD="fuzzIdentityReference"
      GRADLE_TASK="fuzzIdentityReference"
      ;;
    :fuzz:fuzzNip55SignerProtocol)
      TEST_CLASS="dev.ipf.whitenoise.android.fuzz.Nip55SignerProtocolFuzzTest"
      ENTRY_METHOD="fuzzNip55SignerProtocol"
      GRADLE_TASK="fuzzNip55SignerProtocol"
      ;;
    :fuzz:fuzzTriageSelfCheck)
      TEST_CLASS="$SELF_CHECK_TEST_CLASS"
      ENTRY_METHOD="$SELF_CHECK_ENTRY_METHOD"
      GRADLE_TASK="fuzzTriageSelfCheck"
      SUBTARGET=""
      SUBTARGET_ID=""
      return 0
      ;;
    *)
      return 1
      ;;
  esac

  case "$subtarget_name" in
    NostrEventJson) SUBTARGET_ID=0 ;;
    RelayEnvelopeFrames) SUBTARGET_ID=1 ;;
    RelayEnvelopeSequence) SUBTARGET_ID=2 ;;
    ProfileLink) SUBTARGET_ID=0 ;;
    RecipientNormalize) SUBTARGET_ID=1 ;;
    RecipientTokenize) SUBTARGET_ID=2 ;;
    PlausibleClipboard) SUBTARGET_ID=3 ;;
    ParseContentRow) SUBTARGET_ID=0 ;;
    ParseActivityResult) SUBTARGET_ID=1 ;;
    SignedEventPubkeyHelpers) SUBTARGET_ID=2 ;;
    *) return 1 ;;
  esac
  return 0
}

resolve_subtarget() {
  local task="$1"
  local reproducer="$2"
  local requested_name="${3:-}"
  if [[ "$task" == "$SELF_CHECK_TARGET" ]]; then
    lookup_mapping "$task" ""
    return 0
  fi
  local derived_id
  derived_id="$(read_subtarget_id "$reproducer")"
  local derived_name
  if ! derived_name="$(subtarget_name_for_id "$task" "$derived_id")"; then
    log "ERROR: unknown subtarget id $derived_id in reproducer"
    return 1
  fi
  if [[ -n "$requested_name" && "$requested_name" != "$derived_name" ]]; then
    log "ERROR: subtarget mismatch requested=$requested_name derived=$derived_name id=$derived_id"
    return 1
  fi
  SUBTARGET="$derived_name"
  SUBTARGET_ID="$derived_id"
  lookup_mapping "$task" "$SUBTARGET"
}

minimize_reproducer() {
  local gradle_task="$1"
  local crash_file="$2"
  local output_dir="$3"
  TRIAGE_STAGE="minimize"
  rm -rf "$output_dir"
  mkdir -p "$output_dir"
  set +e
  MINIMIZE_OUTPUT="$(
    ./gradlew :fuzz:fuzzMinimizeCrash \
      -PfuzzMinimizeTask="$gradle_task" \
      -PfuzzMinimizeInput="$crash_file" \
      -PfuzzMinimizeOutputDir="$output_dir" \
      --no-daemon 2>&1
  )"
  local status=$?
  set -e
  if [[ $status -ne 0 ]]; then
    log "minimize=failed status=$status"
    TRIAGE_STATUS="minimize_failed"
    return "$status"
  fi
  MINIMIZED_FILE="$output_dir/minimized-crash"
  if [[ ! -f "$MINIMIZED_FILE" ]]; then
    log "minimize=failed reason=missing_output"
    TRIAGE_STATUS="minimize_failed"
    return 1
  fi
  MINIMIZED_DIGEST="$(digest "$MINIMIZED_FILE")"
  log "minimize=passed digest=$MINIMIZED_DIGEST"
  TRIAGE_STATUS="minimized"
  return 0
}

extract_exception_class() {
  local output="$1"
  local exception
  exception="$(printf '%s\n' "$output" | grep -Eo 'Caused by: [a-zA-Z0-9_.$]+' | head -1 | sed 's/Caused by: //' || true)"
  if [[ -z "$exception" ]]; then
    exception="$(printf '%s\n' "$output" | grep -Eo '== Java Exception: [a-zA-Z0-9_.$]+' | head -1 | sed 's/.*: //' || true)"
  fi
  if [[ -z "$exception" ]]; then
    exception="$(printf '%s\n' "$output" | grep -Eo 'java\.lang\.[A-Za-z0-9_.$]+' | head -1 || true)"
  fi
  if [[ -z "$exception" ]]; then
    exception="manual-review"
  fi
  printf '%s' "$exception"
}

replay_reproducer() {
  local test_class="$1"
  local entry_method="$2"
  local reproducer="$3"
  TRIAGE_STAGE="replay"
  local replay_root="fuzz/build/repro-replay"
  local replay_inputs="$replay_root/dev/ipf/whitenoise/android/fuzz/${test_class##*.}Inputs/${entry_method}"
  local repro_digest
  repro_digest="$(digest "$reproducer")"
  rm -rf "$replay_root"
  mkdir -p "$replay_inputs"
  cp "$reproducer" "$replay_inputs/repro-${repro_digest}"
  set +e
  REPLAY_OUTPUT="$(
    ./gradlew :fuzz:replayFuzzRegression \
      --tests "${test_class}.${entry_method}" \
      -PfuzzReplayInputsDir="$replay_root" \
      --no-daemon 2>&1
  )"
  local status=$?
  set -e
  REPLAY_STATUS=$status
  REPLAY_EXCEPTION_CLASS="$(extract_exception_class "$REPLAY_OUTPUT")"
}

assert_passing_replay() {
  local test_class="$1"
  local entry_method="$2"
  local passing_input="$3"
  replay_reproducer "$test_class" "$entry_method" "$passing_input"
  if [[ $REPLAY_STATUS -ne 0 ]]; then
    log "self_check_passing_replay=failed class=$test_class status=$REPLAY_STATUS exception_class=$REPLAY_EXCEPTION_CLASS"
    return 1
  fi
  log "self_check_passing_replay=passed class=$test_class"
}

self_check_passing_replay() {
  assert_passing_replay \
    "dev.ipf.whitenoise.android.fuzz.IdentityReferenceFuzzTest" \
    "fuzzIdentityReference" \
    "fuzz/src/test/resources/dev/ipf/whitenoise/android/fuzz/IdentityReferenceFuzzTestInputs/fuzzIdentityReference/profile_link_marmot.input"
  assert_passing_replay \
    "dev.ipf.whitenoise.android.fuzz.Nip55SignerProtocolFuzzTest" \
    "fuzzNip55SignerProtocol" \
    "fuzz/src/test/resources/dev/ipf/whitenoise/android/fuzz/Nip55SignerProtocolFuzzTestInputs/fuzzNip55SignerProtocol/parse_activity_result.input"
}

self_check() {
  local mapped_name
  if ! mapped_name="$(subtarget_name_for_id :fuzz:fuzzZapstoreProtocol 253)" ||
    [[ "$mapped_name" != "RelayEnvelopeFrames" ]]; then
    log "self_check=failed step=subtarget_mapping expected=RelayEnvelopeFrames actual=${mapped_name:-unknown}"
    exit 1
  fi

  local work_dir="fuzz/build/triage-self-check"
  local crash_file="$work_dir/crash.input"
  local minimize_dir="$work_dir/minimized"
  rm -rf "$work_dir" "$TRIAGE_METADATA_DIR"
  mkdir -p "$work_dir"
  printf '%s' "$SELF_CHECK_MAGIC" >"$crash_file"

  if ! minimize_reproducer "fuzzTriageSelfCheck" "$crash_file" "$minimize_dir"; then
    log "self_check=failed step=minimize"
    exit 1
  fi

  replay_reproducer "$SELF_CHECK_TEST_CLASS" "$SELF_CHECK_ENTRY_METHOD" "$MINIMIZED_FILE"
  if [[ $REPLAY_STATUS -eq 0 ]]; then
    log "self_check=failed step=replay expected_crash=true actual_status=0"
    exit 1
  fi
  if [[ "$REPLAY_EXCEPTION_CLASS" != *"IllegalStateException"* ]]; then
    log "self_check=failed step=replay expected_exception=IllegalStateException actual_exception=$REPLAY_EXCEPTION_CLASS"
    exit 1
  fi
  log "self_check=passed minimize_digest=$MINIMIZED_DIGEST exception_class=$REPLAY_EXCEPTION_CLASS"
}

if [[ "${1:-}" == "--self-check" ]]; then
  self_check
  exit 0
fi

if [[ "${1:-}" == "--self-check-passing-replay" ]]; then
  self_check_passing_replay
  exit 0
fi

if [[ -z "$TARGET" ]]; then
  usage
  exit 1
fi

ENGINE_VERSION="$(./gradlew :fuzz:dependencies --configuration testRuntimeClasspath --quiet 2>/dev/null | grep 'jazzer-junit:' | head -1 | sed -E 's/.*jazzer-junit:([^ ]*).*/\1/' || echo 'unknown')"
log "engine=jazzer-junit:$ENGINE_VERSION target=$TARGET"

START_SECONDS=$SECONDS
EXIT_CODE=0

if [[ -n "$REPRO" ]]; then
  if [[ ! -f "$REPRO" ]]; then
    log "ERROR: reproducer not found: $REPRO"
    exit 1
  fi
  if ! resolve_subtarget "$TARGET" "$REPRO" "$SUBTARGET"; then
    log "ERROR: unknown task/subtarget combination: $TARGET ${SUBTARGET:-$REPRO}"
    usage
    exit 1
  fi
  REPRO_DIGEST="$(digest "$REPRO")"
  if [[ -n "${SUBTARGET:-}" ]]; then
    log "subtarget=$SUBTARGET id=$SUBTARGET_ID digest=$REPRO_DIGEST"
  else
    log "subtarget=none digest=$REPRO_DIGEST"
  fi

  REPRO_REVIEW_DIR="fuzz/build/repro-review/${ENTRY_METHOD}"
  mkdir -p "$REPRO_REVIEW_DIR"
  REPRO_REVIEW_FILE="$REPRO_REVIEW_DIR/${REPRO_DIGEST}"
  cp "$REPRO" "$REPRO_REVIEW_FILE"
  log "repro_review_file=$REPRO_REVIEW_FILE"

  MINIMIZE_DIR="fuzz/build/repro-review/${ENTRY_METHOD}-minimized-${REPRO_DIGEST}"
  if ! minimize_reproducer "$GRADLE_TASK" "$REPRO_REVIEW_FILE" "$MINIMIZE_DIR"; then
    write_triage_metadata
    exit 1
  fi

  replay_reproducer "$TEST_CLASS" "$ENTRY_METHOD" "$MINIMIZED_FILE"
  if ! privacy_check "$MINIMIZED_FILE"; then
    TRIAGE_STATUS="privacy_blocked"
    write_triage_metadata
    exit 2
  fi

  if [[ $REPLAY_STATUS -ne 0 ]]; then
    classify "$REPLAY_EXCEPTION_CLASS"
    log "replay=failed status=$REPLAY_STATUS exception_class=$REPLAY_EXCEPTION_CLASS"
    log "promotion=blocked copy reviewed input to fuzz/regression-corpus/${GRADLE_TASK}/ after app unit test"
    TRIAGE_STATUS="replay_failed"
    write_triage_metadata
    exit "$REPLAY_STATUS"
  fi
  log "replay=passed"
  log "promotion=manual copy reviewed input to fuzz/regression-corpus/${GRADLE_TASK}/ after app unit test"
  TRIAGE_STATUS="passed"
  TRIAGE_STAGE="complete"
  write_triage_metadata
else
  log "reproduce: JAZZER_FUZZ=1 ./gradlew $TARGET -PfuzzMaxDuration=3m -PfuzzMaxHeap=2g --no-daemon"
  log "triage: scripts/fuzz-triage.sh $TARGET <reproducer-file> [SubtargetName]"
fi

ELAPSED=$((SECONDS - START_SECONDS))
log "elapsed=${ELAPSED}s"
exit "$EXIT_CODE"
