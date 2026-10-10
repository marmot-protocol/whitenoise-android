#!/usr/bin/env bash
# Select the focused PR smoke test or the full post-merge instrumented suite.

set -euo pipefail

event_name="${1:-}"
review_demo_e2e="${2:-false}"
document_provider_matrix="${3:-false}"
responsiveness_only="${4:-false}"

if [[ "$event_name" == "workflow_dispatch" && "$review_demo_e2e" == "true" && "$document_provider_matrix" == "true" ]]; then
  echo "Select one opt-in instrumented suite per dispatch" >&2
  exit 2
fi

if [[ "$event_name" == "workflow_dispatch" && "$responsiveness_only" == "true" ]]; then
  if [[ "$review_demo_e2e" == "true" || "$document_provider_matrix" == "true" ]]; then
    echo "Select one opt-in instrumented suite per dispatch" >&2
    exit 2
  fi
  ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.annotation=dev.ipf.whitenoise.android.ResponsivenessDeviceAcceptance \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --stacktrace
  required_file="$(mktemp)"
  trap 'rm -f "$required_file"' EXIT
  grep -E '\.(ConversationRetainedTranscriptFirstFrameAndroidTest|ChatListConnectionResumeDeviceTest)#' \
    config/instrumented-required-cases.txt > "$required_file"
  python3 scripts/check_instrumented_required_cases.py \
    app/build/outputs/androidTest-results/connected --required "$required_file"
  exit 0
fi

if [[ "$event_name" == "workflow_dispatch" && "$review_demo_e2e" == "true" ]]; then
  exec ./scripts/run-review-demo-e2e.sh
fi

if [[ "$event_name" == "workflow_dispatch" && "$document_provider_matrix" == "true" ]]; then
  exec ./scripts/run-document-provider-matrix.sh
fi

# Pull requests run only the classes annotated @PullRequestDeviceSmoke. The filter is an
# annotation rather than a class list because AGP hands a comma-separated
# testInstrumentationRunnerArguments.class value to `am instrument` truncated at the
# first comma, so a list silently ran just its first class.
smoke_annotation=dev.ipf.whitenoise.android.PullRequestDeviceSmoke
manual_fixture_annotation=dev.ipf.whitenoise.android.ManualDeviceFixture

if [[ "$event_name" == "pull_request" ]]; then
  ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.annotation="$smoke_annotation" \
    -Pandroid.testInstrumentationRunnerArguments.notAnnotation="$manual_fixture_annotation" \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --stacktrace
else
  ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notAnnotation="$manual_fixture_annotation" \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --stacktrace
  # A green full suite can still hide a required regression that @SdkSuppress or a
  # rename filtered out. Require each declared case to have executed and passed.
  python3 scripts/check_instrumented_required_cases.py app/build/outputs/androidTest-results/connected
fi

# PRs and master run native correctness only. Timing is a manual physical-device
# operation; explicit opt-in app suites returned above.
exec ./gradlew :cryptoBenchmark:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.core.nostr.NostrEventVerifierInstrumentedTest \
  --no-daemon --stacktrace
