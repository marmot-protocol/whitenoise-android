#!/usr/bin/env bash
# Select the focused PR smoke test or the full post-merge instrumented suite.

set -euo pipefail

event_name="${1:-}"
if [[ "$event_name" == "pull_request" ]]; then
  exec ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.core.ProfileImageDialSafetyIntegrationTest \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --stacktrace
fi

exec ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  --no-daemon --stacktrace
