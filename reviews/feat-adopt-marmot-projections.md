# Code Review: feat/adopt-marmot-projections (origin/master..HEAD)

## Round 2 (adversarial)

## Summary

All three round-1 issues are correctly and completely fixed by `8c3b8d31`: `syncEngineMute()` is now invoked from every local-mute mutator (including `setConversationNotifyMode`, which the unmute path in `GroupDetailsScreen` calls), `singleSelectionMuted` in `ChatsScreen` now ORs `item.engineMuted()`, and `ChatRow`'s preview `Text` has `Modifier.weight(1f, fill = false)`. The redundant-FFI-resolve suggestion from round 1 is also fixed (`chatRowNeedsMediaKindResolve` now bails when `attachmentKind != null`), with a new regression test. Digging further, though, the mute fix inherits a real staleness bug from the chat list's existing visibility-gated recompute, and the sibling mark-unread feature (added in this same range) has the identical "half-wired new signal" shape that round 1 flagged for mute — just for a different consumer.

## Issues

### Correctness: manually-marked-unread chats don't reach the per-account unread dot/aggregate

**app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt:191-194, 240**

> ```kotlin
> val hasUnread: Boolean
>     // A manual mark-unread renders the same badge as real unread; the
>     // engine clears it when the conversation is read again.
>     get() = projection?.hasUnread == true || projection?.manuallyMarkedUnread == true
> ...
> fun effectiveUnreadCount(activeAccountIdHex: String?): ULong = if (removedFromGroup(activeAccountIdHex)) 0uL else unreadCount
> ```

`hasUnread` (and therefore `effectiveHasUnread`, used for row bolding, the selection bar's mark-read/unread gating, and the "Unread" system folder's `it.hasUnread` count in `ChatFoldersScreen.kt:255`) now correctly folds in `manuallyMarkedUnread`. But `effectiveUnreadCount` still returns the raw numeric `unreadCount` field only — it never looks at `manuallyMarkedUnread`. That numeric value is exactly what feeds the per-account aggregate:

**app/src/main/java/dev/ipf/whitenoise/android/state/AccountUnread.kt:108-114**

> ```kotlin
> internal fun accountUnreadCount(
>     items: Iterable<ChatListItem>,
>     activeAccountIdHex: String?,
> ): ULong =
>     items.fold(0uL) { total, item ->
>         if (item.group.archived) total else total + item.effectiveUnreadCount(activeAccountIdHex)
>     }
> ```

which is written straight into `accountUnreadCounts` from `ChatsController.recompute()` (`Controllers.kt:3423-3427`) and is the sole input to `WhiteNoiseAppState.accountShowsUnreadDot()` (`AppState.kt:2917`) — the function whose own doc comment says it's "shared by the active avatar, the secondary top-bar avatars, and the account switcher so no avatar can light for another account's unread," specifically to prevent this class of drift (#805).

Concretely: mark an already-read chat as unread via the new action. The chat list row goes bold, the chat shows up in the "Unread" folder, the selection bar offers "Mark as read" — but the account-switcher/top-bar avatar dot for that account never lights, because `unreadCount` (0, since there's no actual new message) is all the aggregate ever sums. This is the same "wired into some consumers, not others" shape round 1 found for mute, just on the new mark-unread signal. `AccountUnreadTest.kt` corroborates the gap: every test row hardcodes `manuallyMarkedUnread = false`, so nothing exercises this combination.

### Correctness (narrow window): the new engine-mute read in GroupDetailsScreen is sourced from data frozen for the entire time that screen is open

**app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt:3908-3909**

> ```kotlin
> /** The engine's durable mute projection for the chat, from the live list. */
> fun engineConversationMuted(groupIdHex: String): Boolean = chatsController?.items?.firstOrNull { it.group.groupIdHex == groupIdHex }?.engineMuted() == true
> ```

`chatsController.items` is exactly the list `ChatsController.recompute()` intentionally stops publishing to while `chatListVisible == false` (`Controllers.kt:3432-3435`: "Hidden behind an open conversation: keep folding updates into the backing maps... but defer the projection rebuild... until the list returns"). `MainShell.kt:237-239` sets `chatListVisible = (selectedChat == null)` — and `GroupDetailsScreen` (and the `ConversationNotificationSettingsScreen` it hosts) is only reachable from inside an open conversation (`ConversationScreen.kt:2571`), i.e. precisely when `chatListVisible` is `false`.

So `conversationMuted` in `GroupDetailsScreen.kt:294-296` (`conversationNotifyMode == ChatNotifyMode.NONE || appState.engineConversationMuted(...)`) reads a snapshot of `items` that is frozen for the entire time this screen is visible. The initial value on entry is fine (the chat list was still updating up to the moment the conversation opened), but an engine-mirrored mute or unmute that arrives from another device *while the user is already inside this conversation* will not be reflected here — the toggle can keep showing the pre-entry state — until the user backs out to the chat list (which flushes the deferred recompute) and back in. This doesn't regress the round-1 fix for the common case (same-device mute/unmute still updates instantly through the local-prefs half of the `||`), but it does undercut the specific cross-device-convergence guarantee this screen was just wired up to show.

Note `ChatsController` already has a read path that isn't gated by visibility — `chatItemForGroup(groupIdHex)` (`Controllers.kt:2715-2718`) projects straight off the backing `chatRowsByGroup` map, which keeps folding updates regardless of `chatListVisible`. It wouldn't fully solve this on its own (that map isn't Compose-observable, so nothing would trigger a recomposition when it changes while hidden), but it's a starting point if this window turns out to matter in practice.

## Suggestions

### `syncEngineMute`'s fire-and-forget engine writes have no ordering protection

**app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt:3944-3961**

Each call to `setConversationMuted`/`muteConversationFor`/`setConversationNotifyMode` launches an independent `mutationsScope.launch { marmotIo { ... } }` job; `marmotIo` hops to `Dispatchers.IO` (`AppState.kt:2483-2486`), a thread pool with no ordering guarantee between two concurrently-launched coroutines. A rapid mute-then-unmute (or the reverse) issues two independent `setChatMuted`/`clearChatMuted` calls with no shared queue and no return-value reconciliation — unlike `mergeMarkReadChatListRow`'s monotonic-timestamp merge for read-state races, there's nothing here to make the *last* engine write win if the two calls complete out of order. Likely low-impact given local prefs stay the authoritative signal for this device, but the durable state other devices converge on could end up disagreeing with the user's actual last action.

### Minor duplication: `resolveFolderChatIds` re-derives the local-mute check `isLocallyMuted` already provides

**app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt:180-186, 213-216**

> ```kotlin
> val isLocallyMuted: (String) -> Boolean =
>     remember(mutedConversations, appState.activeAccountRef) { ... }
> ...
> isMuted = { groupIdHex ->
>     ChatMutePreferences.compositeKey(accountRef, groupIdHex) in mutedConversations ||
>         groupIdHex in engineMutedChatIds
> },
> ```

The folder-membership `isMuted` closure re-implements the exact composite-key lookup `isLocallyMuted` was just introduced for a few lines above, instead of calling `isLocallyMuted(groupIdHex) || groupIdHex in engineMutedChatIds`. Not a bug (both evaluate identically today), but the duplication is exactly the kind of drift risk that caused round 1's split-brain bug in the first place.

### Test gap: the round-1 fixes and the new mark-unread aggregate path have no direct assertions

Nothing in this range unit-tests `singleSelectionMuted`'s OR logic, `GroupDetailsScreen`'s `conversationMuted` OR logic, or the `ChatRow` weight-modifier layout fix — `ChatsScreenSelectionActionsCoverageTest` only regex-scrapes handler wiring (`controller.markUnread(item)` is called, `clearSelection()` runs), not the actual muted-state computation. Similarly, `AccountUnreadTest.kt` never sets `manuallyMarkedUnread = true` on a fixture row, which is exactly the gap that let the Issue above ship untested.

### Carried forward from round 1 (still open, not in this round's fix list)

- `ConversationController.isDm`/`isDirectConversation` (`Controllers.kt:4149-4150, 4179-4180`) still use the un-upgraded `GroupProjector.isDm(memberCount, name)` heuristic, unlike every list-derived consumer (`ChatRow`, `ChatListFiltering`, `ChatFolderChipModel`, `ChatFoldersScreen`, `RecipientResolution`), which were all migrated to `item.isDm()` / the `conversationKind`-aware overload in this round.
- `ChatsController.markUnread` still folds through `mergeMarkReadChatListRow`, which can drop a just-applied `manuallyMarkedUnread = true` under the same race described in round 1.

## What's Done Well

- All three round-1 correctness issues are fixed at the root cause rather than patched around, and the fixes are minimal, symmetric diffs (`syncEngineMute` called from all four local-mute mutators; both `ChatsScreen` mute reads now OR the same `engineMuted()`).
- `chatRowNeedsMediaKindResolve`'s new `attachmentKind != null` early-return ships with a dedicated regression test (`projectedAttachmentKindSkipsTheLocalMediaResolve`) proving the local timeline round trip is actually skipped, not just that the preview text is right.
- The `isDm()` migration is thorough: every list-derived DM/group classification site in this diff (filtering, folder chip counts, folder screen counts, recipient resolution, avatar-open gating) was updated together, so there's no leftover split-brain between them — only the pre-existing `ConversationController` gap (already called out in round 1) remains.
- Localization stayed in lock-step: `chat_row_action_mark_unread`, `media_album`, and `media_counted_format` all landed in every locale file plus the identical-value suppression list with a stated rationale, not a blanket exemption.
