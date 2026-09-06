# Composer dictation compatibility matrix

## App-owned dictation update (2026-09-06)

PR [2496](https://github.com/marmot-protocol/whitenoise-android/pull/2496)
selects the app-owned recognizer from the composer. The compatibility matrix
below records the earlier, pre-2198 provider-Activity default; statements that
the composer does not select app-owned recognition are historical. This branch
also carries a provider-Activity recovery path after a recognizer permission
error with no accumulated transcript.

Microphone permission and the device-wide microphone privacy switch are
different gates. Since Android 16, the effective AppOps check can reject an app
with a valid runtime grant while that switch is off. White Noise must not
classify microphone mute as a permanent app-permission denial. Show an explicit
Microphone access is off dialog with a link to Android privacy settings, without
starting a muted recording or foreground service. Offline Voice Input can show
Listening while globally muted on the physical GrapheneOS fixture; do not rely
on provider UI to explain this gate. White Noise never changes the privacy
switch. Android does not expose the software toggle's current state to ordinary
apps, so an effective AppOps denial with a valid runtime grant uses the same
visible privacy recovery. Without a runtime grant, request RECORD_AUDIO normally.

Test both initial runtime denial and an existing while-in-use grant with the
global switch off/on. Keep the microphone enabled throughout speech capture,
provider decoding, and Done; then restore the fixture's original privacy state.
Verify actual recognized text in the origin draft, and never send fixture text.
The microphone-access regression exercises the Android platform implementation
with an effective privacy denial, rather than only a fake controller grant.

## Historical compatibility matrix (before PR 2496)

This runbook is the source of truth for the Android speech contracts that White
Noise can use for composer dictation. It covers
[#2276](https://github.com/marmot-protocol/whitenoise-android/issues/2276) and
provides the compatibility boundary consumed by the app-owned work in
[#2198](https://github.com/marmot-protocol/whitenoise-android/issues/2198).

White Noise must choose one mode before a session starts. It must never move to
a different speech contract after an error: there is **no silent fallback**
between provider Activity, app-owned `SpeechRecognizer`, and keyboard/IME
handoff.

The current provider-Activity path performs a cancellable 1.5-second
availability check after an explicit dictation tap. It shows **Checking speech
service…** before launch and **Opening speech service…** only after the provider
resolves. This is launch-readiness feedback, not a claim that the provider's
model is loaded or ready to listen. White Noise does not prewarm this path,
launch provider UI while idle, or open the microphone in the background.

## Android speech contracts

| Contract | Who owns capture and UI? | White Noise entry point | Required Android capability | Current support |
|---|---|---|---|---|
| Provider Activity | The installed provider owns capture, endpointing, permission UI, and its Activity. | `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` from the composer waveform. | A visible Activity resolving `android.speech.action.RECOGNIZE_SPEECH`. | Current composer default. Returned text is inserted into the immutable origin draft and is never sent automatically. |
| App-owned recognizer | White Noise owns session controls and lifecycle; the selected service owns recognition. | `SpeechRecognizer.createSpeechRecognizer(context, selectedComponent)`. | `RECORD_AUDIO`, a non-empty selected `Settings.Secure.VOICE_RECOGNITION_SERVICE`, and that exact component discoverable through `RecognitionService.SERVICE_INTERFACE`. | Controller integration is available for [#2198](https://github.com/marmot-protocol/whitenoise-android/issues/2198), but the current composer does not select this mode. It binds the selected component explicitly and never asks Android to choose an arbitrary fallback. |
| Voice IME / keyboard handoff | The enabled keyboard or voice IME owns capture, model lifecycle, and text commit. | Normal editor connection and committed text; no White Noise speech API call. | An enabled input method that provides voice input. | Supported as ordinary keyboard input. It is not a White Noise dictation session and cannot provide app-owned Done/Cancel, navigation persistence, or immutable-origin routing. |

The composer must keep provider Activity behavior until [#2198](https://github.com/marmot-protocol/whitenoise-android/issues/2198) deliberately changes its selected mode. The app-owned controller is not a fallback for a missing Activity, and the Activity is not a fallback for a missing selected `RecognitionService`.

## Runtime probes and deterministic failures

### Provider Activity mode

1. Query `ACTION_RECOGNIZE_SPEECH` through package visibility.
2. If no Activity resolves, report `ProviderUnavailable` without changing the draft.
3. If the provider cancels or returns blank text, preserve the draft. A late or duplicate result cannot change another conversation.

### App-owned `SpeechRecognizer` mode

1. Read the selected recognition component from
   `Settings.Secure.VOICE_RECOGNITION_SERVICE`.
2. Reject an empty or malformed component.
3. Query `android.speech.RecognitionService` and require that the exact selected
   component is present.
4. Bind that component explicitly with
   `SpeechRecognizer.createSpeechRecognizer(context, component)`.
5. If the selected provider disappears between probe and bind, report
   `ProviderUnavailable`; never retry through Android's implicit provider
   selection.
6. Map unsupported language/model errors to `ProviderUnavailable`, network
   errors to `Network`, and recognizer saturation to `RecognizerBusy`.

The app-owned API does not expose a portable, synchronous guarantee that a
provider's offline model is already loaded. White Noise therefore does not
claim offline readiness from service discovery alone. A provider that reports
language/model unavailability is unsupported for that session; downloading or
configuring models remains provider-owned.

### Voice IME mode

White Noise receives ordinary committed editor text. It cannot probe whether a
voice IME has downloaded a local model, cancel its recognition session, or
recover its partial transcript. Those controls remain in the IME.

## Product-facing unsupported states

| State | User-visible outcome | Safe recovery |
|---|---|---|
| No recognition Activity for current composer mode | Dictation is unavailable; draft and cursor remain unchanged. | Configure an Android speech provider in voice-input settings, use keyboard voice input, or continue typing. |
| No selected `RecognitionService` for app-owned mode | App-owned dictation does not start. No implicit service is tried. | Select a voice-recognition service in Android settings. |
| Selected service not discoverable or removed | `ProviderUnavailable`; no transcript is written. | Re-enable/reinstall the selected provider or select another service. |
| Provider language/model unavailable | `ProviderUnavailable`; no automatic network/provider fallback. | Configure a supported language/model in the provider, or use another input path. |
| Network required but unavailable | `Network`; draft remains unchanged. | Restore connectivity or use a provider/IME with a downloaded local model. |
| Provider/IME controls differ from White Noise controls | White Noise cannot promise manual stop, cancellation, or navigation persistence. | Use the controls supplied by that provider/IME. App-owned controls are tracked by [#2198](https://github.com/marmot-protocol/whitenoise-android/issues/2198). |

Android exposes voice-input settings on builds that implement
`android.settings.VOICE_INPUT_SETTINGS`; keyboard configuration uses
`android.settings.INPUT_METHOD_SETTINGS`. A settings deep link must itself be
resolved before launch because some GrapheneOS/provider combinations omit the
voice-input screen.

## GrapheneOS and provider matrix

A row is a compatibility claim only after its probe and journey have been run
on that configuration. Do not infer support from the presence of an app icon or
IME alone.

| Configuration | Activity probe | Selected-service probe | Expected supported mode | Evidence status |
|---|---|---|---|---|
| GrapheneOS + Google speech provider with configured service | Record exact resolver output. | Record selected component and matching service. | Provider Activity now; app-owned mode only after [#2198](https://github.com/marmot-protocol/whitenoise-android/issues/2198) selects it. | Pending exact-head physical run. |
| GrapheneOS + Voice IME only | May be absent. | Do not assume Voice IME exports a `RecognitionService`; record actual output. | Keyboard/IME handoff. Provider Activity and app-owned mode are unsupported unless their independent probes pass. | Pending exact-head physical run. |
| GrapheneOS with no configured speech provider | Absent or non-launchable. | Empty/undiscoverable. | Typing and ordinary keyboard input only. | Pending exact-head physical run; deterministic unit coverage protects the draft. |
| API 36 provider-free emulator | Absent. | Empty/undiscoverable. | No White Noise speech mode. | Automated unavailable-path coverage. |

### Observed capability probe

- 2026-08-31, Pixel 6a (`bluejay`) running GrapheneOS: Offline Voice Input (`dev.notune.transcribe`) was the selected service. Package queries returned one exported `RecognitionService` and one `ACTION_RECOGNIZE_SPEECH` Activity. This proves both Android contracts are discoverable on that configuration; the exact-APK UI journeys remain pending.

## Capture the configuration

On a development device, record these values without changing provider or app
data. The commands below use the guarded Hermes physical-fixture client; other
development environments may substitute their ordinary `adb` client. Hold an
`android-device-session` for any stateful UI journey.

```sh
/opt/data/.local/bin/phone-adb shell getprop ro.product.manufacturer
/opt/data/.local/bin/phone-adb shell getprop ro.product.model
/opt/data/.local/bin/phone-adb shell getprop ro.build.version.release
/opt/data/.local/bin/phone-adb shell getprop ro.build.version.sdk
/opt/data/.local/bin/phone-adb shell getprop ro.build.fingerprint
/opt/data/.local/bin/phone-adb shell settings get secure voice_recognition_service
/opt/data/.local/bin/phone-adb shell cmd package query-activities -a android.speech.action.RECOGNIZE_SPEECH
/opt/data/.local/bin/phone-adb shell cmd package query-services -a android.speech.RecognitionService
```

Never disable, remove, or reconfigure a personal speech provider merely to
manufacture an unsupported row. Use provider-free test hardware or an emulator.

## Provider Activity physical journey

Use a test conversation and a recognizable phrase such as “dictation matrix
alpha.” Do not use a production message that might be sent accidentally.

1. Record the configuration probes above.
2. With TalkBack enabled, verify **Dictate text** remains distinct from **Hold to
   record voice message**.
3. With blank and nonblank drafts, tap the composer waveform. Confirm White
   Noise shows **Checking speech service…**, then **Opening speech service…**,
   and the configured provider Activity opens without showing White Noise's
   in-app listening strip. It must not claim **Loading model** or **Ready to
   listen**.
4. Cancel. Confirm the original draft and selection remain unchanged.
5. Complete recognition. Confirm text is inserted at the captured selection and
   remains editable; it must never send automatically.
6. Start again, navigate to another conversation/account, and complete. Confirm
   only the immutable origin draft changes.
7. Enable Android developer option **Don't keep activities**, launch and return
   once, and verify no duplicate provider launch or duplicate insertion. Disable
   the option immediately afterward.

## App-owned recognizer physical journey

Run this only on an exact head whose composer deliberately selects
`ConversationDictationMode.InApp` (the current composer does not).

1. Require the selected-service probe to return one exact matching component.
2. Start dictation and verify White Noise, rather than a provider Activity, owns
   Done/Cancel and session status.
3. Stop, cancel, navigate, background/foreground, and remove the origin target;
   confirm cancellation is bounded and late callbacks cannot write.
4. Repeat after configuring the provider's local model. Record observed offline
   behavior, but do not generalize it to other provider versions or languages.
5. Remove the local model only on disposable test hardware, then verify the
   provider's language/model error is surfaced without switching contracts.

## Regression hooks

The compatibility contract is anchored by:

- `app/src/main/AndroidManifest.xml`: visibility for both speech contracts.
- `ConversationDictationController.kt`: bounded provider-Activity readiness,
  selected-service probe, explicit component binding, failure mapping,
  cancellation, and immutable-origin writes.
- `ConversationDictationCompatibilityContractTest.kt`: malformed/missing/
  undiscoverable selected-service probes plus manifest/runbook anchors.
- `ConversationDictationControllerTest.kt`: app-owned mode selection,
  unavailable-provider mapping, cancellation, stale callbacks, and draft
  preservation.
- `ComposerDictationControlTest.kt`: the current composer selects provider
  Activity mode and does not silently enter the app-owned controller path.

Run focused coverage and the repository fast gate:

```sh
./gradlew --no-daemon \
  :app:testDevPlayDebugUnitTest \
  --tests 'dev.ipf.whitenoise.android.audio.ConversationDictationCompatibilityContractTest' \
  --tests 'dev.ipf.whitenoise.android.audio.ConversationDictationControllerTest' \
  --tests 'dev.ipf.whitenoise.android.ui.conversation.composer.ComposerDictationControlTest'
python3 /opt/data/scripts/hermes_test_gate.py --tier fast -- \
  ./gradlew --no-daemon :app:ktlintCheck :app:testDevPlayDebugUnitTest
```
