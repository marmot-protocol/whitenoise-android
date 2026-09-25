#!/usr/bin/env bash
# Select the focused PR smoke test or the full post-merge instrumented suite.

set -euo pipefail

event_name="${1:-}"
document_provider_matrix="${2:-false}"

if [[ "$event_name" == "workflow_dispatch" && "$document_provider_matrix" == "true" ]]; then
  exec ./scripts/run-document-provider-matrix.sh
fi

# Pull requests run only the classes annotated @PullRequestDeviceSmoke. The filter is an
# annotation rather than a class list because AGP hands a comma-separated
# testInstrumentationRunnerArguments.class value to `am instrument` truncated at the
# first comma, so a list silently ran just its first class.
smoke_annotation=dev.ipf.whitenoise.android.PullRequestDeviceSmoke

if [[ "$event_name" == "pull_request" ]]; then
  exec ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.annotation="$smoke_annotation" \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --stacktrace
fi

exec ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  --no-daemon --stacktrace
