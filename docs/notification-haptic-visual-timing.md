# Notification haptic/visual timing evidence

`NotificationHapticVisualTimingDeviceTest` records three privacy-safe markers:

- `WN notification preparation`
- `WN notification notify`
- `WN notification listener post`

The test provisions its debug-only `NotificationListenerService`, posts a real
message notification, reports preparation and notify-to-listener durations, and
revokes listener access afterward. It records no sender, group, or message text.

These markers establish app preparation and Android listener-delivery timing.
They do **not** establish when vibration physically starts or when heads-up pixels
become visible. Measure those boundaries with a platform trace and, on an affected
device, a high-frame-rate external recording that keeps both the screen and the
device's physical movement in frame.

## Run

Use a debug build on a test account/device. Do not uninstall the existing app:

```sh
./gradlew :app:connectedDevPlayDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ipf.whitenoise.android.notifications.NotificationHapticVisualTimingDeviceTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

Capture Perfetto at the same time with notification, SystemUI/view, Binder,
scheduler, and device vibration tracks enabled. Align `WN notification notify`
and `WN notification listener post` with the vibrator and SystemUI frame tracks,
then use the external recording to validate perceived onset. Report each result
as a device/model/build observation rather than a universal framework guarantee.

## Matrix

Run warm- and cold-avatar cases on:

- a Pixel/AOSP-family device and the originally affected non-Pixel or hardened device;
- screen on/unlocked, screen off/locked, and notification shade already open;
- sound + vibration, vibration-only, sound-only, silent, and custom vibration;
- direct message, group message, and mention channels.

Verify the first card is immediately useful and correctly redacted. When an
avatar later enriches it, verify the same card updates without another sound,
vibration, heads-up interruption, badge increment, or duplicated history.

## Heads-up dwell evidence (#2412)

`NotificationHeadsUpDurationDeviceTest#controlledEnrichmentTimelineForExternalCapture`
records matched `initial_only` and `enrich_same_key` runs. Both use the same
synthetic initial card and a 1,500 ms hold; only the latter releases deferred
avatar enrichment. Each then leaves the card untouched for eight seconds,
requires the shade card to remain active, and cancels only its exact synthetic
target. App notify, exact package/tag/id listener callbacks, and explicit cleanup
times are reported separately. `FLAG_ONLY_ALERT_ONCE` is asserted for enrichment.

An avatar update preserves the active card's channel and grouping while avoiding
a repeat alert. AndroidX `setSilent(true)` can change an ungrouped notification
into its silent group; this is distinct from updating the same notification
with `setOnlyAlertOnce(true)`. The fixture reports the resulting group choice,
but only actual SystemUI frames establish whether the banner stayed visible.

### Safe targets

The fixture rejects unsupported targets before moving Home or changing listener
access. The screen must already be on and unlocked; the fixture never wakes or
unlocks it or changes display timeout, DND, or accessibility settings.

- Default: disposable API 30 emulator, with `-e allowHeadsUpProbe true`.
- Physical: API 37, exact isolated package
  `dev.ipf.whitenoise.android.preview.prlocal`, and both
  `-e allowHeadsUpProbe true -e allowPhysicalHeadsUpProbe true`.
  A separate explicit user authorization and verified serial are required.
  Existing Dev, production, and shared PR-preview installations are not targets.

On a physical target, notification permission must already be granted to that
isolated package. The external driver must snapshot and restore any permission
it provisions after the runner exits, including failure/abort paths. Runtime
permission is never revoked inside instrumentation because revocation can kill
the runner. Existing listener access is retained; the fixture revokes only a
listener grant it added. The driver must also verify listener restoration after
an aborted run. Only the armed synthetic tuple is retained by the debug listener.

### Build and capture

Build without connected-device discovery. The repository normally enables only
preview release variants; this debug fixture requires a separately reviewed,
local-only Gradle init script that enables exactly `previewPlayDebug` for the
isolated local package. Preserve that script and its hash with the evidence;
do not modify the production variant policy or substitute a Dev installation.
With that adapter, build the isolated physical preview:

```sh
env -u PR_NUMBER PR_PREVIEW_CHANNEL=isolated ./gradlew \
  --init-script /absolute/path/to/reviewed-isolated-preview.init.gradle \
  :app:assemblePreviewPlayDebug :app:assemblePreviewPlayDebugAndroidTest \
  -Pandroid.injected.build.abi=arm64-v8a
```

Resolve APKs from that build's variant-specific `output-metadata.json`; AGP may
place injected-ABI builds under `app/build/intermediates/apk/` instead of
`app/build/outputs/apk/`. Verify metadata and APK freshness against the completed
build, rather than choosing an older file solely by its directory.
Check app/test package IDs, target package in the instrumentation manifest,
signer, ABI, and SHA256 before installing with explicit `adb -s SERIAL install -r -t`.
Do not use stale intermediate APKs, uninstall, clear data, or run a connected
Gradle test task against a personal device.

Record the exact device/build, channel importance, DND, accessibility timeout,
installed hashes, and prior permission/listener state. Start a bounded external
recording before the first synthetic post; verify encoder readiness and coverage
of the initial banner, intervention and complete observation window. For each
mode, invoke only the selected method with:

```text
-e class dev.ipf.whitenoise.android.notifications.NotificationHeadsUpDurationDeviceTest#controlledEnrichmentTimelineForExternalCapture
-e allowHeadsUpProbe true
-e headsUpControlledEnrichment initial_only
-e headsUpObservationMs 8000
```

Repeat with `enrich_same_key` on the same APKs/settings. Add the physical opt-in
only for the verified isolated physical target. The instrumentation runner is
`<verified-test-package>/androidx.test.runner.AndroidJUnitRunner`. Use a unique
artifact prefix for each arm; verify installed hashes and restored permissions
afterward. Keep recordings private and synthetic; discard unrelated personal
notification content from any shared evidence.

The test deliberately ends with an assumption status after its real lifecycle
assertions. This means **external pixel review required**, not an automated
duration pass. An instrumentation exit code or listener callback alone is not
pixel evidence. Inspect timestamped original frames in both arms. Report sampled
intervals and any missing coverage honestly; a screenrecord without audio does
not establish sound or physical haptic suppression. Use an external recording
and appropriate platform traces when assessing those boundaries.

The removed SystemUI dump/log classifiers did not establish a validated removal
reason or rendered membership. They are not needed for this matched experiment;
do not interpret a missing log or opaque dump object as an absent banner.

### Remaining acceptance matrix

The automated fixture covers one synthetic group on Home. It does not close
the full issue's device/behavior matrix:

- the reported GrapheneOS/Pixel build and a supported AOSP-family build;
- chat list, Home and another foreground app;
- cached/cold sender and conversation avatars;
- isolated messages, same-priority bursts and higher-priority bursts;
- DM, group and mention channels;
- default/extended accessibility timeouts;
- actual sound/haptics, privacy, account changes, read/mute and dismissal races.

Android owns natural banner lifetime. Do not compensate with `setTimeoutAfter`,
ongoing notifications, full-screen intents, or app-owned vibration. Relevant
primary contracts are [notification updates][android-updates],
[AndroidX silent behavior][androidx-silent], and
[notification-listener collection removal][listener-removal].

[android-updates]: https://developer.android.com/develop/ui/compose/notifications/create-notification#Updating
[androidx-silent]: https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/core/core/src/main/java/androidx/core/app/NotificationCompat.java
[listener-removal]: https://developer.android.com/reference/android/service/notification/NotificationListenerService#onNotificationRemoved(android.service.notification.StatusBarNotification,%20android.service.notification.NotificationListenerService.RankingMap,%20int)
