# Notification enrichment ownership

The first slice of [#3320](https://github.com/marmot-protocol/whitenoise-android/issues/3320)
separates optional picture-work execution from the presenter's publication transaction.
The initial post, payload construction, live-card rewrite and dismissal keep their
existing owners. Publication-resource extraction remains a follow-up under the issue.

## Responsibilities

| Owner | Responsibility |
| --- | --- |
| `LocalNotificationPresenter` | Initial publication, picture resolution and content, live-card/message checks, shortcuts, write pacing and platform effects |
| `NotificationEnrichmentRunner` | Inline versus detached execution and the detached operation's retained show reference |
| `ConversationCardPostSynchronizer` | Shared show registration, account/group scope, current-show/dismissal receipts and per-card serialization |
| `NotificationCardGenerations` | Reference-counted card generations and displayed-generation dismissal |
| Existing AppState/native pipeline | Authoritative notification inputs and captured runtime/account/lock eligibility |

The presenter passes its existing `enrichmentLauncher` to the runner. Production
still uses the same process-owned scope. The runner creates no scope, queue, epoch,
cache or platform-notification policy. `runOrLaunch` receives the existing show
token and an operation that retains the presenter's content and eligibility checks.

## Execution and settlement

Ready-picture work runs inline in the original caller and takes no detached
reference. Exceptions and cancellation propagate to that caller as before.

Work needing a remote picture first calls `retainShow`. A stale or dismissed show
is refused without launching. An admitted operation releases only that retained
reference when its executed body finishes, fails or is cancelled. Synchronous
launcher cancellation also releases and propagates; a launcher RuntimeException
releases and is contained. Detached body Exceptions other than cancellation keep
the existing debug-only diagnostic path; cancellation propagates. The cleanup is
atomic and runs once even if a launcher executes the body and then throws.

The initial registration remains owned by `withRegisteredShow` and its `finally`.
An older ordinary message can still contribute its initial post/history while a
newer message exists; only enrichment/replacement requires newest-show validity.
The runner does not substitute a new generic current/disposed check for these rules.

The existing launcher returns `Unit`. This extraction preserves that contract; it
does not add a job handle or establish cleanup for a launcher that silently drops
an accepted block or a queued job cancelled before its body starts.

## Publication and privacy

The first useful card and its local identity shortcut still publish before remote
picture work. Enrichment updates only the same eligible live message, rechecking
captured account/runtime/privacy state and the card's current show under the
existing write lock. It keeps the card's identity, text/history, timestamp, actions
and quiet/head-up/rank behavior. Dismissal, a newer message or lost eligibility
prevents stale content from returning.

AppState's separate `refreshLateIdentityImage` remains caller-owned. This dispatch
extraction does not detach it, merge its eligibility rules or change its avatar
resolution bounds. Notification rendering and the initial post's alert, rewrite,
shortcut and artwork resources remain in their existing nested cleanup boundaries.

## Regression and manual coverage

`NotificationEnrichmentRunnerTest` exercises the real synchronizer/generations for
inline propagation, stale admission, detached lifetime and failure/cancellation
settlement, including a launcher that throws after executing the body.
`LocalNotificationEnrichmentLifetimeTest` drives the actual presenter connection:
the initial registration returns while its picture work is pending, the detached
reference retires after the rewrite, and an eligibility change during suspended
picture resolution prevents an old-account write while another account survives.

The existing conversation, enrichment-race, dismissal, heads-up, identity-image,
shortcut, preview-privacy, artwork and alert-budget suites retain their contracts.
Manual cases NTF-006 and NTF-010 describe first-post/late-picture and dismissal/
privacy behavior. Revision-specific CI and device results belong in the PR's
qualification record, not in this ownership map.
