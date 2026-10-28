# Chat-list controller ownership

Issue [#3242](https://github.com/marmot-protocol/whitenoise-android/issues/3242)
tracks the separation of Android presentation lifetimes. The subscription and
archive slices landed in #3318 and #3394. This inventory describes the subsequent
row-presentation and scheduling boundary; it does not claim passing runtime or
device qualification from source inspection.

| Responsibility | Owner | Boundary |
| --- | --- | --- |
| Account binding, recovery and paging orchestration | `ChatsController` and `ChatListLiveSubscriptions` | Existing injected native adapters, bind fences and retained-row failure behavior remain unchanged. MDK owns query and recovery policy. |
| Paired subscription resources | `ChatListSubscriptionOwner` | Each captured attempt closes once; teardown and bind-finally share that identity. |
| Authoritative rows, existing enrichment inputs and published lists | `ChatsController` | One backing state owner; no additional row/profile store or cache. |
| Retained and off-window row presentation | `ChatListRowProjector` | A synchronous view over existing controller inputs calls the established pure projection. Off-window rows use their own MDK selection and never enter the bounded window. |
| Visible publication scheduling | `ChatListPresentationLifetime` | Owns visibility, pending publication and one debounce job. Rebind cancels its old queued work; clear cannot reactivate it. The controller retains its shared enrichment/watchdog scope. |
| Unread aggregation, sorting, avatar warmup and enrichment | `ChatsController.recompute` | Unread and on-demand backing reads remain live while hidden. Visible publication and its enrichment wait until return; return flushes synchronously. |
| Pending archive/restore intent and batch settlement | `ChatListArchiveActions` | Exact overlay identity and captured binding guard admission and completion. The controller retains the native commit adapter and group lock. |
| Selection and action eligibility | Existing chat-list UI, selection helpers and `ChatListActionOwner` | Selection remains transient UI state. The existing view scope retires on controller/account/runtime/folder/archive/search/query/unread-view changes. Session checks separately permit already-admitted native completion. |

Projection keeps native-selected presentation, avatar, preview and actions paired
with their row. Existing local-name reconciliation, roster authority, last-known
member display fallback, exact-text Markdown fallback, media fallback, removed
membership, activity sequence and optimistic send/archive overlays are unchanged.
Only retained rows borrow a retained optimistic send overlay. A keyed off-window
result carries its own native selection.

`ChatsControllerListVisibilityTest` exercises the actual scheduling collaborator:
burst coalescing, return before a pending debounce, hidden unread publication,
synchronous return, rebind reset and clear without cancelling unrelated scope
work. `ChatListRowProjectorTest` exercises retained/off-window selection and the
existing pure projection with archive overlays. The controller wiring assertion
in `ChatsControllerOptimisticArchiveTest` checks live backing reads and unread
aggregation while hidden, then synchronous visible publication on return.

The existing archive controller suite retains partial success with newer unread
state, cancellation, superseding intents, bind reset during a lock wait and
completion after clear. Initial-load, recovery/reconnect, bounded first read,
account-switch local-snapshot ordering and teardown suites remain regression
targets for the existing subscription and controller boundaries. Hosted CI must
execute the applicable suites; these descriptions do not claim results.

Permanent manual scenarios `CHL-001`, `CHL-007` and `CHL-015` cover account/view
selection, archive races and retained rows during refresh failure. No layout,
strings, screenshot baselines or native artifact pins change. Whole-issue
completion requires reviewing these combined boundaries and qualifying their
production lifecycle behavior. Any required human preview still needs exact-APK
populated-upgrade and cold-restart qualification first.
