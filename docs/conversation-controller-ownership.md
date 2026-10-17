# Conversation controller ownership (#3244)

This is the conversation-only implementation of the [researched controller plan](https://github.com/marmot-protocol/whitenoise-android/issues/3244).
The chat-list projection/action extraction in #3242 remains separate.

## Source and overlap audit

The extraction starts at master `50fb85b39647d89700aace5b8217fc33fc06ed77`.
Before editing, #3244 was assigned only to `mubarakcoded`, with no implementation PR;
Project 7 recorded it In Progress. The source overlap was checked against:

| Work | Inspected revision | Boundary preserved here |
| --- | --- | --- |
| #3318, Datawav's subscription owner | `32a924b03924ae9c5216d7405e8d082e65036441` | Chat-list source is untouched; no replacement list subscription owner. |
| #3322, local removal and presentation fixes | `94b7c90689a8b0ef49e454d82ed92818f0fdf073` | Leave `forgetGroupLocal`, `runLocalGroupRemoval`, and runtime-scoped removal notices in the facade; no blanket suppression of app-wide removal feedback. |
| #3315, background media downloads | `34a2f8d624536664004e07675d652002e5688b45` | AppState scheduler, durable download work and native transfer ownership are untouched. |
| #3335, pending MarmotKit adoption | `8b2b3c6f8a27faad32a78f6971310505c2f6303f` | No native pin/binding change is imported. Recheck the complete lock before requesting previews. |

The controller's `withMutationLockResult` remains the shared admission entry for
#3322's removal changes. It delegates to the extracted presentation owner with the
same single-flight semantics. Those active changes can rebase without rewriting
their native/cleanup paths. Reconcile this map again before readiness if their heads
or master change; it is source evidence, not contributor approval or merge proof.

## Responsibility and lifetime map

| Owner | Owns | Ends when |
| --- | --- | --- |
| `ConversationController` | Immutable account/group/runtime binding, visible projection, group/member presentation, command facade, and the single guarded atomic native page + sidecar commit | Screen disposal retires publication; app-owned command results may still settle their retained account/group state. |
| `ConversationTimelineSession` | Start/retry admission, controller read scope, per-attempt scope, bounded initial open/snapshot, reconnect wait, installed subscription handles, foreground handoff receipt | Attempt finalization retires its handles; account teardown fences future opens; screen disposal cancels reads and receipts. Paging and close use the same active-call mutex. |
| `ConversationMutationPresentation` | Transient mutation slot, errors, archive overlay identity, existing authoritative group-application receipt | Each command releases its own slot/overlay in `finally`; timeline reconnect/disposal does not own command execution. |
| `ConversationAttachmentPresentation` | Row transfer observers, availability waiters, open-intent adapters, pinned cache/source identities and bounded byte/thumbnail adapters | Screen disposal cancels presentation waiters. Native downloads/retries/cancellation acknowledgements remain app-owned. |
| Existing `inviteStreamScope` | Post-accept warm-up and agent stream observation | Controller disposal, never a single failed/ended timeline attempt (#279). |
| Existing AppState/native owners | Optimistic sends/edits, retained upload bytes, durable command acceptance, transfer acquisition/retry and encrypted caches | Existing command/native lifetime, including acknowledgement arriving after screen disposal. |

No second subscription, message store, durable Kotlin state, retry policy, generation
authority, API migration, or visual redesign is introduced. The existing authority
receipt moved with mutation presentation; page staleness and native window ownership
still use their existing guards. Retirement claims each installed handle once under
the session monitor, so account teardown and an attempt finalizer cannot close it twice.

## Evidence required

New controller-boundary cases hold roster initialization across repeated start calls,
hold an admitted page across account teardown, and dispose a pinned controller while
native send acknowledgement is pending. Existing reconnect, first-frame handoff,
archive precedence/cancellation, reply identity, edit and attachment suites remain
part of hosted validation. Structural gates follow the actual extracted mutex and
retirement implementation rather than a stale source location.

Run compilation, full unit/screenshot verification, lint and analysis on GitHub per
`docs/ci-request-policy.md`. Local checks are limited to source/document validators.
No test has passed merely because it has been added.

Before readiness, record the exact-head hosted results and independent reviews;
inspect actionable review threads and docstring coverage. For physical qualification,
use the exact signed current-head APK and retain app data: populated in-place upgrade
from the current master runtime, a separate stopped-process cold restart, then rapid
chat/account switches, paging/reconnect, pending send/edit/archive, and attachment
open/retry/cancellation. Device and hosted evidence are separate. No device PASS is
claimed by this source map.
