# Dictation completion and foreground ownership

Android owns capture, editor presentation and foreground-service lifecycle;
MDK continues to own draft persistence, target validation and message dispatch.

`NotificationStreamForegroundService` is the sole Android foreground service
for connection work and app-owned dictation. `ConversationForegroundRecord` owns its independent leases and ordered posts.
Its dictation delegate renders
only the controller's current, opaque session token. Connection and microphone
work hold independent leases; releasing one keeps the other alive. The host
publishes the union of their actual foreground-service types. Boot/sticky
connection recovery never acquires a microphone lease. Connection Stop reads
ownership on Main, leaves a queued microphone start alone, and is fenced by
newer connection requests; an old User toggle cannot bootstrap after it was
disabled while the host was still being created.

The ongoing notification retains ID 1001. Active capture and final transcription
show dictation controls; failed recovery shows only Open app. After successful
completion, dismissal or recovery expiry, it shows the ordinary connection card,
or is removed if no foreground work remains. Every promotion and removal uses
this same service record on Android's main thread. Connection refreshes do not
retain a separate copy of dictation controls, and no restoration callback can
re-publish an old session after completion. This matters because Android queues
foreground notification posts and retains the submitted notification in the
service record. See [ServiceRecord](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/am/ServiceRecord.java)
and [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/launch).

`ConversationDictationCompletionIntent` distinguishes an automatic endpoint
choice from an explicit gesture and a committed delivery. Silence seals capture
and selects the configured default. Until delivery commits, the first explicit
Paste or Send overrides that default; another explicit gesture cannot change
it. The controller remains the owner of capture/drain barriers, generation
fences and cancellation. The foreground card and in-app controls read that
same state; `ConversationDictationNotification` renders the drawer controls
without owning lifecycle or recognition.

Paste writes the transcript to the draft. Send follows the existing immutable
origin payload, reply, account, target and semantic draft-generation fences.
A pre-transport rejection becomes `SendBlocked`, retaining the transcript and
original draft separately. Retry explicitly submits the retained transcript
against the unchanged origin without recording again. If the composer has changed,
an explicit Paste recovers the transcript into the latest draft for review; Retry
cannot absorb another writer’s text or attachment generation. A dispatch
whose acceptance is uncertain remains `DeliveryUnknown` and offers no resend.
Send never reports success by silently performing Paste. If recognition stopped with only a recognized prefix, Retry Send first requires an explicit choice to send that text or Paste it for review; provider settings remain accessible.

Tests exercise captured asynchronous notification posts/removals across both
startup orders and all three actions, independent lease removal, microphone
promotion rejection, explicit choices during automatic final-drain processing,
blocked Send/retry and uncertain transport. Physical qualification additionally
checks the actual drawer/service records and provider on the exact PR artifact.
The permanent manual matrix is DIC-002 in
[manual-release-testing.md](manual-release-testing.md).

A rejected dispatch may advance only its draft revision after its own conditional
rollback succeeds. The draft adapter returns the generation accepted by that
write, rather than sampling the latest generation afterward. Retry Send keeps
that exact fence, so even a concurrent same-text attachment mutation blocks it.
The account, conversation, reply and captured text remain the original payload.

Capture, processing and recovery are separate phases of one logical foreground
lease. The microphone type is removed only after native recorder closure is
acknowledged. A failed transcription retains its bounded volatile PCM and text
under non-microphone `specialUse` protection for up to 30 minutes, showing only
“Dictation needs attention” and Open app. Recovery never leaves recording
Cancel/Paste/Send controls behind. Explicit dismissal, successful delivery,
Paste recovery or expiry ends that lease and wipes remaining PCM. Expiry leaves
one ordinary dismissible notice. The recovery deadline uses elapsed time, including
deep sleep. Timer delivery, foreground return, native closure and recovery actions
check that deadline before reattaching or accessing retained data. Cleanup runs
when a callback executes; no wake lock or exact-alarm permission is added.
Retry transcribes the sealed recording without
opening a new microphone. If Android destroyed the service, a foreground return
can reattach recovery; a rejected reattach preserves the existing failure/data. Every native closure acknowledgment uses the same logical-session fence. Returning before closure defers recovery reattachment and leaves the watchdog armed; leaving the foreground cancels that deferred request.

While an authorized microphone type is active, connection wakes, registration
sync, toggles and presentation refreshes update the notification without calling
`startForeground` with microphone again. Ordinary type bits may conservatively
remain on the existing record until capture closes. A rejected narrowing keeps
that record protected, replaces its obsolete presentation and retries narrowing
on a foreground return. It never detaches and restarts from background, or
changes the user's Keep connected preference.

Android 14 and later allow an unlocked user to swipe away an ongoing notification.
There is deliberately no delete/cancel callback: hiding presentation must not
stop capture, lose the completion intent or change Send to Paste. In-app controls
remain authoritative, and a later phase may update the card. This differs from
Android's foreground-service Task Manager Stop action, which stops the process
without callbacks. See [Android 14 notification dismissal](https://developer.android.com/about/versions/14/behavior-changes-all#non-dismissable-notifications)
and [Task Manager Stop](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping).

Recovery is volatile and never writes raw audio to disk. Force-stop, reboot,
Android Task Manager Stop or exceptional process termination can lose it; foreground
protection does not promise persistence across those operations. Removing the
Activity from recents does not discard recovery (`stopWithTask=false`). The
one-second closure watchdog interrupts a stalled recorder instead of merely
assuming closure, while normal completion retains the existing bounded tail.
Logical session/lease generations and a separate notification-action generation
reject obsolete controls after a failure and retry.

## Special-use declaration

The manifest's special-use subtype covers the user-enabled encrypted connection,
user-initiated dictation/transcription, and bounded on-device recovery. For Play
Console review, the declaration should explain that the user starts dictation,
may leave the app while recognition completes, and can recover unfinished text
or sealed audio after a provider failure. Recovery remains visible via a generic
Open app notification, uses no microphone after capture closes, has a 30-minute
maximum and ends earlier on delivery, Paste or dismissal. This document is the
technical justification; distribution approval is not inferred from tests.
