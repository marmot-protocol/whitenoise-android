#!/usr/bin/env bash
# Privacy-safe triage for :fuzz Jazzer crashes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TARGET="${1:-}"
REPRO="${2:-}"
SUBTARGET="${3:-}"

usage() {
  cat <<'EOF'
Usage: scripts/fuzz-triage.sh <gradle-task> [reproducer-file] [subtarget-name]
       scripts/fuzz-triage.sh --self-check

Supported tasks:
  :fuzz:fuzzZapstoreProtocol
  :fuzz:fuzzIdentityReference
  :fuzz:fuzzNip55SignerProtocol

Example:
  scripts/fuzz-triage.sh :fuzz:fuzzZapstoreProtocol fuzz/build/repro-review/crash-001 NostrEventJson

Steps performed:
  1. Validate task/subtarget mapping
  2. Privacy scan on reproducer bytes (digest only; no payload logging)
  3. Fresh JVM replay via :fuzz:replayFuzzRegression (when reproducer supplied)
  4. Classification guidance (public regression vs private security report)

Manual follow-up required before retention:
  - Minimize with JAZZER_FUZZ=1 and libFuzzer minimization
  - Add a deterministic app unit test covering the finding
  - Copy a reviewed input into fuzz/regression-corpus/<target>/ with subtarget prefix byte
  - Re-run ./gradlew :fuzz:replayFuzzRegression
EOF
}

write_subtarget_prefix() {
  local subtarget_id="$1"
  local byte
  byte="$(printf '%02x' "$subtarget_id")"
  printf '%b' "\\x$byte"
}

self_check() {
  local tmp
  tmp="$(mktemp)"
  write_subtarget_prefix 1 >"$tmp"
  local first_byte
  first_byte="$(od -An -tx1 -N1 "$tmp" | tr -d ' \n')"
  rm -f "$tmp"
  if [[ "$first_byte" != "01" ]]; then
    printf '[fuzz-triage] self_check=failed expected_prefix=01 actual_prefix=%s\n' "$first_byte"
    exit 1
  fi
  printf '[fuzz-triage] self_check=passed\n'
}

if [[ "${1:-}" == "--self-check" ]]; then
  self_check
  exit 0
fi

if [[ -z "$TARGET" ]]; then
  usage
  exit 1
fi

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

privacy_check() {
  local file="$1"
  local patterns='nsec[0-9]|password|Bearer |api[_-]?key|@[a-z0-9.-]+\.(chat|app)|wss?://'
  if grep -Eiq "$patterns" "$file"; then
    log "PRIVACY_BLOCK: reproducer matches sensitive pattern; store digest only"
    log "digest=$(digest "$file")"
    return 1
  fi
  if LC_ALL=C grep -Pq '[^\x09\x0A\x0D\x20-\x7E]' "$file"; then
    log "NOTE: reproducer contains non-ASCII bytes; manual review required"
  fi
  log "privacy_check=passed digest=$(digest "$file")"
  return 0
}

classify() {
  local exception_class="${1:-unknown}"
  log "classification=MANUAL_REVIEW default_private=true exception_class=$exception_class"
  log "classification_guidance=use private vulnerability reporting until a maintainer rules out security impact"
}

lookup_mapping() {
  local task="$1"
  local subtarget_name="$2"
  case "$task" in
    :fuzz:fuzzZapstoreProtocol)
      TEST_CLASS="dev.ipf.whitenoise.android.fuzz.ZapstoreProtocolFuzzTest"
      ENTRY_METHOD="fuzzZapstoreProtocol"
      INPUTS_DIR="fuzz/src/test/resources/dev/ipf/whitenoise/android/fuzz/ZapstoreProtocolFuzzTestInputs/fuzzZapstoreProtocol"
      case "$subtarget_name" in
        NostrEventJson) SUBTARGET_ID=0 ;;
        RelayEnvelopeFrames) SUBTARGET_ID=1 ;;
        RelayEnvelopeSequence) SUBTARGET_ID=2 ;;
        *) return 1 ;;
      esac
      ;;
    :fuzz:fuzzIdentityReference)
      TEST_CLASS="dev.ipf.whitenoise.android.fuzz.IdentityReferenceFuzzTest"
      ENTRY_METHOD="fuzzIdentityReference"
      INPUTS_DIR="fuzz/src/test/resources/dev/ipf/whitenoise/android/fuzz/IdentityReferenceFuzzTestInputs/fuzzIdentityReference"
      case "$subtarget_name" in
        ProfileLink) SUBTARGET_ID=0 ;;
        RecipientNormalize) SUBTARGET_ID=1 ;;
        RecipientTokenize) SUBTARGET_ID=2 ;;
        PlausibleClipboard) SUBTARGET_ID=3 ;;
        *) return 1 ;;
      esac
      ;;
    :fuzz:fuzzNip55SignerProtocol)
      TEST_CLASS="dev.ipf.whitenoise.android.fuzz.Nip55SignerProtocolFuzzTest"
      ENTRY_METHOD="fuzzNip55SignerProtocol"
      INPUTS_DIR="fuzz/src/test/resources/dev/ipf/whitenoise/android/fuzz/Nip55SignerProtocolFuzzTestInputs/fuzzNip55SignerProtocol"
      case "$subtarget_name" in
        ParseContentRow) SUBTARGET_ID=0 ;;
        ParseActivityResult) SUBTARGET_ID=1 ;;
        SignedEventPubkeyHelpers) SUBTARGET_ID=2 ;;
        IntentFallbackBudget) SUBTARGET_ID=3 ;;
        *) return 1 ;;
      esac
      ;;
    *)
      return 1
      ;;
  esac
  return 0
}

ENGINE_VERSION="$(./gradlew :fuzz:dependencies --configuration testRuntimeClasspath --quiet 2>/dev/null | grep 'jazzer-junit:' | head -1 | sed 's/.*jazzer-junit://' || echo 'unknown')"
log "engine=jazzer-junit:$ENGINE_VERSION target=$TARGET"

START_SECONDS=$SECONDS

if [[ -n "$REPRO" ]]; then
  if [[ ! -f "$REPRO" ]]; then
    log "ERROR: reproducer not found: $REPRO"
    exit 1
  fi
  if [[ -z "$SUBTARGET" ]]; then
    log "ERROR: subtarget name required when replaying a reproducer"
    usage
    exit 1
  fi
  if ! lookup_mapping "$TARGET" "$SUBTARGET"; then
    log "ERROR: unknown task/subtarget combination: $TARGET $SUBTARGET"
    usage
    exit 1
  fi
  privacy_check "$REPRO" || exit 2

  REPRO_REVIEW_DIR="fuzz/build/repro-review/${ENTRY_METHOD}"
  mkdir -p "$REPRO_REVIEW_DIR"
  REPRO_DIGEST="$(digest "$REPRO")"
  REPRO_REVIEW_FILE="$REPRO_REVIEW_DIR/${REPRO_DIGEST}"
  {
    write_subtarget_prefix "$SUBTARGET_ID"
    cat "$REPRO"
  } >"$REPRO_REVIEW_FILE"
  log "repro_review_file=$REPRO_REVIEW_FILE subtarget=$SUBTARGET id=$SUBTARGET_ID"

  REPLAY_ROOT="fuzz/build/repro-replay"
  REPLAY_INPUTS="$REPLAY_ROOT/dev/ipf/whitenoise/android/fuzz/${TEST_CLASS##*.}Inputs/${ENTRY_METHOD}"
  rm -rf "$REPLAY_ROOT"
  mkdir -p "$REPLAY_INPUTS"
  cp "$REPRO_REVIEW_FILE" "$REPLAY_INPUTS/repro-${REPRO_DIGEST}"

  set +e
  REPLAY_OUTPUT="$(
    ./gradlew :fuzz:replayFuzzRegression \
      --tests "${TEST_CLASS}.${ENTRY_METHOD}" \
      -PfuzzReplayInputsDir="$REPLAY_ROOT" \
      --no-daemon 2>&1
  )"
  REPLAY_STATUS=$?
  set -e

  EXCEPTION_CLASS="$(printf '%s\n' "$REPLAY_OUTPUT" | grep -Eo '[A-Za-z0-9_.$]+Exception|[A-Za-z0-9_.$]+Error' | head -1 || true)"
  if [[ -z "$EXCEPTION_CLASS" ]]; then
    EXCEPTION_CLASS="manual-review"
  fi

  if [[ $REPLAY_STATUS -ne 0 ]]; then
    classify "$EXCEPTION_CLASS"
    log "replay=failed status=$REPLAY_STATUS exception_class=$EXCEPTION_CLASS"
    log "promotion=blocked copy reviewed input to fuzz/regression-corpus/${ENTRY_METHOD}/ after app unit test"
    exit "$REPLAY_STATUS"
  fi
  log "replay=passed"
  log "promotion=manual copy reviewed input to fuzz/regression-corpus/${ENTRY_METHOD}/ after app unit test"
else
  log "reproduce: JAZZER_FUZZ=1 ./gradlew $TARGET -PfuzzMaxDuration=3m -PfuzzMaxHeap=2g --no-daemon"
  log "minimize: rerun the crashing subtarget with libFuzzer minimization enabled"
  log "replay: scripts/fuzz-triage.sh $TARGET <reproducer-file> <SubtargetName>"
fi

ELAPSED=$((SECONDS - START_SECONDS))
log "elapsed=${ELAPSED}s"
