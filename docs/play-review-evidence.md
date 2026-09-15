# Play review evidence

Use this alongside [the release runbook](android-release-pipeline.md) and the
[manual testing guide](manual-release-testing.md). This is an operator guide,
not a second product backlog. Play launch work is tracked in
[#2127](https://github.com/marmot-protocol/whitenoise-android/issues/2127).

## Recordings for the first closed release

Google's [foreground-service declaration guidance](https://support.google.com/googleplay/android-developer/answer/13392821)
requires a review-accessible video demonstrating each declared service feature,
including the user action that starts it. Use synthetic identities, messages,
and media; never expose real conversations, recovery keys, tokens, or accounts.
Record the candidate version and source commit alongside the links. A written
description or a screenshot does not replace a working demonstration.

| Permission | Recording sequence | Existing manual coverage |
| --- | --- | --- |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Open a synthetic text message, choose Speak, go Home while speech continues, show the playback notification and pause/resume/stop controls, then stop and show playback ends. | TTS-001 |
| `FOREGROUND_SERVICE_MICROPHONE` | Show the dictation disclosure and explicit consent, start dictation from a synthetic conversation, speak synthetic text, go Home and show the microphone indicator and foreground controls, then finish/cancel and show microphone access ends. | DIC-001, DIC-002 |
| `FOREGROUND_SERVICE_REMOTE_MESSAGING` | Enable the background message connection in notification settings, show its ongoing notification, go Home, deliver a synthetic message from another test identity/device, then stop the connection using the visible control. | Notification section |
| `FOREGROUND_SERVICE_SPECIAL_USE` | With the background connection explicitly enabled, restart the device or update the app, show restoration and its ongoing notification, receive a synthetic message, then stop it. If screen recording cannot span restart, use an external camera with the test device only in frame. | Notification section |

A single accessible video can cover multiple features if each sequence is clear.
Check every link as a reviewer without access to your private account. Retain the
original recording; do not substitute generated or simulated evidence.

## Foreground-service form explanations

Use Media playback for playback and Background audio input for dictation
(the current Console label; Google’s guidance also calls it Background Audio Access).
White Noise's remote-messaging behavior is a background relay connection, not a
phone-to-web SMS bridge. Describe that actual behavior under Other instead of
claiming a device-continuity feature the app does not implement. Google reviews
whether the declared foreground-service type fits the use case; source behavior
and a completed form do not establish policy approval.

Remote messaging:

> White Noise maintains a user-enabled encrypted messaging connection to Nostr
> relays while the app is in the background. An ongoing notification exposes the
> running connection and a Stop action. Deferring the connection delays incoming
> message notifications; interruption disconnects live subscriptions until
> recovery. The user can turn the connection off. This is background message
> delivery, not a phone-to-web SMS bridge.

Special use:

> After device boot or an app update, White Noise restores the background
> encrypted-message connection only when the user previously enabled it. An
> ongoing notification identifies the connection and lets the user stop it.
> Deferring restoration delays incoming message notifications until the app
> reconnects. The special-use type is limited to these system-wake starts; it
> does not start an unrequested recording or playback session.

The owning implementation is `NotificationStreamForegroundService.kt`,
`TtsPlaybackForegroundService.kt`, and `ConversationDictationForegroundService.kt`.
Recheck the candidate manifest and these paths before reusing an explanation.

## First closed release and managed publishing

1. Complete Data safety, privacy/deletion links, app access, foreground-service,
   and photo/video declarations against the exact candidate. Keep a draft if
   required evidence is missing.
2. Confirm the intended closed track, tester list, and countries. A production
   country selection is not a production release.
3. The automated pipeline uploads only to internal testing. In a separate manual
   Play Console step, promote the verified candidate AAB from the library to the
   closed track; do not rebuild it. The pipeline does not publish this closed
   release. Check the release summary explicitly names the closed track before
   submission.
4. With managed publishing off, an approved closed release may become available
   to its eligible testers automatically. Do not create or submit a production
   release during this preparation.
5. Once the first closed release is published, revisit Publishing overview and
   attempt to enable managed publishing. Read back the enabled state. Google can
   block enabling it before the first publication; a failed attempt is not an
   active hold on a future production release.
6. Record the review status and destination readback with the release receipt.
   Closed-review approval and permission approvals are distinct from production
   approval and public launch.
