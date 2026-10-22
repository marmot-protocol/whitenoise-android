# Chat-list controller ownership

Issue [#3242](https://github.com/marmot-protocol/whitenoise-android/issues/3242)
tracks the separation of Android presentation lifetimes. The archive slice starts
from master `f2ccea408ad0216545948dd6ef68f0ecdd6d0f4d`; this is a source inventory,
not a claim of device qualification or completion of the whole issue.

| Responsibility | Owner after the archive extraction | Boundary |
| --- | --- | --- |
| Account binding, subscription recovery and paging | `ChatsController` and `ChatListLiveSubscriptions` | Open PR #3318 owns the subscription-pair lifetime changes. This extraction does not change those paths. |
| Authoritative rows, member presentation and visible-list projection | `ChatsController` | Existing backing maps and projection scheduling remain in place. There is no new row store. |
| Pending archive/restore presentation and batch settlement | `ChatListArchiveActions` | Owns the existing transient intents; the controller supplies its bind fence, current row lookup and projection callback. |
| Native archive commit and accepted group projection | `ChatsController.applyArchiveMutation` | Keeps the existing account/group commit lock and bind check around publication. MDK still owns durable group state. |
| Selection and action eligibility | Existing chat-list UI and selection helpers | No change to loaded-window selection or native enumeration policy. |

Both bulk chat-list actions and conversation-originated archive intents use the
same owner. It installs all eligible overlays before the first native call, then
retires each intent by object identity. Failure exposes the latest authoritative
row, and cancellation releases unstarted overlays. A reset clears the intents;
an old completion cannot remove a replacement binding's intent. Before a batch
enters MDK, the adapter rechecks the binding and exact intent
inside the existing group lock. A superseded or retired entry never starts a
native write. Once a native call starts, its existing completion path,
cancellation behavior and post-call binding check remain in place; conversation
command acceptance and disposal behavior are unchanged.

`ChatsControllerOptimisticArchiveTest` exercises the real controller wiring with
delayed native adapters: partial success with a newer unread row, account rebind,
batch cancellation, opposite intents in one binding, completion after clear,
a completed restore superseding an unstarted batch entry, and bind reset while
an archive waits for its group lock, and intent replacement during that lock wait.
The existing initial-load, list-visibility and conversation-archive suites remain
regression targets. Hosted CI executes these tests; source inspection alone does
not establish a passing runtime result.

The permanent manual scenario is `CHL-007`. No layouts, strings, screenshot
baselines or native artifact pins change. Whole-issue completion still requires
reconciling #3318, separating the remaining projection/selection responsibilities
where justified, and qualifying the combined lifecycle behavior. A preview for
human testing must pass the repository's exact-APK populated upgrade and cold
restart gates first; historical phone evidence does not qualify this revision.
