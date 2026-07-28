# Code Review: feat/adopt-marmot-projections (origin/master..HEAD)

## Round 5 (adversarial, final)

## Summary

`022e5d9c` addresses both round-4 correctness findings and the round-4 hygiene suggestion, all scoped to `AppState.kt`'s manual-unread sidecar. Two of the three hold cleanly: the concurrent-write race is fully closed — `updateAccountManualUnread` and the new `retainManualUnreadRefs` are the only two places that ever assign `accountManualUnreadRefs` (confirmed by exhaustive grep), both now serialize through one `synchronized(manualUnreadLock)` block, and every call site (the controller's main-thread `recompute()`, the bulk fan-out, the per-notification hot path) routes through them — so the read-modify-write interleaving round 4 demonstrated is no longer reachable. `retainManualUnreadRefs` also closes the removal/sign-out hygiene gap exactly as suggested, symmetric with `accountUnreadCounts`'s own cleanup.

The third fix — `manualUnreadBootstrapped` — closes round 4's literal repro (a cold process restart with a backgrounded account already present at start) but does so with a single process-wide one-shot `Boolean`, not a per-account state. That's a narrower version of the same conflation round 4 flagged: it treats "has this process done its first bulk fold" as a proxy for "has this specific account's rows ever been folded," and those two facts diverge whenever an account's own first fold is skipped or fails independently of the process's first pass. The flag also never resets — not on full sign-out, not anywhere else in the file (only three references total: the declaration, the read, and one unconditional write to `true`). This is a real, narrower-scope descendant of the same finding, not a new unrelated bug — see below for the exposure window and why it's smaller than round 4's original claim. All three round 2-4 deferred items (`ConversationController.isDm` heuristic, `syncEngineMute` ordering, `markUnread` merge race) remain present, unaffected — this commit only touches the manual-unread aggregation path.

## Issues

### Correctness: `manualUnreadBootstrapped` is a single process-wide flag, so a per-account failure during the one-shot pass permanently reopens the discoverability trap for that account alone

**app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt:2989-3024**

> ```kotlin
> val cheapZero =
>     rawCount == 0uL &&
>         !manualBootstrap &&
>         summary.label !in accountManualUnreadRefs
> ...
> accountUnreadCounts = merged
> manualUnreadBootstrapped = true
> // Removed accounts drop their manual flag alongside their count.
> retainManualUnreadRefs(refreshedCounts.keys)
> ```

`manualBootstrap` is captured once (`!manualUnreadBootstrapped`, line 2970) before the concurrent per-account fan-out, so on the very first call it correctly forces every signed-in account — including backgrounded ones — through the row fold at least once. But `updateAccountManualUnread` only runs if that per-account fold *succeeds*: it's called from inside the same `marmotIo { ... }` block (`AppState.kt:3057-3060`) that `chatList()`/`groupMembers()` can throw out of, and that block is wrapped in `runCatchingCancellable` (`AppState.kt:3040, 3063`), which swallows any non-cancellation `Throwable` into `Result.failure` (`Cancellation.kt:13-20`) with no rethrow and no distinction for "this was the one-shot bootstrap attempt." A transient failure for one account during that single forced pass — a network blip, a `chatList`/`groupMembers` FFI error — means that account's `updateAccountManualUnread` never runs this round, yet line 3022 unconditionally sets `manualUnreadBootstrapped = true` regardless of which individual folds actually succeeded. Every subsequent bulk refresh treats that account exactly as round 4 described: `rawCount == 0uL`, not yet in `accountManualUnreadRefs`, `manualBootstrap` now `false` → `cheapZero` fires → the row fold that would discover its manual flag never runs again.

The same unconditional, no-reset design also means the flag doesn't distinguish "this process has bootstrapped" from "this account has been discovered": it is never reset anywhere (confirmed — the only three references in the file are the `false` declaration at 2941, the read at 2970, and the `true` write at 3022; the full-sign-out early return at 2965-2969 doesn't touch it either). So an account that becomes part of the signed-in roster for the first time after this process's one bootstrap pass has already run inherits `manualBootstrap == false` on its very first appearance in `signingAccounts` — it gets no forced fold of its own, only the same `cheapZero` gate every other account uses post-bootstrap.

This is narrower than round 4's original finding, not equally severe — two things bound the blast radius, and both were already established by round 4's own writeup: becoming the active account re-triggers `ChatsController.recompute()`'s live push (`Controllers.kt:3428-3431`), which discovers the flag independent of this gate; and a targeted push notification runs `refreshAccountUnreadCount` (`AppState.kt:3079-3087`), which calls `refreshEffectiveAccountUnreadCount` unconditionally with no `cheapZero`-style shortcut at all. So the exposure window is specifically: an account that is signed in, currently backgrounded, has never yet been the active account in this process, and — because a manually-marked-unread chat with zero real messages is defined to generate no unread-driving notification — never receives the one push that would route through the always-correct hot path. That's a real window (it's exactly the account-switcher's own `LaunchedEffect(Unit) { appState.refreshAccounts() }` at `AccountSelectorSheet.kt:280-289` that would silently reconfirm the wrong state on every open), just smaller than "every backgrounded account, every cold start."

A per-account bootstrapped set (e.g., track which account labels have completed at least one successful row fold, alongside `accountManualUnreadRefs` under the same lock, pruned the same way `retainManualUnreadRefs` prunes now) would tie the guard to the actual invariant — "has this account's rows ever been folded" — instead of "has the process's first call finished," and would cover a failed-then-retried account or a newly-added account without depending on it becoming active or getting notified first.

## Suggestions

### Test gap persists: `refreshAccountUnreadCounts`, the bootstrap flag, and the lock are still untested

No test file references `refreshAccountUnreadCounts`, `refreshEffectiveAccountUnreadCount`, `manualUnreadBootstrapped`, or `manualUnreadLock` (checked `app/src/test` in full). `AccountUnreadTest.kt`'s cases still only exercise the pure row-folding functions (`accountUnreadCount`, `accountHasManualUnread`, `accountShowsUnreadDot`) in isolation, same as round 4 noted. Both this round's finding and the round-4 concurrency fix's correctness are unverifiable by the suite in either direction.

### Carried forward from rounds 2-4 (still open, confirmed present, unaffected by this commit)

- `ConversationController.isDm` / `isDirectConversation` (`Controllers.kt:4163, 4193`) still call the un-upgraded `GroupProjector.isDm(memberCount, name)` two-arg heuristic rather than the `conversationKind`-aware overload every list-derived consumer uses (`GroupProjector.isDm(projection?.conversationKind, memberCount, group.name)`, e.g. `Controllers.kt:257`).
- `ChatsController.markUnread` (`Controllers.kt:3281`) still folds through `mergeMarkReadChatListRow` (`Controllers.kt:460, 2782`), which can drop a just-applied `manuallyMarkedUnread = true` under the stale-comparison race described in round 2.
- `syncEngineMute`'s fire-and-forget per-call `mutationsScope.launch` jobs (`AppState.kt:3997-4045`) still have no ordering protection between a rapid mute/unmute pair.
- `ChatsScreen.kt:214-215`'s `resolveFolderChatIds` still re-derives the composite-key mute check instead of calling `isLocallyMuted(groupIdHex) || groupIdHex in engineMutedChatIds`.

## What's Done Well

- The concurrent-write race is closed correctly, not just narrowed: `accountManualUnreadRefs` has exactly two writers (`updateAccountManualUnread`, `retainManualUnreadRefs`), both now synchronize on the same `manualUnreadLock`, and every one of the three call sites round 4 identified as genuinely concurrent (main-thread `recompute()`, the four-way bulk fan-out, the per-notification hot path) goes through one of those two functions — verified by grepping every reference to the field, not just the call sites round 4 already knew about.
- `retainManualUnreadRefs` closes the round-4 hygiene suggestion cleanly and symmetrically: called both on full sign-out (`emptySet()`, matching `accountUnreadCounts`'s own reset) and after every bulk merge (`refreshedCounts.keys`, matching the numeric map's "removed accounts are dropped" comment) — the sidecar no longer grows unboundedly or outlives a removed account.
- The bootstrap fix fully resolves round 4's named repro (cold restart, backgrounded account already signed in at process start) — the residual gap above is a real but strictly narrower descendant, not a failure to address what round 4 actually demonstrated.
- The `synchronized` pattern matches an already-established, already-tested idiom elsewhere in this codebase (`ChatMutePreferences`'s own `synchronized(mutationLock)`, asserted directly in `ChatMutePreferencesTest.kt:170`), so this isn't a novel concurrency primitive introduced just for this fix.
