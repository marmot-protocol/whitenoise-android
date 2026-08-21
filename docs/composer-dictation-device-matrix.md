# Composer dictation device matrix

This runbook records the device evidence for the provider-owned dictation flow
requested by
[#1911](https://github.com/marmot-protocol/whitenoise-android/issues/1911).
The compact composer waveform is the single dictation entry point and launches
the installed-provider Activity; White Noise does not run a separate in-app
recognizer. This product decision supersedes the attachment-sheet entry point in
#1911 and the in-app listening and processing UI described by #1969/#2029, so
those trackers need an explicit scope update before the PR can claim to close
them.

## Safety rules

- Never uninstall White Noise from a physical device. Install the debug APK in
  place with `adb install -r` so identity, drafts, and message history survive.
- Never run `connectedAndroidTest` on a physical device without
  `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.
- Do not remove or disable a speech provider on a personal device to manufacture
  the unavailable case. Use provider-free hardware or an emulator created for
  that purpose.
- Record an item as passing only after observing it on the named device. An
  emulator result cannot be relabelled as physical-device evidence.

## Evidence record

| Date | Configuration | Device | Result | Evidence |
|---|---|---|---|---|
| 2026-08-16 | Previous split implementation | Pixel 9 Pro XL (device A) | Superseded evidence | The attachment-sheet provider Activity returned editable text successfully, but the composer still used the now-removed in-app recognizer. Do not treat this row as verification of the unified-provider composer shortcut. |
| 2026-08-16 | Provider unavailable | Disposable API 36 Google Play emulator | Automated fallback pass | With no recognition Activity available, the provider path returned `ProviderUnavailable`, preserved the draft, and performed no White Noise microphone capture or provider download. |
| Pending | Provider configured | Pixel 9 Pro XL (device A) | Not run on composer-only head | Verify that the composer waveform launches the provider experience and returns text safely. |

## Build and install without data loss

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDevPlayDebug
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/devPlay/debug/app-dev-play-universal-debug.apk
```

The application id is `dev.ipf.whitenoise.android.dev`. Confirm that the
existing installation was updated rather than replaced:

```sh
adb -s DEVICE_SERIAL shell dumpsys package dev.ipf.whitenoise.android.dev \
  | grep -E 'firstInstallTime|lastUpdateTime|versionName'
```

## Capture the device configuration

Save these values with the test result:

```sh
adb -s DEVICE_SERIAL shell getprop ro.product.manufacturer
adb -s DEVICE_SERIAL shell getprop ro.product.model
adb -s DEVICE_SERIAL shell getprop ro.build.version.release
adb -s DEVICE_SERIAL shell getprop ro.build.version.sdk
adb -s DEVICE_SERIAL shell getprop ro.build.fingerprint
adb -s DEVICE_SERIAL shell cmd package query-activities \
  -a android.speech.action.RECOGNIZE_SPEECH
```

For the unavailable row, the recognition-Activity query must report no handlers
before opening White Noise.

## Configured-provider physical journey

Use a test conversation and a recognizable phrase such as "dictation matrix
alpha". Do not use a production message that might be sent accidentally.

1. With TalkBack enabled, focus the emoji, composer dictation, attachment, and
   voice-note actions. Confirm the spoken labels are respectively **Open emoji
   picker**, **Dictate text**, **Add attachment**, and **Hold to record voice
   message**. Confirm the emoji action remains in its original leading position.
2. Test blank and nonblank drafts. **Dictate text** must remain reachable, and a
   nonblank draft must still show **Send**.
3. Tap the composer waveform. The IME must fully close before the installed
   provider's UI appears. White Noise must not show `Preparing dictation…`, a
   blank keyboard-sized gap, or its voice-note recording UI.
4. Cancel from the provider. The composer must remain usable, preserve the
   draft, and receive no late text.
5. Complete provider recognition. The phrase must enter the editable draft at
   the saved cursor/selection and must never send automatically.
6. Start again, navigate to another chat, and complete recognition. The visible
   chat must remain unchanged. Reopen the origin and confirm its normal
   MDK-backed draft contains the phrase.
7. Repeat step 6 while switching accounts. The result must remain owned by the
   originating account and conversation.
8. Open attachments and confirm there is no second **Dictate text** action.
9. Confirm dictation does not ask White Noise for microphone permission; any
   capture permission and UI belong to the installed provider.
10. Hold the voice-note button and slide to cancel. Its existing gesture and
    plain-microphone icon must remain unchanged and distinct from the waveform.
11. Enable Android developer option **Don't keep activities**, launch the
    composer provider, and return a result. The recreated White Noise
    Activity must not launch a second provider and must deliver at most once to
    the immutable origin. Disable **Don't keep activities** immediately after.
12. Confirm chat-list voice search still launches its existing provider flow and
    fills search without affecting a conversation draft.

## Provider-unavailable physical journey

Run this only on a device whose configuration query reports no recognition
Activity.

1. Open an origin draft containing `Keep this text` with a nontrivial cursor or
   selection.
2. Tap the composer waveform. Confirm the concise unavailable state appears,
   no microphone indicator appears in system privacy UI, and the draft remains
   byte-for-byte unchanged.
3. Dismiss, open attachments, and confirm there is no second **Dictate text**
   action.
4. Navigate, switch accounts, background/foreground the app, and reopen the
   origin. Confirm no draft changed and no stale result appears.
5. With TalkBack enabled, confirm the unavailable copy, Retry, and Dismiss
   actions are announced once and remain reachable.
6. Record the OS/build/provider-query output in the evidence table above.

## Automated acceptance gate

Run after the final code change:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew --no-daemon \
  :app:ktlintCheck \
  :app:testDevPlayDebugUnitTest \
  :app:verifyRoborazziDevPlayDebug \
  :app:verifyRoborazziDevZapstoreDebug \
  :app:assembleDevPlayDebug
```

The provider coordinator coverage explicitly checks success, cancellation,
empty results, stale or duplicate callbacks, navigation and target
disappearance, unavailable providers, immutable draft ownership, and host
recreation without a duplicate provider launch.
