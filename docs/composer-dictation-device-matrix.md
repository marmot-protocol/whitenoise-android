# Composer dictation compatibility and device matrix

## Follow-up contract (stacked on PR 2496, 2026-09-06)

The composer starts app-owned dictation when one eligible recognition service
can be resolved. White Noise owns Cancel, Paste, Send and session lifecycle;
the installed provider owns recognition. The originating account, conversation,
draft revision and insertion anchor are captured before recording.

- Cancel discards this session without changing the draft or sending.
- Paste finishes recognition and inserts into the captured origin; it overrides
  the automatic delivery preference for this session.
- Send finishes recognition and sends once only after authoritative origin
  validation and a final draft check under the conversation commit lock.
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
| Provider Activity | Provider capture, endpointing and UI | An Activity resolving `android.speech.action.RECOGNIZE_SPEECH`. Compatibility path when no service can be used, or an empty-transcript service-attribution permission failure occurs while White Noise still has effective microphone access. |
| Voice IME | Keyboard/IME capture and text commit | Ordinary editor input. White Noise does not control its recording lifecycle or promise immutable-origin routing. |

There is no silent fallback to an arbitrary recognition service. If Android has
an explicit selected component, only that component is eligible. With no selected
component, prefer a single eligible service in the resolved speech Activity's
package; otherwise require exactly one eligible installed service. Disabled,
unexported and application-disabled services are excluded. The resolved component
is pinned and rechecked before each recognition generation; it is never replaced
mid-session merely because the selected setting changes.

The provider Activity compatibility path is a distinct, visible provider-owned
screen. A bounded, cancellable 1.5-second availability check shows Checking speech
service before launch and Opening speech service only after resolution. These
labels do not claim a model is loaded. White Noise's background capture controls
do not apply to that screen. Returned text follows the captured delivery policy
and origin checks; a provider cancel leaves the original draft unchanged.

Offline preference is a request to the provider, not a network-isolation
guarantee. Service discovery cannot prove a model is downloaded or ready.
Language/model setup and downloads remain provider-owned. Unsupported
language/model errors do not switch to another recognition service.

## Microphone access and failure recovery

Runtime RECORD_AUDIO permission, effective AppOps access and device microphone
privacy are separate gates. A missing runtime grant requests permission;
effective privacy denial with a grant shows microphone privacy recovery.
White Noise never changes the privacy switch itself.

If access is revoked during capture, a service permission error must not open
another recording surface. Stop the session, release microphone/foreground
ownership and show the matching permission/privacy error. Only a service-local
permission error while effective app access remains granted can use the
empty-transcript Activity compatibility path.

If a later provider generation fails after useful text was accumulated, retain
that text for explicit review. An ambiguous draft merge offers Copy, Insert at end
or Discard; an uncertain send offers only Copy or Discard. Copying must not silently
discard retained text. A 30-minute session watchdog and bounded processing/send
timeouts prevent orphan recording or indefinite pending UI. Process death does
not persist transcript audio/text; a service restart must not resume old capture.

## Physical evidence requirements

A row is supported only after the exact artifact's journey passes; capability
queries, unit tests and a provider Activity success do not prove app-owned capture.

| Configuration | Capability evidence | Exact-head journey |
|---|---|---|
| Pixel 6a GrapheneOS + Offline Voice Input (`dev.notune.transcribe`) | 2026-09-06 fixture probe found `.VoiceRecognitionService` and `.RecognizeActivity`, with selected service set. | Pending for the updated candidate. |
| Android with empty selected-service setting | Deterministic resolver tests cover the sole service, matching Activity package and ambiguous providers. | Pending physical verification; do not infer from the configured fixture. |
| Activity-only provider | Deterministic controller/coordinator fallback coverage. Earlier published-head user logs show Activity-returned text, not background capture. | Pending for the updated candidate. |
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
