package dev.ipf.whitenoise.android.state

import androidx.compose.ui.graphics.ImageBitmap
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowActionsFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupSystemPreviewNames
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import java.util.Locale

enum class ChatListAvatarSource {
    DURABLE,
    LEGACY_URL,
    ENCRYPTED_GROUP,
    FALLBACK_URL,
}

data class ChatListAvatarSeed(
    val source: ChatListAvatarSource,
    val key: String,
    val image: ImageBitmap,
    val accountRef: String? = null,
    // staleness-exempt: captured loader lifetime, not an independent counter owner.
    val cacheLifetime: Long? = null,
    val animationClassification: Boolean? = null,
)

/** A peer projection stands for a two-person conversation. */
private const val DIRECT_CHAT_MEMBER_COUNT = 2

/**
 * Build the first notification-open frame solely from the exact local row.
 * Membership enrichment is intentionally absent from this boundary because it
 * is loaded by the conversation after navigation and may be queued behind
 * account activation. The row still preserves pre-read unread state.
 */
internal fun chatListItemFromNotificationProjection(
    projection: ChatListRowFfi,
    accountIdHex: String? = null,
): ChatListItem = chatListItemFromProjection(row = projection, activeAccountIdHex = accountIdHex)

/**
 * Builds a chat-list item from the engine projection and its selected presentation.
 * [members] remains the only authoritative source for membership-sensitive fields;
 * [presentationMembers] supplies display continuity while a newer roster loads.
 */
internal fun chatListItemFromProjection(
    row: ChatListRowFfi,
    selectedPresentation: ConversationPresentationFfi? = null,
    selectedAvatarAsset: AvatarAssetFfi? = null,
    selectedPreview: SelectedChatPreviewFfi? = null,
    actions: ChatListRowActionsFfi? = null,
    group: AppGroupRecordFfi? = null,
    activeAccountIdHex: String? = null,
    members: List<AppGroupMemberRecordFfi>? = null,
    presentationMembers: ChatListMemberPresentation? = null,
    previewTokens: MarkdownDocumentFfi? = chatRowPreviewTokens(row),
    resolvedMediaPreviewFallback: MediaPreviewFallback? = null,
    removed: Boolean = false,
    activitySequence: ULong = 0uL,
    awaitingSendPreview: Boolean = false,
    hasOptimisticSendPreview: Boolean = false,
): ChatListItem {
    val baseGroup = group ?: emptyGroupRecord(row)
    val presentation = members?.let { chatListMemberPresentation(it, activeAccountIdHex) } ?: presentationMembers
    val selectedPeer = selectedPresentation?.peerId?.takeIf { it.isNotBlank() && !it.equals(activeAccountIdHex, true) }
    val resolvedMemberCount = presentationMemberCount(selectedPresentation, selectedPeer, presentation)
    // A replaced group picture still selected by a dirty presentation yields to the row's current picture.
    val supersededPicture = selectedPresentation?.selectsSupersededGroupPicture(row) == true
    val displayGroup =
        chatListDisplayGroup(
            row,
            baseGroup,
            selectedPresentation.takeUnless { supersededPicture },
            resolvedMemberCount,
        )
    return ChatListItem(
        group = displayGroup,
        ownerAccountIdHex = activeAccountIdHex,
        latest = chatListLatestMessage(row),
        otherMemberAccount = members?.let { GroupProjector.otherMemberAccount(it, activeAccountIdHex) },
        memberCount = members?.let(GroupProjector::uniqueMemberCount) ?: 0,
        memberSnapshot = members?.let(::GroupMemberSnapshot),
        presentationOtherMemberAccount = selectedPeer ?: presentation?.otherMemberAccount,
        presentationMemberCount = resolvedMemberCount,
        presentationActiveAccountIsSoleMember = presentation?.activeAccountIsSoleMember == true,
        projection = row,
        inviteConfirmationUnresolved =
            !displayGroup.selfMembership.isNonMember() &&
                (group?.let { it.pendingConfirmation != row.pendingConfirmation } ?: row.pendingConfirmation),
        selectedPresentation =
            if (supersededPicture) {
                selectedPresentation?.copy(
                    avatar = SelectedAvatarFfi.Placeholder(row.groupIdHex, PresentationSourceFfi.GROUP_FALLBACK),
                    avatarSource = PresentationSourceFfi.GROUP_FALLBACK,
                )
            } else {
                selectedPresentation
            },
        selectedAvatarAsset =
            adoptableSelectedAvatarAsset(
                asset = selectedAvatarAsset.takeUnless { supersededPicture },
                avatarSource = selectedPresentation?.avatarSource,
                group = displayGroup,
                memberCount = resolvedMemberCount,
            ),
        selectedPreview = selectedPreview,
        actions = actions,
        previewTokens = previewTokens,
        resolvedMediaPreviewFallback = resolvedMediaPreviewFallback,
        removed = removed,
        activitySequence = activitySequence,
        awaitingSendPreview = awaitingSendPreview,
        hasOptimisticSendPreview = hasOptimisticSendPreview,
    )
}

/** Uses the selected group count, then the direct peer, then the roster retained for display. */
private fun presentationMemberCount(
    selected: ConversationPresentationFfi?,
    peer: String?,
    roster: ChatListMemberPresentation?,
): Int {
    val unnamedGroup = selected?.title as? PresentationTextFfi.UnnamedGroup
    return unnamedGroup?.memberCount?.coerceAtMost(Int.MAX_VALUE.toULong())?.toInt()
        ?: if (peer != null) DIRECT_CHAT_MEMBER_COUNT else roster?.memberCount ?: 0
}

/**
 * The real activity time of a chat another of the account's devices added here, or null for any other chat. MDK
 * stamps such a copy's `conversationCreatedAt` with the moment it was added, which is newer than all of its imported
 * history, so that stamp becomes `activitySortAt` and every chat on a newly linked device sorts by when it arrived
 * (mdk#3580). A conversation created after its own last message can only be such a copy, so its activity is the
 * newest message or read position instead. Remove once MDK carries the chat's own creation time.
 */
private fun ChatListRowFfi.linkedCopyActivityAt(): ULong? {
    val lastAt = lastMessage?.timelineAt?.takeIf { it > 0uL } ?: return null
    val installedAfterHistory = activitySortAt == conversationCreatedAt && conversationCreatedAt > lastAt
    return if (installedAfterHistory) maxOf(lastAt, lastReadTimelineAt ?: 0uL) else null
}

/** The chat-list row's latest message as a renderable record, or null when the row has none. */
private fun chatListLatestMessage(row: ChatListRowFfi): AppMessageRecordFfi? =
    row.lastMessage?.let { preview ->
        AppMessageRecordFfi(
            invalidated = false,
            messageIdHex = preview.messageIdHex,
            direction = "received",
            groupIdHex = row.groupIdHex,
            sender = preview.sender,
            plaintext = preview.plaintext,
            contentTokens = EMPTY_MARKDOWN_DOCUMENT,
            kind = preview.kind,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = preview.retentionSeconds,
            retentionExpiresAt = preview.retentionExpiresAt,
            recordedAt = preview.timelineAt,
            receivedAt = preview.timelineAt,
            sticker = preview.sticker,
        )
    }

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
    /** Conflicting confirmation snapshots require a canonical MDK read before showing actions. */
    val inviteConfirmationUnresolved: Boolean = false,
    /** MDK-selected title/avatar/peer projection for this exact chat row. */
    val selectedPresentation: ConversationPresentationFfi? = null,
    /** MarmotKit's durably stored avatar for this row (0.10.1); preferred over fetching its URL. */
    val selectedAvatarAsset: AvatarAssetFfi? = null,
    /** MarmotKit-selected message, draft, invitation, or empty preview for this row. */
    val selectedPreview: SelectedChatPreviewFfi? = null,
    val awaitingSendPreview: Boolean = false,
    val hasOptimisticSendPreview: Boolean = false,
    /** All ten advisory row capabilities from the same native snapshot as [projection]. */
    val actions: ChatListRowActionsFfi? = null,
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
    /** The identity whose native row this is, including rows displayed for an inactive profile. */
    val ownerAccountIdHex: String? = null,
) {
    /** Inviter or direct peer used by avatar fallbacks across chat, picker, notification and shortcut surfaces. */
    internal val avatarAccount: String?
        get() =
            GroupProjector.avatarAccount(
                group,
                presentationOtherMemberAccount,
                presentationMemberCount,
                ownerAccountIdHex,
            )

    val id: String = group.groupIdHex

    /** Presentation only: conflicting snapshots cannot establish invitation consent. */
    val checkingInvitation: Boolean
        get() =
            inviteConfirmationUnresolved &&
                group.acceptsInviteResults() &&
                projection?.disbanding != true &&
                projection?.lifecycleState != GroupLifecycleStateFfi.DISBANDED

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

    /** The selected remote avatar URL, sanitized before any network loader sees it. */
    val selectedAvatarUrl: String?
        get() =
            (selectedPresentation?.avatar as? SelectedAvatarFfi.RemoteImage)
                ?.url
                ?.let(ProfileSanitizer::protocolImageUrl)

    /** Stable MDK-owned identity used by generated-avatar fallbacks. */
    val selectedAvatarSeed: String?
        get() =
            when (val avatar = selectedPresentation?.avatar) {
                is SelectedAvatarFfi.RemoteImage -> avatar.cacheKey
                is SelectedAvatarFfi.EncryptedGroupImage -> avatar.cacheKey
                is SelectedAvatarFfi.Placeholder -> avatar.stableSeed
                null -> null
            }?.takeIf(String::isNotBlank)

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

    /** Time of the message shown to the reader. Empty and pruned rows retain their last known activity. */
    val previewTimestamp: ULong?
        get() = projection?.lastMessage?.timelineAt?.takeIf { it > 0uL } ?: latestAt

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
            projection?.linkedCopyActivityAt()
                ?: projection?.activitySortAt?.takeIf { it > 0uL }
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

    /**
     * Delivery tick for the projected last message, or null for no tick. MDK reports a delivery state only for a
     * message this installation sent. A last message [ownAccountIdHex] wrote on another of its devices has none, yet
     * it is the account's own and already delivered, so it gets the sent tick instead of reading as received.
     */
    fun projectedDeliveryIndicator(ownAccountIdHex: String? = null): OutgoingMessageIndicator? {
        val last = projection?.lastMessage?.takeUnless { it.deleted }
        return when {
            awaitingSendPreview -> OutgoingMessageIndicator.Sending
            last == null -> null
            last.fromAnotherOwnDevice(ownAccountIdHex) -> OutgoingMessageIndicator.Sent
            else -> last.deliveryState.outgoingIndicator()
        }
    }

    /** Whether this preview is a message [ownAccountIdHex] wrote that this installation did not send. */
    private fun ChatListMessagePreviewFfi.fromAnotherOwnDevice(ownAccountIdHex: String?): Boolean {
        val received = deliveryState == ChatListMessageDeliveryStateFfi.NOT_APPLICABLE && groupSystem == null
        return received && ownAccountIdHex != null && sender.equals(ownAccountIdHex, ignoreCase = true)
    }

    /** The engine's durable mute projection — ORed with local preferences. */
    fun engineMuted(): Boolean = projection?.muted == true

    /** Engine-durable pin state; unprojected rows read as unpinned. */
    fun pinned(): Boolean = projection?.pinned == true

    /** Zero-based display position inside the pinned block, engine-normalized. */
    fun pinnedPosition(): UInt? = projection?.pinnedPosition

    /** Projected conversation kind first, presentation headcount heuristic as fallback. */
    fun isDm(): Boolean = GroupProjector.isDm(projection?.conversationKind, presentationMemberCount, group.name)

    /**
     * Converts the latest native projection to chat-list copy without rendering poll wire data. [groupSystemNames]
     * lets a membership row name the member it affected; without it the row keeps its name-free passive form.
     */
    fun projectedPreviewText(
        copy: MessageTextCopy = MessageTextCopy.Default,
        empty: String = "No messages yet",
        groupSystemNames: GroupSystemPreviewNames? = null,
    ): String {
        val preview = projection?.lastMessage
        return projectedChatListPreviewText(
            preview =
                preview?.let {
                    ChatListPreviewInput(
                        it.plaintext,
                        it.kind,
                        it.deleted,
                        it.attachmentKind,
                        it.attachmentCount,
                        it.groupSystem,
                        isSticker = it.sticker != null,
                    )
                },
            copy = copy,
            empty = empty,
            groupSystemNames = groupSystemNames,
            mediaFallback = resolvedMediaPreviewFallback,
            latestFallback = { fallback -> MessageProjector.previewText(latest, copy, fallback) },
        )
    }
}
