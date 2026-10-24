# Quiet background synchronization (#3290)

Status: **incomplete implementation; no production controls or scheduling are
connected yet**. The policy tests in this draft cover ownership decisions and
migration only. They do not prove delivery, cancellation, battery use, or issue
closure. See [#3290](https://github.com/marmot-protocol/whitenoise-android/issues/3290).

## Implemented decision boundary

Message notifications remain an MDK setting per account. Background
synchronization will be an Android preference for the device. Aggregate attention
across signed-in accounts before deciding which device-wide work owner to use:

| Background sync | Any account can alert | Owner |
| --- | --- | --- |
| Off | Either | Foreground/manual actions only |
| On | Yes | Existing supported FCM or persistent delivery |
| On | No, including OS permission denial | Quiet periodic work |

Do not change the retained FCM/persistent choice when pausing background work.
An incomplete MDK settings read defers owner reconciliation; it is not evidence
that every account disabled alerts. With no signed-in account, there is no
reason to bootstrap a periodic message-sync runtime.

For migration, explicit new consent wins. Otherwise preserve a persistent
connection or native-push intent from any signed-in account. A false persistent
preference is insufficient to infer opt-out: FCM uses that value too. If neither
an explicit preference nor persistent intent resolves migration and native-push
settings cannot be loaded, leave migration pending. Users with both old delivery
transports disabled must not be enrolled in quiet work.

`BackgroundSyncPolicyTest` covers these decisions. The production entry points
still have their existing behavior.

## Runtime integration that must precede the controls

The inspected Android base is
[`b76ad1dcf`](https://github.com/marmot-protocol/whitenoise-android/tree/b76ad1dcf513e76e74a140187e4292cfb8c24bd8).
Its pinned MDK is
[`3fb4b4759ac676b0c21c4c3527074866c9fa4c89`](https://github.com/marmot-protocol/mdk/tree/3fb4b4759ac676b0c21c4c3527074866c9fa4c89).
Source inspection establishes these constraints; no physical-device observation
is claimed:

- [MDK start](https://github.com/marmot-protocol/mdk/blob/3fb4b4759ac676b0c21c4c3527074866c9fa4c89/crates/marmot-app/src/runtime/mod.rs#L1695)
  starts asynchronous relay activation and catch-up. Ending a WorkManager job or
  releasing the Android foreground-service connection does not itself stop these
  native workers.
- [MDK shutdown](https://github.com/marmot-protocol/mdk/blob/3fb4b4759ac676b0c21c4c3527074866c9fa4c89/crates/marmot-app/src/runtime/mod.rs#L897)
  latches the runtime into stopping. Calling `start()` on that same handle is not
  a supported resume path. The
  [terminal close contract](https://github.com/marmot-protocol/mdk/blob/3fb4b4759ac676b0c21c4c3527074866c9fa4c89/crates/marmot-uniffi/src/lib.rs#L460)
  requires a fresh handle after `shutdownAndClose()`.
- Android's [bootstrap owner](../../app/src/main/java/dev/ipf/whitenoise/android/state/StartupCoordinator.kt)
  currently retains one started runtime. Its replacement path is for failed
  initialization, not normal background retirement. A timeout on a caller's
  wait does not cancel the process-owned bootstrap/catch-up attempt.
- [Attachment workers](../../app/src/main/java/dev/ipf/whitenoise/android/state/AttachmentDownloadWorker.kt)
  and [user-initiated transfers](../../app/src/main/java/dev/ipf/whitenoise/android/state/AttachmentUserInitiatedDownloadService.kt)
  use this same runtime. Closing it solely because message sync is disabled
  would also invalidate those operations. The foreground-service host also
  serves dictation; releasing its connection must preserve the dictation owner.

A correct implementation needs explicit ownership for bounded native work and
safe retirement/reopening of the one process runtime. It must account for
foreground UI, notification actions, transfers and dictation, reject stale
callbacks, and rebind subscriptions after replacement. Runtime leases alone are
insufficient: keeping a started runtime alive for a transfer also keeps its
message workers alive. Skipping `start()` is not a verified messaging-free media
path either: native `download_media` routes through account-worker reconciliation.
A reviewed MDK messaging-network suspension contract could preserve local access
and independent attachment work. Any alternative must demonstrate that same
separation; the current source does not establish it. Do not solve this by constructing a second concurrent runtime,
calling `shutdown()` followed by `start()` on the same handle, or claiming a
coroutine timeout bounds native work.

## Remaining implementation and evidence

1. Establish and test native/runtime ownership, including foreground arrival
   during close, worker cancellation during bootstrap/catch-up, replacement
   failure, and active attachment/notification-action leases. Explicitly test
   **background attachment active + message sync disabled + no autonomous
   message/invite relay work**. Do not call leases alone sufficient, and keep
   attachment consent independent. If the required capability belongs in MDK, adopt its
   reviewed published artifact before using it in Android.
2. Persist independent sync consent after authoritative migration. Retain the
   selected transport, including multi-account native-push intent. Use the
   existing delivery-intent and runtime generations to reject A-B-A callbacks.
3. Reconcile one regular WorkManager owner with connected-network and
   battery-not-low constraints. Evaluate a one-hour interval and 15-minute flex
   against #2786; these are a candidate budget, not measured acceptance or a
   delivery guarantee. Disable/cancel under the same admission boundary used by
   push, boot, token rotation, service and reconnect paths. Do not expedite quiet
   work or route it through a foreground service.
4. Add Message notifications (this account) and Background synchronization
   (this device) controls. Explain delayed quiet delivery, foreground-only
   behavior, and OS permission/Doze/force-stop limitations. Clear/fence pending
   alerts on opt-out and reconcile backlog without replaying it on re-enable.
5. Extend `NotificationDeliveryModeRecoveryTest`, `PushWakeRecoveryWorkerTest`
   and `PushWakeWorkManagerTest` at their real entry points. Add persistence,
   cancellation, recreation and screenshot coverage plus permanent manual
   checklist/source fragments when those surfaces exist.
6. On a retained-data device installation, measure actual quiet message/invite
   receipt, native wake duration, fully disabled background behavior and prompt
   foreground recovery. A scheduled WorkSpec or green policy test is not delivery
   evidence. Preserve all installed identities and history.

This draft deliberately does not use a closing keyword for #3290. The controls,
work admission, runtime lifecycle and device acceptance remain required work.
