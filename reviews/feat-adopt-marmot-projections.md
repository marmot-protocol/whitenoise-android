# Code Review: feat/adopt-marmot-projections (origin/master..HEAD)

## Round 4 (adversarial)

## Summary

`c4686c13` correctly fixes round 3's "shared aggregate double-duties as a count" finding: `accountUnreadCounts` is back to a pure message count (`effectiveUnreadCount`, not the old `effectiveUnreadContribution`), and manual-unread now lives in its own `accountManualUnreadRefs` sidecar Set that only `accountShowsUnreadDot()` reads. Badge purity is verified intact — `unreadCountForAccount()` never touches the new set. But the sidecar's own maintenance has two new problems: the bulk-refresh short-circuit meant to protect an already-known flag makes that same flag undiscoverable the first time for any account that hasn't been live/active this session, and the sidecar is written from three independent, unsynchronized call sites (two of them genuinely concurrent, on different threads), unlike the numeric aggregate it sits beside, which was deliberately built to avoid exactly that hazard. All previously-fixed issues (rounds 1-2) and previously-flagged open suggestions (rounds 2-3) were re-verified against the current code and hold unchanged.

## Issues

### Correctness: a background account's manual-unread flag can never be discovered — the bulk refresh's own short-circuit gates the only code that would set it

**app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt:2966-2984**

> ```kotlin
> val rawCount = rawCountsByHex?.get(summary.accountIdHex)?.unreadCount
> // The cheap engine total can't see the client's
> // manual-unread flag, so an account we believe is
> // manually flagged always takes the row fold —
> // which also refreshes that flag from the rows.
> summary.label to
>     if (rawCount == 0uL && summary.label !in accountManualUnreadRefs) {
>         0uL
>     } else {
>         refreshEffectiveAccountUnreadCount(summary, memberGate)
>             ?: rawCount
>             ?: previous[summary.label]
>             ?: 0uL
>     }
> ```

The comment's own premise — "an account we believe is manually flagged always takes the row fold" — is the bug. `refreshEffectiveAccountUnreadCount` (line 3029-3032) is the *only* code path that calls `updateAccountManualUnread`, and it is only reached when `summary.label` is **already** in `accountManualUnreadRefs`, or the engine's raw count is nonzero. A manually-marked-unread chat with no real messages — the defining case for this feature — produces `rawCount == 0`. So the very first time an account's manual flag needs to be *discovered* (not just re-confirmed), the check `summary.label !in accountManualUnreadRefs` is true (the set doesn't know it yet), the shortcut fires, `0uL` is assigned, and `refreshEffectiveAccountUnreadCount`/`updateAccountManualUnread` never runs. This is a chicken-and-egg trap: the code path that would tell us the account is flagged only runs once we already believe it's flagged.

`accountManualUnreadRefs` is only ever seeded by two things: this same row-fold (which the shortcut now prevents from ever running for real), or `ChatsController.recompute()`'s live push (`Controllers.kt:3428-3431`) — and `chatsController` is a single instance bound to whichever account is currently active (`AppState.kt:1850`), so that push never fires for a background account.

Concretely: sign in on two devices/sessions to account A (active) and account B (signed in but backgrounded). On device/session where B was previously active, mark a chat unread on B, then switch to A. `accountManualUnreadRefs["B"]` was correctly seeded to `true` at that point (self-heals fine within that process's lifetime). Now kill the app and restart cold. `refreshAccounts()` runs at bootstrap (`AppState.kt:2614`) with a fresh `accountManualUnreadRefs = emptySet()`. For B, `rawCount == 0` and `"B" !in accountManualUnreadRefs` (true, freshly empty) → shortcut fires, B is assigned `0uL`, and its manual flag is never re-derived. B's account-switcher dot stays dark for as long as B remains backgrounded — until B becomes active again (reinstating the live push) or a notification arrives for B specifically (triggering the per-notification hot path at `AppState.kt:3051-3059`, which does call through to the row-fold). This defeats the doc comment on `refreshEffectiveAccountUnreadCount` that this exact function exists so "cross-account indicators stay honest for background accounts too (`AppState.kt:3005`, #662)" — for manual-unread specifically, background accounts are exactly what's now broken.

### Correctness: `accountManualUnreadRefs` is updated from three unsynchronized call sites, at least two of which run on different threads — lost updates are reachable within a single bulk refresh

**app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt:2935-2942, 2960-2984**

> ```kotlin
> internal fun updateAccountManualUnread(
>     accountRef: String?,
>     hasManualUnread: Boolean,
> ) {
>     val ref = accountRef?.takeIf { it.isNotBlank() } ?: return
>     accountManualUnreadRefs =
>         if (hasManualUnread) accountManualUnreadRefs + ref else accountManualUnreadRefs - ref
> }
> ```

This is a plain read-modify-write on a shared `mutableStateOf<Set<String>>` — read the current set, compute a new one, assign. It has three call sites: `ChatsController.recompute()` on `Dispatchers.Main.immediate` (`Controllers.kt:3347,3428-3431`), and `refreshEffectiveAccountUnreadCount` on `Dispatchers.IO` (via `marmotIo`, `AppState.kt:2483-2486`) from both the bulk `refreshAccountUnreadCounts` (line 3029-3032, invoked from up to `ACCOUNT_UNREAD_ACCOUNT_FANOUT = 4` (`AppState.kt:6772`) truly-concurrent `async` blocks) and the single-account `refreshAccountUnreadCount` hot path (line 3057). None of the three coordinate with each other or with a version/timestamp check.

This doesn't require the notification-vs-switcher cross-path timing round 3 flagged for the sibling map — it's reachable from a single call to `refreshAccountUnreadCounts()` alone, whenever 2+ signed-in accounts both take the row-fold branch (e.g., both have real unread, or both are already flagged): each is a separate `async { accountGate.withPermit { ... } }` that runs genuinely concurrently (semaphore just bounds it to 4, doesn't serialize), and each calls `updateAccountManualUnread` directly from inside its own coroutine. If account A's read-compute-write of `accountManualUnreadRefs` interleaves with account B's (A reads `{}`, computes `{} + "A"`; B reads `{}` before A writes, computes `{} - "B"` = `{}`; A writes `{"A"}`; B writes `{}`), A's correctly-computed flag is silently dropped — the dot for A goes dark despite A having a genuinely manually-unread chat.

Contrast this with how the sibling numeric aggregate in the *same function* avoids this exact hazard: each `async` block only returns a local `Pair<label, count>` (line 2972-2980), and the shared `accountUnreadCounts` is only ever mutated once, sequentially, after `awaitAll()` completes (line 2985-2996 builds `refreshedCounts` from the collected pairs before touching state). `updateAccountManualUnread` bypasses that pattern entirely by mutating the shared set directly from inside the still-concurrent `async` bodies.

## Suggestions

### `accountManualUnreadRefs` has no removal-time or full-sign-out cleanup, unlike `accountUnreadCounts`

**app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt:2946-2947, 2992-2996**

`refreshAccountUnreadCounts` explicitly resets `accountUnreadCounts = emptyMap()` when no signing accounts remain (line 2947), and its merge step explicitly keeps only labels present in `refreshedCounts` — i.e., currently signed-in accounts — so a removed/signed-out account's entry is dropped (the comment at line 2989 says as much: "Accounts absent from refreshedCounts (removed) are still dropped"). `accountManualUnreadRefs` has no equivalent: nothing clears it on full sign-out, and nothing prunes an individual account's ref when that account is removed while flagged — the set can only grow or have entries explicitly cleared by `updateAccountManualUnread(ref, false)`, which never runs again for a ref that's no longer in `accounts`. In practice this looks self-healing if the same account signs back in later (the stale `true` entry just forces one extra row-fold instead of causing a wrong dot), so this reads as a hygiene/unbounded-growth gap rather than a demonstrated wrong-dot bug, but it's worth closing for symmetry with the map it sits beside.

### Carried forward from rounds 2-3 (still open, confirmed present, unaffected by this commit)

- `ConversationController.isDm` / `isDirectConversation` (`Controllers.kt:4163, 4193`) still call the un-upgraded `GroupProjector.isDm(memberCount, name)` two-arg heuristic rather than the `conversationKind`-aware overload every list-derived consumer uses.
- `ChatsController.markUnread` (`Controllers.kt:3281`) still folds through `mergeMarkReadChatListRow` (`Controllers.kt:460, 2782`), which can drop a just-applied `manuallyMarkedUnread = true` under the stale-comparison race described in round 2.
- `syncEngineMute`'s fire-and-forget per-call `mutationsScope.launch` jobs (`AppState.kt:3969-3987`) still have no ordering protection between a rapid mute/unmute pair.
- `ChatsScreen.kt:213-215`'s `resolveFolderChatIds` still re-derives the composite-key mute check (`ChatMutePreferences.compositeKey(accountRef, groupIdHex) in mutedConversations || groupIdHex in engineMutedChatIds`) instead of calling `isLocallyMuted(groupIdHex) || groupIdHex in engineMutedChatIds`.

### Test gap: neither of this round's new issues would be caught by the existing suite

`AccountUnreadTest.manualUnreadIsABooleanSidecarNotACount` only exercises the pure `accountHasManualUnread(rows, ...)` function in isolation — it proves the split is correct in principle but never touches `refreshAccountUnreadCounts`, the short-circuit, or any concurrent invocation. As round 3 noted, nothing in the suite exercises `refreshAccountUnreadCounts`/`refreshEffectiveAccountUnreadCount` at all, so both the bootstrap-discovery gap and the concurrent-write race have no coverage in either direction.

## What's Done Well

- Badge purity is fully restored: `effectiveUnreadContribution` is gone, `accountUnreadCounts` only ever accumulates `effectiveUnreadCount`, and `AccountUnreadTest.manualUnreadIsABooleanSidecarNotACount` directly asserts the split (`accountUnreadCount` stays `0`, `accountHasManualUnread` is the boolean signal) — round 3's "aggregate double-duties as a count" finding is closed at the root, not patched around.
- The sidecar is a clean, minimally-invasive addition: a dedicated `Set<String>` with its own updater, read by exactly one consumer (`accountShowsUnreadDot`), rather than another special case threaded through the existing numeric map.
- All five round-1/round-2 correctness fixes (`syncEngineMute` wiring, both `ChatsScreen` mute reads, `ChatRow`'s preview weight modifier, `chatRowNeedsMediaKindResolve`'s early return, `GroupDetailsScreen`'s move to `latestChatListRow`) were re-verified against the current code and hold exactly as previously described.
- Every round 2-3 open suggestion was re-checked and confirmed still present, unchanged — nothing was silently dropped or regressed by this commit, which only touches the manual-unread aggregation path.
