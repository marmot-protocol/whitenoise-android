# Composer dictation device matrix

This runbook records the physical-device evidence required by issues
[#1911](https://github.com/marmot-protocol/whitenoise-android/issues/1911),
[#1969](https://github.com/marmot-protocol/whitenoise-android/issues/1969),
[#2029](https://github.com/marmot-protocol/whitenoise-android/issues/2029), and
[#2030](https://github.com/marmot-protocol/whitenoise-android/issues/2030).
It is verification evidence, not a second product backlog.

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
| 2026-08-16 | Provider configured | Pixel 9 Pro XL (`46131FDAS003CG`) | Partial pass | One recognition Activity and three recognition services were discoverable. The compact action entered `Dictating…` without reopening the keyboard, the attachment-sheet action handed off to the provider Activity, recognized text returned to an editable draft without sending, and the original draft was restored after cleanup. TalkBack, denial, navigation, and voice-note handoff were not all recorded during this pass. |
| 2026-08-16 | Provider unavailable | Disposable API 36 Google Play emulator | Automated fallback pass | After disabling the disposable emulator's recognition packages, Android returned no recognition activities or services. Production availability calls mapped both composer entry points to `ProviderUnavailable`, acquired no microphone lease, preserved the draft, and showed `Speech recognition isn’t available`. This proves the fallback but is not the physical GrapheneOS/AOSP row required by #1969. |
| Pending | Provider unavailable | Provider-free physical GrapheneOS/AOSP device | Not run | Required before #1969/#2030 can be closed literally. |

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
adb -s DEVICE_SERIAL shell cmd package query-services \
  -a android.speech.RecognitionService
```

For the unavailable row, both package queries must report no handlers before
opening White Noise. Also record whether the device is GrapheneOS, another AOSP
build, or an OEM Android build.

## Configured-provider physical journey

Use a test conversation and a recognizable phrase such as "dictation matrix
alpha". Do not use a production message that might be sent accidentally.

1. With TalkBack enabled, focus the emoji, inline dictation, attachment, and
   voice-note actions. Confirm the spoken labels are respectively **Open emoji
   picker**, **Dictate text**, **Add attachment**, and **Hold to record voice
   message**. Confirm the emoji action remains in its original leading position.
2. Test blank and nonblank drafts. **Dictate text** must remain reachable, and a
   nonblank draft must still show **Send**.
3. Tap inline **Dictate text**. The IME must close and stay closed. Confirm the
   compact `Starting dictation…`, `Dictating…`, and `Transcribing…` transitions;
   TalkBack must announce state changes only once.
4. Cancel while starting, listening, and processing. Each cancellation must
   return to a usable composer, preserve the draft, and produce no late text.
5. Complete recognition. The phrase must enter the editable draft at the saved
   cursor/selection and must never send automatically.
6. Start again, navigate to another chat, and complete recognition. The visible
   chat must remain unchanged. Reopen the origin and confirm its normal
   MDK-backed draft contains the phrase.
7. Repeat step 6 while switching accounts. The result must remain owned by the
   originating account and conversation.
8. Open attachments and choose **Dictate text**. Confirm provider-owned UI
   appears, White Noise's voice-note UI is absent, Cancel is nondestructive, and
   a successful result returns to the originating draft.
9. Deny microphone permission for inline dictation, then test permanent denial
   and the Settings recovery action. The attachment-sheet provider path must not
   request White Noise's microphone permission.
10. Start inline dictation and confirm voice-note recording cannot acquire the
    microphone. After cancelling dictation, hold the voice-note button and slide
    to cancel; its existing gesture must still work. Repeat in the opposite order
    and confirm dictation cannot capture concurrently.
11. Enable Android developer option **Don't keep activities**, launch the
    attachment-sheet provider, and return a result. The recreated White Noise
    Activity must not launch a second provider and must deliver at most once to
    the immutable origin. Disable **Don't keep activities** immediately after.
12. Confirm chat-list voice search still launches its existing provider flow and
    fills search without affecting a conversation draft.

## Provider-unavailable physical journey

Run this only on a device whose configuration query already reports no
recognition Activity and no recognition service.

1. Open an origin draft containing `Keep this text` with a nontrivial cursor or
   selection.
2. Tap inline **Dictate text**. Confirm the concise unavailable state appears,
   no microphone indicator appears in system privacy UI, and the draft remains
   byte-for-byte unchanged.
3. Dismiss, open attachments, and tap **Dictate text**. Confirm the same
   unavailable outcome without a crash, provider download, or fallback model.
4. Navigate, switch accounts, background/foreground the app, and reopen the
   origin. Confirm no draft changed and no stale result appears.
5. With TalkBack enabled, confirm the unavailable copy, Retry, and Dismiss
   actions are announced once and remain reachable at the largest font scale.
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

The coordinator acceptance suite is
`ConversationDictationCoordinatorTest`; it explicitly covers success and empty
results, duplicate/replacement/stale generations, navigation and target
disappearance, denial and unavailable providers, microphone exclusion,
cancellation in every nonterminal state, host recreation without provider
relaunch, and exactly-once resource release under terminal and lifecycle races.
