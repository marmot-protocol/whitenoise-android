#!/usr/bin/env bash
# Select the focused PR smoke test or the full post-merge instrumented suite.

set -euo pipefail

event_name="${1:-}"

# Read-aloud highlights are geometry measured from real font metrics, which
# the Robolectric suite only simulates, so every production-row placement class
# earns a device pass on pull requests that trigger this workflow.
highlight_classes=dev.ipf.whitenoise.android.ui.conversation.TimelineRowTtsHighlightPaintAndroidTest,dev.ipf.whitenoise.android.ui.conversation.TtsHighlightPlacementAndroidTest,dev.ipf.whitenoise.android.ui.conversation.TtsRichLeafPlacementAndroidTest

if [[ "$event_name" == "pull_request" ]]; then
  exec ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.WarmResumeFirstUsefulFrameTest,dev.ipf.whitenoise.android.core.ProfileImageDialSafetyIntegrationTest,dev.ipf.whitenoise.android.core.ForwardMediaReferenceFfiIntegrationTest,dev.ipf.whitenoise.android.media.MediaReferenceSupportFuzzIntegrationTest,dev.ipf.whitenoise.android.share.InboundShareTaskReuseDeviceTest,"$highlight_classes" \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    --no-daemon --stacktrace
fi

exec ./gradlew :app:connectedDevZapstoreDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  --no-daemon --stacktrace
