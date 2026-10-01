# Dictation completion and foreground ownership

Android owns capture, editor presentation and foreground-service lifecycle;
MDK continues to own draft persistence, target validation and message dispatch.

`NotificationStreamForegroundService` is the sole Android foreground service
for connection work and app-owned dictation. Its dictation delegate renders
only the controller's current, opaque session token. Connection and microphone
work hold independent leases; releasing one keeps the other alive. The host
publishes the union of their actual foreground-service types. Boot/sticky
connection recovery never acquires a microphone lease. Connection Stop reads
ownership on Main, leaves a queued microphone start alone, and is fenced by
newer connection requests; an old User toggle cannot bootstrap after it was
disabled while the host was still being created.

The ongoing notification retains ID 1001. While a microphone lease is live,
it shows dictation controls; afterward it shows the ordinary connection card,
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
same state.

Paste writes the transcript to the draft. Send follows the existing immutable
origin payload, reply, account, target and semantic draft-generation fences.
A pre-transport rejection becomes `SendBlocked`, retaining the transcript and
original draft separately. Retry explicitly submits the retained transcript
against the unchanged origin without recording again. If the composer has changed,
an explicit Paste recovers the transcript into the latest draft for review; Retry
cannot absorb another writer’s text or attachment generation. A dispatch
whose acceptance is uncertain remains `DeliveryUnknown` and offers no resend.
Send never reports success by silently performing Paste.

Tests exercise captured asynchronous notification posts/removals across both
startup orders and all three actions, independent lease removal, microphone
promotion rejection, explicit choices during automatic final-drain processing,
blocked Send/retry and uncertain transport. Physical qualification additionally
checks the actual drawer/service records and provider on the exact PR artifact.
The permanent manual matrix is DIC-002 in
[manual-release-testing.md](manual-release-testing.md).
