#!/usr/bin/env bash
# Select the focused PR smoke test or the full post-merge instrumented suite.

set -euo pipefail

event_name="${1:-}"

# Read-aloud highlights are geometry drawn from real font metrics, and the
# APK a tester installs is minified while every other lane here builds
# `debug`. Run this class twice so a highlight that survives one and not the
# other names its own cause.
highlight_class=dev.ipf.whitenoise.android.ui.conversation.TimelineRowTtsHighlightPaintAndroidTest

run_minified_highlight_pass() {
  ./gradlew :app:connectedDevZapstoreBenchmarkReleaseAndroidTest \
    -PwhitenoiseTestBuildType=benchmarkRelease \
    -Pandroid.testInstrumentationRunnerArguments.class="$highlight_class" \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --stacktrace
}

if [[ "$event_name" == "pull_request" ]]; then
  ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.core.ProfileImageDialSafetyIntegrationTest,dev.ipf.whitenoise.android.core.ForwardMediaReferenceFfiIntegrationTest,dev.ipf.whitenoise.android.media.MediaReferenceSupportFuzzIntegrationTest,"$highlight_class" \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --stacktrace
  exec_status=0
  run_minified_highlight_pass || exec_status=$?
  exit "$exec_status"
fi

./gradlew :app:connectedDevZapstoreDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  --no-daemon --stacktrace
run_minified_highlight_pass
