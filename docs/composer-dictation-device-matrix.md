# Composer dictation compatibility and device matrix

## Follow-up contract (stacked on PR 2496, 2026-09-06)

The composer resolves one eligible recognition service and chooses its capture
surface before recording. A version-keyed caller-audio capability check selects
app-owned dictation or the provider Activity. White Noise owns Cancel, Paste, Send and session lifecycle;
the installed provider owns recognition. The originating account, conversation,
draft revision and insertion anchor are captured before recording.

- Cancel discards this session without changing the draft or sending.
- Paste finishes recognition and inserts into the captured origin; it overrides
  the automatic delivery preference for this session.
- Send finishes recognition and sends once only after authoritative origin
  validation and a final draft check under the conversation commit lock. The
  captured draft clears when its pending row appears and is restored if delivery
  is not confirmed, without overwriting newer edits.
- The first completion choice wins. Paste and Send become disabled while that
  choice finishes; Cancel remains available until irreversible dispatch begins.
- After dispatch begins, the controls show a pending send and cannot cancel or
  retry it. An unconfirmed result retains the transcript with Copy and Discard,
  never automatic insertion or retry.
- Automatic silence completion uses the preference captured at session start.
  A provider endpointer alone does not finish manual dictation: a new recognition
  generation continues the same logical session. Repeated speech in separate
  generations is preserved; duplicate callbacks from an old generation are not.

While the origin conversation is visible, its composer owns the controls.
Elsewhere in the app, one persistent bottom bar owns them. The foreground
notification exposes the same three actions, with a unique process/controller
and session token. Stale notification actions cannot mutate another session.
Notification title and body never include the transcript, account or conversation.
Starting and processing use an indeterminate progress indicator in both app
control surfaces and the notification; listening does not show a loading spinner.
The status changes to pending only at the irreversible send boundary.
When app notifications or the dictation channel are disabled, an in-app notice
offers the relevant Android settings page and refreshes on return. In-app
dictation remains usable; the app never changes notification permissions itself.
Android explicitly permits foreground capture while
[hiding its notification drawer entry](https://developer.android.com/develop/ui/compose/notifications/notification-permission),
so running the service alone is not proof that the user can reach its actions.

Android's [progress guidance](https://developer.android.com/develop/ui/compose/components/progress)
distinguishes unknown-duration work from measurable completion. We have no
provider model-loading percentage, so never invent one. Its
[SpeechRecognizer contract](https://developer.android.com/reference/android/speech/SpeechRecognizer)
also warns against continuous recognition and requires recognizer destruction.
This feature therefore supports bounded, explicitly initiated sessions, with
generation cleanup, a session watchdog and no promise of uninterrupted or offline
recognition across every provider, OS restriction or process death.

## Android speech contracts

| Contract | Capture and UI owner | Capability and boundary |
|---|---|---|
| App-owned `SpeechRecognizer` | White Noise controls; provider recognition | Effective microphone access and one eligible `RecognitionService`; explicit component binding. |
| Provider Activity | Provider capture, endpointing and UI | An Activity resolving `android.speech.action.RECOGNIZE_SPEECH`, chosen before capture when the provider cannot accept caller audio. An active in-app recording never switches to this surface after failure. |
| Voice IME | Keyboard/IME capture and text commit | Ordinary editor input. White Noise does not control its recording lifecycle or promise immutable-origin routing. |

New gestures resolve a valid Android-selected RecognitionService first, then an
exact versioned White Noise choice, then a sole logical provider/engine. A missing
Android selection is not an instruction to pick the default speech Activity's app.
Ambiguity opens the provider chooser before microphone access. A stale saved choice
is cleared and requests a fresh choice; that gesture does not silently substitute
another provider. There is no silent fallback between providers during or between
sessions. Selecting or cancelling records nothing and preserves the draft;
tap dictation again after selection.

Settings → Dictation → Speech provider groups services, recognition Activities and
voice IMEs by package, retaining genuine service engines. Disabled, private,
test-only and improperly permissioned services are excluded; the exact FUTO
`org.futo.voiceinput/org.futo.voiceinput.DummyService` placeholder is excluded
without excluding its provider Activity or unrelated classes named DummyService.
Voice IMEs are discovered through declared voice subtypes, plus Gboard's known
voice-typing package. This identifies a keyboard surface, not proof that voice typing
is configured, available in every language, or a public speech service.

Saved choices contain package, longVersionCode and exact service/Activity/IME
components. Malformed and versionless records are not restored. Every gesture
refreshes installed state; the resolved identity and capture privilege are pinned
for that logical session. Removing/disabling/replacing a pinned component fails
without switching to another provider. Provider Activity intents constrain both
package and component; result registrations and callbacks are scoped to a request
ID and preserve the captured origin across navigation and cancellation.

Resolving a component does not establish caller-audio support. Android grants
microphone binding capabilities to selected, configured on-device and preinstalled
recognizers. Ordinary unselected providers need White Noise to supply audio through
`EXTRA_AUDIO_SOURCE` and must implement caller attribution and descriptor reading.
On supported Android versions, the first use of such a provider sends an empty
pipe before capturing audio. A result or no-match response establishes support;
permission/client rejection selects the provider Activity. The answer is cached
for the provider package and version code, so upgrading a provider rechecks it.
Inconclusive errors, including `ERROR_SERVER`, and timeouts are not cached and
continue through the in-app path, where the real failure remains visible. Android
versions without caller-audio extras use the provider Activity when needed.

This chooses one recording UI per gesture. Cancellation/backgrounding during the
check prevents recording and discards late callbacks. A missing provider or a
failed active recording offers recovery; it never opens a second recording UI.
Android 17 has no recognizer picker: opening the provider's app permits model/setup
recovery, while the system voice-input action can open an unrelated assistant role.

The provider Activity compatibility path is a distinct, visible provider-owned
screen. A bounded, cancellable 1.5-second availability check shows Checking speech
service before launch and Opening speech service only after resolution. These
labels do not claim a model is loaded. White Noise's background capture controls
do not apply to that screen. Returned text follows the captured delivery policy
and origin checks; a provider cancel leaves the original draft unchanged.

Offline preference is a request to the provider, not a network-isolation
guarantee. Service discovery cannot prove a model is downloaded or ready.
Language/model setup and downloads remain provider-owned. Offline Voice Input
engine failures offer Open speech service so the user can configure its model.
Unsupported language/model errors do not switch to another recognition service.

## Microphone access and failure recovery

Runtime RECORD_AUDIO permission, effective AppOps access and device microphone
privacy are separate gates. A missing runtime grant requests permission;
effective privacy denial with a grant shows microphone privacy recovery.
White Noise never changes the privacy switch itself.

If access is revoked during capture, a service permission error must not open
another recording surface. Stop the session, release microphone/foreground
ownership and show the matching permission/privacy error. A service-local
permission error with effective app access still granted gets one bounded native
retry after fresh safety checks. A service that rejects the retry as well cannot
serve app-owned capture on that device: release microphone and foreground
ownership and end the session as a provider access rejection. Do not open the
provider's recognition Activity in its place. Its floating surface takes over
the screen, its capture is not White Noise's to cancel, paste or send, and a
first tap that lands there while a later tap reaches app-owned capture makes the
control surface unpredictable. Offer the provider's own setup app instead.

If a later provider generation fails after useful text was accumulated, retain
that text for explicit review. An ambiguous draft merge offers Copy, Insert at end
or Discard; an uncertain send offers only Copy or Discard. Copying must not silently
discard retained text. A 30-minute session watchdog and bounded processing/send
timeouts prevent orphan recording or indefinite pending UI. Process death does
not persist transcript audio/text; a service restart must not resume old capture.

## Android 17 multi-provider acceptance matrix (not device results)

Use a disposable fixture with synthetic drafts and configured provider models. Do
not change personal provider selections to manufacture a result. Record Android
build, White Noise artifact, package longVersionCode and each exposed component.
Leave Android's recognition-service setting unset for the multi-provider case;
Android's assistant-role settings page does not establish a selected recognizer.
No device testing was performed for this provider-selection implementation.

| Provider family | Guarded expectation | Still required on the exact artifact |
|---|---|---|
| FUTO Voice Input | Exclude its exact internal DummyService; retain exported recognition Activities. Show Opens provider window only when one is available. | Confirm installed component names, model setup, explicit window routing, cancel and origin-safe result. No in-app compatibility claim without a real service and verified caller audio. |
| Google Speech Services | Enumerate the installed build's eligible services and Activities; preserve engine sub-choices. A valid Android-selected service wins. | Verify selected/unselected configurations, caller-audio verdict, version invalidation and provider setup. Installation alone proves no capability. |
| AOSP / GrapheneOS Speech Services | Discover actual components rather than assuming package identity or Google compatibility. Preinstalled capture privilege does not count as verified caller-audio support. | Verify the build's available surfaces, empty/no-model/error paths, exact identity pinning and cancellation. |
| Gboard voice typing | A keyboard surface is independent of RecognitionService/recognition Activity. Show Keyboard only when neither app surface exists. | Verify configured voice typing in the keyboard itself; it must not be auto-routed as an app speech service. Language/download/account availability is provider-owned. |

Run DIC-004 through DIC-006 for precedence, stale records, in-session preference
changes, explicit Activity routing, old callbacks, capability labels, empty/loading
states, screen-reader semantics, 200% font and RTL. Test two engines in one package:
a conclusive verdict for one component/version must never label the other supported.
Package-only legacy verdicts are ignored.

## Physical evidence requirements

A row is supported only after the exact artifact's journey passes; capability
queries, unit tests and a provider Activity success do not prove app-owned capture.

| Configuration | Capability evidence | Exact-head journey |
|---|---|---|
| Pixel 6a GrapheneOS + Offline Voice Input (`dev.notune.transcribe`) | Earlier 2026-09-07 diagnostics used a populated selected-service setting. That configuration was set through ADB and is not representative of a user-reachable Android 17 setup. | Historical diagnostic evidence only; do not reuse it as current acceptance. |
| Android with empty selected-service setting + caller-audio-capable Offline Voice Input | Owner tests on 2026-09-08 at White Noise `f4f71f1a88f082e5c9e66cc986b9f4e5e739b857` with provider `c689f00` returned transcripts in three consecutive sessions, dropped zero audio and released microphone/service ownership after each. | Configured-model caller-audio path passed. This precedes the capability router and is not device proof for its new head; missing-model recovery remains separate. |
| Provider without caller-audio support | The capability router chooses the Activity before real capture after a conclusive probe, or when Android lacks the audio-source extras. | Verify first-use check, cached route, cancellation and provider-upgrade recheck on an appropriate fixture. |
| Activity-only provider | Explicit compatibility controller coverage remains. An exact saved Activity or a sole Activity-only provider can route directly; multiple choices require the pre-microphone chooser. | Pending dedicated configuration. |
| Voice IME only / no speech provider | Independent Android contracts; IME presence is not evidence of a service or Activity. | Pending dedicated configuration; do not reconfigure personal providers to manufacture a result. |

Use the guarded fixture preflight and session tools. Record provider package
version, Android version/fingerprint, exact APK hash and source head. These
read-only probes are examples:

```sh
/opt/data/.local/bin/android-device-status --json
/opt/data/.local/bin/phone-adb shell getprop ro.build.fingerprint
/opt/data/.local/bin/phone-adb shell settings get secure voice_recognition_service
/opt/data/.local/bin/phone-adb shell cmd package query-services --brief -a android.speech.RecognitionService
/opt/data/.local/bin/phone-adb shell cmd package query-activities --brief -a android.speech.action.RECOGNIZE_SPEECH
```

### Bounded provider diagnostic, 2026-09-07

At source `030e2dba59c8da06875a118e5cafeb3d607b600f`, revoking the app's
microphone grant and starting dictation produced the expected Android permission
dialog; denial started no native capture, and the original grant was restored.
An already-granted permission correctly does not request another dialog.

A separate, freshly permitted client reproduced the installed provider's native
error 9 in both cold and resident processes. A self-attributed AudioRecord test
provider also failed before Ready. A correctly caller-attributed recorder reached
Ready and captured nonzero microphone samples twice through unchanged White Noise.
Registering only the client's own attribution source did not repair the original
provider. The temporary preview start-ordering experiment did not provide a fix
and has been removed; normal native startup remains immediate after foreground
service promotion and listener installation.

An isolated diagnostic adapter then used the original provider's byte-identical
native inference libraries and bundled model with correctly attributed microphone
capture. Through unchanged White Noise, actual speaker-to-microphone test speech
matched the expected text and Pasted directly into the self-test draft. Capture
continued while the system speech sample screen was foregrounded and the app
returned to the original conversation. A separate Cancel test preserved the draft
and released capture without inference. The original provider selection, privacy
setting and APKs were restored, the synthetic draft removed, and the diagnostic
app disabled. No message was sent.

These historical results isolated a provider/client compatibility boundary. The
2026-09-08 caller-audio result above supersedes the unsolved-cause status, but
neither result establishes all production lifecycle gates. The diagnostic adapter is one-shot, not a
replacement provider. Send, notification actions, prolonged manual silence,
lock/unlock and current-head full native acceptance remain unverified.

Never disable, remove or reconfigure a personal provider to manufacture an
unsupported case. Do not erase app data. Hold an `android-device-session` for
stateful UI input, wake/unlock without credentials, and sleep the screen afterward.
Use the audited exact-head stable-preview install workflow.

### App-owned journey

Use an explicitly disposable/self test conversation. Never send fixture text to
a real contact; where no safe send target exists, report that boundary.

1. With blank and nonblank drafts, start through Dictate text, distinct from
   Record voice message. Verify the app owns capture and all three actions are
   touch-accessible and correctly announced by TalkBack.
2. Dictate a recognizable test phrase, pause across provider endpointing, then
   dictate the same phrase again. Both segments must survive.
3. Cancel in the composer, persistent navigation bar and notification. Verify no
   draft mutation or message, and bounded microphone/service release.
4. Paste through each surface. Verify exact text in the origin draft, preserved
   insertion anchor and no send, including when Send is the stored preference.
5. Send only to the disposable/self target through each surface. Verify exactly
   one message and correct payload, including when Paste is the stored preference.
   Rapid repeated taps and retained old notifications cannot duplicate delivery.
6. Navigate away, press Home, lock/unlock and return during capture. Verify
   recording continuity and controls; a provider Activity opening is not a pass.
7. Change the origin draft or remove the origin while finalization is pending.
   Verify no wrong-target send or overwrite; retained text requires explicit review.
8. Exercise permission denial and global microphone privacy off/on separately,
   restoring original state. A privacy denial must not launch provider recording.
9. Verify uncertainty after dispatch retains Copy/Discard without resend; never
   induce a duplicate against a real conversation.
10. Capture current UI/notification screenshots and bounded PII-free diagnostic
    events. Check for crashes, stale actions and microphone ownership leaks.
11. Deny notifications or disable only the dictation channel. Verify the in-app
    settings notice, restore the original setting, return, and confirm both the
    notice dismissal and real drawer actions. Never claim drawer coverage while
    notifications remain hidden.

### Provider Activity journey

On an appropriate test configuration, confirm the readiness labels, one provider
launch, cancel preservation and one immutable-origin result. Repeat navigation and
Activity recreation without duplicate launch or insertion. Record provider-owned
controls honestly; their behavior does not satisfy the app-owned Home/notification
gate.

## Regression evidence

Focused tests cover controller lifecycle, service routing, stale tokens,
eligibility/pinning, effective microphone access, coordinator integration,
persistent/compact controls and root ownership. Roborazzi snapshots cover changed
composer/settings rendering and the navigation bar at normal and large-font RTL
sizes. Commit generated baselines and verify both DevZapstore and DevPlay variants.

Local tests are not device evidence or CI authority. Required GitHub checks and
preview provenance must match the exact pushed signed head. A formal GitHub
approval must come from an identity distinct from the PR author; never self-review
or merge this PR as part of the device workflow.

## Focused validation evidence for provider selection

Run from this worktree through the shared heavy slot; no broad suite is needed
for this handoff.

```bash
/opt/data/scripts/hermes_test_gate.py --tier focused -- /opt/data/bin/hermes-heavy-run ./gradlew --offline --no-daemon --max-workers=2 :app:testDevZapstoreDebugUnitTest \
  --tests 'dev.ipf.whitenoise.android.audio.ConversationDictationProvidersTest' \
  --tests 'dev.ipf.whitenoise.android.audio.ConversationDictationServiceResolutionTest' \
  --tests 'dev.ipf.whitenoise.android.audio.ConversationDictationCallerAudioSupportTest' \
  --tests 'dev.ipf.whitenoise.android.audio.ConversationDictationCallerAudioRequirementTest' \
  --tests 'dev.ipf.whitenoise.android.audio.ConversationDictationCompatibilityContractTest' \
  --tests 'dev.ipf.whitenoise.android.audio.ConversationDictationControllerTest' \
  --tests 'dev.ipf.whitenoise.android.state.ConversationDictationPreferencesTest' \
  --tests 'dev.ipf.whitenoise.android.ui.WhiteNoiseAppDictationTest' \
  --tests 'dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationCoordinatorTest'
```

Screenshots were recorded, inspected, and verified on DevZapstore:

```bash
/opt/data/scripts/hermes_test_gate.py --tier focused -- /opt/data/bin/hermes-heavy-run ./gradlew --offline --no-daemon --max-workers=2 :app:verifyRoborazziDevZapstoreDebug \
  --tests 'dev.ipf.whitenoise.android.ui.screenshot.DictationProviderScreenshotTest'
/opt/data/scripts/hermes_test_gate.py --tier focused -- python3 scripts/check_manual_test_guide.py
/opt/data/scripts/hermes_test_gate.py --tier focused -- python3 -m unittest scripts/test_check_manual_test_guide.py
```

PNG baselines committed for this stage: `dictation_provider_unknown_light.png`,
`dictation_provider_window_large_rtl_dark.png`, `dictation_provider_empty_light.png`,
`dictation_provider_loading_light.png`, `dictation_provider_verified_light.png`,
and `dictation_provider_capabilities_light.png`. Device matrix rows above remain
pending; JVM or screenshot results must not be described as Android 17 device proof.
