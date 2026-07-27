# Code Review: feat/adopt-marmot-projections (origin/master..HEAD)

## Summary

This range adds seven small, well-isolated adoption surfaces on top of the new MDK chat-list/timeline projections: a disappearing-message bubble indicator, typed attachment chat-list previews, a chat-list delivery tick, engine-mirrored mute state, a mark-unread action, engine-driven DM classification, and pending-leave suppression. Each surface is unit-tested at the pure-function layer (`GroupProjector`, `TimelineProjector`, `ProjectedPreviewTextTest`, etc.), and the diffs are small and readable. The main problem is the mute-mirroring feature (979efd33): the new `ChatListItem.engineMuted()` read path was wired into exactly one consumer (the chat-list row/folder rendering in `ChatsScreen`), while three other "is this chat muted" call sites were left reading local-only state, producing a real, user-visible split-brain between what the row shows and what the per-conversation Notification Settings screen / selection-bar overflow menu let you do about it.

## Issues

### Correctness: Conversation Notification Settings can't clear an engine-only mute

**app/src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt:294, 495-501**

> ```kotlin
> val conversationMuted = conversationNotifyMode == ChatNotifyMode.NONE
> ...
> onToggleMute = { turnOn ->
>     if (turnOn) {
>         showMuteDurationDialog = true
>     } else {
>         // Unmute back to the All/Only-mentions preference the mute hid.
>         appState.setConversationNotifyMode(controller.group.groupIdHex, conversationRestoreMode)
>     }
> },
> ```

`conversationMuted` (passed to `ConversationNotificationSettingsScreen` as `isMuted`) is derived purely from local `ChatMutePreferences` — it never reads `controller`'s engine-projected mute state the way `ChatsScreen`'s row rendering now does (`item.engineMuted() || isLocallyMuted(...)`, see `ChatsScreen.kt:744-471`). And the "turn mute off" branch calls `appState.setConversationNotifyMode(...)` directly, not `appState.setConversationMuted(groupIdHex, false)` (compare `AppState.kt:3915-3922`). `setConversationNotifyMode` only writes local prefs — it never calls the new `syncEngineMute()` (`AppState.kt:3940-3959`), so `clearChatMuted` is never invoked on the engine.

Concretely: once a conversation is muted via the engine (from this device's own `muteConversationFor`, or mirrored in from another device), this screen has no way to observe that mute (its toggle can show "off" while the chat-list row right behind it shows the muted bell icon), and even if the user does flip the switch off, the engine's durable mute is never cleared — so the row keeps showing muted, and any other device relying on the durable setting (the entire point of this feature, per the `syncEngineMute` doc comment) never learns the mute was lifted.

### Correctness: selection-bar overflow "Mute/Unmute" reads stale (local-only) mute state

**app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt:398-404**

> ```kotlin
> val singleSelectionMuted =
>     singleSelectedItem?.let { item ->
>         appState.activeAccountRef?.let { accountRef ->
>             ChatMutePreferences.compositeKey(accountRef, item.group.groupIdHex) in mutedConversations
>         }
>     } ?: false
> ```

This value drives both the overflow menu's label/icon (`muted` in `ChatListSelectionBar.kt:116-134`, "Unmute" + bell vs. "Mute" + bell-off) and the actual toggle action (`val nextMuted = !singleSelectionMuted` at `ChatsScreen.kt:593`). It was not updated alongside the new `ChatListItem.engineMuted()` getter, unlike the row's own `isMuted` a few hundred lines down (`item.engineMuted() || isLocallyMuted(item.group.groupIdHex)`, `ChatsScreen.kt:471`).

Net effect: long-press-select a chat whose bell-off badge is showing only because of the engine projection (not local prefs) and the overflow menu will offer "Mute" (not "Unmute") — there is no way to clear that mute from this menu; tapping it just re-mutes (already-true, harmless but confusing), while the row directly behind the selection bar visibly disagrees with what the menu is telling the user.

### Correctness (lower confidence): chat-row delivery tick can push the preview text past the visible row width

**app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatRow.kt:288-301**

> ```kotlin
> Row(verticalAlignment = Alignment.CenterVertically) {
>     if (draft == null && !item.group.pendingConfirmation) {
>         item.projectedDeliveryIndicator()?.let { indicator ->
>             OutgoingIndicatorIcon(indicator, tint = MaterialTheme.colorScheme.onSurfaceVariant)
>             Spacer(Modifier.width(3.dp))
>         }
>     }
>     Text(
>         text = preview,
>         maxLines = 1,
>         overflow = TextOverflow.Ellipsis,
>         ...
>     )
> }
> ```

`Text` has no `Modifier.weight(1f, fill = false)`. Compose's `Row`/`Column` measures non-weighted siblings against the *same* incoming max-width constraint rather than the space left after earlier siblings — that's exactly why "icon + truncating text" rows conventionally give the text a weight modifier. Here, when the delivery icon renders (any outgoing last message: sending/sent/failed) next to a preview long enough to need the ellipsis, the `Text` is still measured as if it owned the full row width, so the icon+spacer (~20dp) get tacked on in addition rather than budgeted out of the available width — the combined row can end up wider than the row's actual slot, and the truncation no longer accounts for the space the tick icon occupies. Give the `Text` `Modifier.weight(1f, fill = false)` (matching the icon-then-text pattern used correctly elsewhere, e.g. `ChatRowTrailingContent`'s icon+badge Row, which never needs to truncate long content) so it's properly constrained to "row width minus icon".

## Suggestions

### `conversationKind`-based DM classification wasn't threaded into the open-conversation controller

**app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt:4146-4147, 4176-4177**

> ```kotlin
> val isDm: Boolean
>     get() = GroupProjector.isDm(memberCount, group.name)
> ...
> val isDirectConversation: Boolean
>     get() = GroupProjector.isDm(memberCount, group.name)
> ```

`ChatListItem.isDm()` (`Controllers.kt:257`) now prefers the engine's projected `conversationKind` over the name/headcount heuristic, but `ConversationController`'s `isDm`/`isDirectConversation` — used for the open conversation's top-bar subtitle gating and, more importantly, `deleteCapabilityFor`'s deletion-capability matrix — still use the old heuristic exclusively. If the engine's projection and the heuristic ever disagree for a given conversation (the whole reason this PR trusts the engine over the heuristic in the chat list), the chat list and the open conversation screen can now classify the same conversation differently, and delete permissions are computed from the *un*-upgraded classification. This may be intentional incremental scoping ("where rows are at hand" per the commit message), but it's worth confirming deliberately rather than leaving it as a silent gap.

### Local media-kind resolution no longer short-circuits when the engine already projects `attachmentKind`

**app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt:626-632, 3587-3600**

> ```kotlin
> internal fun chatRowNeedsMediaKindResolve(row: ChatListRowFfi): String? {
>     val preview = row.lastMessage ?: return null
>     if (preview.deleted) return null
>     if (preview.kind != 9uL) return null
>     if (preview.plaintext.isNotBlank()) return null
>     return preview.messageIdHex.takeIf { it.isNotBlank() }
> }
> ```

`projectedPreviewText` now prefers `preview.attachmentKind` over `resolvedMediaPreviewFallback` (`Controllers.kt:278-282`), but this gate (which schedules an off-main `timelineMessages` FFI round-trip per qualifying row, `Controllers.kt:3587-3641`) doesn't check `preview.attachmentKind != null` before deciding a row "needs" resolving. For every blank-plaintext kind-9 row whose attachment kind the engine already projects — presumably the common case this whole feature targets — the app still performs a redundant local timeline fetch whose result `projectedPreviewText` will never use. Not a correctness bug (the app-side fallback is simply overridden), but a needless per-row FFI round trip that the new projection should let this gate skip.

### `markUnread` reuses a merge function purpose-built for mark-*read* semantics

**app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt:2771-2781, 3278-3292**

`ChatsController.markUnread` folds `setChatManuallyUnread`'s return row through `applyChatListRow` → `mergeMarkReadChatListRow`. That merge function has a branch (`Controllers.kt:484-490`) that, when the returned row's `lastMessage` compares as older than the in-memory row's (e.g. a live subscription update lands during the FFI round trip), discards everything from the returned row except the read watermark — silently dropping the just-applied `manuallyMarkedUnread = true`. Narrow window, and `markAllRead` already has the same shape of race, but worth a comment (or a small dedicated merge) noting the mark-unread flag can be lost under that race rather than reusing read-specific merge logic implicitly.

## What's Done Well

- Every new pure-function surface (`retentionIndicatorVisible`, `GroupProjector.isDm` overload, `attachmentLabel`, `outgoingIndicator()`, `leaveRequestPending` suppression) ships with a focused unit test, including the deliberately-adversarial cases (explicit `0` retention, `UNKNOWN`/`null` conversation kind fallback, deleted last message suppressing the delivery tick).
- The `MessageInlineFooter` layout change (retention icon insertion) correctly recomputes `timeIndex` from both optional leading elements instead of hardcoding an index, so the edited-label/retention-icon/time/status ordering stays correct in every combination.
- Localization: both new strings (`media_album`, `media_counted_format`) landed in all nine locale files plus the default, and the `LocalizationResourceTest` false-positive-identical-value suppression list was updated with a clear rationale rather than just silenced.
- `syncEngineMute`'s docstring is explicit that local preferences remain the source of truth for notification suppression and the engine write is convergence-only — a good design note that made the inconsistency in the Issues section easy to pin down precisely.
