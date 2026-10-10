# Notification haptic/visual timing evidence

`NotificationHapticVisualTimingDeviceTest` records three privacy-safe markers:

- `WN notification preparation`
- `WN notification notify`
- `WN notification listener post`

The test provisions its debug-only `NotificationListenerService`, posts a real
message notification, reports preparation and notify-to-listener durations, and
revokes only listener access granted by the fixture afterward, preserving any
pre-existing grant. Listener setup allows Android's delayed rebinding after
instrumentation restarts an already-bound process; this allowance does not
extend the content-stage or notify-to-listener ceilings. The #2453 companion reads only its synthetic
fixture body transiently to assert the resolved content, then classifies each
callback as `Fallback`, `Resolved`, or `Other`; timing reports contain no sender,
group, identity, or message text. The listener also accepts only the test's exact
package, notification tag, and notification ID, so unrelated notifications cannot enter the
evidence stream.

These markers establish app preparation and Android listener-delivery timing.
They do **not** establish when vibration physically starts or when heads-up pixels
become visible. Measure those boundaries with a platform trace and, on an affected
device, a high-frame-rate external recording that keeps both the screen and the
device's physical movement in frame.

## Run

Use a debug build on a test account/device. Do not uninstall the existing app:

On Android 13+, grant notification permission on the selected test device before
running. The fixture requires that permission and never grants or revokes it.

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

Run the first-draw content companion on API 30 and API 36 with notification
history enabled where available. Record listener payload callbacks separately
from a Perfetto/SystemUI frame trace: the listener proves same-key content and
`onlyAlertOnce`, while only the frame trace or an external recording can show
whether the platform exposed both revisions to the user.

The timeout scenario holds one synchronous local identity read until the first
fallback callback is delivered, then releases it and requires exactly one silent
same-key correction. The caller starts one absolute 75 ms local-resolution
deadline, and coordinator admission and setup consume that same budget. This
leaves 25 ms nominal caller-return headroom inside the unchanged measured 100 ms
content-stage ceiling; it does not guarantee Android scheduling latency. Work
finishing at or after 75 ms uses the fallback and matching silent correction.
Deterministic unit
tests advance an injected monotonic clock to its exact boundary. Resolver
completion before that boundary does not prove when the caller resumed, when
`ContentComplete` was sampled, when `notify` returned, or when pixels rendered;
those later observations retain their real scheduling overhead.

Verify the first card is immediately useful and correctly redacted. The typed
AppState path uses already-decoded local imagery or a stable monogram; remote
image loading primes future cards and must not cause a cosmetic second write.
When bounded text resolution requires a late correction, verify that it keeps
the same grouping/channel without another sound, vibration, heads-up interruption,
badge increment, or duplicated history. Do not confuse the lower-level avatar
fixture below with this production AppState behavior.

## Heads-up dwell evidence (#2412)

`NotificationHeadsUpDurationDeviceTest#controlledEnrichmentTimelineForExternalCapture`
records one arm per run, selected with `headsUpControlledEnrichment`:

| Arm | Second write |
| --- | --- |
| `initial_only` | none, the control |
| `enrich_same_key` | deferred avatar enrichment released |
| `second_message` | a second message to the live card inside the burst window |
| `late_correction` | silent replacement of the message text |
| `invite_refresh` | silent rewrite of an invite's sender identity |

All arms use the same synthetic initial card and a 1,500 ms hold. Each then leaves
the card untouched for eight seconds, requires the shade card to remain active, and
cancels only its exact synthetic target. App notify, exact package/tag/id listener
callbacks, and explicit cleanup times are reported separately. For every second
write the test asserts `FLAG_ONLY_ALERT_ONCE`, that the write is not silenced, and
that its channel, group, group alert behavior and sort key equal the first post's.
The reported fields are `first_post_group_alert_behavior`,
`second_post_group_alert_behavior`, `first_post_sort_key` and `second_post_sort_key`.

### Why a same-key write must not use `setSilent`

AndroidX applies `setSilent(true)` when a card builds. A card with no group key
joins the `silent` group, and a non-summary child with a group key is moved to
`GROUP_ALERT_SUMMARY`, so `Notification.suppressAlertingDueToGrouping()` becomes
true for it. The platform treats that card as not alerting: SystemUI's heads-up
coordinator evaluates an update that is no longer heads-up eligible by removing
the banner still showing for the key, after the minimum display time. This was
read from AndroidX 1.19.1 bytecode (`NotificationCompatBuilder`) and the AOSP
`main` sources (`Notification`, `NotificationInterruptStateProviderImpl`,
`HeadsUpCoordinator`, `NotificationAttentionHelper`); it has not been captured on a
device, so only SystemUI frames establish that a banner stayed visible.

`setOnlyAlertOnce(true)` alone already mutes sound, vibration and a repeat banner
for an update of a posted card, and it leaves group alerting untouched. The
presenter therefore reads the live same-key card at the final write, under the card
lock, and sets only that flag, copying the live sort key. Without a live card the
platform has nothing to treat as an update, so the write keeps `setSilent` and a
card the user swiped away cannot ring a second time. A live card that was itself
built silent never showed a banner, so a later quiet write keeps it silent. The
preview-toggle scrub and legacy card adoption still request `silent = true`. A
swipe that lands between the live read and the platform post can still let one
`FLAG_ONLY_ALERT_ONCE` write ring, a window of milliseconds that no flag choice closes.

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
arm, invoke only the selected method with:

```text
-e class dev.ipf.whitenoise.android.notifications.NotificationHeadsUpDurationDeviceTest#controlledEnrichmentTimelineForExternalCapture
-e allowHeadsUpProbe true
-e headsUpControlledEnrichment initial_only
-e headsUpObservationMs 8000
```

Repeat with each other arm on the same APKs/settings. Add the physical opt-in
only for the verified isolated physical target. The instrumentation runner is
`<verified-test-package>/androidx.test.runner.AndroidJUnitRunner`. Use a unique
artifact prefix for each arm; verify installed hashes and restored permissions
afterward. Keep recordings private and synthetic; discard unrelated personal
notification content from any shared evidence.

The test deliberately ends with an assumption status after its real lifecycle
assertions. This means **external pixel review required**, not an automated
duration pass. An instrumentation exit code or listener callback alone is not
pixel evidence. Inspect timestamped original frames in each arm and its `initial_only` control. Report sampled
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

## Long message expansion (#1901)

A first message with no carried history posts as the standard expandable text block
once its body reaches `MIN_EXPANDED_SINGLE_MESSAGE_CODE_POINTS` Unicode code points.
Shorter bodies, and every card that carries an earlier message, use the conversation
template. The text block is a single block of text and cannot hold separate earlier
messages, so a long newest message behind carried history shows as many lines as
Android gives the conversation template. That is a platform limit that this change
documents and does not work around: folding history into the text block would need new
extras and edges toward a second copy of the message state, and the other standard
inbox template ellipsizes each line. A body is still cut at 1,000 code points either way.

The threshold is a conservative starting value and not a measured one. The text block
carries no sender or conversation icon and Android classifies a conversation by its
conversation template, so the value is kept well above zero. The text block is not a
conversation card, so a single message that now reaches it, from 120 code points where it
used to be 160, loses the conversation classification, and with it the important
conversation Do Not Disturb exception and the conversation section of the shade. That is
the cost of showing more text and it applies to a message only while it is the sole
message on its card. Cards that carry earlier messages keep the conversation template.

Hidden previews and app lock never use the text block. Hidden previews rewrite the card
as a generic conversation card that keeps its shortcut, so it keeps the conversation
classification and its Do Not Disturb exceptions. App lock shows a generic card with no
conversation shortcut, so Android does not treat it as a conversation. Neither reveals a
sender, group or message text.

Confirm or retune the threshold with before and after shade captures: one long DM, one
long group message and a long newest message behind a carried message, each collapsed and
expanded at default and large font, naming the Android version and device and counting the
visible lines. Record any truncation Android applies as a platform limit.

[android-updates]: https://developer.android.com/develop/ui/compose/notifications/create-notification#Updating
[androidx-silent]: https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/core/core/src/main/java/androidx/core/app/NotificationCompat.java
[listener-removal]: https://developer.android.com/reference/android/service/notification/NotificationListenerService#onNotificationRemoved(android.service.notification.StatusBarNotification,%20android.service.notification.NotificationListenerService.RankingMap,%20int)
