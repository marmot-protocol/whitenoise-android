# Conversation controller ownership

The conversation-only extraction in [#3244](https://github.com/marmot-protocol/whitenoise-android/issues/3244)
separates timeline lifetime from transient mutation and attachment presentation.
The chat-list projection/action owner from #3242 and #3318 remains separate.

## Responsibility and lifetime map

| Owner | Owns | Ends when |
| --- | --- | --- |
| `ConversationController` | Immutable account/group/runtime binding, visible projection, group/member presentation, command facade, and the single guarded atomic native page + sidecar commit | Screen disposal retires its visible state; app-owned command results may still settle their captured account/group state. |
| `ConversationTimelineSession` | Start/retry admission, controller read scope, per-attempt scope, bounded initial open/snapshot, reconnect wait, installed subscription handles, foreground handoff receipt | Attempt finalization retires its handles; account teardown fences future opens; screen disposal cancels reads and receipts. Paging and close share the same active-call mutex. |
| `ConversationMutationPresentation` | Transient mutation slot, errors, archive overlay identity and the existing group-authority counter | Each command releases its own slot/overlay in `finally`; timeline reconnect/disposal does not own command execution. |
| `ConversationAttachmentPresentation` | Row transfer observers, availability waiters, open-intent adapters, pinned cache/source identities and bounded byte/thumbnail adapters | Screen disposal cancels presentation waiters. Native downloads/retries/cancellation acknowledgements remain app-owned. |
| Existing `inviteStreamScope` | Post-accept warm-up and agent stream observation | Controller disposal, never a single failed/ended timeline attempt. |
| Existing AppState/native owners | Optimistic sends/edits, retained upload bytes, durable command acceptance, transfer acquisition/retry and encrypted caches | Existing command/native lifetime, including acknowledgement arriving after screen disposal. |

`runStart` installs the session's current read scope inside its load-error boundary.
Roster prefetch and markdown hydration launched through that scope remain children
of the same guarded operation. A child failure becomes a conversation-load error;
retirement still releases each installed subscription handle once. The retirement
callback clears the window sidecar under the shared lock, while the active-call
mutex prevents closing a handle during an admitted page request.

The foreground retention timer also runs in this read scope. It requests the
existing MDK retention sweep; MDK retains policy, validation and persistence.

## Accepted commands and presentation

The facade's `withMutationLockResult` delegates to `runIfIdle`: overlapping calls
are dropped, and a failed or cancelled call releases its slot. Group applications
advance the existing group-authority counter; a late archive result cannot
overwrite a newer application.

An accepted archive command retains its existing acceptance and recovery behavior
after screen disposal. If its group-authority receipt still matches, its returned
record settles the captured account's chat list before its optimistic overlay is
removed. Only assignment to the retired controller's `group` field is fenced.
Success notices keep their conversation destination, and failures retain the
existing account/runtime-gated feedback. Local-removal feedback keeps its separate
app-owned path from #3322.

Attachment slot keys share one definition with the facade's cache adapters. Cache
keys use the account/group captured by the attachment presentation. UI waiter
retirement does not cancel an MDK-accepted acquisition, and a replacement observer
can join that same native operation through the AppState download owner from #3315.

## Regression boundaries

`ConversationArchiveOptimisticAcceptanceTest` covers acceptance, authority
precedence, overlay rollback, captured-account list settlement and late feedback.
`ConversationTimelineReconnectIntegrationTest` covers replacement snapshots,
page/teardown serialization and child-load failure handling.
`MediaDownloadHostRegressionTest` exercises the extracted attachment request entry
while native acquisition is held and the first observer is disposed. The separate
app-owned retry-admission case is complementary coverage.

Manual scenarios remain under their permanent IDs: CHL-007 for archive/restore,
MSG-007 for edits, MSG-021 for paging, CON-015/CON-033 for lifecycle transitions,
and MED-008/MED-009 for attachment opening and transfer actions. Revision-specific
CI, review and signed-APK/device results belong in the PR qualification record.
