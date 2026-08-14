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
