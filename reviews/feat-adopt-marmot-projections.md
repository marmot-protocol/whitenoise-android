# Code Review: feat/adopt-marmot-projections (origin/master..HEAD)

## Round 3 (adversarial)

## Summary

All five previously-flagged correctness issues hold under re-verification: `syncEngineMute()` still fires from every local-mute mutator, `ChatsScreen`'s two mute reads still OR `engineMuted()`, `ChatRow`'s preview `Text` still carries `Modifier.weight(1f, fill = false)`, `chatRowNeedsMediaKindResolve` still bails on a projected `attachmentKind`, and `GroupDetailsScreen` no longer reads the visibility-frozen `chatsController.items` — it now prefers `ConversationController.latestChatListRow`, which is correctly scoped per-group (`projection.groupIdHex == group.groupIdHex` gates every update) and correctly reset per conversation (a fresh `ConversationController` is `remember`'d per `openChat.id`). That part of round 2's fix is solid. But the sibling fix in the same commit (`4d3c44f2`) — routing `manuallyMarkedUnread` into the per-account unread aggregate so the account dot lights — has two new problems: the aggregate it patched is shared with a numeric badge that now shows a wrong number, and a separate, untouched refresh path silently reverts the fix for the very case it was built for.

## Issues

### Correctness: the manual-unread account dot doesn't survive `refreshAccountUnreadCounts()` — opening the account switcher can turn it back off

**app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt:2950-2959**

> ```kotlin
> val rawCount = rawCountsByHex?.get(summary.accountIdHex)?.unreadCount
> summary.label to
>     if (rawCount == 0uL) {
>         0uL
>     } else {
>         refreshEffectiveAccountUnreadCount(summary, memberGate)
>             ?: rawCount
>             ?: previous[summary.label]
>             ?: 0uL
>     }
> ```

`rawCount` comes from the engine's own `accountUnreadSummary()` FFI call, whose `unreadCount` field is documented as "Total unread messages across all unarchived conversations" (`app/src/main/java/dev/ipf/marmotkit/marmot_uniffi.kt:9814-9827`) — a real per-message tally with no notion of the client-only `manuallyMarkedUnread` flag. When that raw total is `0` (the exact case a manually-marked-unread chat with no new messages produces), this code hard-codes the account's contribution to `0` and never calls `refreshEffectiveAccountUnreadCount` — the only function that reads chat-list rows and could apply `ChatListItem.effectiveUnreadContribution` (the very fix this round shipped, `AccountUnread.kt:122-126`).

Concretely: mark an already-read chat unread. `ChatsController.recompute()` (`Controllers.kt:3423-3427`) immediately computes `accountUnreadCounts[account] = 1` via the live path and the dot lights — correct, per the round-2 fix. Then open the account switcher: `AccountSelectorSheet`'s `LaunchedEffect(Unit)` unconditionally calls `appState.refreshAccounts()` (`AccountSelectorSheet.kt:280-289`), which calls `refreshAccountUnreadCounts()`. For that account, `rawCount` is `0` (no real unread messages), so the shortcut above assigns `0` — and since nothing else touched `accountUnreadCounts` during the suspension, the "don't clobber a fresher concurrent update" guard a few lines down (`AppState.kt:2966-2974`, `previous[ref] != count`) doesn't fire, because nothing changed. The final `accountUnreadCounts = merged` (line 2975) commits the `0`, and the dot goes dark — while the chat itself is still sitting there manually marked unread and bold in the list (`ChatRow` reads `effectiveHasUnread` directly off the live item, unaffected by this aggregate). This isn't a hypothetical race; opening the switcher is the sheet's own first action on every open, and this refresh path was never touched by the round-3 fix, so it's still running the pre-fix logic.

This is the same "wired into some consumers, not others" shape rounds 1-2 flagged for mute and mark-unread respectively, one layer deeper: the live recompute path and the bulk-refresh path are two independent implementations of "this account's unread aggregate," and only one of them learned about manual-unread this round.

### Correctness: the same aggregate now double-duties as a message *count*, so `effectiveUnreadContribution` inflates a real number

**app/src/main/java/dev/ipf/whitenoise/android/state/AccountUnread.kt:117-126**

> ```kotlin
> internal fun ChatListItem.effectiveUnreadContribution(activeAccountIdHex: String?): ULong =
>     maxOf(
>         effectiveUnreadCount(activeAccountIdHex),
>         if (effectiveHasUnread(activeAccountIdHex)) 1uL else 0uL,
>     )
> ```

This value flows into `accountUnreadCounts` (`Controllers.kt:3423-3427`), which feeds *two* different consumers with different semantics:

- `accountShowsUnreadDot()` (`AppState.kt:2917`) — a boolean "> 0", for which this fix is exactly correct.
- `unreadCountForAccount()` (`AppState.kt:2909`) → `AccountSelectorSheet.kt:195,232-233` → `UnreadCountBadge(unreadCount)` (`ui/common/Badges.kt:16-30`), which renders a literal number and an accessibility string pulled from `R.plurals.unread_messages_count` — `"%1$d unread message(s)"` (`app/src/main/res/values/strings.xml:876-879`).

Marking a read chat unread now adds `+1` to a number that's user-facing as "N unread messages" even though zero messages are actually unread. With one real unread chat (say 3 messages) and one manually-marked chat (0 messages), the account switcher shows "4 unread messages" — overcounting by exactly the number of manually-marked chats with no backlog. `AccountUnreadTest.accountUnreadCount_manualUnreadLightsTheDotWithoutACount` (`AccountUnreadTest.kt`) names the intent precisely — "lights the dot *without* a count" — but the fix landed at the shared-aggregate level, not at the dot-specific call site, so the "without a count" half of that intent isn't actually true for the other consumer of the same map.

Both issues share one root cause: `accountUnreadCounts` is being asked to serve as both a boolean "does this account need attention" signal and a numeric "how many messages" count, and manual-unread only cleanly fits the first. A dot-only boolean (e.g., a parallel `Set<String>`/map of "has manual-or-real unread" computed alongside the numeric total, or computing the dot from `effectiveHasUnread` directly rather than from a `> 0` count) would let `accountUnreadCounts` stay a true message count for the badge, and would sidestep the `rawCount == 0` shortcut entirely since the dot would no longer depend on that numeric field at all.

## Suggestions

### Carried forward from round 2 (still open)

- `ConversationController.isDm` / `isDirectConversation` (`Controllers.kt:4158-4159, 4188-4189`) still use the un-upgraded `GroupProjector.isDm(memberCount, name)` heuristic, unlike every list-derived consumer, which is now uniformly on `item.isDm()`/the `conversationKind`-aware overload (confirmed: `ChatFolderChipModel.kt` was migrated this round too).
- `ChatsController.markUnread` (`Controllers.kt:3277-3293`) still folds through `mergeMarkReadChatListRow` (`applyChatListRow`, `Controllers.kt:2774-2782`), which can drop a just-applied `manuallyMarkedUnread = true` under the same stale-comparison race described in round 2.
- `syncEngineMute`'s fire-and-forget per-call `mutationsScope.launch` jobs (`AppState.kt:3944-3963`) still have no ordering protection between a rapid mute/unmute pair.
- `ChatsScreen.kt:195-216`'s `resolveFolderChatIds` still re-derives the composite-key mute check `isLocallyMuted` already provides, instead of calling it.

### Test gap: neither of this round's new issues would be caught by the existing suite

`AccountUnreadTest.kt`'s new case only exercises the pure `accountUnreadCount(rows, ...)` function in isolation, asserting the aggregate value is `1` — which is correct for the dot but is exactly the value that turns out wrong for the badge. Nothing in the suite exercises `refreshAccountUnreadCounts`/`refreshEffectiveAccountUnreadCount` at all (no test file references either function), so the raw-count short-circuit regression has no coverage in either direction.

## What's Done Well

- `latestChatListRow` is a clean, minimal fix: scoped to the conversation's own `groupIdHex` at the subscription-pipeline call site (`Controllers.kt:4757` `if (projection.groupIdHex == group.groupIdHex)`), rebuilt fresh per conversation via the existing `remember(openChat.id, ...)` keying in `MainShell.kt`, and read as a plain reactive `val` in `GroupDetailsScreen` rather than trapped in a stale `remember` block — no cross-conversation leakage, no stuck-stale-forever failure mode.
- All five round-1/round-2 fixes were re-verified against the current code (not just trusted from the prior write-up) and hold exactly as described.
- The `isDm()` migration picked up one more site this round (`ChatFolderChipModel.kt`), continuing to close the gap round 2 noted, with only the `ConversationController` pair left.
