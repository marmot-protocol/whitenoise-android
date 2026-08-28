#!/usr/bin/env bash
# Select the focused PR smoke test or the full post-merge instrumented suite.

set -euo pipefail

event_name="${1:-}"

# Read-aloud highlights are geometry measured from real font metrics, which
# the Robolectric suite only simulates, so this class earns a device pass.
highlight_class=dev.ipf.whitenoise.android.ui.conversation.TimelineRowTtsHighlightPaintAndroidTest

if [[ "$event_name" == "pull_request" ]]; then
  exec ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.core.ProfileImageDialSafetyIntegrationTest,dev.ipf.whitenoise.android.core.ForwardMediaReferenceFfiIntegrationTest,dev.ipf.whitenoise.android.media.MediaReferenceSupportFuzzIntegrationTest,"$highlight_class" \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --stacktrace
fi

exec ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  --no-daemon --stacktrace
