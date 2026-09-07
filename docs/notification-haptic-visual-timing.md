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

`NotificationHeadsUpDurationDeviceTest` exercises a cold-avatar notification
as two framework posts under one `(tag, id)`: an alerting first card and an
avatar-enriched update carrying `FLAG_ONLY_ALERT_ONCE`. It records app `notify`
markers, listener post/update callbacks, an eight-second natural observation
window, and the explicit test-cleanup `cancel`. All identities and content are
synthetic. The test reports whether the enriched update uses AndroidX's silent
notification group and its resulting group-alert behavior
([`NotificationCompat.Builder.setSilent`][androidx-silent]). Those values are
experimental inputs, not evidence that either one caused a heads-up collapse.

This companion is deliberately restricted to an unlocked, interactive API 30
emulator and requires explicit instrumentation opt-in before it grants listener
access or moves to Home. Build without Gradle device discovery, verify the exact
disposable task-emulator serial and image, then install and invoke the runner
only through `adb -s`. Never run these commands against a physical device or
uninstall an existing White Noise installation:

```sh
(
set -eu
TASK_EMULATOR=emulator-5554
TASK_AVD=wn_2453_api30_google
HEADS_UP_TEST=dev.ipf.whitenoise.android.notifications.NotificationHeadsUpDurationDeviceTest
test "$(adb -s "$TASK_EMULATOR" get-state)" = "device"
test "$(adb -s "$TASK_EMULATOR" shell getprop ro.kernel.qemu | tr -d '\r')" = "1"
test "$(adb -s "$TASK_EMULATOR" shell getprop ro.build.version.sdk | tr -d '\r')" = "30"
test "$(adb -s "$TASK_EMULATOR" emu avd name | tr -d '\r' | head -n 1)" = "$TASK_AVD"
TASK_ABI="$(adb -s "$TASK_EMULATOR" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$TASK_ABI" in
  arm64-v8a | armeabi-v7a | x86 | x86_64) ;;
  *) exit 1 ;;
esac

./gradlew :app:assembleDevPlayDebug :app:assembleDevPlayDebugAndroidTest \
  -Pandroid.injected.build.abi="$TASK_ABI"

APP_APK="app/build/intermediates/apk/devPlay/debug/app-dev-play-$TASK_ABI-debug.apk"
TEST_APK="app/build/intermediates/apk/androidTest/devPlay/debug/app-dev-play-debug-androidTest.apk"
test -f "$APP_APK"
test -f "$TEST_APK"
test "$(apkanalyzer manifest application-id "$APP_APK")" = "dev.ipf.whitenoise.android.dev"
test "$(apkanalyzer manifest application-id "$TEST_APK")" = "dev.ipf.whitenoise.android.dev.test"

adb -s "$TASK_EMULATOR" install -r -t "$APP_APK"
adb -s "$TASK_EMULATOR" install -r -t "$TEST_APK"
adb -s "$TASK_EMULATOR" shell am instrument -w -r \
  -e class "$HEADS_UP_TEST" \
  -e allowHeadsUpProbe true \
  -e headsUpObservationMs 8000 \
  dev.ipf.whitenoise.android.dev.test/androidx.test.runner.AndroidJUnitRunner
)
```

If AGP places either artifact elsewhere, resolve it from that variant's
`output-metadata.json`, then repeat both `apkanalyzer` package checks before any
install. `install -r -t` updates the emulator in place; there is intentionally
no uninstall step.

The fixture cancels any stale copy of its exact synthetic target, captures a
pre-post SystemUI baseline, posts and enriches the card, then leaves the card
untouched for the observation window. During that window it expects no
`NotificationListenerService` removal callback and expects the exact card to
remain in `NotificationManager.activeNotifications`. Only after collecting
SystemUI evidence does it cancel the card and require listener removal reason
`REASON_APP_CANCEL`.

A listener removal means the shade card left Android's notification collection;
it is not a heads-up banner-collapse signal. The API 30 probe therefore also
discovers an allowlisted `StatusBar`/`StatusBarGoogle` dumpable and isolates only
its indentation-bounded `HeadsUpManagerPhone state:` subsection. This boundary
matters because a sibling shade section can retain the same key after the
heads-up view is gone. The probe also filters `sysui_heads_up_status` event-log
deltas to the exact synthetic key. The event log can expose show/hide transition
timestamps, but the Android 11 implementation does not guarantee a
reason-bearing removal record. The fixture probes the newer `NotifHeadsUpLog`
buffer only when it is registered. If the image exposes no exact-key initial
state, natural hide transition, or reason-bearing removal line, the run reports
its filtered evidence and ends as **inconclusive**, never as a pass.

The current API 30 probe has no validated reason-bearing removal format, so its
duration conclusion is always **inconclusive**, even when card delivery and
cleanup assertions succeed. Modern buffer rows remain raw diagnostic evidence;
words such as “remove” or “reason” are not proof of completed removal. A missing
or failed initial/final SystemUI section is likewise unavailable evidence, not
proof that the target banner is absent. Existing listener access is retained;
the fixture revokes only a grant it added for that run. Listener setup allows up
to 15 seconds because instrumentation restarts the target process and Android
11 defers rebinding a dead listener by 10 seconds. This readiness allowance is
separate from notification delivery and the untouched observation window.

Listener and SystemUI diagnostics still do not prove rendered-pixel dwell. A
duration claim additionally requires a Perfetto trace containing the test's
`WN heads-up ...` markers and an external high-frame-rate recording. Record the
emulator image/build fingerprint, channel importance, DND, and accessibility
timeout setting. Do not record a real sender, account, group, message, relay, or
notification payload.

The automated probe covers only one isolated cold-avatar group message on Home.
The following cases remain a manual/device evidence matrix rather than claims
made by that test:

Run the following cases independently so a platform avalanche is not mistaken
for the isolated-message defect:

- White Noise chat list foregrounded, launcher/home visible, and another app
  foregrounded;
- warm and cold sender/conversation avatars;
- one isolated message, a rapid same-priority burst, and a higher-priority burst;
- direct-message, group-message, and mention channels;
- default and extended accessibility timeouts;
- the reported GrapheneOS/Pixel build and one supported AOSP-family build.

For each isolated run, verify that the app emits no cancel before the natural
observation ends, SystemUI retains the heads-up view for its chosen dwell
window, and the shade card remains after that view ends. Report burst shortening
separately. White Noise must not counter platform decisions with
`setTimeoutAfter`, an ongoing notification, a full-screen intent, or app-owned
vibration.

The source-level contract behind this matrix is:

- Android's notification guidance recommends updating the same ID and using
  `setOnlyAlertOnce()` for non-realerting updates, and warns that update rate is
  limited ([Android notification updates][android-updates]).
- `NotificationListenerService` reports collection removal and a framework
  reason; its callback is not a rendered heads-up lifecycle API
  ([listener removal API][listener-removal]).
- Android 11 SystemUI owns heads-up timeout/removal and emits the legacy visible
  status event without a public reason contract
  ([HeadsUpManager][aosp-r-manager],
  [HeadsUpManagerPhone][aosp-r-phone]).
- Newer SystemUI still owns update/removal scheduling
  ([HeadsUpCoordinator][aosp-coordinator],
  [BaseHeadsUpManager][aosp-manager]).
- AOSP's avalanche controller may deliberately shorten queued heads-up entries
  ([AvalancheController][aosp-avalanche]).

[android-updates]: https://developer.android.com/develop/ui/compose/notifications/create-notification#Updating
[androidx-silent]: https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/core/core/src/main/java/androidx/core/app/NotificationCompat.java
[listener-removal]: https://developer.android.com/reference/android/service/notification/NotificationListenerService#onNotificationRemoved(android.service.notification.StatusBarNotification,%20android.service.notification.NotificationListenerService.RankingMap,%20int)
[aosp-r-manager]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/packages/SystemUI/src/com/android/systemui/statusbar/policy/HeadsUpManager.java
[aosp-r-phone]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/packages/SystemUI/src/com/android/systemui/statusbar/phone/HeadsUpManagerPhone.java
[aosp-coordinator]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/coordinator/HeadsUpCoordinator.kt
[aosp-manager]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/policy/BaseHeadsUpManager.java
[aosp-avalanche]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/statusbar/policy/AvalancheController.kt
