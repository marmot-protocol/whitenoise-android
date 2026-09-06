package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import java.util.Locale

data class ChatListItem(
    val group: AppGroupRecordFfi,
    val latest: AppMessageRecordFfi?,
    /** Counterparty resolved only from the current authoritative roster. */
    val otherMemberAccount: String?,
    /** Headcount resolved only from the current authoritative roster. */
    val memberCount: Int,
    /** Current authoritative roster; never populated from stale presentation state. */
    val memberSnapshot: GroupMemberSnapshot?,
    /**
     * Last-known values retained only for title/avatar continuity while a newer
     * roster is loading. Membership-sensitive consumers must use the fields
     * above instead.
     */
    val presentationOtherMemberAccount: String? = otherMemberAccount,
    val presentationMemberCount: Int = memberCount,
    val presentationActiveAccountIsSoleMember: Boolean = false,
    val projection: ChatListRowFfi? = null,
    /**
     * Bounded snapshot of decoded pixels that were already in a presentation
     * loader when this row was published. Holding the hit on the immutable row
     * closes the publication-to-composition eviction race without starting or
     * waiting for network/decode work.
     */
    val firstFrameAvatar: ChatListAvatarSeed? = null,
    /**
     * Markdown AST for the last-message preview line. [ChatsController]
     * prefers MDK's projected document on the first frame and parses off-main
     * only when that AST is empty. Null (or an empty document) means the row
     * renders the raw plaintext fallback.
     * Only attached when the preview would show the message body itself
     * (non-deleted, non-blank), so fallback copy is never styled.
     */
    val previewTokens: MarkdownDocumentFfi? = null,
    /**
     * When the chat-list projection's last message has a blank body, the
     * engine row carries no `imeta` tags. [ChatsController] resolves the kind
     * and filename fallback off-main from the local timeline (same source of
     * truth as the row) and attaches it here so [projectedPreviewText] can
     * preserve the same caption -> filename -> media label precedence as
     * [MessageProjector.previewText].
     */
    val resolvedMediaPreviewFallback: MediaPreviewFallback? = null,
    /**
     * Known removal evidence that the [memberSnapshot] roster alone can't
     * carry: a successful self-leave (including leaving as the sole member,
     * which caches an *empty* roster) or a loaded roster that omits self.
     * Set by [ChatsController] when removal is established; lets
     * [removedFromGroup] treat a known-empty-post-removal roster as real
     * removal while a null/failed-fetch empty roster stays non-removed.
     */
    val removed: Boolean = false,
    /**
     * In-memory arrival order for activities that share the engine's
     * unix-seconds sort timestamp. Zero for ordinary standalone projections;
     * [ChatsController] supplies a monotonic value for live/local activity.
     */
    val activitySequence: ULong = 0uL,
) {
    val id: String = group.groupIdHex

    /**
     * Case-folded group id, for the folder membership sets that key chats by
     * lowercased hex. Folded once per projection so a folder-chip pass over F
     * folders stops allocating F lowercase copies of every row's id.
     */
    internal val foldedId: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        group.groupIdHex.lowercase(Locale.ROOT)
    }

    val projectedTitle: String?
        get() = projection?.title?.takeIf { it.isNotBlank() }

    /**
     * The title a NAMED group's row displays and sorts by: the projection's
     * title when present, else the raw group name, both routed through
     * [ProfileSanitizer.displayName] because the name is peer-supplied —
     * bidi overrides / zero-width spoofing chars must never reach the UI or
     * the sort key (#980). Null for unnamed groups, or when sanitization
     * strips the whole name; callers then fall back to the same
     * unnamed-group projection the row already uses.
     *
     * Memoized per projection. Sanitization is NFKC normalization plus a
     * code-point scan and a regex whitespace collapse, and this value is read
     * by the row label, the search filter, folder keyword rules, and the
     * chat-list sort tie-break — the last of which asked for it once per
     * comparison. The inputs are constructor state of an immutable
     * projection, so the answer cannot change for a given instance.
     * `PUBLICATION` keeps concurrent readers safe without a lock: the
     * computation is pure, so whichever result publishes first is correct.
     * A `lazy` body property takes no part in `equals`, `hashCode`, or `copy`.
     */
    val sanitizedNamedTitle: String? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        group.name.takeIf { it.isNotBlank() }?.let { raw ->
            // Fall back to the raw name when a stale projected title
            // sanitizes away entirely, mirroring chatListItemDisplayTitle's
            // recovery path so a row never renders named but sorts unnamed.
            projectedTitle?.let(ProfileSanitizer::displayName)
                ?: ProfileSanitizer.displayName(raw)
        }
    }

    /**
     * Case-folded tie-break key for [sortChatListItems], memoized for the same
     * reason as [sanitizedNamedTitle]: the comparator evaluates its last key
     * for both operands of every tied comparison, so an unmemoized key costs
     * O(n log n) sanitizations and lowercase allocations per rebuild instead
     * of at most one per row.
     */
    internal val sortTitleKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        sanitizedNamedTitle?.lowercase()
            ?: (presentationOtherMemberAccount ?: "~$presentationMemberCount").lowercase()
    }

    val latestAt: ULong?
        // Prefer MDK's durable semantic activity timestamp. It is advanced by
        // real conversation activity and retained across secure pruning, while
        // `updatedAt` is merely the projection rebuild time. This keeps an
        // all-pruned unread chat in its original position instead of promoting
        // it when the prune rebuilds the row (#866).
        //
        // The remaining chain is defensive compatibility for synthetic/legacy
        // rows whose semantic timestamps are zero. `conversationCreatedAt`
        // preserves the correct position for a genuinely empty conversation;
        // `updatedAt` remains the final projection fallback.
        //
        // All values use the same unix-seconds unit as `timelineAt`.
        get() =
            projection?.activitySortAt?.takeIf { it > 0uL }
                ?: projection?.lastMessage?.timelineAt
                ?: projection?.lastReadTimelineAt
                ?: projection?.conversationCreatedAt?.takeIf { it > 0uL }
                ?: projection?.updatedAt
                ?: latest?.recordedAt

    val unreadCount: ULong
        get() = projection?.unreadCount ?: 0uL

    val hasUnread: Boolean
        // A manual mark-unread renders the same badge as real unread; the
        // engine clears it when the conversation is read again.
        get() = projection?.hasUnread == true || projection?.manuallyMarkedUnread == true

    /** At least one unread message in this chat mentions the active account. */
    val unreadMention: Boolean
        get() = projection?.unreadMention ?: false

    /**
     * Whether the active account is no longer a member of this group. Two
     * independent signals establish this:
     *
     *  - [removed]: an explicit marker [ChatsController] sets once removal is
     *    *known* — a self-leave (including leaving as the sole member, which
     *    caches an empty roster) or a roster fetch that loaded and omits self.
     *    This is what lets a genuinely-empty post-removal roster suppress the
     *    badge, since an empty [memberSnapshot] alone is ambiguous.
     *  - a *loaded, non-empty* [memberSnapshot] that omits self — the engine's
     *    `groupMembers` roster landed and self isn't in it.
     *
     * A null snapshot (fetch hasn't landed) and an empty snapshot *without* the
     * [removed] marker (a best-effort fetch failure) are both treated as "not
     * yet known", so neither suppresses the row's badge. Returns false with a
     * blank/absent active account, matching [GroupProjector] semantics.
     */
    fun removedFromGroup(activeAccountIdHex: String?): Boolean {
        val active = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        // Authoritative: the reconciled group and source row both carry the
        // local account's own membership. REMOVED (evicted) and LEFT (voluntary)
        // both mean non-member, and take precedence over the roster heuristic
        // below (which stays as a fallback for the optimistic self-leave window
        // and rows whose projection hasn't landed yet).
        if (group.selfMembership.isNonMember() || projection?.selfMembership?.isNonMember() == true) return true
        // A durably-queued leave reads as already-left: the user asked to go,
        // and the engine retries the commit until the group agrees.
        if (projection?.leaveRequestPending == true) return true
        // A disbanded group is terminal for every member; while a disband
        // converges the engine already gates ordinary work, so the row offers
        // no member affordances either way.
        if (projection?.lifecycleState == GroupLifecycleStateFfi.DISBANDED) return true
        if (projection?.disbanding == true) return true
        if (removed) return true
        val snapshot = memberSnapshot?.takeIf { it.members.isNotEmpty() } ?: return false
        return !snapshot.containsAccount(active)
    }

    /**
     * The unread badge to render for this row given the active account. A group
     * the user has been removed from keeps a frozen [unreadCount] in the
     * projection (the engine stops advancing reads once self is evicted), which
     * reads as a stale alert; suppress it to zero so a removed group shows no
     * badge (#625).
     */
    fun effectiveUnreadCount(activeAccountIdHex: String?): ULong = if (removedFromGroup(activeAccountIdHex)) 0uL else unreadCount

    /** [hasUnread] with the removed-group suppression applied (#625). */
    fun effectiveHasUnread(activeAccountIdHex: String?): Boolean = hasUnread && !removedFromGroup(activeAccountIdHex)

    /** Delivery tick for the projected last message, or null for no tick. */
    fun projectedDeliveryIndicator(): OutgoingMessageIndicator? =
        projection
            ?.lastMessage
            ?.takeUnless { it.deleted }
            ?.deliveryState
            ?.outgoingIndicator()

    /** The engine's durable mute projection — ORed with local preferences. */
    fun engineMuted(): Boolean = projection?.muted == true

    /** Engine-durable pin state; unprojected rows read as unpinned. */
    fun pinned(): Boolean = projection?.pinned == true

    /** Zero-based display position inside the pinned block, engine-normalized. */
    fun pinnedPosition(): UInt? = projection?.pinnedPosition

    /** Projected conversation kind first, presentation headcount heuristic as fallback. */
    fun isDm(): Boolean = GroupProjector.isDm(projection?.conversationKind, presentationMemberCount, group.name)

    fun projectedPreviewText(
        copy: MessageTextCopy = MessageTextCopy.Default,
        empty: String = "No messages yet",
    ): String {
        val preview = projection?.lastMessage ?: return MessageProjector.previewText(latest, copy, empty)
        return when {
            preview.deleted -> copy.deleted
            preview.kind == 1200uL -> preview.plaintext.ifBlank { copy.agentStreamStarted }
            // Kind-1009 edits are an in-place mutation of an existing
            // message body; they must not bump the chat-list preview to
            // "edit content" nor reorder the conversation. The original
            // [latest] message stays projected — drop this row's edit
            // payload from the preview text path.
            preview.kind == 1009uL -> MessageProjector.previewText(latest, copy, empty)
            // Before the generic plaintext arm: a kind-1210 last message would
            // otherwise leak its raw JSON content into the chat list.
            MessageProjector.isGroupSystemKind(preview.kind) ->
                GroupSystemEvents.previewText(preview.plaintext, copy.groupSystem)
            preview.plaintext.isNotBlank() -> preview.plaintext
            // The engine's typed attachment projection beats the app-side
            // fallback, which derives from tags and optimistic state.
            preview.attachmentKind != null ->
                copy.attachmentLabel(requireNotNull(preview.attachmentKind), preview.attachmentCount)
            resolvedMediaPreviewFallback != null -> resolvedMediaPreviewFallback.text(copy)
            else -> MessageProjector.previewText(latest, copy, copy.message)
        }
    }
}
