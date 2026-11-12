package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowActionsFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListViewQueryFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.ChatPinStateFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.diagnostics.DmCreationAttempt
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.newchat.directChatPreferenceOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Message id of a row whose chat-list preview body is blank and must be
 * resolved from the local timeline before a typed media label can render.
 */
internal fun chatRowNeedsMediaKindResolve(row: ChatListRowFfi): String? {
    val preview = row.lastMessage ?: return null
    if (preview.deleted) return null
    if (preview.kind != 9uL) return null
    if (preview.plaintext.isNotBlank()) return null
    // The engine's typed attachment projection already labels this preview —
    // no local timeline read needed.
    if (preview.attachmentKind != null) return null
    return preview.messageIdHex.takeIf { it.isNotBlank() }
}

/**
 * Build a conversation-open [ChatListItem] from a targeted authoritative
 * [groupDetails] read. Used immediately after create returns a canonical group
 * id, before the broad chat-list subscription materializes a row (#1729).
 */
internal fun chatListItemFromAuthoritativeGroupDetails(
    details: GroupDetailsFfi,
    activeAccountIdHex: String?,
): ChatListItem {
    val applied = applyAuthoritativeGroupDetails(details)
    val members = applied.members
    return ChatListItem(
        group = applied.group,
        latest = null,
        otherMemberAccount = GroupProjector.otherMemberAccount(members, activeAccountIdHex),
        memberCount = GroupProjector.uniqueMemberCount(members),
        memberSnapshot = members.let(::GroupMemberSnapshot),
        projection = null,
    )
}

/**
 * Build the first notification-open frame solely from the exact local row.
 * Membership enrichment is intentionally absent from this boundary because it
 * is loaded by the conversation after navigation and may be queued behind
 * account activation. The row still preserves pre-read unread state.
 */
internal fun chatListItemFromNotificationProjection(projection: ChatListRowFfi): ChatListItem = chatListItemFromProjection(row = projection)

/**
 * When a provisional open (no chat-list row yet) is already foregrounded,
 * upgrade [open] to the authoritative projected row exactly once when it
 * arrives — no second navigation event and no duplicate list row.
 */
internal fun reconcileOpenChatWithAuthoritativeRow(
    open: ChatListItem,
    authoritative: ChatListItem,
): ChatListItem? =
    when {
        !open.group.groupIdHex.equals(authoritative.group.groupIdHex, ignoreCase = true) -> null
        open.projection != null -> null
        authoritative.projection == null -> null
        else -> authoritative
    }

/**
 * Upgrade a provisional foreground open using the live chat-list backing row,
 * without waiting for the frozen [ChatsController.items] snapshot while the
 * list is hidden behind an open conversation (#1729).
 */
internal fun reconcileProvisionalOpenChat(
    open: ChatListItem,
    chatsController: ChatsController,
): ChatListItem? {
    if (open.projection != null) return null
    val authoritative =
        chatsController
            .chatItemForGroup(open.group.groupIdHex)
            ?.takeIf { it.projection != null }
    return authoritative?.let { reconcileOpenChatWithAuthoritativeRow(open, it) }
}

internal fun cacheAppliedGroupMembers(
    appState: WhiteNoiseAppState,
    account: String,
    groupIdHex: String,
    members: List<AppGroupMemberRecordFfi>,
) {
    appState.cacheGroupMemberSnapshot(account, groupIdHex, members)
    appState.requestProfiles(members.map { it.memberIdHex })
}

internal data class AuthoritativeChatListMembers(
    val memberCacheByGroup: Map<String, List<AppGroupMemberRecordFfi>>,
    val removedGroupIds: Set<String>,
)

/**
 * Whether a roster returned from `groupMembers` is complete enough to cache.
 * An empty roster can be a transient catch-up result and must stay uncached so
 * the chat list retries instead of pinning an Unknown DM title. Once self-
 * removal is independently known, an empty roster is terminal and cacheable.
 * A joined direct conversation containing only self is likewise unresolved for
 * one grace retry: this absorbs transient hydration gaps without preserving a
 * departed peer forever.
 */
internal fun memberSnapshotReadyToCache(
    members: List<AppGroupMemberRecordFfi>,
    knownSelfRemoval: Boolean = false,
    directConversation: Boolean = false,
    activeAccountIdHex: String? = null,
    selfOnlyDirectGraceElapsed: Boolean = false,
): Boolean {
    if (knownSelfRemoval) return true
    val active = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() }
    val directRosterReady =
        !directConversation ||
            active == null ||
            members.none { GroupProjector.isActiveAccountMember(it, active) } ||
            members.any { !GroupProjector.isActiveAccountMember(it, active) } ||
            selfOnlyDirectGraceElapsed
    return members.isNotEmpty() && directRosterReady
}

private fun isSelfOnlyDirectRoster(
    members: List<AppGroupMemberRecordFfi>,
    directConversation: Boolean,
    activeAccountIdHex: String?,
): Boolean = directConversation && GroupProjector.isSelfSoleMember(members, activeAccountIdHex)

internal fun memberSnapshotRetryDelayMillis(backoffTier: Int): Long {
    var delayMs = MEMBER_FETCH_INITIAL_RETRY_DELAY_MS
    repeat(backoffTier.coerceIn(0, MEMBER_FETCH_MAX_BACKOFF_TIER)) {
        delayMs = nextRetryBackoffMillis(delayMs, MEMBER_FETCH_MAX_RETRY_DELAY_MS)
    }
    return delayMs
}

internal fun applyAuthoritativeChatListMembers(
    groupIdHex: String,
    members: List<AppGroupMemberRecordFfi>,
    activeAccountIdHex: String?,
    memberCacheByGroup: Map<String, List<AppGroupMemberRecordFfi>>,
    removedGroupIds: Set<String>,
): AuthoritativeChatListMembers {
    val normalizedActive = activeAccountIdHex?.trim()?.takeIf { it.isNotEmpty() }
    val activeMissing =
        normalizedActive != null &&
            members.none { GroupProjector.isActiveAccountMember(it, normalizedActive) }
    return AuthoritativeChatListMembers(
        memberCacheByGroup = memberCacheByGroup + (groupIdHex to members),
        removedGroupIds = if (activeMissing) removedGroupIds + groupIdHex else removedGroupIds - groupIdHex,
    )
}

private data class OptimisticChatListPreviewMatch(
    val entryKey: String?,
    val activitySequence: ULong,
)

private data class RemovedChatRowSnapshot(
    val row: ChatListRowFfi,
    val activitySequence: ULong,
    val optimisticState: OptimisticChatListPreviewState?,
)

internal fun shouldPreserveChatListProjection(
    hasSeededLocalSnapshot: Boolean,
    preserveLoadedContent: Boolean,
    hasLoadedLocalSnapshot: Boolean,
): Boolean = hasSeededLocalSnapshot || (preserveLoadedContent && hasLoadedLocalSnapshot)

internal fun isEligibleForwardTarget(
    item: ChatListItem,
    activeAccountIdHex: String?,
): Boolean = !item.group.pendingConfirmation && !item.removedFromGroup(activeAccountIdHex)

private fun AppGroupRecordFfi.debugSummary(): String =
    "id=${groupIdHex.take(8)} archived=$archived pending=$pendingConfirmation " +
        "welcomer=${welcomerAccountIdHex?.take(8)} relays=${relays.size} name=${name.ifBlank { "<blank>" }}"

private fun ChatListRowFfi.debugSummary(): String =
    "id=${groupIdHex.take(8)} archived=$archived pending=$pendingConfirmation unread=$unreadCount " +
        "last=${lastMessage?.messageIdHex?.take(8)} title=${title.ifBlank { "<blank>" }}"

private fun logStaleChatListUnreadRejected(
    keptUnread: ULong,
    rejectedUnread: ULong,
) {
    Log.w(
        "DMChats",
        "stale chat-list unread rejected kept=$keptUnread rejected=$rejectedUnread source=client_stale_fold",
    )
}

private const val MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY = 64
private const val CHAT_LIST_AVATAR_WARM_ROWS = 24

// Maximum number of `groupMembers` FFI roster reads running at once from the
// chat-list projection. Keeps large accounts from flooding IO at startup while
// still letting shared-group snapshots materialize in the background.
private const val MEMBER_FETCH_FANOUT = 4
private const val INITIAL_MEMBER_FALLBACK_FANOUT = 8
private const val INITIAL_MEMBER_FALLBACK_CUTOFF_MS = 500L

private const val NANOS_PER_MILLISECOND = 1_000_000L

private const val MEMBER_FETCH_INITIAL_RETRY_DELAY_MS = 250L
private const val MEMBER_FETCH_MAX_RETRY_DELAY_MS = 300_000L
private const val MEMBER_FETCH_MAX_BACKOFF_TIER = 11
private const val PREVIEW_PARSE_FANOUT = 4
private const val MEDIA_KIND_RESOLVE_FANOUT = 4

/** Retry cadence while MarmotKit reports a documented not-ready state; well under the connectivity backoff. */
internal const val NOT_READY_RETRY_DELAY_MS = 2_000L

/** A failed account-wide read leaves the picker incomplete, so it reports FAILED and offers Retry. */
private fun ProfileGroupPickerState.failedWhenAccountWideReadFailed(accountWide: ProfileAccountWideGroups) =
    if (accountWide.readFailed) copy(loadState = ProfileGroupPickerLoadState.FAILED) else this

/** Owns the active account's chat-list projection and live subscription lifecycle. */
class ChatsController private constructor(
    internal val appState: WhiteNoiseAppState,
    private val memberSnapshotLoader: suspend (String, String) -> List<AppGroupMemberRecordFfi>,
    private val memberSnapshotRetryDelay: (Int) -> Long,
    private val groupArchivedUpdater: suspend (String, String, Boolean) -> AppGroupRecordFfi,
    initialAccountRef: String?,
    initialLocalSnapshot: AccountSwitchLocalSnapshot?,
    private val initialConnectionAttemptClaim: () -> Boolean,
) {
    internal val liveSubscriptions = appState.chatListLiveSubscriptions()

    /** Creates a standalone controller whose initial subscription validation stays silent. */
    constructor(appState: WhiteNoiseAppState) :
        this(appState = appState, initialConnectionAttemptClaim = { false })

    /**
     * Creates the process-shell controller whose first live subscription may
     * claim the one cold-start connection presentation owned by that process.
     */
    internal constructor(
        appState: WhiteNoiseAppState,
        initialConnectionAttemptClaim: () -> Boolean,
    ) :
        this(
            appState = appState,
            memberSnapshotLoader = { accountRef, groupIdHex ->
                appState.marmotIo { groupMembers(accountRef, groupIdHex) }
            },
            memberSnapshotRetryDelay = ::memberSnapshotRetryDelayMillis,
            groupArchivedUpdater = { accountRef, groupIdHex, archived ->
                appState.marmotIo { setGroupArchived(accountRef, groupIdHex, archived) }
            },
            initialAccountRef = null,
            initialLocalSnapshot = appState.consumeAccountSwitchLocalSnapshot(appState.activeAccountRef),
            initialConnectionAttemptClaim = initialConnectionAttemptClaim,
        )

    /** Creates a test controller with injected member loading and no retained local snapshot. */
    internal constructor(
        appState: WhiteNoiseAppState,
        initialAccountRef: String,
        memberSnapshotRetryDelay: (Int) -> Long = ::memberSnapshotRetryDelayMillis,
        memberSnapshotLoader: suspend (String, String) -> List<AppGroupMemberRecordFfi>,
    ) : this(
        appState,
        memberSnapshotLoader,
        memberSnapshotRetryDelay,
        { accountRef, groupIdHex, archived ->
            appState.marmotIo { setGroupArchived(accountRef, groupIdHex, archived) }
        },
        initialAccountRef,
        null,
        { false },
    )

    /** Creates a test controller seeded with an account-switch local projection. */
    internal constructor(
        appState: WhiteNoiseAppState,
        initialAccountRef: String,
        initialLocalSnapshot: AccountSwitchLocalSnapshot,
        memberSnapshotRetryDelay: (Int) -> Long = ::memberSnapshotRetryDelayMillis,
        memberSnapshotLoader: suspend (String, String) -> List<AppGroupMemberRecordFfi>,
    ) : this(
        appState,
        memberSnapshotLoader,
        memberSnapshotRetryDelay,
        { accountRef, groupIdHex, archived ->
            appState.marmotIo { setGroupArchived(accountRef, groupIdHex, archived) }
        },
        initialAccountRef,
        initialLocalSnapshot,
        { false },
    )

    /** Creates a test controller with injected member loading and archive mutation behavior. */
    internal constructor(
        appState: WhiteNoiseAppState,
        initialAccountRef: String,
        memberSnapshotRetryDelay: (Int) -> Long = ::memberSnapshotRetryDelayMillis,
        memberSnapshotLoader: suspend (String, String) -> List<AppGroupMemberRecordFfi>,
        groupArchivedUpdater: suspend (String, String, Boolean) -> AppGroupRecordFfi,
    ) : this(
        appState,
        memberSnapshotLoader,
        memberSnapshotRetryDelay,
        groupArchivedUpdater,
        initialAccountRef,
        null,
        { false },
    )

    /** Consumes the process-scoped right to present the initial connection attempt. */
    internal fun claimInitialConnectionPresentation(): Boolean = initialConnectionAttemptClaim()

    var items by mutableStateOf<List<ChatListItem>>(emptyList())
        private set
    var archivedItems by mutableStateOf<List<ChatListItem>>(emptyList())
        private set
    // Starts true: a new controller has no snapshot yet. ChatsScreen must not
    // paint authoritative EmptyChats until bind() finishes the first local
    // snapshot (issue #1697).

    var isLoading by mutableStateOf(initialLocalSnapshot == null)
        private set
    internal var slowStartupEpoch by mutableStateOf(-1L)
    val startupTakingLonger: Boolean get() = isLoading && slowStartupEpoch == bindEpoch

    /**
     * True once the bound account's local projection has returned, including
     * an authoritative empty result. Unlike `items.isNotEmpty()`, this remains
     * a valid warm-resume seed for accounts with no conversations.
     */
    var hasLoadedLocalSnapshot by mutableStateOf(initialLocalSnapshot != null)
        private set
    var error by mutableStateOf<ErrorPresentation?>(null)
        private set

    private val retryLoadSignal = Channel<Unit>(Channel.CONFLATED)
    private var initialLoadWatchdog: Job? = null

    // staleness-exempt: observable retry trigger consumed by the chat-list UI.
    var retryGeneration by mutableLongStateOf(0L)
        private set
    private var terminalLoadFailure = false

    /** Recovery generation represented by the latest published row snapshot. */
    var recoveryProjectionGeneration by mutableLongStateOf(0L)
        private set
    private val pendingRecoveryProjectionGeneration = RecoveryProjectionGenerationHandoff()

    /** Re-awaits an outstanding initial read; only a finished terminal attempt creates a new bind. */
    fun retryLoad() {
        if (terminalLoadFailure) {
            terminalLoadFailure = false
            retryGeneration += 1L
        } else {
            if (!hasLoadedLocalSnapshot && error != null) {
                error = null
                isLoading = true
                watchInitialLoad(bindEpoch)
            }
            retryLoadSignal.trySend(Unit)
        }
    }

    /** Owns a presentation timer independently of a potentially blocked native snapshot. */
    private fun watchInitialLoad(epoch: Long) {
        initialLoadWatchdog?.cancel()
        initialLoadWatchdog = recomputeScope.launch { watchActionableChatListStartup(epoch) }
    }

    /** A deadline is recoverable presentation state, never an authoritative empty local snapshot. */
    internal fun publishInitialLoadTimeout(epoch: Long) {
        if (!isActiveBindEpoch(epoch) || hasLoadedLocalSnapshot || error != null) return
        isLoading = false
        error =
            privacySafeErrorPresentation(
                operationCode = "CHAT_LIST_LOAD_TIMEOUT",
                throwable = IllegalStateException("initial local projection exceeded presentation deadline"),
                message = AppText.Resource(R.string.startup_taking_too_long),
            )
    }

    /** Publishes visible and archived rows as a projection would, for state readers of the chat list's rows. */
    @VisibleForTesting
    internal fun publishItemsForTest(
        visible: List<ChatListItem>,
        archived: List<ChatListItem> = emptyList(),
    ) {
        items = visible
        archivedItems = archived
    }

    /** Publishes the terminal no-snapshot failure used by first-frame screen coverage. */
    @VisibleForTesting
    internal fun publishInitialLoadFailureForTest(failure: ErrorPresentation) {
        check(!hasLoadedLocalSnapshot) { "Only the no-snapshot fallback may publish an initial load failure" }
        isLoading = false
        error = failure
        terminalLoadFailure = true
    }

    /** The account this controller is currently bound to (observable so
     *  notification routing can tell when the right account's list is ready). */
    var boundAccountRef by mutableStateOf<String?>(initialLocalSnapshot?.accountRef ?: initialAccountRef)
        private set

    /**
     * Monotonic signal for [chatRowsByGroup] membership changes. Incremented when
     * a group id is added, removed, or replaced by snapshot — not on in-place
     * row updates — so foreground provisional opens can reconcile against live
     * backing rows while [items] stays frozen behind an open conversation (#1729).
     * staleness-exempt: this observable projection membership version is consumed by Compose.
     */
    var materializedGroupsRevision by mutableLongStateOf(0L)
        private set

    /**
     * Counts the membership removals whose cause Android cannot know — a window replacement or native
     * removal that drops a row could be an eviction or a deletion. Additions and local deletions leave it
     * alone, so a full-list read taken before them can still prove what the list holds (#3586).
     * staleness-exempt: this observable membership version is consumed by Compose.
     */
    var ambiguousMembershipRemovalsRevision by mutableLongStateOf(0L)
        private set

    // Chats this controller deleted locally, in order, so an earlier full-list read can drop exactly those.
    private val locallyDeletedGroupIds = mutableListOf<String>()

    /** How many local deletions have been recorded, the mark a full-list read captures before it starts. */
    internal val locallyDeletedGroupCount: Int get() = locallyDeletedGroupIds.size

    /** Folded ids of the chats deleted locally after [mark] deletions had been recorded. */
    internal fun locallyDeletedGroupIdsSince(mark: Int): Set<String> = locallyDeletedGroupIds.drop(mark).toSet()

    /** Complete observable forward-picker revision; staleness-exempt: it is a Compose version. */
    var forwardTargetsRevision by mutableLongStateOf(0L)
        private set

    // staleness-exempt: observable member projection version consumed by derived UI.
    var memberSnapshotsRevision by mutableLongStateOf(0L)
        private set

    internal var accountRef: String? = initialLocalSnapshot?.accountRef ?: initialAccountRef
        private set
    private var pendingInitialLocalSnapshot = initialLocalSnapshot

    private fun chatRowKey(groupIdHex: String): String = groupIdHex.lowercase()

    private fun nextChatActivitySequence(): ULong {
        if (nextActivitySequence != ULong.MAX_VALUE) nextActivitySequence += 1uL
        return nextActivitySequence
    }

    /** Captures in-flight previews before a same-process controller replacement. */
    internal fun captureOptimisticPreviewHandoff(): OptimisticChatListPreviewHandoff? {
        val owner = accountRef
        return if (owner == null || optimisticChatListPreviewByGroup.isEmpty()) {
            null
        } else {
            OptimisticChatListPreviewHandoff(
                accountRef = owner,
                rowsByGroup = LinkedHashMap(chatRowsByGroup),
                activitySequenceByGroup = LinkedHashMap(activitySequenceByGroup),
                statesByGroup = optimisticChatListPreviewByGroup.mapValues { (_, state) -> state.snapshot() },
                nextActivitySequence = nextActivitySequence,
            )
        }
    }

    /** Restores only a same-account handoff before the replacement is exposed. */
    internal fun restoreOptimisticPreviewHandoff(
        handoff: OptimisticChatListPreviewHandoff,
        expectedAccountRef: String?,
    ): Boolean {
        if (!canRestoreOptimisticPreviewHandoff(handoff, expectedAccountRef)) return false
        val restoredAccountRef = requireNotNull(expectedAccountRef)
        accountRef = restoredAccountRef
        boundAccountRef = restoredAccountRef
        chatRowsByGroup.clear()
        chatRowsByGroup.putAll(handoff.rowsByGroup)
        activitySequenceByGroup.clear()
        activitySequenceByGroup.putAll(handoff.activitySequenceByGroup)
        optimisticChatListPreviewByGroup.clear()
        optimisticChatListPreviewByGroup.putAll(
            handoff.statesByGroup.mapValues { (_, state) -> state.snapshot() },
        )
        nextActivitySequence = handoff.nextActivitySequence
        optimisticChatListPreviewByGroup.forEach { (rowKey, state) ->
            materializeOptimisticChatListPreview(rowKey, state)
        }
        hasLoadedLocalSnapshot = true
        isLoading = false
        recompute(scheduleBackgroundEnrichment = false)
        return true
    }

    /** Rejects cleared, cross-account, and already-bound replacements before any state is copied. */
    private fun canRestoreOptimisticPreviewHandoff(
        handoff: OptimisticChatListPreviewHandoff,
        expectedAccountRef: String?,
    ): Boolean =
        !isCleared &&
            expectedAccountRef != null &&
            handoff.accountRef == expectedAccountRef &&
            (boundAccountRef == null || boundAccountRef == expectedAccountRef)

    private fun chatListActivityAdvanced(
        previous: ChatListRowFfi,
        current: ChatListRowFfi,
    ): Boolean = compareChatListActivity(previous, current) > 0

    /** Chooses when a materialized row receives a fresh in-memory order token. */
    private fun shouldAdvanceChatActivitySequence(
        current: ChatListRowFfi?,
        folded: ChatListRowFfi,
        observesNewActivity: Boolean,
    ): Boolean = current == null || chatListActivityAdvanced(current, folded) || observesNewActivity

    private fun compareChatListActivity(
        previous: ChatListRowFfi,
        current: ChatListRowFfi,
    ): Int {
        val lastMessageCompare = compareChatListLastMessageActivity(previous, current)
        return if (lastMessageCompare != 0) {
            lastMessageCompare
        } else {
            current.activitySortAt.compareTo(previous.activitySortAt)
        }
    }

    private fun compareChatListLastMessageActivity(
        previous: ChatListRowFfi,
        current: ChatListRowFfi,
    ): Int {
        val currentLast = current.lastMessage
        val previousLast = previous.lastMessage
        return when {
            currentLast == null && previousLast == null -> 0
            currentLast == null -> -1
            previousLast == null -> 1
            else ->
                compareTimelineAtMessageIdHex(
                    currentLast.timelineAt,
                    currentLast.messageIdHex,
                    previousLast.timelineAt,
                    previousLast.messageIdHex,
                )
        }
    }

    private fun rememberBaselineActivitySequence(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
        activitySequence: ULong,
    ) {
        val activity = chatListLastMessageActivity(row)
        state.baselineActivitySequenceByLastMessage.remove(activity)
        state.baselineActivitySequenceByLastMessage[activity] = activitySequence
        while (state.baselineActivitySequenceByLastMessage.size > MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY) {
            state.baselineActivitySequenceByLastMessage.remove(state.baselineActivitySequenceByLastMessage.keys.first())
        }
    }

    private fun chatListLastMessageActivity(row: ChatListRowFfi): ChatListLastMessageActivity =
        ChatListLastMessageActivity(
            activitySortAt = row.activitySortAt,
            timelineAt = row.lastMessage?.timelineAt,
            messageIdHex = row.lastMessage?.messageIdHex,
        )

    private fun representsSameChatListActivity(
        optimistic: ChatListMessagePreviewFfi,
        authoritative: ChatListMessagePreviewFfi,
    ): Boolean =
        optimistic.timelineAt == authoritative.timelineAt &&
            optimistic.sender == authoritative.sender &&
            optimistic.plaintext == authoritative.plaintext &&
            optimistic.kind == authoritative.kind &&
            optimistic.deleted == authoritative.deleted &&
            optimistic.attachmentKind == authoritative.attachmentKind &&
            optimistic.attachmentCount == authoritative.attachmentCount

    private fun matchingOptimisticPreview(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
    ): OptimisticChatListPreviewMatch? {
        val authoritative = row.lastMessage ?: return null
        val matchingEntry =
            state.entries.entries.firstOrNull { (_, entry) ->
                entry.confirmedMessageIdHex == authoritative.messageIdHex
            } ?: state.entries.entries.firstOrNull { (_, entry) ->
                entry.confirmedMessageIdHex == null &&
                    representsSameChatListActivity(entry.preview, authoritative)
            }
        return matchingEntry?.let { (entryKey, entry) ->
            OptimisticChatListPreviewMatch(entryKey, entry.activitySequence)
        } ?: state.failedFallbackEntry
            ?.takeIf { entry -> representsSameChatListActivity(entry.preview, authoritative) }
            ?.let { entry ->
                OptimisticChatListPreviewMatch(entryKey = null, activitySequence = entry.activitySequence)
            }
            ?: state.confirmedActivitySequenceById[authoritative.messageIdHex]?.let { activitySequence ->
                OptimisticChatListPreviewMatch(entryKey = null, activitySequence = activitySequence)
            }
    }

    /** Uses the canonical accepted-pending ID before falling back to render-shape matching. */
    private fun authoritativeOptimisticPreviewMatch(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
    ): OptimisticChatListPreviewMatch? {
        val acceptedPendingEntryKey =
            row.lastMessage
                ?.takeIf { it.deliveryState == ChatListMessageDeliveryStateFfi.DELIVERED }
                ?.messageIdHex
                ?.let { confirmedMessageIdHex ->
                    appState.acceptedPendingTextOptimisticId(
                        accountRef = accountRef,
                        groupIdHex = row.groupIdHex,
                        confirmedMessageIdHex = confirmedMessageIdHex,
                    )
                }
        return acceptedPendingEntryKey
            ?.let { entryKey ->
                state.entries[entryKey]?.let { entry ->
                    OptimisticChatListPreviewMatch(entryKey, entry.activitySequence)
                }
            } ?: matchingOptimisticPreview(state, row)
    }

    private fun optimisticMatchIsStale(
        match: OptimisticChatListPreviewMatch,
        baselineRow: ChatListRowFfi,
        row: ChatListRowFfi,
        baselineActivitySequence: ULong,
        activityCompare: Int,
    ): Boolean =
        match.activitySequence < baselineActivitySequence ||
            activityCompare < 0 ||
            (
                match.activitySequence == baselineActivitySequence &&
                    row.lastMessage?.messageIdHex != baselineRow.lastMessage?.messageIdHex
            )

    /**
     * Keep only the newest failed preview that a later provisional entry is
     * currently hiding. If that newer entry is abandoned, the row can reveal
     * this fallback instead of jumping to the pre-send baseline. The scalar
     * slot preserves the decrypted-preview memory bound: repeated failures
     * replace one fallback rather than accumulating one entry per send.
     */
    private fun pruneUnreachableFailedEntries(state: OptimisticChatListPreviewState) {
        state.failedFallbackEntry =
            state.failedFallbackEntry?.takeIf {
                it.activitySequence > state.baselineActivitySequence
            }
        val newestSequence = state.entries.values.maxOfOrNull { it.activitySequence } ?: return
        val newestSupersededFailure =
            state.entries.values
                .asSequence()
                .filter { entry ->
                    entry.confirmedMessageIdHex == null &&
                        entry.preview.deliveryState == ChatListMessageDeliveryStateFfi.FAILED &&
                        entry.activitySequence > state.baselineActivitySequence &&
                        entry.activitySequence < newestSequence
                }.plus(state.failedFallbackEntry?.let(::sequenceOf).orEmpty())
                .maxByOrNull { it.activitySequence }
        state.failedFallbackEntry = newestSupersededFailure
        state.entries.entries.removeAll { (_, entry) ->
            entry.confirmedMessageIdHex == null &&
                entry.preview.deliveryState == ChatListMessageDeliveryStateFfi.FAILED &&
                (entry.activitySequence <= state.baselineActivitySequence || entry.activitySequence < newestSequence)
        }
    }

    private fun retireCommittedOptimisticEntriesThrough(
        state: OptimisticChatListPreviewState,
        activitySequence: ULong,
    ) {
        state.entries.entries.removeAll { (_, entry) ->
            entry.confirmedMessageIdHex != null &&
                entry.activitySequence <= activitySequence
        }
    }

    /**
     * Folds [row] into the optimistic baseline and returns the row that is safe
     * to expose to conversation-scoped consumers. A pending matching echo is
     * intentionally returned while parked; a rejected stale replay returns the
     * already-accepted baseline instead.
     */
    private fun foldOptimisticChatListBaseline(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
        acceptBackwardActivity: Boolean = false,
        observesNewActivity: Boolean = false,
    ): ChatListRowFfi {
        val activityCompare = compareChatListActivity(state.baselineRow, row)
        val match =
            authoritativeOptimisticPreviewMatch(state, row)
                ?.takeUnless { acceptBackwardActivity && activityCompare < 0 }
        acceptDeliveredOptimisticPreview(state, row)
        // A failed send is terminal: its callback will never report a confirmed
        // id, so parking an authoritative row against it would strand the row
        // on FAILED and discard every later update for that message.
        val pendingEntryKey =
            match?.entryKey?.takeIf {
                val entry = state.entries[it]
                entry != null &&
                    entry.confirmedMessageIdHex == null &&
                    entry.preview.deliveryState != ChatListMessageDeliveryStateFfi.FAILED
            }
        if (pendingEntryKey != null) {
            // The stream can expose the locally committed row before send()
            // reports whether publishing succeeded. Keep it provisional so a
            // later failure still restores the pre-send baseline.
            state.entries[pendingEntryKey] = state.entries.getValue(pendingEntryKey).copy(pendingAuthoritativeRow = row)
            return row
        }
        var acceptRow = true
        if (match != null) {
            // A coalesced snapshot can skip every earlier successful send.
            // Retire older committed previews, whose compact id/sequence
            // tombstones still recognize queued echoes. Pending previews stay
            // until their callback can record a confirmed id or roll them back.
            retireCommittedOptimisticEntriesThrough(state, match.activitySequence)
            // The echo keeps the order assigned when the local send was
            // accepted. If a later authoritative activity already owns the
            // row, consuming this stale echo must not move the row backward.
            val staleMatch =
                optimisticMatchIsStale(
                    match,
                    state.baselineRow,
                    row,
                    state.baselineActivitySequence,
                    activityCompare,
                )
            if (staleMatch && !acceptBackwardActivity) {
                acceptRow = false
            } else {
                state.baselineActivitySequence = match.activitySequence
                rememberBaselineActivitySequence(state, row, match.activitySequence)
            }
        } else if (acceptBackwardActivity && foldSnapshotKeepingConfirmedSend(state, row)) {
            acceptRow = false
        } else {
            acceptRow =
                foldUnmatchedChatListRow(
                    state,
                    row,
                    activityCompare,
                    acceptBackwardActivity,
                    observesNewActivity,
                )
        }
        if (acceptRow) state.baselineRow = row
        return if (acceptRow) row else state.baselineRow
    }

    /**
     * Treats a delivered authoritative row as the exact optimistic ID handoff that an open conversation
     * normally performs. This keeps the chat-list subscription sufficient after navigation disposes the route.
     */
    private fun acceptDeliveredOptimisticPreview(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
    ) {
        val delivered =
            row.lastMessage?.takeIf {
                it.deliveryState == ChatListMessageDeliveryStateFfi.DELIVERED
            } ?: return
        appState.pendingSendDiagnostics.surfaceSettled(
            delivered.messageIdHex,
            PerformancePhase.CHAT_LIST_SETTLED,
        )
        val entryKey =
            delivered.messageIdHex.let { confirmedMessageIdHex ->
                appState.acceptedPendingTextOptimisticId(
                    accountRef = accountRef,
                    groupIdHex = row.groupIdHex,
                    confirmedMessageIdHex = confirmedMessageIdHex,
                )
            }
        entryKey?.let { acceptedEntryKey ->
            state.entries[acceptedEntryKey]
                ?.takeIf { it.confirmedMessageIdHex == null }
                ?.let { entry ->
                    state.confirmedActivitySequenceById[delivered.messageIdHex] = entry.activitySequence
                    while (state.confirmedActivitySequenceById.size > MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY) {
                        state.confirmedActivitySequenceById.remove(state.confirmedActivitySequenceById.keys.first())
                    }
                    state.entries[acceptedEntryKey] =
                        entry.copy(
                            preview = delivered,
                            confirmedMessageIdHex = delivered.messageIdHex,
                            pendingAuthoritativeRow = null,
                        )
                }
        }
    }

    /**
     * Sequencing for a row that matches no optimistic preview: reuse the
     * sequence already accepted for this activity when it is known, take a
     * fresh one for genuinely newer activity, and otherwise keep the accepted
     * ordering. Returns whether the row may replace the baseline.
     */
    private fun foldUnmatchedChatListRow(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
        activityCompare: Int,
        acceptBackwardActivity: Boolean,
        observesNewActivity: Boolean,
    ): Boolean {
        val knownActivitySequence =
            state.baselineActivitySequenceByLastMessage[chatListLastMessageActivity(row)]
        var acceptRow = true
        when {
            knownActivitySequence != null -> {
                if (knownActivitySequence < state.baselineActivitySequence && !acceptBackwardActivity) {
                    acceptRow = false
                } else {
                    state.baselineActivitySequence = knownActivitySequence
                    rememberBaselineActivitySequence(state, row, knownActivitySequence)
                }
            }

            activityCompare > 0 || observesNewActivity -> {
                state.baselineActivitySequence = nextChatActivitySequence()
                rememberBaselineActivitySequence(state, row, state.baselineActivitySequence)
                state.entries.entries.removeAll { (_, entry) ->
                    entry.confirmedMessageIdHex != null &&
                        entry.activitySequence < state.baselineActivitySequence
                }
            }

            activityCompare < 0 && acceptBackwardActivity -> {
                state.baselineActivitySequence = 0uL
                rememberBaselineActivitySequence(state, row, 0uL)
            }

            // Subscription rows remain authoritative for content even when
            // their last-message tuple compares backward. Keep the accepted
            // ordering sequence without leaving other fields stale after
            // same-second id order or preview loss.
            else -> rememberBaselineActivitySequence(state, row, state.baselineActivitySequence)
        }
        return acceptRow
    }

    /**
     * Handles a snapshot replay that lags a send the engine already confirmed.
     * Rewinding the row to the older last message would regress the list below
     * the conversation's own state, but dropping the row wholesale
     * would strand its unread and read-state — subscription rows stay
     * authoritative for content. So the confirmed send keeps the row's last
     * message and sort position while every other field comes from the fresh
     * snapshot. Returns false when this does not apply, leaving the caller's
     * normal handling in charge.
     */
    private fun foldSnapshotKeepingConfirmedSend(
        state: OptimisticChatListPreviewState,
        row: ChatListRowFfi,
    ): Boolean {
        val confirmedLastMessage =
            state.baselineRow.lastMessage?.takeIf { baselineLastMessage ->
                state.confirmedActivitySequenceById.containsKey(baselineLastMessage.messageIdHex) &&
                    row.lastMessage?.messageIdHex != baselineLastMessage.messageIdHex &&
                    compareChatListActivity(state.baselineRow, row) < 0
            } ?: return false
        val merged =
            row.copy(
                lastMessage = confirmedLastMessage,
                activitySortAt = state.baselineRow.activitySortAt,
            )
        state.baselineRow = merged
        rememberBaselineActivitySequence(state, merged, state.baselineActivitySequence)
        return true
    }

    /** Materializes the newest visible optimistic entry without allowing sequence time to regress. */
    private fun materializeOptimisticChatListPreview(
        rowKey: String,
        state: OptimisticChatListPreviewState,
    ) {
        pruneUnreachableFailedEntries(state)
        val latestEntry =
            state.entries.values
                .asSequence()
                .plus(state.failedFallbackEntry?.let(::sequenceOf).orEmpty())
                .maxByOrNull { it.activitySequence }
        val visibleEntry = latestEntry?.takeIf { it.activitySequence > state.baselineActivitySequence }
        chatRowsByGroup[rowKey] =
            visibleEntry?.let { entry ->
                state.baselineRow.copy(
                    lastMessage = entry.preview,
                    activitySortAt = maxOf(state.baselineRow.activitySortAt, entry.preview.timelineAt),
                    updatedAt = maxOf(state.baselineRow.updatedAt, entry.preview.timelineAt),
                )
            } ?: state.baselineRow
        activitySequenceByGroup[rowKey] = visibleEntry?.activitySequence ?: state.baselineActivitySequence
        if (state.hasNoOptimisticPreviewWork()) {
            optimisticChatListPreviewByGroup.remove(rowKey)
        }
    }

    private val chatRowsByGroup = LinkedHashMap<String, ChatListRowFfi>()
    private var selectedAvatarAssetsByGroup: Map<String, AvatarAssetFfi> = emptyMap()

    private var selectedPresentationsByGroup = emptyMap<String, ConversationPresentationFfi>()
    private var selectedPreviewsByGroup = emptyMap<String, SelectedChatPreviewFfi>()
    private var selectedActionsByGroup = emptyMap<String, ChatListRowActionsFfi>()
    internal val chatRows: Collection<ChatListRowFfi>
        get() = chatRowsByGroup.values
    private var groupRecordsById = mapOf<String, AppGroupRecordFfi>()

    // Holds a locally committed rename on its row until the subscription agrees (#2696). A clear
    // stays held until the group record agrees too, because a blank row falls back to its name.
    private val localGroupNames = LocalGroupFieldAuthority(holdClearAfterAgreement = true)

    // Whole-second activity timestamps need an in-memory tie-break that follows
    // the order local/live activity is accepted. This is bounded to one scalar
    // per materialized row and cleared with the backing projection on bind.
    private val activitySequenceByGroup = mutableMapOf<String, ULong>()

    // staleness-exempt: ordered activity tie-breaker, not an async publication fence.
    private var nextActivitySequence = 0uL
    private val optimisticChatListPreviewByGroup = mutableMapOf<String, OptimisticChatListPreviewState>()
    private val archiveActions =
        ChatListArchiveActions(
            rowForGroup = { chatRowsByGroup[chatRowKey(it)] },
            isCurrent = ::isActiveBindEpoch,
            onChanged = { recompute() },
            updateGroup = ::applyArchiveMutation,
            onFailure = { error ->
                appState.presentFailure(R.string.toast_couldnt_update_chat, "CHAT_ARCHIVE_UPDATE", error)
            },
            onSuccess = { archived ->
                appState.presentTransient(if (archived) R.string.toast_chat_archived else R.string.toast_chat_restored)
            },
        )

    // Lifecycle-scoped member snapshots for the current chat rows. Initial
    // bind seeds identifier-only rows in bounded `groupMemberIdsPage` calls;
    // later group invalidations and a failed initial page fall back to the
    // enriched per-group `groupMembers` reader. These snapshots drive unnamed
    // titles, folder rules, shared groups, and profile actions without becoming
    // a second persistent source of protocol truth.
    private var memberCacheByGroup: Map<String, List<AppGroupMemberRecordFfi>> = emptyMap()

    // A live group-record update invalidates the authoritative roster, but its
    // last rendered values remain useful for title/avatar continuity while a
    // replacement loads. Keep only the display-shaped values separate from the
    // authoritative cache so business rules cannot consume a stale roster.
    // Cleared on each bind and as soon as a complete replacement arrives.
    private var presentationMembersByGroup: Map<String, ChatListMemberPresentation> = emptyMap()

    // Groups the active account is known to have been removed/left from, keyed
    // by group id hex. Set on a confirmed self-leave or when a loaded roster
    // omits self; lets [ChatListItem.removedFromGroup] treat a genuinely-empty
    // post-removal roster as real removal (a fetch-failure empty roster, which
    // never adds an id here, stays non-removed). Cleared on every bind.
    private var removedGroupIds: Set<String> = emptySet()

    // Tracks groups whose member fetch is currently in flight, preventing
    // duplicate FFI work for the same group. Entries are added in
    // [schedulePendingMemberFetches] and removed in the same coroutine's
    // `finally`. Failed or transiently-empty loads schedule a backoff retry;
    // `bind()` clears the set alongside the cache to reset both at once.
    private val inFlightMemberFetches = mutableSetOf<String>()

    // Exponential-backoff tier per group for roster reads that fail or return a
    // roster too incomplete to cache (e.g. empty while catch-up is still
    // materializing members). The tier is capped, while retries continue until
    // a roster lands or the group/account lifecycle ends, so Unknown cannot
    // become permanent after a finite retry budget.
    private val memberFetchRetryBackoffTierByGroup = mutableMapOf<String, Int>()
    private val memberFetchRetryJobsByGroup = mutableMapOf<String, Job>()
    private val failedMemberFetches = mutableSetOf<String>()
    private val terminalMemberFetches = mutableSetOf<String>()

    // A self-only direct roster gets one dedicated confirmation read before it
    // becomes authoritative. Keep this separate from the general backoff tier:
    // an earlier error or empty read must not consume the self-only grace retry.
    private val selfOnlyDirectGraceRetryGroups = mutableSetOf<String>()

    // Widening member snapshots to every group makes the chat-list projection
    // much more useful, but the app should not start one roster FFI call per
    // group on large accounts. Keep at most one fetch in flight per group while
    // bounding simultaneous FFI/IO work across all groups.
    private val memberFetchGate = Semaphore(MEMBER_FETCH_FANOUT)

    // Parsed markdown for each row's last-message preview, keyed by the exact
    // plaintext (tokens must always describe the text beside them — keying by
    // group or message id would go stale on edits). This is derived UI state
    // over the live rows, not a second store of protocol data: it's pruned to
    // the texts the current rows actually show (in
    // [schedulePendingPreviewParses]) and cleared on bind. A parse failure
    // caches the empty document, which renders as plaintext and stops the
    // row from re-parsing on every recompute.
    private var previewTokensByText = mapOf<String, MarkdownDocumentFfi>()

    // Resolved media fallbacks for blank chat-list preview rows, keyed by the
    // last message id. Derived UI state over the live rows (pruned to ids still
    // on screen), not a protocol cache — same lifecycle as [previewTokensByText].
    private var mediaPreviewFallbackByMessageId = mapOf<String, MediaPreviewFallback>()

    // Same single-state invariant as [inFlightMemberFetches], keyed by
    // preview plaintext: pending → in flight (here) → cached.
    private val inFlightPreviewParses = mutableSetOf<String>()
    private val inFlightMediaKindResolves = mutableSetOf<String>()
    private val previewParseGate = Semaphore(PREVIEW_PARSE_FANOUT)
    private val mediaKindResolveGate = Semaphore(MEDIA_KIND_RESOLVE_FANOUT)

    // Monotonically increments on every `bind()`. Captured by each
    // [schedulePendingMemberFetches] job; once a later bind has happened
    // (account switch, sign-out, or re-bind), the captured epoch no
    // longer matches and the job drops its result instead of poisoning
    // the new account's cache with stale members.
    private val bindLifetime = StalenessGuard()

    /** Current account-binding token passed through projection helpers. */
    internal val bindEpoch: Long
        get() = bindLifetime.capture()

    // Monotonically increments whenever a live group update invalidates member
    // freshness. Member-fetch jobs capture it and drop results that started
    // before a newer group-details update, so a stale in-flight roster cannot
    // overwrite an authoritative local mutation or live group-state change (#825).
    private val memberCacheLifetime = StalenessGuard()

    /** Current authoritative member-cache token passed through loaders. */
    private val memberCacheEpoch: Long
        get() = memberCacheLifetime.capture()
    private var isCleared = false

    internal val folderSource = ChatFolderLiveSource()

    /** Folder memberships MarmotKit computes over every chat, refreshed as the list and folders change (#3652). */
    internal val folderMembership by lazy { ChatFolderMembership(recomputeScope, liveSubscriptions.chatListQueryIds) }

    /** The selected folder's own windows, open beside [chatListWindows] while a folder is selected (#3652). */
    @Volatile
    internal var folderWindows: ChatListWindowSet? = null
        private set

    /** Whether [folderWindows] is open, as snapshot state so the chat list switches its paging when it changes. */
    internal var folderWindowsOpen by mutableStateOf(false)
        private set

    /** Installs or clears the selected folder's windows, closing any set it replaces. */
    internal fun replaceFolderWindows(windows: ChatListWindowSet?) {
        val previous = folderWindows
        folderWindows = windows
        folderWindowsOpen = windows != null
        if (previous !== windows) previous?.close()
    }

    private val subscriptionOwner = ChatListSubscriptionOwner()
    private val liveSubscriptionLock get() = subscriptionOwner.lock
    internal val chatListWindows: ChatListWindowSet? get() = subscriptionOwner.windows
    private val connectionOwner =
        ChatListConnectionOwner(appState) {
            synchronized(liveSubscriptionLock) {
                chatListWindows != null &&
                    subscriptionOwner.hasChats &&
                    accountRef == boundAccountRef &&
                    accountRef == appState.activeAccountRef
            }
        }
    internal val connectionState: ChatListConnectionState get() = connectionOwner.state

    fun refreshConnectionReadiness() = connectionOwner.refresh(presentAttempt = true)

    fun revalidateConnectionReadiness() = connectionOwner.refresh(presentAttempt = false)

    /** Revalidates retained subscriptions silently and attributes the foreground source. */
    internal fun revalidateConnectionReadinessOnForeground() {
        connectionOwner.refresh(presentAttempt = false, source = PerformancePhase.CONNECTION_FOREGROUND)
    }

    /** Keeps a disconnected aggregate relay sample distinct from a foreground lifecycle edge. */
    internal fun revalidateConnectionReadinessOnRelaySample() =
        connectionOwner.refresh(presentAttempt = false, source = PerformancePhase.CONNECTION_RELAY_SAMPLE)

    /** Records a closed rendered banner state in the active opt-in readiness episode. */
    internal fun noteConnectionPresentation(phase: PerformancePhase) = connectionOwner.notePresentation(phase)

    /** Invalidates an account-owned attempt without attributing teardown to transport failure. */
    fun invalidateConnectionReadiness() = connectionOwner.invalidate()

    /** Records network loss only at a confirmed validated-internet boundary. */
    internal fun invalidateConnectionReadinessOnNetworkLoss() {
        connectionOwner.invalidate(PerformancePhase.CONNECTION_NETWORK_LOST)
    }

    /** Ends account-owned streams and invalidates roster results before closing their handles. */
    suspend fun closeLiveSubscriptionsForAccountTeardown(accountRef: String) {
        val teardown =
            synchronized(liveSubscriptionLock) {
                if (!shouldTeardownLiveSubscriptionsForAccount(accountRef, this.accountRef, boundAccountRef)) {
                    null
                } else {
                    this.accountRef = null
                    boundAccountRef = null
                    invalidateConnectionReadiness()
                    subscriptionOwner.take()
                }
            } ?: return
        val (attempt, job) = teardown
        withContext(NonCancellable + Dispatchers.IO) { attempt?.close() }
        if (shouldCancelLiveSubscriptionJob(job, coroutineContext[Job])) {
            job?.cancelAndJoin()
        }
    }

    /** Binds [accountRef] while preserving local projection and retrying live subscriptions. */
    suspend fun bind(
        accountRef: String?,
        preserveLoadedContent: Boolean = false,
    ) {
        if (isCleared) return
        accountRef?.let { appState.recordAccountSwitchMark("bind-start", it) }
        val currentBindJob = coroutineContext[Job]
        synchronized(liveSubscriptionLock) { subscriptionOwner.bindJob = currentBindJob }
        chatsDebug { "bind account=${accountRef?.take(8)}" }
        this.accountRef = accountRef
        this.boundAccountRef = accountRef
        val seededLocalSnapshot =
            pendingInitialLocalSnapshot?.takeIf { snapshot -> snapshot.accountRef == accountRef }
        pendingInitialLocalSnapshot = null
        val keepLoadedContent =
            shouldPreserveChatListProjection(
                hasSeededLocalSnapshot = seededLocalSnapshot != null,
                preserveLoadedContent = preserveLoadedContent,
                hasLoadedLocalSnapshot = hasLoadedLocalSnapshot,
            )
        isLoading = accountRef != null && !keepLoadedContent
        if (!keepLoadedContent) {
            hasLoadedLocalSnapshot = accountRef == null
            resetBackingState()
        }
        bindLifetime.advance()
        presentationLifetime.reset()
        val epoch = bindEpoch
        initialLoadWatchdog?.cancel()
        if (isLoading) {
            recomputeScope.launch { watchSlowChatListStartup(epoch) }
            watchInitialLoad(epoch)
        }
        connectionOwner.reset(accountRef, epoch)
        recompute(scheduleBackgroundEnrichment = seededLocalSnapshot == null)
        error = null
        terminalLoadFailure = false

        if (accountRef == null) {
            synchronized(liveSubscriptionLock) {
                if (subscriptionOwner.bindJob === currentBindJob) {
                    subscriptionOwner.bindJob = null
                }
            }
            return
        }
        val chatListLoad = appState.beginHostPerformance(HostPerformanceOperationFfi.CHAT_LIST_LOAD)
        val archivedChatListLoad = appState.beginHostPerformance(HostPerformanceOperationFfi.ARCHIVED_CHAT_LIST_LOAD)
        try {
            appState.refreshDraftSummaries(accountRef)
            val catchUpGate = ChatListCatchUpGate()
            var retryDelayMs = LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
            var lastFailureNotReady = false
            var localFramePresented = preserveLoadedContent && seededLocalSnapshot == null && keepLoadedContent
            var pendingReadinessCatchUp: Deferred<AccountCatchUpResult>? = null
            var initialSubscriptionProjection = true
            if (seededLocalSnapshot != null) {
                // Render the preinstalled one-shot seed before live or background enrichment.
                appState.recordAccountSwitchMark("seed-bound", accountRef)
                awaitRenderedChatListFrame()
                if (isActiveBindEpoch(epoch) && shouldRetryLiveSubscriptionForAccount(accountRef, boundAccountRef)) {
                    appState.recordAccountSwitchLocalSnapshotRendered(accountRef, chatRows.size)
                    localFramePresented = true
                    if (catchUpGate.claimInitial()) {
                        pendingReadinessCatchUp = appState.launchCatchUpAccounts()
                    }
                    // A performance-shaped handoff may contain only the
                    // rosters needed to render first-frame identity. Do not
                    // turn every deferred named-group roster into an N-call
                    // `groupMembers` fan-out here: the live subscription path
                    // immediately below owns one batched member-id hydration.
                    recompute(scheduleBackgroundEnrichment = false)
                }
            }
            while (coroutineContext.isActive && isActiveBindEpoch(epoch)) {
                val subscriptionAttempt = ChatListSubscriptionOwner.Attempt()
                lastFailureNotReady = false
                var receivedLiveUpdate = false
                val completeChatList = folderSource.complete.value
                var folderSourceChanged = false
                val connectionAttempt =
                    if (initialSubscriptionProjection) {
                        initialSubscriptionProjection = false
                        if (claimInitialConnectionPresentation()) {
                            connectionOwner.beginSessionAttempt(accountRef, epoch)
                        } else {
                            connectionOwner.beginSubscriptionValidation(accountRef, epoch)
                        }
                    } else {
                        connectionOwner.beginSessionAttempt(accountRef, epoch)
                    }
                try {
                    val chatListStream =
                        liveSubscriptions.openFolderSource(accountRef, completeChatList)
                    subscriptionAttempt.windows = chatListStream
                    if (!isActiveBindEpoch(epoch) || !coroutineContext.isActive) break
                    connectionOwner.noteSubscriptionBoundary(
                        connectionAttempt,
                        PerformancePhase.CONNECTION_CHAT_LIST_OPEN,
                    )
                    val chatStream = liveSubscriptions.openChats(accountRef, true)
                    subscriptionAttempt.chats = chatStream
                    if (!isActiveBindEpoch(epoch) || !coroutineContext.isActive) break
                    connectionOwner.noteSubscriptionBoundary(connectionAttempt, PerformancePhase.CONNECTION_CHATS_OPEN)
                    synchronized(liveSubscriptionLock) {
                        if (isActiveBindEpoch(epoch)) {
                            subscriptionOwner.install(subscriptionAttempt)
                        }
                    }
                    val initialFrame = chatListStream.frame().withFolder(folderWindows)
                    requireCompleteChatListWindowRows(
                        validateChatListWindowRows(accountRef, chatListStream, initialFrame.rows) &&
                            !chatListStream.closed &&
                            chatListWindows === chatListStream,
                    )
                    if (!isActiveBindEpoch(epoch)) break
                    chatListStream.publishIfCurrent(initialFrame) { rows ->
                        replacePresentedChatRows(rows, chatListStream.installed(ChatListViewFfi.CHATS))
                        stagedChatListFrame = ChatListFrameMark(chatListStream, initialFrame.revision)
                    }
                    appState.schedulePendingLocalGroupDeleteCleanup()
                    appState.recordAccountSwitchLocalRowsReady(accountRef, chatRows.size)
                    val initialGroups =
                        withContext(Dispatchers.IO) {
                            chatStream.snapshot()
                        }.associateBy { it.groupIdHex }
                    if (!isActiveBindEpoch(epoch)) break
                    groupRecordsById = initialGroups.mapValues { (_, group) -> withRetainedName(group) }
                    groupRecordsById.values.forEach(::requestGroupProfiles)
                    seedInitialMemberIdProjection(accountRef, epoch)
                    if (!isActiveBindEpoch(epoch)) break
                    recordMemberDerivedLocalReadyIfComplete()
                    chatsDebug {
                        "snapshot account=${accountRef.take(8)} rows=${chatRows.size} groups=${groupRecordsById.size} " +
                            "${chatRows.map { it.debugSummary() }}"
                    }
                    hasLoadedLocalSnapshot = true
                    isLoading = false
                    error = null
                    initialLoadWatchdog?.cancel()
                    recompute()
                    chatListLoad.success()
                    archivedChatListLoad.success()
                    // Draw the local projection before catch-up; live updates fold fresh state afterward.
                    if (!localFramePresented) {
                        awaitRenderedChatListFrame()
                        appState.recordAccountSwitchLocalSnapshotRendered(accountRef, chatRows.size)
                        localFramePresented = true
                    }
                    if (pendingReadinessCatchUp == null && catchUpGate.claimInitial()) {
                        pendingReadinessCatchUp = connectionOwner.launchCatchUp()
                    }
                    val readinessCatchUp = pendingReadinessCatchUp
                    pendingReadinessCatchUp = null
                    readinessCatchUp?.let(connectionOwner::observe)

                    folderSourceChanged =
                        folderSource.receiveUntilChanged(completeChatList) {
                            coroutineScope {
                                // Bounded windows only, the complete stream already holds every folder chat.
                                val folderJob =
                                    if (completeChatList) {
                                        null
                                    } else {
                                        launch { runFolderWindows(accountRef, chatListStream) }
                                    }
                                try {
                                    runUntilFirstLiveSubscriptionEnds(
                                        first = {
                                            chatListStream.receive { view, replacement ->
                                                appState.recoveryDiagnostics
                                                    .recordChatListSubscriptionReceived()
                                                    ?.let { generation ->
                                                        pendingRecoveryProjectionGeneration.publish(generation)
                                                    }
                                                chatsDebug {
                                                    "chat list window view=$view sequence=${replacement.sequence} " +
                                                        "rows=${replacement.rows.size} merged=${chatListStream.rows.size}"
                                                }
                                                requireCompleteChatListWindowRows(
                                                    applyChatListWindowRows(accountRef, chatListStream),
                                                )
                                                receivedLiveUpdate = true
                                                connectionOwner.noteLiveUpdate(connectionAttempt)
                                                appState.schedulePendingLocalGroupDeleteCleanup()
                                            }
                                            connectionOwner.noteSubscriptionBoundary(
                                                connectionAttempt,
                                                PerformancePhase.CONNECTION_CHAT_LIST_COMPLETED,
                                            )
                                        },
                                        second = {
                                            while (isActive) {
                                                val update =
                                                    withContext(Dispatchers.IO) {
                                                        chatStream.next()
                                                    } ?: break
                                                if (!isActiveBindEpoch(epoch) || !isActive) break
                                                receivedLiveUpdate = true
                                                connectionOwner.noteLiveUpdate(connectionAttempt)
                                                requestGroupProfiles(update)
                                                chatsDebug {
                                                    "chat update account=${accountRef.take(8)} ${update.debugSummary()}"
                                                }
                                                foldGroup(update)
                                            }
                                            connectionOwner.noteSubscriptionBoundary(
                                                connectionAttempt,
                                                PerformancePhase.CONNECTION_CHATS_COMPLETED,
                                            )
                                        },
                                    )
                                } finally {
                                    folderJob?.cancel()
                                }
                            }
                        }
                    if (folderSourceChanged) {
                        connectionOwner.noteSubscriptionBoundary(
                            connectionAttempt,
                            PerformancePhase.CONNECTION_SOURCE_CHANGED,
                        )
                    }
                } catch (cancel: CancellationException) {
                    connectionOwner.noteSubscriptionBoundary(
                        connectionAttempt,
                        PerformancePhase.CONNECTION_SUBSCRIPTION_CANCELLED,
                    )
                    throw cancel
                } catch (throwable: Throwable) {
                    if (!isActiveBindEpoch(epoch)) break
                    connectionOwner.noteSubscriptionBoundary(
                        connectionAttempt,
                        PerformancePhase.CONNECTION_SUBSCRIPTION_FAILED,
                    )
                    chatListLoad.failure()
                    archivedChatListLoad.failure()
                    chatsDebug(throwable) {
                        "live chat subscription failed account=${accountRef.take(8)}: " +
                            "${throwable.message ?: throwable.javaClass.simpleName}"
                    }
                    isLoading = false
                    val hasLoadedContent = chatRows.isNotEmpty()
                    lastFailureNotReady = DiagnosticFormatter.isNotReady(throwable)
                    error =
                        privacySafeErrorPresentation(
                            operationCode = if (hasLoadedContent) "CHAT_LIST_REFRESH" else "CHAT_LIST_LOAD",
                            throwable = throwable,
                            message =
                                AppText.Resource(
                                    when {
                                        lastFailureNotReady -> R.string.error_not_ready
                                        hasLoadedContent -> R.string.error_loaded_content_may_be_out_of_date
                                        else -> R.string.error_try_again
                                    },
                                ),
                        )
                } finally {
                    subscriptionOwner.detach(subscriptionAttempt)
                    connectionOwner.finishSessionAttempt(connectionAttempt)
                    withContext(NonCancellable + Dispatchers.IO) { subscriptionAttempt.close() }
                }
                if (!coroutineContext.isActive || !isActiveBindEpoch(epoch)) break
                if (folderSourceChanged || folderSource.complete.value != completeChatList) {
                    initialSubscriptionProjection = true
                    continue
                }
                // Reset only after a real live update, not after a successful
                // bind/snapshot. A relay that connects and immediately closes
                // should keep backing off instead of pinning retries at 500ms.
                retryDelayMs =
                    liveSubscriptionRetryDelayMillisAfterAttempt(
                        currentRetryDelayMs = retryDelayMs,
                        receivedUpdate = receivedLiveUpdate,
                    )
                // MDK documents its not-ready states as clearing on their own once startup hydration
                // finishes, so poll them briskly instead of letting the connectivity backoff stretch.
                if (lastFailureNotReady) retryDelayMs = minOf(retryDelayMs, NOT_READY_RETRY_DELAY_MS)
                chatsDebug { "chat subscriptions ended; retrying in ${retryDelayMs}ms account=${accountRef.take(8)}" }
                connectionOwner.notePresentation(PerformancePhase.CONNECTION_RETRY_WAIT, durationMs = retryDelayMs)
                val userRequestedRetry = withTimeoutOrNull(retryDelayMs) { retryLoadSignal.receive() } != null
                if (userRequestedRetry) connectionOwner.notePresentation(PerformancePhase.CONNECTION_RETRY_REQUESTED)
                retryDelayMs =
                    if (userRequestedRetry) {
                        LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS
                    } else {
                        nextLiveSubscriptionRetryDelayMillis(retryDelayMs)
                    }
            }
        } catch (cancel: CancellationException) {
            chatListLoad.cancel()
            archivedChatListLoad.cancel()
            // Expected when LaunchedEffect re-keys (account switch, navigate
            // away). Re-throw so structured concurrency unwinds cleanly and we
            // don't log normal lifecycle events as bind failures.
            throw cancel
        } catch (throwable: Throwable) {
            if (isActiveBindEpoch(epoch)) {
                chatListLoad.failure()
                archivedChatListLoad.failure()
                chatsDebug(throwable) {
                    "bind failed account=${accountRef.take(8)}: ${throwable.message ?: throwable.javaClass.simpleName}"
                }
                isLoading = false
                error = privacySafeErrorPresentation("CHAT_LIST_LOAD", throwable)
                terminalLoadFailure = true
            }
        } finally {
            if (isActiveBindEpoch(epoch)) initialLoadWatchdog?.cancel()
            chatListLoad.unavailable()
            archivedChatListLoad.unavailable()
            synchronized(liveSubscriptionLock) {
                if (subscriptionOwner.bindJob === currentBindJob) {
                    subscriptionOwner.bindJob = null
                }
            }
            chatsDebug { "unbind account=${accountRef.take(8)} (chat-list + chats subscriptions closed)" }
        }
    }

    private suspend fun awaitRenderedChatListFrame() {
        // Choreographer callbacks run before traversal. Two callbacks guarantee
        // the published local snapshot gets one complete draw before catch-up.
        repeat(2) {
            suspendCancellableCoroutine { continuation ->
                val choreographer = Choreographer.getInstance()
                val callback =
                    Choreographer.FrameCallback {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                continuation.invokeOnCancellation {
                    choreographer.removeFrameCallback(callback)
                }
                choreographer.postFrameCallback(callback)
            }
        }
    }

    private fun foldGroup(
        record: AppGroupRecordFfi,
        invalidateMembers: Boolean = true,
        fromSubscription: Boolean = true,
    ) {
        // A snapshot still carrying a name this device just renamed away from must not restore it
        // underneath the row; [chatListDisplayGroup] falls back to this record's name. A locally
        // committed record is the edit itself, so it neither needs the fence nor counts as the
        // record stream agreeing with it.
        val folded = if (fromSubscription) withRetainedName(record) else record
        groupRecordsById = groupRecordsById + (record.groupIdHex to folded)
        if (invalidateMembers) invalidateMemberCacheForGroup(record.groupIdHex)
        scheduleRecompute()
    }

    /**
     * [record] carrying the retained local name in place of one this device just renamed away from.
     * A newer name ends the fence.
     */
    private fun withRetainedName(record: AppGroupRecordFfi): AppGroupRecordFfi {
        val name = localGroupNames.reconcileRecord(record.groupIdHex, record.name)
        return if (name == record.name) record else record.copy(name = name)
    }

    /** Invalidates authoritative roster state while retaining display-only continuity. */
    private fun invalidateMemberCacheForGroup(groupIdHex: String) {
        val hasRetryScheduled = memberFetchRetryJobsByGroup[groupIdHex]?.isActive == true
        val hasSnapshot =
            memberCacheByGroup.containsKey(groupIdHex) ||
                presentationMembersByGroup.containsKey(groupIdHex)
        val hasPendingFetch = groupIdHex in inFlightMemberFetches || hasRetryScheduled
        if (!hasSnapshot && !hasPendingFetch) return
        cancelMemberSnapshotRetry(groupIdHex)
        failedMemberFetches.remove(groupIdHex)
        selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
        memberCacheByGroup[groupIdHex]?.let { members ->
            val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
            presentationMembersByGroup =
                presentationMembersByGroup +
                (groupIdHex to chatListMemberPresentation(members, activeAccountIdHex))
            memberCacheByGroup = memberCacheByGroup - groupIdHex
        }
        memberCacheLifetime.advance()
    }

    private fun memberSnapshotNeedsFetch(groupIdHex: String): Boolean = !memberCacheByGroup.containsKey(groupIdHex)

    /** Whether an authoritative roster is cached for [groupIdHex], whether or not the window retains its row. */
    internal fun hasMemberSnapshot(groupIdHex: String): Boolean = memberCacheByGroup.containsKey(groupIdHex)

    /** Folds one record exactly as the account-wide chats subscription does, for chats outside the window. */
    @VisibleForTesting
    internal fun foldGroupRecordForTest(record: AppGroupRecordFfi) = foldGroup(record)

    /**
     * Seed every current chat row's identifier-only roster before publishing
     * the first local frame. MDK answers each page from its command-ready local
     * projection without profile enrichment or relay work, so member-derived
     * folders, shared groups, and existing-DM detection do not wait for an
     * N-call `groupMembers` fan-out (#1534).
     *
     * The page contract is all-or-nothing. If any requested group is unknown
     * or quarantined, fall back to eight bounded per-group local reads and hold
     * the first frame for at most 500 ms. Reads that miss the cutoff keep
     * running under this bind's lifecycle and publish in coalesced batches.
     */
    private suspend fun seedInitialMemberIdProjection(
        account: String,
        epoch: Long,
    ) {
        val groupIds = chatRows.map { it.groupIdHex }.filterNot { it in terminalMemberFetches }
        if (groupIds.isEmpty()) return
        val expectedCacheEpoch = memberCacheEpoch
        val projections = loadInitialMemberIdProjections(account, groupIds)
        if (projections == null) {
            seedInitialMemberFallback(account, epoch, expectedCacheEpoch, groupIds)
            return
        }
        if (initialMemberProjectionIsCurrent(account, epoch, expectedCacheEpoch)) {
            val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
            // Await only on-device DM peer presentation so the first row
            // cannot flash a short identity or empty avatar. Relay freshness
            // remains in requestProfile below. The degraded fallback uses this
            // same barrier for every roster resolved before the cutoff.
            appState.warmProfilePresentationsBlocking(
                initialDirectPeerProfileIds(projections, activeAccountIdHex),
            )
            if (initialMemberProjectionIsCurrent(account, epoch, expectedCacheEpoch)) {
                applyInitialMemberIdProjections(projections, activeAccountIdHex)
            }
        }
    }

    private suspend fun seedInitialMemberFallback(
        account: String,
        epoch: Long,
        cacheEpoch: Long,
        groupIds: List<String>,
    ) {
        val liveGroupIds = chatRows.mapTo(mutableSetOf()) { it.groupIdHex }
        val pending =
            groupIds
                .asSequence()
                .filter { it in liveGroupIds }
                .distinctBy { it.lowercase() }
                .filter { memberSnapshotNeedsFetch(it) }
                .filterNot { it in inFlightMemberFetches }
                .filterNot { memberFetchRetryJobsByGroup[it]?.isActive == true }
                .filterNot { it in terminalMemberFetches }
                .toList()
        if (pending.isEmpty()) return
        inFlightMemberFetches.addAll(pending)
        val cutoffStartedAtMs = SystemClock.elapsedRealtime()

        val fallback =
            loadFirstFrameMemberFallback(
                groupIds = pending,
                cutoffMillis = INITIAL_MEMBER_FALLBACK_CUTOFF_MS,
                maxConcurrent = INITIAL_MEMBER_FALLBACK_FANOUT,
            ) { groupIdHex ->
                // Share the controller-wide permit pool with ordinary and
                // retry reads. Otherwise a retry released by one failed
                // fallback could overlap eight still-running fallback reads.
                memberFetchGate.withPermit {
                    memberSnapshotLoader(account, groupIdHex)
                }
            }
        val remainingProfileWarmBudgetMillis =
            (
                INITIAL_MEMBER_FALLBACK_CUTOFF_MS -
                    (SystemClock.elapsedRealtime() - cutoffStartedAtMs)
            ).coerceAtLeast(0L)
        publishInitialMemberFallbackResults(
            results = fallback.firstFrameResults,
            account = account,
            epoch = epoch,
            cacheEpoch = cacheEpoch,
            scheduleRecomputeAfterPublish = false,
            profileWarmBudgetMillis = remainingProfileWarmBudgetMillis,
        )
        if (fallback.remainingCount == 0) return

        CoroutineScope(coroutineContext).launch {
            var remaining = fallback.remainingCount
            while (remaining > 0) {
                val completed = mutableListOf(fallback.remainingResults.receive())
                remaining -= 1
                while (remaining > 0) {
                    val next = fallback.remainingResults.tryReceive().getOrNull() ?: break
                    completed += next
                    remaining -= 1
                }
                publishInitialMemberFallbackResults(
                    results = completed,
                    account = account,
                    epoch = epoch,
                    cacheEpoch = cacheEpoch,
                    scheduleRecomputeAfterPublish = true,
                )
            }
        }
    }

    private suspend fun publishInitialMemberFallbackResults(
        results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
        account: String,
        epoch: Long,
        cacheEpoch: Long,
        scheduleRecomputeAfterPublish: Boolean,
        profileWarmBudgetMillis: Long? = null,
    ) {
        when {
            results.isEmpty() -> Unit
            rejectStaleInitialMemberFallbackResults(results, account, epoch, cacheEpoch) -> Unit
            !warmInitialMemberFallbackProfiles(results, profileWarmBudgetMillis) -> {
                continueInitialMemberFallbackProfileWarm(results, account, epoch, cacheEpoch)
            }
            rejectStaleInitialMemberFallbackResults(results, account, epoch, cacheEpoch) -> Unit
            else -> {
                applyInitialMemberFallbackResults(results, epoch, cacheEpoch)
                scheduleRecomputeIfRequested(scheduleRecomputeAfterPublish)
            }
        }
    }

    private fun rejectStaleInitialMemberFallbackResults(
        results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
        account: String,
        epoch: Long,
        cacheEpoch: Long,
    ): Boolean {
        val stale = !initialMemberProjectionIsCurrent(account, epoch, cacheEpoch)
        if (stale && isActiveBindEpoch(epoch)) {
            results.forEach { inFlightMemberFetches.remove(it.groupIdHex) }
            schedulePendingMemberFetches(results.map { it.groupIdHex })
        }
        return stale
    }

    private suspend fun warmInitialMemberFallbackProfiles(
        results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
        budgetMillis: Long?,
    ): Boolean {
        val liveResults =
            results.filter { result ->
                chatRowsByGroup.containsKey(chatRowKey(result.groupIdHex))
            }
        val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
        val projections =
            liveResults.mapNotNull { result ->
                result.result.getOrNull()?.let { members ->
                    AppGroupMemberIdsFfi(result.groupIdHex, members.map { it.memberIdHex }, adminIdsHex = emptyList())
                }
            }
        val peerProfileIds = initialDirectPeerProfileIds(projections, activeAccountIdHex)
        return when {
            peerProfileIds.isEmpty() -> true
            budgetMillis == null -> {
                appState.warmProfilePresentationsBlocking(peerProfileIds)
                true
            }
            budgetMillis == 0L -> false
            else ->
                withTimeoutOrNull(budgetMillis) {
                    appState.warmProfilePresentationsBlocking(peerProfileIds)
                    true
                } == true
        }
    }

    private suspend fun continueInitialMemberFallbackProfileWarm(
        results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
        account: String,
        epoch: Long,
        cacheEpoch: Long,
    ) {
        // The 500 ms first-frame budget is authoritative. Keep these rosters
        // unresolved for that frame rather than publishing a DM peer without
        // its locally persisted name/avatar, then finish the same consumed
        // results under the bind lifecycle.
        CoroutineScope(coroutineContext).launch {
            publishInitialMemberFallbackResults(
                results = results,
                account = account,
                epoch = epoch,
                cacheEpoch = cacheEpoch,
                scheduleRecomputeAfterPublish = true,
            )
        }
    }

    private fun applyInitialMemberFallbackResults(
        results: List<FirstFrameMemberFallbackResult<List<AppGroupMemberRecordFfi>>>,
        epoch: Long,
        cacheEpoch: Long,
    ) {
        results.forEach { completed ->
            val groupIdHex = completed.groupIdHex
            inFlightMemberFetches.remove(groupIdHex)
            if (!chatRowsByGroup.containsKey(chatRowKey(groupIdHex))) return@forEach
            completed.result.fold(
                onSuccess = { members ->
                    applyFetchedMemberSnapshot(
                        groupIdHex = groupIdHex,
                        members = members,
                        epoch = epoch,
                        cacheEpoch = cacheEpoch,
                        scheduleRecomputeAfterPublish = false,
                    )
                },
                onFailure = { throwable ->
                    chatsDebug(throwable) {
                        "initial member fallback failed group=${groupIdHex.take(8)}: " +
                            (throwable.message ?: throwable.javaClass.simpleName)
                    }
                    // A terminal failure marks the group for this bind only, like the ordinary fetch path.
                    if (isActiveBindEpoch(epoch)) {
                        markMemberSnapshotFetchFailed(groupIdHex, throwable)
                        scheduleMemberSnapshotRetry(groupIdHex, epoch)
                    }
                },
            )
        }
    }

    private fun scheduleRecomputeIfRequested(requested: Boolean) {
        if (requested) scheduleRecompute()
    }

    private suspend fun loadInitialMemberIdProjections(
        account: String,
        groupIds: List<String>,
    ): List<AppGroupMemberIdsFfi>? =
        runCatchingCancellable {
            loadGroupMemberIdsPages(groupIds) { page ->
                appState.marmotIo(MarmotTraceSection.MEMBER_IDS_READ) { groupMemberIdsPage(account, page) }
            }
        }.onFailure { error ->
            chatsDebug(error) {
                "initial member-id projection failed account=${account.take(8)}: " +
                    (error.message ?: error.javaClass.simpleName)
            }
        }.getOrNull()

    private fun initialMemberProjectionIsCurrent(
        account: String,
        epoch: Long,
        expectedCacheEpoch: Long,
    ): Boolean =
        initialMemberFallbackGenerationIsCurrent(
            expectedAccount = account,
            expectedBindEpoch = epoch,
            expectedCacheEpoch = expectedCacheEpoch,
            currentAccount = accountRef,
            currentBindEpoch = bindEpoch,
            currentCacheEpoch = memberCacheEpoch,
            lifecycleActive = isActiveBindEpoch(epoch),
        )

    private fun initialDirectPeerProfileIds(
        projections: List<AppGroupMemberIdsFfi>,
        activeAccountIdHex: String?,
    ): List<String> {
        val rowsByGroup = chatRows.associateBy { chatRowKey(it.groupIdHex) }
        return initialDirectPeerProfileIds(
            projections = projections,
            activeAccountIdHex = activeAccountIdHex,
        ) { groupIdHex, memberCount ->
            val row = rowsByGroup[chatRowKey(groupIdHex)]
            row != null && GroupProjector.isDm(row.conversationKind, memberCount, row.groupName)
        }
    }

    private fun applyInitialMemberIdProjections(
        projections: List<AppGroupMemberIdsFfi>,
        activeAccountIdHex: String?,
        requestProfileRefresh: Boolean = true,
    ) {
        val updatedCache = memberCacheByGroup.toMutableMap()
        var updatedRemovedGroupIds = removedGroupIds
        projections.forEach { projection ->
            val groupIdHex = projection.groupIdHex
            val members = memberRecordsFromIds(projection.memberIdsHex, activeAccountIdHex)
            // Same readiness gate as the streaming path: an empty or
            // self-only-DM roster from the initial page can be a transient
            // catch-up result. Caching it would pin the row on the Unknown
            // fallback forever — memberSnapshotNeedsFetch never retries a
            // cached key — and drop the last-known presentation for nothing.
            // Skipped groups are refetched by the post-bind enrichment sweep,
            // which owns the full gate and retry machinery.
            if (!initialPageRosterReadyToCache(projection.groupIdHex, members, activeAccountIdHex)) {
                return@forEach
            }
            updatedCache[groupIdHex] = members
            updatedRemovedGroupIds =
                if (
                    activeAccountIdHex != null &&
                    members.none { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
                ) {
                    updatedRemovedGroupIds + groupIdHex
                } else {
                    updatedRemovedGroupIds - groupIdHex
                }
            if (requestProfileRefresh) {
                members.map { it.memberIdHex }.forEach(appState::requestProfile)
            }
            presentationMembersByGroup = presentationMembersByGroup - groupIdHex
            cancelMemberSnapshotRetry(groupIdHex)
            memberFetchRetryBackoffTierByGroup.remove(groupIdHex)
            failedMemberFetches.remove(groupIdHex)
            selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
        }
        memberCacheByGroup = updatedCache
        removedGroupIds = updatedRemovedGroupIds
        memberSnapshotsRevision += 1L
    }

    // The initial member-id page is gated with the same conversation context
    // the streaming path derives: a self-only roster for a direct conversation
    // is as transient as an empty one and must be retried, not cached.
    private fun initialPageRosterReadyToCache(
        groupIdHex: String,
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): Boolean =
        memberSnapshotReadyToCache(
            members = members,
            knownSelfRemoval = knownSelfRemovalFor(groupIdHex),
            directConversation = directConversationCandidateFor(groupIdHex, members),
            activeAccountIdHex = activeAccountIdHex,
            selfOnlyDirectGraceElapsed = groupIdHex in selfOnlyDirectGraceRetryGroups,
        )

    /**
     * Whether this account's removal from the group is already known locally,
     * which makes an empty roster terminal rather than a hydration gap.
     */
    private fun knownSelfRemovalFor(groupIdHex: String): Boolean =
        groupIdHex in removedGroupIds ||
            chatRowsByGroup[chatRowKey(groupIdHex)]?.selfMembership?.isNonMember() == true ||
            groupRecordsById[groupIdHex]?.selfMembership?.isNonMember() == true

    /**
     * Whether the group should be treated as a direct conversation for roster
     * readiness, including the unresolved case where the projection has not
     * classified an unnamed low-headcount conversation yet. Shared by the
     * initial-page and streaming gates so the two cannot drift — an earlier
     * copy of this heuristic living in only one of them is what let a
     * self-only DM roster be cached and pin an Unknown title.
     */
    private fun directConversationCandidateFor(
        groupIdHex: String,
        members: List<AppGroupMemberRecordFfi>,
    ): Boolean {
        val row = chatRowsByGroup[chatRowKey(groupIdHex)]
        val memberCount = GroupProjector.uniqueMemberCount(members)
        val groupName = row?.groupName ?: groupRecordsById[groupIdHex]?.name.orEmpty()
        val unresolvedDirectConversation =
            row?.conversationKind == ChatConversationKindFfi.UNKNOWN &&
                memberCount <= 1 &&
                GroupProjector.isUnnamed(groupName)
        return unresolvedDirectConversation ||
            GroupProjector.isDm(
                conversationKind = row?.conversationKind,
                memberCount = memberCount,
                name = groupName,
            )
    }

    private fun recordMemberDerivedLocalReadyIfComplete() {
        val account = accountRef ?: return
        if (chatRows.all { memberCacheByGroup.containsKey(it.groupIdHex) }) {
            appState.recordAccountSwitchMemberDerivedLocalReady(account, chatRows.size)
        }
    }

    // Marmot's `set_group_archived` writes local state + saves but emits no
    // ProjectionUpdated event, so the chat-list snapshot stays stale until the
    // next account switch (issue: unarchive doesn't move chat out of archived
    // section). Callers in ConversationController forward the updated record
    // here via AppState so the chat list reflects the new archived flag.

    /**
     * Publishes a ready optimistic preview with publication-time activity order.
     * Returning to the list shows it immediately while the real stream catches up (#900).
     */
    internal fun applyOptimisticSentPreview(
        groupIdHex: String,
        preview: ChatListMessagePreviewFfi,
    ): Boolean {
        val rowKey = chatRowKey(groupIdHex)
        val row = chatRowsByGroup[rowKey].takeIf { accountRef != null } ?: return false
        val state =
            optimisticChatListPreviewByGroup.getOrPut(rowKey) {
                val baselineActivitySequence = activitySequenceByGroup[rowKey] ?: 0uL
                OptimisticChatListPreviewState(
                    baselineRow = row,
                    baselineActivitySequence = baselineActivitySequence,
                ).also { state ->
                    rememberBaselineActivitySequence(state, row, baselineActivitySequence)
                }
            }
        if (state.failedFallbackEntry?.preview?.messageIdHex == preview.messageIdHex) {
            state.failedFallbackEntry = null
        }
        state.entries[preview.messageIdHex] =
            OptimisticChatListPreviewEntry(
                preview = preview,
                activitySequence = nextChatActivitySequence(),
            )
        materializeOptimisticChatListPreview(rowKey, state)
        scheduleRecompute()
        return true
    }

    /**
     * Reserves a text send's chat-list order at acceptance without publishing
     * its raw Markdown. Parsing may complete after newer list activity, so the
     * later styled/fallback publication must reuse this order rather than gain
     * a fresh sequence merely because its parser was slower.
     */
    internal fun reserveOptimisticSentPreview(
        groupIdHex: String,
        optimisticMessageIdHex: String,
    ): Boolean {
        val rowKey = chatRowKey(groupIdHex)
        val row = chatRowsByGroup[rowKey].takeIf { accountRef != null } ?: return false
        val state =
            optimisticChatListPreviewByGroup.getOrPut(rowKey) {
                val baselineActivitySequence = activitySequenceByGroup[rowKey] ?: 0uL
                OptimisticChatListPreviewState(
                    baselineRow = row,
                    baselineActivitySequence = baselineActivitySequence,
                ).also { newState ->
                    rememberBaselineActivitySequence(newState, row, baselineActivitySequence)
                }
            }
        state.reservedActivitySequenceById[optimisticMessageIdHex] = nextChatActivitySequence()
        scheduleRecompute()
        while (state.reservedActivitySequenceById.size > MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY) {
            state.reservedActivitySequenceById.remove(state.reservedActivitySequenceById.keys.first())
        }
        return true
    }

    /** Publishes a parsed text preview using its acceptance-time order. */
    internal fun applyReservedOptimisticSentPreview(
        groupIdHex: String,
        preview: ChatListMessagePreviewFfi,
    ): Boolean {
        val rowKey = chatRowKey(groupIdHex)
        val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return false
        val activitySequence = state.reservedActivitySequenceById.remove(preview.messageIdHex)
        return if (activitySequence == null) {
            false
        } else {
            if (state.failedFallbackEntry?.preview?.messageIdHex == preview.messageIdHex) {
                state.failedFallbackEntry = null
            }
            state.entries[preview.messageIdHex] =
                OptimisticChatListPreviewEntry(
                    preview = preview,
                    activitySequence = activitySequence,
                )
            materializeOptimisticChatListPreview(rowKey, state)
            scheduleRecompute()
            true
        }
    }

    /** Commits the accepted optimistic entry while preserving its original list-order sequence. */
    internal fun commitOptimisticSentPreview(
        groupIdHex: String,
        optimisticMessageIdHex: String,
        confirmedMessageIdHex: String,
    ) {
        val rowKey = chatRowKey(groupIdHex)
        val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return
        val entry = state.entries[optimisticMessageIdHex] ?: return
        state.failedFallbackEntry =
            state.failedFallbackEntry?.takeIf {
                it.activitySequence >= entry.activitySequence
            }
        state.confirmedActivitySequenceById[confirmedMessageIdHex] = entry.activitySequence
        while (state.confirmedActivitySequenceById.size > MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY) {
            state.confirmedActivitySequenceById.remove(state.confirmedActivitySequenceById.keys.first())
        }
        state.entries[optimisticMessageIdHex] =
            entry.copy(
                preview =
                    entry.preview.copy(
                        messageIdHex = confirmedMessageIdHex,
                        groupSystem = null,
                        deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED,
                    ),
                confirmedMessageIdHex = confirmedMessageIdHex,
                pendingAuthoritativeRow = null,
            )
        // Re-evaluate an echo that arrived while this send was still pending.
        // An exact confirmed id retires this entry; an id mismatch is offered to
        // the next still-pending identical send instead.
        entry.pendingAuthoritativeRow?.let { row -> foldOptimisticChatListBaseline(state, row) }
        state.entries[optimisticMessageIdHex]?.let { committedEntry ->
            state.entries.entries.removeAll { (messageIdHex, candidate) ->
                messageIdHex != optimisticMessageIdHex &&
                    candidate.confirmedMessageIdHex != null &&
                    candidate.activitySequence < committedEntry.activitySequence
            }
            val supersededByNewerCommit =
                state.entries.any { (messageIdHex, candidate) ->
                    messageIdHex != optimisticMessageIdHex &&
                        candidate.confirmedMessageIdHex != null &&
                        candidate.activitySequence > committedEntry.activitySequence
                }
            if (committedEntry.activitySequence <= state.baselineActivitySequence || supersededByNewerCommit) {
                state.entries.remove(optimisticMessageIdHex)
            }
        }
        materializeOptimisticChatListPreview(rowKey, state)
        scheduleRecompute()
    }

    /**
     * Flips a still-tracked optimistic preview to FAILED instead of erasing
     * it. A failed send must stay visible as failed on the row — silently
     * reverting the list to the prior message while the conversation shows a
     * failed bubble breaks trust in the list. Genuine abandonment
     * (discard, own eviction) still uses [rollbackOptimisticSentPreview].
     */
    internal fun failOptimisticSentPreview(
        groupIdHex: String,
        optimisticMessageIdHex: String,
    ) {
        val rowKey = chatRowKey(groupIdHex)
        val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return
        val entry = state.entries[optimisticMessageIdHex] ?: return
        state.entries[optimisticMessageIdHex] =
            entry.copy(
                preview = entry.preview.copy(deliveryState = ChatListMessageDeliveryStateFfi.FAILED),
            )
        materializeOptimisticChatListPreview(rowKey, state)
        scheduleRecompute()
    }

    /** Removes a reservation or entry only when that exact optimistic id is still tracked. */
    internal fun rollbackOptimisticSentPreview(
        groupIdHex: String,
        optimisticMessageIdHex: String,
    ) {
        val rowKey = chatRowKey(groupIdHex)
        val state = optimisticChatListPreviewByGroup[rowKey].takeIf { accountRef != null } ?: return
        val removedReservation = state.reservedActivitySequenceById.remove(optimisticMessageIdHex) != null
        val removedEntry = state.entries.remove(optimisticMessageIdHex)
        val removedFallback = state.failedFallbackEntry?.preview?.messageIdHex == optimisticMessageIdHex
        if (removedFallback) state.failedFallbackEntry = null
        if (!removedReservation && removedEntry == null && !removedFallback) return
        removedEntry
            ?.pendingAuthoritativeRow
            ?.takeIf { row -> row.lastMessage?.deliveryState == ChatListMessageDeliveryStateFfi.DELIVERED }
            ?.let { row -> foldOptimisticChatListBaseline(state, row) }
        materializeOptimisticChatListPreview(rowKey, state)
        scheduleRecompute()
    }

    /** [clearedName] is the name a committed clear removed, so a blank [record] name is not read as "no name yet". */
    fun applyLocalGroupUpdate(
        record: AppGroupRecordFfi,
        clearedName: String? = null,
    ) {
        applyLocalGroupProjection(record, members = null, clearedName = clearedName)
    }

    fun applyLocalGroupDetails(
        record: AppGroupRecordFfi,
        members: List<AppGroupMemberRecordFfi>,
    ) {
        applyLocalGroupProjection(record, members)
    }

    /** Applies a local group mutation and invalidates any older roster fetch. */
    private fun applyLocalGroupProjection(
        record: AppGroupRecordFfi,
        members: List<AppGroupMemberRecordFfi>?,
        clearedName: String? = null,
    ) {
        if (accountRef == null) return
        val rowKey = chatRowKey(record.groupIdHex)
        val retained = groupRecordsById[record.groupIdHex] != null || chatRowsByGroup.containsKey(rowKey)
        if (members != null) {
            memberCacheLifetime.advance()
            presentationMembersByGroup = presentationMembersByGroup - record.groupIdHex
            cancelMemberSnapshotRetry(record.groupIdHex)
            memberFetchRetryBackoffTierByGroup.remove(record.groupIdHex)
            failedMemberFetches.remove(record.groupIdHex)
            selfOnlyDirectGraceRetryGroups.remove(record.groupIdHex)
            members
                .map { it.memberIdHex }
                .filter { it.isNotBlank() }
                .forEach(appState::requestProfile)
            val updated =
                applyAuthoritativeChatListMembers(
                    groupIdHex = record.groupIdHex,
                    members = members,
                    activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
                    memberCacheByGroup = memberCacheByGroup,
                    removedGroupIds = removedGroupIds,
                )
            memberCacheByGroup = updated.memberCacheByGroup
            removedGroupIds = updated.removedGroupIds
            memberSnapshotsRevision += 1L
        }
        // Off-window profile management uses the same authoritative roster cache. Updating it must
        // not insert a chat into the retained native window or create a second group projection.
        if (!retained) {
            if (members != null) scheduleRecompute()
            return
        }
        // chatListItemFromProjection reads row.archived / row.pendingConfirmation
        // (not just the group record), so patch both the chat row and the group
        // record to keep them consistent.
        (optimisticChatListPreviewByGroup[rowKey]?.baselineRow ?: chatRowsByGroup[rowKey])?.let { row ->
            val updated = locallyProjectedChatRow(rowKey, row, record, clearedName)
            optimisticChatListPreviewByGroup[rowKey]?.let { state ->
                state.baselineRow = updated
                materializeOptimisticChatListPreview(rowKey, state)
            } ?: run {
                chatRowsByGroup[rowKey] = updated
            }
        }
        foldGroup(record, invalidateMembers = members == null, fromSubscription = false)
    }

    /**
     * Patches [row] from a locally committed [record].
     *
     * A rename, including clearing the name, also moves the prepared row title and the selected
     * presentation the list displays, searches and sorts by, and is retained against a stale
     * pre-rename snapshot (#2696).
     */
    private fun locallyProjectedChatRow(
        rowKey: String,
        row: ChatListRowFfi,
        record: AppGroupRecordFfi,
        clearedName: String?,
    ): ChatListRowFfi {
        // Only an explicit clear empties the row; any other blank record simply carries no name yet.
        // The caller states the intent because the subscription may already have folded the cleared
        // record, so the prior name cannot be read back from live state.
        val projectedName = if (clearedName != null) "" else record.name.ifBlank { row.groupName }
        val renamed = projectedName != row.groupName
        // A row that already shows the clear still fences the cleared name against the group record,
        // which arrives on its own stream and is what a blank row falls back to.
        if (clearedName != null && clearedName != row.groupName) {
            localGroupNames.record(record.groupIdHex, clearedName, projectedName)
        }
        if (renamed) {
            localGroupNames.record(record.groupIdHex, row.groupName, projectedName)
            selectedPresentationsByGroup =
                selectedPresentationsByGroup[rowKey]?.let { presentation ->
                    selectedPresentationsByGroup + (rowKey to presentation.withLocalGroupTitle(projectedName))
                } ?: selectedPresentationsByGroup
        }
        return row.copy(
            archived = record.archived,
            pendingConfirmation = record.pendingConfirmation,
            groupName = projectedName,
            title = if (renamed) projectedName else row.title,
        )
    }

    fun applyProfileGroupDetails(
        account: String,
        details: GroupDetailsFfi,
    ) {
        if (accountRef == null) return
        val applied = applyAuthoritativeGroupDetails(details)
        val groupIdHex = applied.group.groupIdHex
        if (groupRecordsById[groupIdHex] == null && !chatRowsByGroup.containsKey(chatRowKey(groupIdHex))) return
        cacheAppliedGroupMembers(appState, account, groupIdHex, applied.members)
        applyLocalGroupProjection(applied.group, applied.members)
    }

    // Project every current chat row into a ChatListItem. Reads chatRows (kept
    // current by the bind loop even when recompute is deferred behind an open
    // conversation, #6), so on-demand callers — shared groups, DM lookup,
    // by-id resolution for navigation — see freshly created/updated groups
    // instead of the stale `items` snapshot.
    // Last-known roster presentation for a row whose live caches are cold: a
    // fresh bind (cold start, account switch, controller recreation) starts
    // with empty per-bind maps while the account-scoped snapshot cache may
    // still hold the roster from the previous session's group-details or
    // conversation reads. Falling back keeps a previously resolved title and
    // avatar on screen instead of the Unknown placeholder until hydration
    // catches up. No new state: this reads an existing cache.
    private fun lastKnownPresentation(
        groupIdHex: String,
        activeAccountIdHex: String?,
    ): ChatListMemberPresentation? {
        // An authoritative roster wins downstream — skip the fallback lookup
        // entirely so hydrated rows pay no per-recompute cache reads.
        if (memberCacheByGroup.containsKey(groupIdHex)) return null
        return presentationMembersByGroup[groupIdHex]
            ?: appState
                .cachedGroupMemberSnapshot(accountRef, groupIdHex)
                ?.members
                ?.let { chatListMemberPresentation(it, activeAccountIdHex) }
    }

    /** Resolves the bound account identity, falling back to the active account before binding completes. */
    private fun projectionAccountIdHex(): String? = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex

    /** Projects the existing backing rows without retaining another presentation snapshot. */
    private fun currentProjectedItems(activeAccountIdHex: String? = projectionAccountIdHex()): List<ChatListItem> {
        val projector = rowProjector(activeAccountIdHex)
        return chatRows.map(projector::retained)
    }

    /** Creates a synchronous projector over the current controller-owned inputs for the selected account. */
    private fun rowProjector(activeAccountIdHex: String?): ChatListRowProjector =
        ChatListRowProjector(
            activeAccountIdHex = activeAccountIdHex,
            groups = groupRecordsById,
            members = memberCacheByGroup,
            presentationMembers = { lastKnownPresentation(it, activeAccountIdHex) },
            tokens = previewTokensByText,
            mediaFallbacks = mediaPreviewFallbackByMessageId,
            removed = removedGroupIds,
            activitySequences = activitySequenceByGroup,
            optimisticPreviews = optimisticChatListPreviewByGroup,
            archiveActions = archiveActions,
            localGroupNames = localGroupNames,
            presentations = selectedPresentationsByGroup,
            avatars = selectedAvatarAssetsByGroup,
            previews = selectedPreviewsByGroup,
            actions = selectedActionsByGroup,
        )

    internal fun boundAccountIdHex(): String? {
        val ref = accountRef ?: return null
        return appState.accounts.firstOrNull { it.label == ref }?.accountIdHex
    }

    /** Projects one retained row with the live window's presentation, preview, actions and caches. */
    internal fun projectChatRow(
        authoritativeRow: ChatListRowFfi,
        activeAccountIdHex: String? = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
    ): ChatListItem = rowProjector(activeAccountIdHex).retained(authoritativeRow)

    /** Projects an off-window MDK row without materializing it into the retained window. */
    internal fun projectPresentedRow(
        presented: PresentedChatRowFfi,
        activeAccountIdHex: String? = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
    ): ChatListItem = rowProjector(activeAccountIdHex).presented(presented)

    /** Keeps source picture identity separate from MDK-selected display URL/hash rewrites. */
    internal fun currentGroupAvatarItem(groupIdHex: String): ChatListItem? {
        val row =
            chatRowsByGroup[chatRowKey(groupIdHex)]
                ?: chatRows.firstOrNull { it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
                ?: return null
        val item = projectChatRow(row)
        val sourceGroup = chatListDisplayGroup(row, groupRecordsById[row.groupIdHex] ?: emptyGroupRecord(row))
        return item.copy(group = sourceGroup)
    }

    fun profileAddableGroups(
        targetAccountIdHex: String,
        activeAccountIdHex: String?,
    ): List<ChatListItem> = profileAddableGroupsState(targetAccountIdHex, activeAccountIdHex).groups

    /** Groups [targetAccountIdHex] can be added to, including those [accountWide] holds beyond the window. */
    internal fun profileAddableGroupsState(
        targetAccountIdHex: String,
        activeAccountIdHex: String?,
        accountWide: ProfileAccountWideGroups = ProfileAccountWideGroups(),
    ): ProfileGroupPickerState =
        profileAddableGroupsState(
            items = mergeForwardTargets(currentProjectedItems(activeAccountIdHex), accountWide.items()),
            targetAccountIdHex = targetAccountIdHex,
            activeAccountIdHex = activeAccountIdHex,
            failedGroupIds = failedMemberFetches + accountWide.failedGroupIds,
        ).failedWhenAccountWideReadFailed(accountWide)

    /** Groups [targetAccountIdHex] can be made an admin of, including those [accountWide] holds beyond the window. */
    internal fun profilePromotableGroupsState(
        targetAccountIdHex: String,
        activeAccountIdHex: String?,
        accountWide: ProfileAccountWideGroups = ProfileAccountWideGroups(),
    ): ProfileGroupPickerState =
        profilePromotableGroupsState(
            items = mergeForwardTargets(currentProjectedItems(activeAccountIdHex), accountWide.items()),
            targetAccountIdHex = targetAccountIdHex,
            activeAccountIdHex = activeAccountIdHex,
            failedGroupIds = failedMemberFetches + accountWide.failedGroupIds,
        ).failedWhenAccountWideReadFailed(accountWide)

    /**
     * The confirmed, still-active 1:1 DM with [reference] (npub or hex), or
     * null. A match must be an *implicit* DM in its current MLS state: no
     * custom group name, the active account still a member, and the live roster
     * exactly `{me, target}` (see [GroupProjector.isImplicitDmWith]). The
     * counterparty is stored as hex, so compare in both hex and npub forms.
     *
     * This is deliberately strict (#825): a two-person conversation that was
     * renamed, or one the target was removed from (leaving the user talking to
     * themselves), is no longer a DM with that person, so Start-DM must open a
     * fresh DM instead of resurfacing the stale group. There is intentionally
     * no closest-historical-match fallback. Shared with the new-chat sheet so
     * "open existing DM" and "don't create a duplicate DM" agree on what counts
     * as an existing one.
     */
    fun existingDirectChat(reference: String): ChatListItem? = existingDirectChatCandidates(reference).firstOrNull()

    /**
     * Every current implicit DM with [reference], best first per
     * [directChatPreferenceOrder]. Ordering matters because duplicates are
     * reachable — both sides can each have created a DM — and the tap that opens
     * one must land on the same conversation every time.
     */
    private fun existingDirectChatCandidates(reference: String): List<ChatListItem> {
        val normalizedReference = reference.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
        return chatRows
            .asSequence()
            .filter { !it.pendingConfirmation }
            .mapNotNull { row ->
                val members = memberCacheByGroup[row.groupIdHex] ?: return@mapNotNull null
                val match =
                    GroupProjector.isImplicitDmWith(
                        members = members,
                        name = row.groupName,
                        activeAccountIdHex = activeAccountIdHex,
                        targetIdHex = normalizedReference,
                        equivalentTarget = { other -> appState.npub(other).equals(normalizedReference, ignoreCase = true) },
                    )
                if (match) projectChatRow(row) else null
            }.sortedWith(directChatPreferenceOrder)
            .toList()
    }

    /** Read-request dedupe reads the native flag without constructing a display item. */
    internal fun hasManualUnreadReminder(groupIdHex: String): Boolean? {
        val row =
            chatRowsByGroup[chatRowKey(groupIdHex)]
                ?: chatRows.firstOrNull { it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
        return row?.manuallyMarkedUnread
    }

    fun chatItemForGroup(groupIdHex: String): ChatListItem? {
        val row = chatRowsByGroup[chatRowKey(groupIdHex)] ?: chatRows.firstOrNull { it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
        return row?.let { projectChatRow(it) }
    }

    /**
     * Revalidate picker provenance against the current backing row and an
     * authoritative local [groupDetails] read (#1701). Uses [chatRowsByGroup],
     * not the debounced [items] snapshot.
     */
    internal suspend fun resolveProvenanceDirectChat(
        provenanceGroupIdHex: String?,
        targetReference: String,
        diagnosticAttempt: DmCreationAttempt? = null,
    ): NewMessageDirectChatResolution {
        val unavailable = NewMessageDirectChatResolution(item = null, createRequired = false)
        val account = accountRef ?: return unavailable
        return resolveDirectChatGroup(
            account = account,
            epoch = bindEpoch,
            activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
            groupIdHex = provenanceGroupIdHex,
            targetReference = targetReference,
            chatItemForGroup = ::chatItemForGroup,
            diagnosticAttempt = diagnosticAttempt,
        )
    }

    // Lightweight membership probe over the raw rows — no per-row ChatListItem
    // projection.
    fun containsGroup(groupIdHex: String): Boolean = chatRowsByGroup.containsKey(chatRowKey(groupIdHex))

    /** Lowercase IDs of every chat the bounded windows retain, as a read-only view of the raw rows. */
    internal fun retainedGroupIds(): Set<String> = chatRowsByGroup.keys

    /**
     * Chats the active account can forward a message into, recent first.
     *
     * A forward fans a fresh send into each selected group (encrypted under
     * that group's own state — see [WhiteNoiseAppState.forwardText]), so the
     * targets are the same confirmed conversations the chat list shows: an
     * invite (`pendingConfirmation`) is not yet a joined group the account can
     * send into, so it is excluded. Archived chats are included by default so a
     * forward can reach a muted/parked conversation (the issue leaves
     * archived-target handling open; allowing it keeps the picker complete and
     * is the less surprising default). Ordering reuses [sortChatListItems] so
     * the picker matches the chat list's "recent first" feel.
     */
    fun forwardTargets(): List<ChatListItem> =
        sortChatListItems(
            currentProjectedItems().filter { item ->
                isEligibleForwardTarget(
                    item = item,
                    activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
                )
            },
        )

    /** Applies one ordered update emitted by the active chat-list subscription. */
    @VisibleForTesting
    internal fun applyChatListSubscriptionUpdate(
        accountRef: String,
        update: ChatListSubscriptionUpdateFfi,
    ) {
        when (update) {
            is ChatListSubscriptionUpdateFfi.Row -> {
                val row = update.row
                requestChatRowProfiles(row)
                chatsDebug {
                    "chat list update account=${accountRef.take(8)} trigger=${update.trigger} ${row.debugSummary()}"
                }
                foldChatRow(row, update.trigger)
            }
            is ChatListSubscriptionUpdateFfi.Snapshot -> {
                chatsDebug {
                    "chat list snapshot account=${accountRef.take(8)} " +
                        "trigger=${update.trigger} rows=${update.rows.size}"
                }
                // Contract: atomically replace the held rows and drop any prior
                // row absent from the snapshot.
                update.rows.forEach(::requestChatRowProfiles)
                replaceChatRows(update.rows)
                scheduleRecompute()
            }
            is ChatListSubscriptionUpdateFfi.RemoveRow -> {
                chatsDebug {
                    "chat list remove account=${accountRef.take(8)} trigger=${update.trigger} id=${update.groupIdHex.take(8)}"
                }
                removeChatRow(update.groupIdHex)
            }
        }
    }

    /** Applies the merged rows only after checking an unexpected active-row drop against MDK. */
    @VisibleForTesting
    internal suspend fun applyChatListWindowRows(
        accountRef: String,
        windows: ChatListWindowSet,
    ): Boolean {
        val folder = folderWindows
        val folderRevision = folder?.currentRevision
        val frame = windows.frame().withFolder(folder)
        val valid = validateChatListWindowRows(accountRef, windows, frame.rows)
        // The plain revision guards only the plain rows; a folder swap or install since capture makes this frame
        // stale too, and the change that superseded it publishes its own frame.
        val folderCurrent = folderWindows === folder && folder?.currentRevision == folderRevision
        return when {
            windows.closed || chatListWindows !== windows -> {
                windows.close()
                false
            }
            !windows.isCurrent(frame) || !folderCurrent -> true
            !valid -> {
                windows.close()
                false
            }
            else -> {
                windows.publishIfCurrent(frame) { rows ->
                    replacePresentedChatRows(rows, windows.installed(ChatListViewFfi.CHATS))
                    stagedChatListFrame = ChatListFrameMark(windows, frame.revision)
                    scheduleRecompute()
                }
                !windows.closed
            }
        }
    }

    // A failed keyed read or a confirmed missing row must abort before replacing the coherent frame.
    @Suppress("ReturnCount")
    private suspend fun validateChatListWindowRows(
        accountRef: String,
        windows: ChatListWindowSet,
        rows: List<PresentedChatRowFfi>,
    ): Boolean {
        val lookup = liveSubscriptions.presentedRowByGroup ?: return true
        val activeWindow = windows.installed(ChatListViewFfi.CHATS)
        val previous =
            chatRowsByGroup.map { (key, row) -> optimisticChatListPreviewByGroup[key]?.baselineRow ?: row }
        val candidates = missingActiveTopChatRows(previous, rows, activeWindow)
        for (old in candidates) {
            val authoritative =
                runCatchingCancellable { lookup(accountRef, old.groupIdHex) }
                    .getOrElse { failure ->
                        Log.w("DMChats", "CHAT_LIST_ROW_CHECK_FAILED reason=${failure.javaClass.simpleName}")
                        return false
                    }?.row ?: continue
            if (authoritative.belongsInActiveChats() && activeWindow?.shouldContain(authoritative) == true) {
                Log.w(
                    "DMChats",
                    "CHAT_LIST_INCOMPLETE account=${chatListLogHash(accountRef)} " +
                        "generation=${chatListLogHash(activeWindow.subscriptionGeneration)} " +
                        "sequence=${activeWindow.sequence} previous=${previous.size} incoming=${rows.size}",
                )
                return false
            }
        }
        return true
    }

    /**
     * Atomically replaces both base rows and their matching selected presentation. [activeWindow] is the
     * active-chats window this frame came from, which tells a row evicted past its tail from a removed one.
     */
    private fun replacePresentedChatRows(
        rows: List<PresentedChatRowFfi>,
        activeWindow: ChatListWindowSnapshotFfi? = null,
    ) {
        selectedPresentationsByGroup =
            rows.associate { presented ->
                chatRowKey(presented.row.groupIdHex) to
                    localGroupNames.reconciledPresentation(presented.row, presented.presentation)
            }
        // MarmotKit 0.10.1 stores avatars durably and names each row's asset here; the row prefers those
        // bytes over fetching its URL, so a cached avatar survives being offline.
        selectedAvatarAssetsByGroup =
            rows
                .mapNotNull { presented ->
                    presented.avatarAsset?.let { chatRowKey(presented.row.groupIdHex) to it }
                }.toMap()
        selectedPreviewsByGroup =
            rows.associate { presented -> chatRowKey(presented.row.groupIdHex) to presented.preview }
        selectedActionsByGroup =
            rows.associate { presented -> chatRowKey(presented.row.groupIdHex) to presented.actions }
        rows.forEach { requestChatRowProfiles(it.row) }
        replaceChatRows(rows.map(PresentedChatRowFfi::row), activeWindow)
    }

    /** Folds one authoritative row and mirrors it into any mounted conversation. */
    private fun foldChatRow(
        incoming: ChatListRowFfi,
        trigger: ChatListUpdateTriggerFfi? = null,
    ) {
        val row = localGroupNames.reconciled(incoming)
        if (trigger == ChatListUpdateTriggerFfi.MUTE_CHANGED || trigger == ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH) {
            appState.acceptAuthoritativeMuteProjection(accountRef, row.groupIdHex, row.muted, row.mutedUntilMs)
        }
        val key = chatRowKey(row.groupIdHex)
        val state = optimisticChatListPreviewByGroup[key]
        val current = state?.baselineRow ?: chatRowsByGroup[key]
        val folded =
            when {
                current == null -> row
                trigger != null -> reduceSubscriptionChatListRow(current, row, trigger)
                else -> row
            }
        val observesNewActivity = observesSubscriptionActivity(current, folded, trigger)
        if (current != null && row.unreadCount > folded.unreadCount) {
            logStaleChatListUnreadRejected(
                keptUnread = folded.unreadCount,
                rejectedUnread = row.unreadCount,
            )
        }
        if (folded == current) {
            appState.publishConversationChatListRow(accountRef, folded)
            return
        }
        val membershipChanged = current == null
        val effectivePublicationRow =
            if (state == null) {
                chatRowsByGroup[key] = folded
                if (shouldAdvanceChatActivitySequence(current, folded, observesNewActivity)) {
                    activitySequenceByGroup[key] = nextChatActivitySequence()
                }
                folded
            } else {
                foldOptimisticChatListBaseline(
                    state,
                    folded,
                    acceptBackwardActivity = trigger == ChatListUpdateTriggerFfi.LAST_MESSAGE_DELETED,
                    observesNewActivity = observesNewActivity,
                ).also {
                    materializeOptimisticChatListPreview(key, state)
                }
            }
        appState.publishConversationChatListRow(accountRef, effectivePublicationRow)
        if (membershipChanged) noteMaterializedGroupMembershipChanged(removal = MembershipRemoval.None)
        scheduleRecompute()
    }

    /** Replaces one snapshot row and returns the metadata row safe to publish. */
    private fun replaceChatRow(
        key: String,
        row: ChatListRowFfi,
        previous: ChatListRowFfi?,
        previousSequence: ULong?,
        wasMaterialized: Boolean,
        optimisticState: OptimisticChatListPreviewState?,
    ): ChatListRowFfi {
        if (optimisticState != null) {
            return foldOptimisticChatListBaseline(optimisticState, row, acceptBackwardActivity = true).also {
                materializeOptimisticChatListPreview(key, optimisticState)
            }
        }
        chatRowsByGroup[key] = row
        activitySequenceByGroup[key] =
            when {
                previous == null && wasMaterialized -> nextChatActivitySequence()
                previous != null && chatListActivityAdvanced(previous, row) -> nextChatActivitySequence()
                else -> previousSequence ?: 0uL
            }
        return row
    }

    /**
     * Fold a chat-list row returned synchronously from a mark-read FFI call.
     * The chat-list subscription normally carries unread deltas, but
     * [markTimelineMessageRead]'s authoritative projection is its return value;
     * applying it here keeps badges and reopen dividers current even when no
     * OS notification was posted (issue #1251).
     */
    fun applyChatListRow(row: ChatListRowFfi) {
        if (accountRef == null) return
        val key = chatRowKey(row.groupIdHex)
        val current = optimisticChatListPreviewByGroup[key]?.baselineRow ?: chatRowsByGroup[key]
        if (current == null) {
            foldChatRow(row)
            return
        }
        val merged = mergeMarkReadChatListRow(current, row) ?: return
        foldChatRow(merged)
    }

    /**
     * Replaces the chat-list window and refreshes mounted conversation metadata. [activeWindow], when known,
     * classifies rows that left because they now rank outside its retained span as evictions rather than removals.
     */
    private fun replaceChatRows(
        incoming: List<ChatListRowFfi>,
        activeWindow: ChatListWindowSnapshotFfi? = null,
    ) {
        val rows = incoming.map(localGroupNames::reconciled)
        rows.forEach {
            appState.acceptAuthoritativeMuteProjection(accountRef, it.groupIdHex, it.muted, it.mutedUntilMs)
        }
        val previousRowsByGroup =
            chatRowsByGroup.keys.associateWith { key ->
                optimisticChatListPreviewByGroup[key]?.baselineRow ?: chatRowsByGroup.getValue(key)
            }
        val previousKeys = previousRowsByGroup.keys
        val previousSequences =
            previousRowsByGroup.keys.associateWith { key ->
                optimisticChatListPreviewByGroup[key]?.baselineActivitySequence
                    ?: activitySequenceByGroup[key]
                    ?: 0uL
            }
        val wasMaterialized = previousRowsByGroup.isNotEmpty()
        chatRowsByGroup.clear()
        activitySequenceByGroup.clear()
        rows.forEach { row ->
            val key = chatRowKey(row.groupIdHex)
            val previous = previousRowsByGroup[key]
            val effectivePublicationRow =
                replaceChatRow(
                    key = key,
                    row = row,
                    previous = previous,
                    previousSequence = previousSequences[key],
                    wasMaterialized = wasMaterialized,
                    optimisticState = optimisticChatListPreviewByGroup[key],
                )
            appState.publishConversationChatListRow(accountRef, effectivePublicationRow)
        }
        optimisticChatListPreviewByGroup.keys.retainAll(chatRowsByGroup.keys)
        val liveGroupIds = rows.mapTo(mutableSetOf()) { it.groupIdHex }
        presentationMembersByGroup = presentationMembersByGroup.filterKeys { it in liveGroupIds }
        selfOnlyDirectGraceRetryGroups.retainAll(liveGroupIds)
        memberFetchRetryJobsByGroup.keys
            .filterNot { it in liveGroupIds }
            .toList()
            .forEach(::cancelMemberSnapshotRetry)
        memberFetchRetryBackoffTierByGroup.keys.retainAll(liveGroupIds)
        failedMemberFetches.retainAll(liveGroupIds)
        if (previousKeys != chatRowsByGroup.keys.toSet()) {
            // A bounded window evicts its tail whenever an off-window chat moves in, and a scrolled window also
            // evicts past its first row. Those chats still exist, so an earlier full read still covers them; only a
            // row the window's span should still hold is an ambiguous removal. A frame carries no new keys for a
            // dropped row, so a mid-list chat that rose to the head on a new message counts as ambiguous too.
            val dropped =
                previousRowsByGroup.any { (key, row) ->
                    key !in chatRowsByGroup && !row.evictedFrom(activeWindow)
                }
            noteMaterializedGroupMembershipChanged(
                removal = if (dropped) MembershipRemoval.Ambiguous else MembershipRemoval.None,
            )
        }
    }

    private fun snapshotChatRowForRemoval(groupIdHex: String): RemovedChatRowSnapshot? {
        val rowKey = chatRowKey(groupIdHex)
        val optimisticState = optimisticChatListPreviewByGroup[rowKey]
        val row = optimisticState?.baselineRow ?: chatRowsByGroup[rowKey] ?: return null
        return RemovedChatRowSnapshot(
            row = row,
            activitySequence = optimisticState?.baselineActivitySequence ?: activitySequenceByGroup[rowKey] ?: 0uL,
            optimisticState = optimisticState?.snapshot(),
        )
    }

    /** Captures rollback state and hides the row together at admitted local-removal time. */
    private fun removeChatRowOptimistically(groupIdHex: String): RemovedChatRowSnapshot? {
        val snapshot = snapshotChatRowForRemoval(groupIdHex)
        removeChatRow(groupIdHex, optimistic = true, removal = MembershipRemoval.LocalDeletion)
        return snapshot
    }

    /**
     * Drops one row from the retained window. [removal] says whether the chat is known to be gone — a local
     * deletion — or whether, as for a native removal, the row may only have left the bounded window.
     */
    private fun removeChatRow(
        groupIdHex: String,
        optimistic: Boolean = false,
        removal: MembershipRemoval = MembershipRemoval.Ambiguous,
    ) {
        val rowKey = chatRowKey(groupIdHex)
        val removedRow = chatRowsByGroup.remove(rowKey)
        if (removedRow != null) {
            activitySequenceByGroup.remove(rowKey)
            optimisticChatListPreviewByGroup.remove(rowKey)
            if (!optimistic) finishRemovedChatRowClientState(removedRow.groupIdHex)
        }
        // Off-window rows can still belong to a mounted full-list read. Every removal changes membership
        // and schedules its replacement, even when this bounded window held no row.
        noteMaterializedGroupMembershipChanged(removal = removal, groupIdHex = groupIdHex)
        scheduleRecompute()
    }

    private fun finishRemovedChatRowClientState(groupIdHex: String) {
        val rowKey = chatRowKey(groupIdHex)
        selectedPresentationsByGroup = selectedPresentationsByGroup - rowKey
        selectedPreviewsByGroup = selectedPreviewsByGroup - rowKey
        selectedActionsByGroup = selectedActionsByGroup - rowKey
        cancelMemberSnapshotRetry(groupIdHex)
        memberFetchRetryBackoffTierByGroup.remove(groupIdHex)
        failedMemberFetches.remove(groupIdHex)
        selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
        presentationMembersByGroup = presentationMembersByGroup - groupIdHex
        localGroupNames.forget(groupIdHex)
    }

    private fun restoreRemovedChatRow(snapshot: RemovedChatRowSnapshot) {
        val rowKey = chatRowKey(snapshot.row.groupIdHex)
        if (chatRowsByGroup.containsKey(rowKey)) return
        chatRowsByGroup[rowKey] = snapshot.row
        activitySequenceByGroup[rowKey] = snapshot.activitySequence
        snapshot.optimisticState?.let { state ->
            optimisticChatListPreviewByGroup[rowKey] = state
            materializeOptimisticChatListPreview(rowKey, state)
        }
        noteMaterializedGroupMembershipChanged(removal = MembershipRemoval.None)
        scheduleRecompute()
    }

    /** Waits for admitted removals without serializing independent full native reads. */
    internal suspend fun readAfterRemovals(read: suspend () -> List<PresentedChatRowFfi>?): List<PresentedChatRowFfi>? {
        val runtime = appState.runtimeGeneration
        val sameRuntime =
            appState.localGroupDeleteCleanupMutex.withLock {
                appState.runtimeGeneration == runtime
            }
        // A deletion admitted after this barrier changes the revision captured by the caller before
        // admission, so its in-flight snapshot cannot restore pre-delete rows. The native read itself
        // must remain concurrent with other readers, including a superseding profile request.
        return if (sameRuntime) read().takeIf { appState.runtimeGeneration == runtime } else null
    }

    /** Bumps the membership version, and records what a removal means for an earlier full-list read. */
    private fun noteMaterializedGroupMembershipChanged(
        removal: MembershipRemoval,
        groupIdHex: String? = null,
    ) {
        materializedGroupsRevision += 1L
        when (removal) {
            MembershipRemoval.None -> Unit
            MembershipRemoval.Ambiguous -> ambiguousMembershipRemovalsRevision += 1L
            MembershipRemoval.LocalDeletion ->
                locallyDeletedGroupIds += requireNotNull(groupIdHex).lowercase(Locale.ROOT)
        }
    }

    /**
     * Flip the archived flag on `groupIdHex` from the chat-list surface
     * (swipe / long-press menu). Mirrors `ConversationController.setArchived`
     * but takes the id by parameter since the caller doesn't have an open
     * conversation. Standard mutation-lock + toast pattern; local group
     * record is updated immediately so the row reflows without waiting on
     * the projection echo.
     *
     * Pass `notify = false` to suppress the built-in success toast. The
     * swipe-to-archive path uses this so it can surface its own
     * actionable "Chat archived — Undo" snackbar instead of the plain
     * confirmation (see #296); the long-press menu keeps `notify = true`.
     * The failure toast always fires regardless of `notify`, since a
     * silent failure would leave the user with no signal.
     */
    suspend fun setArchived(
        groupIdHex: String,
        archived: Boolean,
        notify: Boolean = true,
    ): Boolean = setArchived(listOf(groupIdHex), archived, notify) > 0

    /**
     * Bulk archive/unarchive counterpart. Every eligible row receives its
     * presentation-only intent before the first engine call starts, so the
     * whole selection leaves the current folder in one frame. The authoritative
     * chat/group maps remain untouched until Marmot confirms each mutation.
     */
    suspend fun setArchived(
        groupIds: Collection<String>,
        archived: Boolean,
        notify: Boolean = true,
    ): Int {
        val account = accountRef ?: return 0
        return archiveActions.setArchived(account, bindEpoch, groupIds, archived, notify)
    }

    /**
     * Resolves rows beyond the retained window before installing this presentation's archive intents.
     * Admission follows the view owner; already-started native commits settle against its session fence.
     */
    internal suspend fun setArchivedForOwner(
        groupIds: Collection<String>,
        archived: Boolean,
        notify: Boolean,
        owner: ChatListActionOwner,
    ): Int {
        if (!chatListActionIsCurrent(owner)) return 0
        val rows =
            groupIds.distinctBy(String::lowercase).mapNotNull { id ->
                chatRowsByGroup[chatRowKey(id)] ?: resolveChatListActionRow(id, owner)
                    ?.takeIf {
                        if (archived) it.actions.canArchive else it.actions.canRestore
                    }?.row
            }
        return if (chatListActionIsCurrent(owner)) {
            val resolved =
                ResolvedArchiveRows(
                    rows,
                    isCurrent = { chatListActionIsCurrent(owner) },
                    completionIsCurrent = { chatListActionSessionIsCurrent(owner) },
                )
            archiveActions.setArchivedRows(owner.accountRef, owner.bindEpoch, resolved, archived, notify)
        } else {
            0
        }
    }

    /** Admits only the current intent under the group lock; native work already started keeps its completion path. */
    private suspend fun applyArchiveMutation(
        account: String,
        groupIdHex: String,
        archived: Boolean,
        intent: OptimisticArchiveIntent,
    ): Boolean =
        appState.withGroupCommitLock(account, groupIdHex) {
            currentCoroutineContext().ensureActive()
            if (!archiveActions.ownsIntent(groupIdHex, intent)) return@withGroupCommitLock false
            val updated = groupArchivedUpdater(account, groupIdHex, archived)
            if (currentCoroutineContext().isActive && archiveActions.ownsCompletion(groupIdHex, intent)) {
                applyLocalGroupUpdate(updated)
                true
            } else {
                false
            }
        }

    /**
     * Begin a single-row presentation-only archive intent on behalf of a
     * conversation surface (top bar or Group Details), so the chat-list row
     * moves in the same frame the surface acknowledges the action. Returns null
     * when the row is absent or already presents [archived]; the caller must
     * finish the returned intent once its engine commit settles either way.
     */
    internal fun beginConversationArchiveIntent(
        groupIdHex: String,
        archived: Boolean,
    ): OptimisticArchiveIntent? = archiveActions.begin(listOf(groupIdHex), archived, bindEpoch).singleOrNull()?.second

    /** Retire a conversation-surface archive intent; stale or rebound intents are ignored. */
    internal fun finishConversationArchiveIntent(
        groupIdHex: String,
        intent: OptimisticArchiveIntent,
    ) = archiveActions.finish(groupIdHex, intent)

    /**
     * Leave `groupIdHex` from the chat-list long-press menu. Mirrors the
     * conversation-screen guard: a sole admin in a multi-member group is
     * blocked (the group would lose its only admin); a sole admin in a
     * single-member group resets local protocol state without publishing a
     * leave that has no remaining participant. Both paths share `GroupProjector`'s
     * pure predicates so the safety levels stay aligned — see
     * [ConversationController.leaveGroup] for the canonical reference.
     *
     * Re-read the native durable row and full authoritative group details at the
     * commit boundary. A failed or inconsistent roster preserves local history.
     * Last-member removal uses native forget and the existing Android cleanup journal.
     */
    suspend fun leaveGroup(
        groupIdHex: String,
        deleteAfterLeave: Boolean = false,
        onStage: (ChatDepartureStage) -> Unit = {},
    ): Boolean {
        val account = accountRef ?: return false
        val epoch = bindEpoch
        val runtime = appState.runtimeGeneration
        val attempt =
            ChatListDepartureAttempt(
                account,
                groupIdHex,
                boundAccountIdHex(),
                { chatListDepartureIsCurrent(account, epoch, runtime) },
                onStage,
            )
        if (!attempt.isCurrent()) return false
        onStage(ChatDepartureStage.CHECKING)
        return runCatchingCancellable {
            val departed = departCapturedChat(attempt, deleteAfterLeave)
            if (!departed || !attempt.isCurrent()) return@runCatchingCancellable false
            attempt.soleMemberResult?.let { return@runCatchingCancellable it }
            if (attempt.cleanupOnly && deleteAfterLeave) {
                completeDepartedChatCleanup(groupIdHex, onStage)
            } else {
                finishChatListDeparture(attempt, deleteAfterLeave)
            }
        }.onFailure {
            if (attempt.isCurrent()) {
                appState.presentFailure(
                    if (attempt.demoted) {
                        R.string.toast_demoted_but_couldnt_leave
                    } else {
                        R.string.toast_couldnt_leave_chat
                    },
                    if (attempt.demoted) "CHAT_LEAVE_AFTER_DEMOTE" else "CHAT_LEAVE",
                    it,
                )
            }
        }.getOrDefault(false)
    }

    /** Resolve membership under the native commit lock before selecting a departure branch. */
    private suspend fun departCapturedChat(
        attempt: ChatListDepartureAttempt,
        deleteAfterLeave: Boolean,
    ): Boolean =
        appState.withGroupCommitLock(attempt.account, attempt.groupId) {
            if (!attempt.isCurrent()) return@withGroupCommitLock false
            val row = appState.marmotIo { chatListRow(attempt.account, attempt.groupId) }
            if (!attempt.isCurrent()) return@withGroupCommitLock false
            when {
                row == null -> departWithoutChatRow(attempt, deleteAfterLeave)
                row.leaveRequestPending -> {
                    foldChatRow(row)
                    appState.presentTransient(R.string.toast_leave_not_confirmed_history_kept)
                    false
                }
                row.selfMembership.isNonMember() -> {
                    foldChatRow(row)
                    attempt.cleanupOnly = true
                    attempt.onStage(ChatDepartureStage.LEFT)
                    true
                }
                else -> departJoinedChat(attempt, deleteAfterLeave)
            }
        }

    /** A wiped app projection does not prove that the native MLS group has been left. */
    private suspend fun departWithoutChatRow(
        attempt: ChatListDepartureAttempt,
        deleteAfterLeave: Boolean,
    ): Boolean {
        val details = chatDepartureGroupDetails(attempt.account, attempt.groupId)
        if (!attempt.isCurrent()) return false
        return if (details == null) {
            attempt.cleanupOnly = true
            true
        } else {
            departJoinedChat(attempt, deleteAfterLeave, details)
        }
    }

    /** Only an exact native UnknownGroup proves absence; transport and hydration failures preserve history. */
    private suspend fun chatDepartureGroupDetails(
        account: String,
        groupIdHex: String,
    ): GroupDetailsFfi? =
        try {
            appState.marmotIo { groupDetails(account, groupIdHex) }
        } catch (failure: MarmotKitException.UnknownGroup) {
            if (!failure.groupIdHex.equals(groupIdHex, ignoreCase = true)) throw failure
            null
        }

    /** Read roles at the commit boundary; Android never invents a roster after a failed native read. */
    private suspend fun departJoinedChat(
        attempt: ChatListDepartureAttempt,
        deleteAfterLeave: Boolean,
        preparedDetails: GroupDetailsFfi? = null,
    ): Boolean {
        val details = preparedDetails ?: appState.marmotIo { groupDetails(attempt.account, attempt.groupId) }
        if (!attempt.isCurrent()) return false
        val resolution = resolveAuthoritativeGroupRoster(details, attempt.activeAccountId)
        check(details.group.groupIdHex.equals(attempt.groupId, ignoreCase = true) && resolution.invariant == null) {
            "Authoritative group roster unavailable"
        }
        return departResolvedChat(attempt, deleteAfterLeave, resolution)
    }

    /**
     * Leaves a chat whose authoritative roster [resolution] has been read. A durable leave request already pending
     * stays provisional, so it keeps the history and reports that the leave is not confirmed yet.
     */
    private suspend fun departResolvedChat(
        attempt: ChatListDepartureAttempt,
        deleteAfterLeave: Boolean,
        resolution: GroupRosterResolution,
    ): Boolean {
        val group = resolution.applied.group
        if (group.leaveRequestPending) {
            appState.presentTransient(R.string.toast_leave_not_confirmed_history_kept)
            return false
        }
        attempt.members = resolution.applied.members
        val count = resolution.uniqueMemberCount
        val sole = GroupProjector.shouldDissolveAsSoleMember(attempt.members, attempt.activeAccountId)
        return if (chatListDepartureAllowed(group, attempt.activeAccountId, count, sole)) {
            when {
                sole -> removeSoleMemberChat(attempt, deleteAfterLeave)
                GroupProjector.requiresSelfDemoteBeforeLeave(group, attempt.activeAccountId, count) ->
                    demoteAndLeaveChat(attempt)
                else -> appState.marmotIo { leaveGroup(attempt.account, attempt.groupId) }
            }
            true
        } else {
            false
        }
    }

    /**
     * Steps down as admin, asking first if MDK holds that back, then leaves, even if the chat list goes away. The
     * question stays cancellable, so a departure cancelled while the user decides changes nothing; once the step-down
     * starts, it and the leave after it run to completion together.
     */
    private suspend fun demoteAndLeaveChat(attempt: ChatListDepartureAttempt) {
        appState.runGroupChange(
            attempt.account,
            attempt.groupId,
            actionLabel = R.string.group_action_step_down_and_leave,
        ) {
            withContext(NonCancellable) {
                val demotion =
                    appState.marmotIo(MarmotTraceSection.SELF_DEMOTE_ADMIN) {
                        selfDemoteAdminDetailed(attempt.account, attempt.groupId)
                    }
                attempt.demoted = true
                if (attempt.isCurrent()) {
                    attempt.onStage(ChatDepartureStage.ADMIN_DEMOTED)
                    appState.applyLocalGroupUpdate(demotion.details.group)
                }
                appState.marmotIo { leaveGroup(attempt.account, attempt.groupId) }
            }
        }
    }

    private fun chatListDepartureAllowed(
        group: AppGroupRecordFfi,
        activeAccountIdHex: String?,
        memberCount: Int,
        soleMember: Boolean,
    ): Boolean {
        val allowed = soleMember || GroupProjector.canLeaveGroup(group, activeAccountIdHex, memberCount)
        if (!allowed) {
            appState.present(R.string.toast_make_another_admin_before_leaving, R.string.toast_group_needs_admin)
        }
        return allowed
    }

    private suspend fun removeSoleMemberChat(
        attempt: ChatListDepartureAttempt,
        deleteAfterLeave: Boolean,
    ) {
        if (!deleteAfterLeave) {
            appState.deleteGroupLocalWithClientCleanup(attempt.account, attempt.groupId)
            return
        }
        // Forget retires native MLS state. The existing durable platform journal makes trailing cleanup recoverable.
        var deferred = false
        val deleted =
            deleteGroupLocalFromChatList(
                attempt.groupId,
                notify = false,
                forgetProtocol = true,
                observer =
                    LocalChatDeleteObserver(
                        onNativeCommitted = { attempt.onStage(ChatDepartureStage.CLEANUP_PENDING) },
                        onCleanupDeferred = { deferred = true },
                    ),
            )
        attempt.soleMemberResult = deleted && !deferred
        if (attempt.soleMemberResult == true) attempt.onStage(ChatDepartureStage.COMPLETED)
    }

    /** Reject stale presentation and destructive session teardown before another departure step. */
    private fun chatListDepartureIsCurrent(
        account: String,
        epoch: Long,
        runtime: Int,
    ): Boolean = accountRef == account && isActiveBindEpoch(epoch) && departureSessionAvailable(account, runtime)

    private fun departureSessionAvailable(
        account: String,
        runtime: Int,
    ): Boolean {
        val ownsAccount = appState.activeAccountRef == account && appState.runtimeGeneration == runtime
        return ownsAccount && !appState.signOutInProgress && !appState.wipeInProgress
    }

    /** Confirm native departure before admitting cleanup; uncertainty always retains history. */
    private suspend fun finishChatListDeparture(
        attempt: ChatListDepartureAttempt,
        deleteAfterLeave: Boolean,
    ): Boolean {
        val confirmed =
            !deleteAfterLeave || confirmChatListDeparture(attempt.account, attempt.groupId, attempt.isCurrent)
        if (!confirmed) return false
        attempt.onStage(ChatDepartureStage.LEFT)
        recordChatListDeparture(attempt.account, attempt.groupId, attempt.activeAccountId, attempt.members)
        return if (deleteAfterLeave) {
            completeDepartedChatCleanup(attempt.groupId, attempt.onStage)
        } else {
            appState.presentTransient(R.string.toast_left_chat)
            attempt.onStage(ChatDepartureStage.COMPLETED)
            true
        }
    }

    /** Native commit and trailing Android cleanup are distinct; deferred cleanup is not full completion. */
    private suspend fun completeDepartedChatCleanup(
        groupIdHex: String,
        onStage: (ChatDepartureStage) -> Unit,
    ): Boolean {
        onStage(ChatDepartureStage.CLEANUP_PENDING)
        var deferred = false
        val deleted =
            deleteGroupLocalFromChatList(
                groupIdHex,
                wording = LocalRemovalWording.Delete.copy(failureTitle = R.string.toast_left_chat_delete_failed),
                observer = LocalChatDeleteObserver(onCleanupDeferred = { deferred = true }),
            )
        return (deleted && !deferred).also { if (it) onStage(ChatDepartureStage.COMPLETED) }
    }

    /** A durable leave request remains provisional even after MDK projects LEFT. */
    private suspend fun confirmChatListDeparture(
        account: String,
        groupIdHex: String,
        isCurrent: () -> Boolean,
    ): Boolean {
        // An evicted MLS session can reject roster reads; use the durable native row.
        val departureRow =
            runCatchingCancellable {
                appState.marmotIo { chatListRow(account, groupIdHex) }
            }.getOrNull()
        if (!isCurrent()) return false
        departureRow?.let { foldChatRow(it) }
        val confirmed =
            departureRow != null && !departureRow.leaveRequestPending && departureRow.selfMembership.isNonMember()
        if (!confirmed) appState.presentTransient(R.string.toast_leave_not_confirmed_history_kept)
        return confirmed
    }

    /** Retire presentation snapshots only after the native departure settles on the current bind. */
    private fun recordChatListDeparture(
        account: String,
        groupIdHex: String,
        activeAccountIdHex: String?,
        members: List<AppGroupMemberRecordFfi>,
    ) {
        appState.removeComposerExpansionForGroup(account, groupIdHex)
        // A removed device's account stays in the group through its other device, so its roster keeps the account.
        val accountStaysOnRoster =
            chatRowsByGroup[chatRowKey(groupIdHex)]?.selfMembership == SelfMembershipFfi.DEVICE_REMOVED
        if (!accountStaysOnRoster) appState.removeActiveAccountFromGroupMemberSnapshot(account, groupIdHex)
        if (activeAccountIdHex != null) {
            val departedMembers =
                if (accountStaysOnRoster) {
                    members
                } else {
                    GroupProjector.membersWithoutActiveAccount(members, activeAccountIdHex)
                }
            memberCacheByGroup = memberCacheByGroup + (groupIdHex to departedMembers)
            removedGroupIds = removedGroupIds + groupIdHex
            recompute()
        }
    }

    /** Explicit single-chat departure; unconfirmed leave never admits local deletion. */
    suspend fun leaveAndDeleteFromChatList(
        groupIdHex: String,
        onStage: (ChatDepartureStage) -> Unit = {},
    ): Boolean =
        leaveGroup(
            groupIdHex = groupIdHex,
            deleteAfterLeave = true,
            onStage = onStage,
        )

    /**
     * Local-only chat-list wipe: hide the row optimistically, run client cleanup
     * + [deleteGroupLocal], and never touch MLS membership. Used by bulk Delete
     * local (#1169) so still-member groups stay joined.
     */
    internal suspend fun deleteGroupLocalFromChatList(
        groupIdHex: String,
        notify: Boolean = true,
        wording: LocalRemovalWording = LocalRemovalWording.Delete,
        observer: LocalChatDeleteObserver = LocalChatDeleteObserver(),
        forgetProtocol: Boolean = false,
        requireNativeEligibility: Boolean = false,
    ): Boolean =
        accountRef?.let { account ->
            val epoch = bindEpoch
            val runtime = appState.runtimeGeneration
            val isCurrent = {
                chatListDepartureIsCurrent(account, epoch, runtime) && appState.retainedAccountReactivationRef == null
            }
            if (!isCurrent()) return@let false
            var removedSnapshot: RemovedChatRowSnapshot? = null
            var nativeCommitted = false
            val wipe =
                runCatching {
                    requireLocalDeleteEligibility(account, groupIdHex, requireNativeEligibility, isCurrent)
                    appState.deleteChatGroupLocalWithRecovery(
                        LocalGroupRemovalRequest(account, groupIdHex, forgetProtocol),
                        isCurrent,
                        observer.readinessBudget,
                        onAdmitted = { removedSnapshot = removeChatRowOptimistically(groupIdHex) },
                    ) {
                        nativeCommitted = true
                        if (isCurrent()) observer.onNativeCommitted()
                    }
                }
            val failure = wipe.exceptionOrNull()
            if (failure != null) {
                appState.schedulePendingLocalGroupDeleteCleanup(retryTransport = true)
                if (isCurrent()) {
                    if (nativeCommitted) {
                        removeChatRow(groupIdHex, removal = MembershipRemoval.LocalDeletion)
                        finishRemovedChatRowClientState(groupIdHex)
                    } else {
                        removedSnapshot?.let(::restoreRemovedChatRow)
                    }
                }
                if (failure is CancellationException) throw failure
                if (isCurrent() && observer.presentsFailure) {
                    // The retry keeps this removal's wording, so a retried abandonment never reads as a delete.
                    appState.presentLocalDeleteFailure(
                        wording.failureTitle,
                        failure,
                        detail = AppText.Resource(wording.failureDetail),
                        notice =
                            appState.localDeleteRetryNotice(account, groupIdHex, isCurrent) {
                                deleteGroupLocalFromChatList(
                                    groupIdHex,
                                    wording = wording,
                                    forgetProtocol = forgetProtocol,
                                    requireNativeEligibility = requireNativeEligibility,
                                )
                            },
                    )
                }
                if (isCurrent()) observer.onFailure(failure)
                false
            } else if (isCurrent()) {
                val success = wording.success.takeIf { notify }
                completeChatLocalRemoval(account, groupIdHex, wipe.getOrDefault(false), success, observer)
                true
            } else {
                false
            }
        } ?: false

    /** Recheck the captured native capability before the row can be hidden or local deletion admitted. */
    private suspend fun requireLocalDeleteEligibility(
        account: String,
        groupIdHex: String,
        required: Boolean,
        isCurrent: () -> Boolean,
    ) {
        if (!required) return
        val row =
            kotlinx.coroutines.withTimeoutOrNull(CHAT_LIST_BATCH_STEP_TIMEOUT_MS) {
                liveSubscriptions.presentedRowByGroup?.invoke(account, groupIdHex)
            }
        if (!isCurrent()) throw CancellationException("Account replaced")
        check(
            row != null &&
                row.row.groupIdHex.equals(groupIdHex, ignoreCase = true) &&
                row.actions.canDeleteLocal,
        ) { "Native local deletion is unavailable" }
    }

    /** Settles a committed removal, confirming it with [success] only once its Android cleanup also finished. */
    private fun completeChatLocalRemoval(
        account: String,
        groupIdHex: String,
        cleanupComplete: Boolean,
        @StringRes success: Int?,
        observer: LocalChatDeleteObserver,
    ) {
        appState.dismissLocalDeleteFailure(account, groupIdHex)
        removeChatRow(groupIdHex, removal = MembershipRemoval.LocalDeletion)
        finishRemovedChatRowClientState(groupIdHex)
        if (!cleanupComplete) observer.onCleanupDeferred()
        if (success != null && cleanupComplete) appState.presentTransient(success)
    }

    private fun requireChatListDepartureCurrent(
        account: String,
        epoch: Long,
        runtime: Int,
    ) {
        if (!chatListDepartureIsCurrent(account, epoch, runtime)) throw CancellationException("Account replaced")
    }

    /** A failed or inconsistent authoritative read is an error, never an inferred normal-member path. */
    internal suspend fun prepareChatListDeparture(groupIdHex: String): List<AppGroupMemberRecordFfi> {
        val account = accountRef ?: throw CancellationException("Account no longer active")
        val epoch = bindEpoch
        val runtime = appState.runtimeGeneration
        val activeId = boundAccountIdHex()
        val row = appState.marmotIo { chatListRow(account, groupIdHex) }
        requireChatListDepartureCurrent(account, epoch, runtime)
        check(row?.leaveRequestPending != true) { "Departure is awaiting native confirmation" }
        if (row?.selfMembership?.isNonMember() == true) return emptyList()
        val details = chatDepartureGroupDetails(account, groupIdHex)
        requireChatListDepartureCurrent(account, epoch, runtime)
        return if (details == null) {
            check(row == null) { "Native group changed during departure preparation" }
            emptyList()
        } else {
            val resolution = resolveAuthoritativeGroupRoster(details, activeId)
            check(details.group.groupIdHex.equals(groupIdHex, ignoreCase = true) && resolution.invariant == null) {
                "Authoritative group roster unavailable"
            }
            val applied = resolution.applied
            val handoverRequired =
                !GroupProjector.shouldDissolveAsSoleMember(applied.members, activeId) &&
                    GroupProjector.isSoleAdminWithOtherMembers(applied.group, activeId, resolution.uniqueMemberCount)
            if (handoverRequired) {
                applied.members
                    .filter { GroupProjector.canTransferAdminTo(applied.group, it, activeId) }
                    .also { check(it.isNotEmpty()) { "No eligible administrator successor" } }
            } else {
                emptyList()
            }
        }
    }

    /** Grant only after revalidation; the ordinary departure path then reconciles roles, Leave and cleanup. */
    suspend fun transferAdminThenDeleteFromChatList(
        groupIdHex: String,
        newAdmin: AppGroupMemberRecordFfi,
        onStage: (ChatDepartureStage) -> Unit = {},
    ): Boolean {
        val account = accountRef ?: return false
        val epoch = bindEpoch
        val runtime = appState.runtimeGeneration
        val isCurrent = { chatListDepartureIsCurrent(account, epoch, runtime) }
        val grant =
            runCatchingCancellable {
                appState.withGroupCommitLock(account, groupIdHex) {
                    if (!isCurrent()) return@withGroupCommitLock false
                    val candidates = prepareChatListDeparture(groupIdHex)
                    if (!isCurrent()) return@withGroupCommitLock false
                    // A remote handover may already have removed the need to grant; never replay it blindly.
                    if (candidates.isNotEmpty()) {
                        check(candidates.any { it.memberIdHex.equals(newAdmin.memberIdHex, ignoreCase = true) }) {
                            "Administrator successor changed"
                        }
                        val promoted =
                            appState.runGroupChange(
                                account,
                                groupIdHex,
                                actionLabel = R.string.group_action_transfer_and_leave,
                                affectedMemberIds = listOf(newAdmin.memberIdHex),
                            ) {
                                appState.marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                                    promoteAdminDetailed(account, groupIdHex, newAdmin.memberIdHex)
                                }
                            }
                        if (isCurrent()) {
                            onStage(ChatDepartureStage.ADMIN_GRANTED)
                            appState.applyLocalGroupUpdate(promoted.details.group)
                        }
                    }
                    true
                }
            }.onFailure {
                if (isCurrent()) {
                    appState.presentFailure(R.string.toast_couldnt_update_admin, "CHAT_DELETE_ADMIN_TRANSFER", it)
                }
            }.getOrDefault(false)
        return grant &&
            isCurrent() &&
            leaveAndDeleteFromChatList(groupIdHex) { stage ->
                if (stage != ChatDepartureStage.CHECKING) onStage(stage)
            }
    }

    /**
     * Flip the chat-list row for [groupIdHex] to its left state after a leave
     * initiated from another surface (the conversation Details screen), where
     * the engine pushes no chat-list update for a self-leave so the row would
     * otherwise stay active until the next bind (issue #767).
     *
     * Mirrors the row-state updates the chat-list [leaveGroup] makes itself:
     * fold the group into [removedGroupIds] (the authoritative removal marker
     * [ChatListItem.removedFromGroup] honours regardless of the cached roster)
     * and, when a roster is already cached, drop self from it so the snapshot
     * agrees, then [recompute] so the row re-projects immediately. No-op when
     * no account is bound or the group isn't on this controller's chat list.
     */
    fun markGroupLeft(groupIdHex: String) {
        if (accountRef == null) return
        if (chatRows.none { it.groupIdHex == groupIdHex }) return
        val activeAccountIdHex = appState.activeAccount?.accountIdHex
        memberCacheByGroup[groupIdHex]?.let { cached ->
            if (activeAccountIdHex != null) {
                memberCacheByGroup =
                    memberCacheByGroup +
                    (groupIdHex to GroupProjector.membersWithoutActiveAccount(cached, activeAccountIdHex))
            }
        }
        removedGroupIds = removedGroupIds + groupIdHex
        recompute()
    }

    internal var chatListBatchInProgress by mutableStateOf(false)
        private set

    /**
     * The immutable captured ids survive filter removal; no source projection is cached here.
     * Admission/finally own one batch; both pre-read and post-read owner fences must stop the loop.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements")
    internal suspend fun runChatListBatch(
        ids: Collection<String>,
        command: ChatListBatchCommand,
        owner: ChatListActionOwner,
        folderId: String? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ChatListBatchResult? {
        if (chatListBatchInProgress || !chatListActionIsCurrent(owner)) return null
        chatListBatchInProgress = true
        val targets = ids.map { it.lowercase(java.util.Locale.ROOT) }.distinct()
        val outcomes = targets.associateWith { ChatListBatchOutcome.NOT_STARTED }.toMutableMap()
        val attemptedIds = mutableSetOf<String>()
        try {
            if (command == ChatListBatchCommand.ARCHIVE || command == ChatListBatchCommand.RESTORE) {
                return runChatListArchiveBatch(targets, command, owner, onProgress)
            }
            for ((index, id) in targets.withIndex()) {
                if (!chatListActionIsCurrent(owner)) break
                onProgress(index, targets.size)
                val result =
                    runCatchingCancellable {
                        kotlinx.coroutines.withTimeoutOrNull(CHAT_LIST_BATCH_STEP_TIMEOUT_MS) {
                            val row = liveSubscriptions.presentedRowByGroup?.invoke(owner.accountRef, id)
                            if (!chatListActionIsCurrent(owner)) return@withTimeoutOrNull null
                            if (row == null) return@withTimeoutOrNull ChatListBatchOutcome.SKIPPED
                            check(row.row.groupIdHex.equals(id, ignoreCase = true))
                            if (command == ChatListBatchCommand.EXCLUDE_FOLDER) {
                                return@withTimeoutOrNull excludeChatListBatchTarget(id, folderId, owner)
                            }
                            chatListBatchDisposition(row, command) ?: run {
                                val item = projectPresentedRow(row, appState.activeAccount?.accountIdHex)
                                attemptedIds += id
                                outcomes[id] = ChatListBatchOutcome.UNCONFIRMED
                                val applied = applyChatListBatchCommand(item, command, owner)
                                if (!chatListActionIsCurrent(owner)) return@withTimeoutOrNull null
                                val pinCommand =
                                    command == ChatListBatchCommand.PIN || command == ChatListBatchCommand.UNPIN
                                if (!applied && pinCommand && !reconcileNativePinState(owner)) {
                                    return@withTimeoutOrNull ChatListBatchOutcome.UNCONFIRMED
                                }
                                val committed = liveSubscriptions.presentedRowByGroup?.invoke(owner.accountRef, id)
                                if (committed == null) {
                                    ChatListBatchOutcome.UNCONFIRMED
                                } else {
                                    check(committed.row.groupIdHex.equals(id, ignoreCase = true))
                                    val disposition = chatListBatchDisposition(committed, command)
                                    if (disposition == ChatListBatchOutcome.SATISFIED) {
                                        if (chatListActionIsCurrent(owner)) applyChatListActionRow(committed.row)
                                        ChatListBatchOutcome.CHANGED
                                    } else {
                                        ChatListBatchOutcome.FAILED
                                    }
                                }
                            }
                        } ?: outcomes.getValue(id).takeIf { it == ChatListBatchOutcome.UNCONFIRMED }
                            ?: ChatListBatchOutcome.FAILED
                    }.getOrElse {
                        outcomes.getValue(id).takeIf { it == ChatListBatchOutcome.UNCONFIRMED }
                            ?: ChatListBatchOutcome.FAILED
                    }
                if (!chatListActionIsCurrent(owner)) break
                outcomes[id] = result
                onProgress(index + 1, targets.size)
            }
            return ChatListBatchResult(outcomes.toMap(), attemptedIds.toSet())
        } finally {
            chatListBatchInProgress = false
        }
    }

    /**
     * Shares the existing whole-selection overlay owner rather than introducing a second archive flow.
     * Classify the complete scope before its shared overlay; fence ownership before and after native reads.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private suspend fun runChatListArchiveBatch(
        targets: List<String>,
        command: ChatListBatchCommand,
        owner: ChatListActionOwner,
        onProgress: (Int, Int) -> Unit,
    ): ChatListBatchResult {
        val outcomes = targets.associateWith { ChatListBatchOutcome.NOT_STARTED }.toMutableMap()
        val attemptedIds = mutableSetOf<String>()
        val timedOutIds = mutableSetOf<String>()
        val rows = mutableListOf<ChatListRowFfi>()
        for (id in targets) {
            if (!chatListActionIsCurrent(owner)) break
            val read =
                runCatchingCancellable {
                    val loaded =
                        kotlinx.coroutines.withTimeoutOrNull(CHAT_LIST_BATCH_STEP_TIMEOUT_MS) {
                            listOf(liveSubscriptions.presentedRowByGroup?.invoke(owner.accountRef, id))
                        } ?: error("Native chat lookup timed out")
                    loaded.single()?.also { row ->
                        check(row.row.groupIdHex.equals(id, ignoreCase = true))
                    }
                }
            if (!chatListActionIsCurrent(owner)) break
            val row = read.getOrNull()
            if (read.isFailure) {
                outcomes[id] = ChatListBatchOutcome.FAILED
            } else if (row == null) {
                outcomes[id] = ChatListBatchOutcome.SKIPPED
            } else {
                val disposition = chatListBatchDisposition(row, command)
                if (disposition == null) rows += row.row else outcomes[id] = disposition
            }
        }
        if (chatListActionIsCurrent(owner)) {
            val commitContext = currentCoroutineContext()
            val commitIsCurrent = { commitContext.isActive && chatListActionIsCurrent(owner) }
            archiveActions.setArchivedRows(
                owner.accountRef,
                owner.bindEpoch,
                ResolvedArchiveRows(rows, commitIsCurrent),
                command == ChatListBatchCommand.ARCHIVE,
                false,
                onAttempted = { id ->
                    val key = id.lowercase(java.util.Locale.ROOT)
                    attemptedIds += key
                    outcomes[key] = ChatListBatchOutcome.UNCONFIRMED
                },
                onSettled = { id, changed ->
                    if (commitIsCurrent()) {
                        outcomes[id.lowercase(java.util.Locale.ROOT)] =
                            if (changed) ChatListBatchOutcome.CHANGED else ChatListBatchOutcome.UNCONFIRMED
                        onProgress(outcomes.values.count { it != ChatListBatchOutcome.NOT_STARTED }, targets.size)
                    }
                },
                stepTimeoutMillis = CHAT_LIST_BATCH_STEP_TIMEOUT_MS,
                onTimedOut = { id -> timedOutIds += id.lowercase(java.util.Locale.ROOT) },
                notifyFailures = false,
            )
        }
        for (id in targets) {
            if (!chatListActionIsCurrent(owner)) break
            // A timed-out native attempt stays uncertain until an explicit read-only Check results.
            if (id in attemptedIds && id !in timedOutIds) {
                val confirmed = reconcileChatListBatchTarget(id, command, owner)
                outcomes[id] =
                    if (confirmed == ChatListBatchOutcome.SATISFIED) ChatListBatchOutcome.CHANGED else confirmed
            }
        }
        return ChatListBatchResult(outcomes.toMap(), attemptedIds.toSet())
    }

    /** Read-only recovery never replays an operation whose completion is unknown. */
    internal suspend fun reconcileChatListBatch(
        previous: ChatListBatchResult,
        command: ChatListBatchCommand,
        owner: ChatListActionOwner,
    ): ChatListBatchResult? {
        if (chatListBatchInProgress || !chatListActionIsCurrent(owner)) return null
        chatListBatchInProgress = true
        val targets = previous.outcomes.filterValues { it == ChatListBatchOutcome.UNCONFIRMED }.keys
        val outcomes = targets.associateWith { ChatListBatchOutcome.UNCONFIRMED }.toMutableMap()
        try {
            for (id in targets) {
                if (!chatListActionIsCurrent(owner)) break
                val confirmed = reconcileChatListBatchTarget(id, command, owner)
                outcomes[id] =
                    if (confirmed == ChatListBatchOutcome.SATISFIED && id in previous.attemptedIds) {
                        ChatListBatchOutcome.CHANGED
                    } else {
                        confirmed
                    }
            }
            return ChatListBatchResult(outcomes.toMap(), previous.attemptedIds.intersect(targets))
        } finally {
            chatListBatchInProgress = false
        }
    }

    /** Read the captured target again without replaying its native mutation. */
    private suspend fun reconcileChatListBatchTarget(
        id: String,
        command: ChatListBatchCommand,
        owner: ChatListActionOwner,
    ): ChatListBatchOutcome =
        runCatchingCancellable {
            kotlinx.coroutines.withTimeoutOrNull(CHAT_LIST_BATCH_STEP_TIMEOUT_MS) {
                if (
                    (
                        command == ChatListBatchCommand.PIN ||
                            command == ChatListBatchCommand.UNPIN
                    ) &&
                    !reconcileNativePinState(owner)
                ) {
                    return@withTimeoutOrNull ChatListBatchOutcome.UNCONFIRMED
                }
                val row = liveSubscriptions.presentedRowByGroup?.invoke(owner.accountRef, id)
                if (!chatListActionIsCurrent(owner)) return@withTimeoutOrNull ChatListBatchOutcome.UNCONFIRMED
                if (row == null) return@withTimeoutOrNull ChatListBatchOutcome.UNCONFIRMED
                check(row.row.groupIdHex.equals(id, ignoreCase = true))
                val disposition = chatListBatchDisposition(row, command)
                if (disposition == ChatListBatchOutcome.SATISFIED) applyChatListActionRow(row.row)
                disposition ?: ChatListBatchOutcome.FAILED
            } ?: ChatListBatchOutcome.UNCONFIRMED
        }.getOrDefault(ChatListBatchOutcome.UNCONFIRMED)

    /** A pin may commit before native row hydration fails; recover all neighbouring positions too. */
    @Suppress("ReturnCount") // Reject stale or unavailable complete pin state before applying a projection.
    private suspend fun reconcileNativePinState(owner: ChatListActionOwner): Boolean {
        val order = authoritativePinOrder(owner) ?: return false
        if (!chatListActionIsCurrent(owner)) return false
        applyPinState(ChatPinStateFfi(order))
        return true
    }

    /** Read the current native pin universe before applying any complete-order projection. */
    @Suppress("ReturnCount") // Refuse retired or unavailable whole-list pin reads before they can alter a projection.
    private suspend fun authoritativePinOrder(owner: ChatListActionOwner): List<String>? {
        if (!chatListActionIsCurrent(owner)) return null
        val rows = liveSubscriptions.presentedChatList?.invoke(owner.accountRef, false) ?: return null
        if (!chatListActionIsCurrent(owner)) return null
        val order =
            rows
                .map { it.row }
                .filter { it.pinned }
                .sortedBy { it.pinnedPosition }
                .map { it.groupIdHex }
        check(order.distinctBy { it.lowercase(java.util.Locale.ROOT) }.size == order.size)
        return order
    }

    /** Apply direct state commands after archive and folder requests have selected their dedicated owners. */
    private suspend fun applyChatListBatchCommand(
        item: ChatListItem,
        command: ChatListBatchCommand,
        owner: ChatListActionOwner,
    ): Boolean =
        when (command) {
            ChatListBatchCommand.READ -> markAllRead(item, owner)
            ChatListBatchCommand.UNREAD -> markUnread(item, owner)
            ChatListBatchCommand.PIN -> setPinned(item, true, owner)
            ChatListBatchCommand.UNPIN -> setPinned(item, false, owner)
            ChatListBatchCommand.MUTE, ChatListBatchCommand.UNMUTE ->
                appState.setConversationMutedAwaited(
                    owner.accountRef,
                    item.group.groupIdHex,
                    command == ChatListBatchCommand.MUTE,
                ) { chatListActionIsCurrent(owner) }
            else -> error("Command $command must use its dedicated batch owner")
        }

    /** Exclude the captured ID only from the still-current captured folder preference owner. */
    @Suppress("ReturnCount") // Guard missing folders and satisfied exclusions before the existing preference mutation.
    private fun excludeChatListBatchTarget(
        id: String,
        folderId: String?,
        owner: ChatListActionOwner,
    ): ChatListBatchOutcome {
        val preferences = appState.chatFolderPreferences
        if (folderId == null || preferences.foldersFor(owner.accountRef).none { it.id == folderId }) {
            return ChatListBatchOutcome.SKIPPED
        }
        if (id in preferences.excludedChats(owner.accountRef, folderId)) return ChatListBatchOutcome.SATISFIED
        if (!chatListActionIsCurrent(owner)) return ChatListBatchOutcome.NOT_STARTED
        return if (preferences.excludeChat(owner.accountRef, folderId, id)) {
            ChatListBatchOutcome.CHANGED
        } else {
            ChatListBatchOutcome.FAILED
        }
    }

    /** Reads MDK's complete pin universe, not the retained list window, and submits one exact permutation. */
    @Suppress("ReturnCount") // Reject stale native permutations before any write.
    internal suspend fun movePinnedSelection(
        ids: Set<String>,
        delta: Int,
        owner: ChatListActionOwner,
    ): ChatListPinMoveResult {
        if (chatListBatchInProgress || !chatListActionIsCurrent(owner)) return ChatListPinMoveResult.UNAVAILABLE
        chatListBatchInProgress = true
        try {
            val order = authoritativePinOrder(owner) ?: return ChatListPinMoveResult.UNAVAILABLE
            val keys = order.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
            if (!ids.all { it.lowercase(java.util.Locale.ROOT) in keys }) return ChatListPinMoveResult.UNAVAILABLE
            val next = moveSelectedPinnedChats(order, ids, delta)
            if (next == order) return ChatListPinMoveResult.UNCHANGED
            var dispatched = false
            val state =
                runCatchingCancellable {
                    appState.marmotIo {
                        if (chatListActionIsCurrent(owner)) {
                            dispatched = true
                            setPinnedChatOrder(owner.accountRef, next)
                        } else {
                            null
                        }
                    }
                }.getOrNull()
            if (!dispatched) return ChatListPinMoveResult.UNAVAILABLE
            if (!chatListActionIsCurrent(owner)) return ChatListPinMoveResult.UNCONFIRMED
            val confirmed =
                state?.orderedGroupIds ?: runCatchingCancellable { authoritativePinOrder(owner) }.getOrNull()
            if (confirmed == null || !chatListActionIsCurrent(owner)) return ChatListPinMoveResult.UNCONFIRMED
            applyPinState(ChatPinStateFfi(confirmed))
            val confirmedKeys = confirmed.map { it.lowercase(java.util.Locale.ROOT) }
            val requestedKeys = next.map { it.lowercase(java.util.Locale.ROOT) }
            return if (confirmedKeys == requestedKeys) {
                ChatListPinMoveResult.MOVED
            } else {
                ChatListPinMoveResult.UNAVAILABLE
            }
        } finally {
            chatListBatchInProgress = false
        }
    }

    /** Pins the controller binding, active account and runtime together with the caller's view lifetime. */
    internal fun captureChatListActionOwner(presentationIsCurrent: () -> Boolean = { true }): ChatListActionOwner? =
        accountRef?.let {
            ChatListActionOwner(
                accountRef = it,
                bindEpoch = bindEpoch,
                runtimeGeneration = appState.runtimeGeneration,
                activeAccountRef = appState.activeAccountRef,
                presentationIsCurrent = presentationIsCurrent,
            )
        }

    /** Requires both the captured session and its presentation before admitting queued row actions. */
    internal fun chatListActionIsCurrent(owner: ChatListActionOwner): Boolean {
        val ownsSession = chatListActionSessionIsCurrent(owner)
        return ownsSession && owner.presentationIsCurrent()
    }

    /** Native work already admitted belongs to its account/binding, even after a folder or search changes. */
    internal fun chatListActionSessionIsCurrent(owner: ChatListActionOwner): Boolean {
        val ownsBoundReference =
            boundAccountRef == owner.accountRef || (owner.activeAccountRef == null && boundAccountRef == null)
        val ownsBinding =
            accountRef == owner.accountRef && ownsBoundReference && isActiveBindEpoch(owner.bindEpoch)
        val ownsSession =
            appState.runtimeGeneration == owner.runtimeGeneration &&
                appState.activeAccountRef == owner.activeAccountRef
        val selectedOrLegacy = owner.activeAccountRef == null || owner.activeAccountRef == owner.accountRef
        val available = !appState.signOutInProgress && !appState.wipeInProgress
        val eligibleOwner = ownsBinding && ownsSession
        val eligibleSession = selectedOrLegacy && available
        return eligibleOwner && eligibleSession
    }

    /**
     * The complete native membership of [scope] for select all (#2925, #3652), or null when this owner is stale.
     * MDK throws ChatPresentationNotReady rather than returning a partial list; callers retry.
     */
    internal suspend fun completeChatListIds(
        scope: ChatListSelectAllScope,
        owner: ChatListActionOwner,
    ): List<String>? {
        val folder = scope.folder
        val readFolder = liveSubscriptions.chatListQueryIds
        val readView = liveSubscriptions.chatListIds
        val available = if (folder != null) readFolder != null else readView != null
        if (!available || !chatListActionIsCurrent(owner)) return null
        val ids =
            if (folder != null) {
                folder.views.flatMap { requireNotNull(readFolder)(owner.accountRef, folder.forView(it)) }.distinct()
            } else {
                requireNotNull(readView)(owner.accountRef, scope.view)
            }
        return ids.takeIf { chatListActionIsCurrent(owner) }
    }

    /**
     * Drops captured IDs that left [scope]; keeps caller order and never adds chats (#2925). Departed groups
     * render inside Chats and Archived but MDK places them in LEFT, so a loaded departed row that was
     * selected stays selected while it is still departed. A folder rechecks against its own query in every
     * view it covers.
     */
    internal suspend fun revalidateCompleteChatListIds(
        scope: ChatListSelectAllScope,
        groupIds: List<String>,
        owner: ChatListActionOwner,
    ): List<String>? {
        val folder = scope.folder
        if (folder != null) return revalidateFolderChatListIds(folder, groupIds, owner)
        val view = scope.view
        val revalidate = liveSubscriptions.revalidateChatListIds?.takeIf { chatListActionSessionIsCurrent(owner) }
        return when {
            groupIds.isEmpty() -> emptyList()
            revalidate == null -> null
            else -> {
                val kept = revalidate(owner.accountRef, view, groupIds).mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
                val outside = groupIds.filter { it.lowercase(Locale.ROOT) !in kept }
                if (outside.isNotEmpty()) {
                    revalidate(owner.accountRef, ChatListViewFfi.LEFT, outside)
                        .mapTo(kept) { it.lowercase(Locale.ROOT) }
                }
                groupIds.filter { it.lowercase(Locale.ROOT) in kept }.takeIf { chatListActionSessionIsCurrent(owner) }
            }
        }
    }

    /**
     * Drops captured IDs that no longer match [folder] in any view it covers; keeps caller order, never adds. A
     * departed group the folder showed from loaded rows stays selected while MarmotKit still files it under Left.
     */
    private suspend fun revalidateFolderChatListIds(
        folder: ChatFolderQuery,
        groupIds: List<String>,
        owner: ChatListActionOwner,
    ): List<String>? {
        val revalidate = liveSubscriptions.revalidateChatListQueryIds?.takeIf { chatListActionSessionIsCurrent(owner) }
        return when {
            groupIds.isEmpty() -> emptyList()
            revalidate == null -> null
            else -> {
                val kept = hashSetOf<String>()
                folder.views.forEach { view ->
                    val matching = revalidate(owner.accountRef, folder.forView(view), groupIds)
                    matching.mapTo(kept) { it.lowercase(Locale.ROOT) }
                }
                val outside = groupIds.filter { it.lowercase(Locale.ROOT) !in kept }
                // Deliberately unfiltered, like the plain path: a departed chat the folder showed was matched by the
                // loaded-rows evaluator, and MarmotKit's Left scope cannot re-evaluate rules such as Unread there, so
                // a selected departed chat stays selected for as long as it is still departed.
                if (outside.isNotEmpty()) {
                    revalidate(owner.accountRef, ChatListViewQueryFfi(ChatListViewFfi.LEFT, emptyList()), outside)
                        .mapTo(kept) { it.lowercase(Locale.ROOT) }
                }
                groupIds.filter { it.lowercase(Locale.ROOT) in kept }.takeIf { chatListActionSessionIsCurrent(owner) }
            }
        }
    }

    /** Resolves a displayed full-list row without materializing it into the bounded controller window. */
    internal suspend fun resolveChatListActionItem(
        groupIdHex: String,
        owner: ChatListActionOwner,
    ): ChatListItem? {
        if (!chatListActionIsCurrent(owner)) return null
        return chatItemForGroup(groupIdHex) ?: resolveChatListActionRow(groupIdHex, owner)?.let {
            projectPresentedRow(it, appState.activeAccount?.accountIdHex)
        }
    }

    private suspend fun resolveChatListActionRow(
        groupIdHex: String,
        owner: ChatListActionOwner,
    ): PresentedChatRowFfi? {
        val lookup =
            liveSubscriptions.presentedRowByGroup?.takeIf {
                chatListActionIsCurrent(owner) && owner.activeAccountRef == owner.accountRef
            } ?: return null
        val revision = materializedGroupsRevision
        val rows =
            runCatchingCancellable {
                readAfterRemovals { listOfNotNull(lookup(owner.accountRef, groupIdHex)) }
            }.getOrNull()
        return rows?.singleOrNull()?.takeIf {
            chatListActionIsCurrent(owner) &&
                materializedGroupsRevision == revision &&
                it.row.groupIdHex.equals(groupIdHex, ignoreCase = true)
        }
    }

    /** Reprojects a committed command for the same session even if its original view has retired. */
    internal fun refreshChatListActionProjection(owner: ChatListActionOwner) {
        if (chatListActionSessionIsCurrent(owner)) scheduleRecompute()
    }

    /** Updates retained native rows without inserting an off-window command result into the bounded list. */
    private fun applyChatListActionRow(row: ChatListRowFfi?) {
        if (row != null && containsGroup(row.groupIdHex)) applyChatListRow(row) else scheduleRecompute()
    }

    /**
     * Mark the chat's unread count to zero by advancing the read pointer to
     * its latest projected message. Without a known message id, the explicit
     * action can still ask MDK to clear a manual reminder; MDK preserves any
     * actual unread count and watermark. Called from the long-press "Mark as read"
     * action — the per-conversation scroll-driven path remains the
     * normal mechanism while a chat is open.
     */
    internal suspend fun markAllRead(
        item: ChatListItem,
        owner: ChatListActionOwner? = captureChatListActionOwner(),
    ): Boolean {
        if (owner == null || !chatListActionIsCurrent(owner)) return false
        val account = owner.accountRef
        val lastId =
            item.projection
                ?.lastMessage
                ?.messageIdHex
                ?.takeIf { it.isNotBlank() }
        val hasReadTarget = lastId != null || item.projection?.manuallyMarkedUnread == true
        val gid = item.group.groupIdHex
        return hasReadTarget &&
            runAdmittedChatListAction(
                owner,
                "markAllRead",
                command = {
                    if (lastId != null) {
                        markTimelineMessageRead(account, gid, lastId)
                    } else {
                        setChatManuallyUnread(account, gid, false)
                    }
                },
            ) { row ->
                applyChatListActionRow(row)
                appState.dismissConversationNotifications(account, gid)
            }
    }

    /** Flag the chat unread until it is next read; the engine owns the clear. */
    internal suspend fun markUnread(
        item: ChatListItem,
        owner: ChatListActionOwner? = captureChatListActionOwner(),
    ): Boolean {
        if (owner == null || !chatListActionIsCurrent(owner)) return false
        val account = owner.accountRef
        return !item.removedFromGroup(appState.activeAccount?.accountIdHex) &&
            runAdmittedChatListAction(
                owner,
                "markUnread",
                command = { setChatManuallyUnread(account, item.group.groupIdHex, true) },
            ) { row -> applyChatListActionRow(row) }
    }

    /** Pin or unpin one chat; newly pinned chats enter at the top of the pinned block. */
    internal suspend fun setPinned(
        item: ChatListItem,
        pinned: Boolean,
        owner: ChatListActionOwner? = captureChatListActionOwner(),
    ): Boolean {
        if (owner == null || !chatListActionIsCurrent(owner)) return false
        return runAdmittedChatListAction(
            owner,
            "setPinned",
            command = { setChatPinned(owner.accountRef, item.group.groupIdHex, pinned) },
        ) { state ->
            applyPinState(state)
            scheduleRecompute()
        }
    }

    /** Boxes a native result so a null row still counts as admitted. */
    private class AdmittedChatListAction<T>(
        val value: T,
    )

    /**
     * Runs one chat-list row command: [command] only while [owner] is still current, and [apply]
     * only while its session still owns the result. Failures stay quiet for the user and are
     * logged in debug builds, since these are idempotent affordances.
     */
    private suspend fun <T> runAdmittedChatListAction(
        owner: ChatListActionOwner,
        label: String,
        command: suspend MarmotInterface.() -> T,
        apply: suspend (T) -> Unit,
    ): Boolean =
        runCatchingCancellable {
            val admitted =
                appState.marmotIo {
                    if (chatListActionIsCurrent(owner)) AdmittedChatListAction(command()) else null
                }
            if (admitted == null || !chatListActionSessionIsCurrent(owner)) {
                false
            } else {
                apply(admitted.value)
                true
            }
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMChatsController", "$label failed", it)
        }.getOrDefault(false)

    /**
     * Fold the engine's normalized pin order into the held rows so the list
     * reorders immediately; the PIN_ORDER_CHANGED trigger (or a snapshot)
     * re-delivers the same state authoritatively moments later.
     */
    private fun applyPinState(state: ChatPinStateFfi) {
        val positionByGroup =
            state.orderedGroupIds
                .withIndex()
                .associate { (index, id) -> id.lowercase() to index.toUInt() }
        var changed = false
        chatRowsByGroup.keys.toList().forEach { key ->
            val position = positionByGroup[key]
            val optimisticState = optimisticChatListPreviewByGroup[key]
            val row = optimisticState?.baselineRow ?: chatRowsByGroup[key] ?: return@forEach
            val updated =
                if (position != null) {
                    row.copy(pinned = true, pinnedPosition = position)
                } else if (row.pinned) {
                    row.copy(pinned = false, pinnedPosition = null)
                } else {
                    row
                }
            if (updated == row) return@forEach
            changed = true
            if (optimisticState == null) {
                chatRowsByGroup[key] = updated
            } else {
                optimisticState.baselineRow = updated
                materializeOptimisticChatListPreview(key, optimisticState)
            }
        }
        if (changed) scheduleRecompute()
    }

    private fun requestGroupProfiles(group: AppGroupRecordFfi) {
        appState.requestProfiles(
            listOfNotNull(group.welcomerAccountIdHex) + group.admins,
        )
    }

    private fun requestChatRowProfiles(row: ChatListRowFfi) {
        row.lastMessage?.sender?.let(appState::requestProfile)
    }

    /**
     * Warms the pictures a row's next notification may show, in this controller's own account's workers: a
     * replaced controller recomputing after an account switch never decodes its rows in the new account's worker.
     */
    private fun preWarmNotificationAvatars(item: ChatListItem) {
        val conversationAvatar =
            ProfileSanitizer.protocolImageUrl(item.group.avatarUrl)
                ?: ProfileSanitizer.protocolImageUrl(item.projection?.avatarUrl)
        val profiles =
            dev.ipf.whitenoise.android.media.MediaTrustDomain
                .publicProfiles(accountRef)
        if (item.selectedAvatarAsset == null) {
            // A group's own picture here is its URL, whose host chooses the bytes, so it decodes single-use, and a
            // direct chat's peer picture is a published one.
            val domain =
                if (GroupProjector.ownsGroupPicture(item)) {
                    dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique
                } else {
                    profiles
                }
            AvatarImageLoader.preWarm(conversationAvatar, domain)
            GroupProjector
                .avatarAccount(item.group, item.presentationOtherMemberAccount, item.presentationMemberCount)
                ?.let { appState.preWarmProfileAvatar(it, profiles) }
        }
        item.latest?.sender?.let { appState.preWarmProfileAvatar(it, profiles) }
    }

    private fun firstFrameAvatarSeed(item: ChatListItem): ChatListAvatarSeed? {
        val owner = accountRef
        return firstFrameGroupAvatarSeed(item, owner, appState::avatarUrl)
    }

    /**
     * Called by the shell when a conversation is foregrounded (`false`) or the
     * chat list is back on screen (`true`). The subscription stays alive either
     * way — returning shows the current list with no reload — only the
     * recompute is paused while hidden and flushed once on return. See #6.
     */
    fun setChatListVisible(visible: Boolean) = presentationLifetime.setVisible(visible)

    /**
     * A draft started, cleared, or received a newer coalesced MDK updatedAtMs,
     * so the chat's effective sort time changed. Re-sort — deferred while the
     * list is hidden and flushed when it returns, like every other recompute.
     */
    fun onDraftSortOrderChanged() {
        if (isCleared) return
        recompute()
    }

    private val recomputeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val presentationLifetime = ChatListPresentationLifetime(recomputeScope) { recompute() }

    /** Keeps folded native updates synchronous while coalescing only their visible publication. */
    private fun scheduleRecompute() = presentationLifetime.schedule()

    /** The window frame whose rows wait for the next visible projection. */
    @Volatile private var stagedChatListFrame: ChatListFrameMark? = null

    /** The window frame whose rows [items] last published; snapshot state, so a pin follow can wait on it. */
    private var presentedChatListFrame by mutableStateOf<ChatListFrameMark?>(null)

    /**
     * Returns [view] to its top and marks the frame a caller should wait for with [chatListFramePresented]: the
     * one holding the restored head, or, when the command installed nothing itself (a newer frame from the
     * receive loop already won, or MDK answered stale), the live set's current frame, which may still sit in
     * the projection debounce. Null only when no live window set remains to wait on.
     */
    internal suspend fun returnChatListToTopFrame(view: ChatListViewFfi): ChatListFrameMark? {
        val windows = chatListWindows
        val account = accountRef
        return when {
            windows == null || account == null -> null
            windows.returnToTop(view) == null -> windows.currentFrameMarkIfLive()
            else ->
                ChatListFrameMark(windows, windows.frame().revision)
                    .takeIf { applyChatListWindowRows(account, windows) }
        }
    }

    /** This set's current frame while it is still open and still the controller's window set, else null. */
    private fun ChatListWindowSet.currentFrameMarkIfLive(): ChatListFrameMark? =
        takeIf { !closed && chatListWindows === it }?.let { ChatListFrameMark(it, it.frame().revision) }

    /**
     * Whether [items] now shows [mark]'s frame or a newer one from the same window set. A replaced or
     * closed window set also counts, since that frame will never be shown and the caller must not wait on it.
     */
    internal fun chatListFramePresented(mark: ChatListFrameMark): Boolean {
        val presented = presentedChatListFrame
        if (chatListWindows !== mark.windows) return true
        return presented != null && presented.windows === mark.windows && presented.revision >= mark.revision
    }

    /** Whether [items] shows a frame newer than [mark] from its window set, or that set was replaced or closed. */
    internal fun chatListFrameSuperseded(mark: ChatListFrameMark): Boolean {
        val presented = presentedChatListFrame
        if (chatListWindows !== mark.windows) return true
        return presented != null && presented.windows === mark.windows && presented.revision > mark.revision
    }

    /**
     * Dispose every reference this controller owns. The chat-list screen calls
     * this once when it disposes the controller, which happens on every account
     * switch and [WhiteNoiseAppState.runtimeGeneration] bump (sign-out,
     * destructive wipe). Without it the controller-owned jobs — and the
     * [ChatsController] state they keep alive, whose projection holds decrypted
     * message previews — would leak for the process lifetime. Mirrors
     * [ConversationController.onCleared].
     */
    fun onCleared() {
        if (isCleared) return
        isCleared = true
        bindLifetime.advance()
        val jobToCancel =
            synchronized(liveSubscriptionLock) {
                accountRef = null
                boundAccountRef = null
                subscriptionOwner.bindJob.also { subscriptionOwner.bindJob = null }
            }
        jobToCancel?.cancel()
        connectionOwner.clear()
        pendingInitialLocalSnapshot = null
        resetBackingState()
        items = emptyList()
        archivedItems = emptyList()
        hasLoadedLocalSnapshot = false
        isLoading = false
        error = null
        presentationLifetime.clear()
        pendingRecoveryProjectionGeneration.clear()
        recoveryProjectionGeneration = 0L
        recomputeScope.cancel()
    }

    private fun resetBackingState() {
        selectedAvatarAssetsByGroup = emptyMap()
        replaceChatRows(emptyList())
        selectedPresentationsByGroup = emptyMap()
        selectedPreviewsByGroup = emptyMap()
        selectedActionsByGroup = emptyMap()
        groupRecordsById = emptyMap()
        activitySequenceByGroup.clear()
        nextActivitySequence = 0uL
        optimisticChatListPreviewByGroup.clear()
        archiveActions.clear()
        memberCacheByGroup = emptyMap()
        presentationMembersByGroup = emptyMap()
        memberSnapshotsRevision += 1L
        removedGroupIds = emptySet()
        inFlightMemberFetches.clear()
        memberFetchRetryJobsByGroup.values.forEach(Job::cancel)
        memberFetchRetryJobsByGroup.clear()
        memberFetchRetryBackoffTierByGroup.clear()
        failedMemberFetches.clear()
        terminalMemberFetches.clear()
        selfOnlyDirectGraceRetryGroups.clear()
        previewTokensByText = emptyMap()
        inFlightPreviewParses.clear()
        mediaPreviewFallbackByMessageId = emptyMap()
        inFlightMediaKindResolves.clear()
    }

    /** Checks that suspended work still belongs to the live account binding. */
    internal fun isActiveBindEpoch(epoch: Long): Boolean {
        val bound = accountRef != null
        return !isCleared && bindLifetime.isCurrent(epoch) && bound
    }

    /** Refreshes account unread state and publishes the visible projection when its presentation lifetime permits. */
    private fun recompute(scheduleBackgroundEnrichment: Boolean = true) {
        if (isCleared) return
        // currentProjectedItems() reads backing maps that remain warm while the
        // visible chat-list projection is intentionally frozen. On-demand UI
        // consumers key on this revision so those hidden updates stay live.
        forwardTargetsRevision += 1L
        val unreadAccountRef = accountRef
        // Project once and reuse for both the per-account aggregate and the
        // visible list, so the aggregate sees the same removed-group
        // suppression the row badge does (#625). The projection is cheap
        // relative to the FFI fan-out it gates, and is needed even while hidden
        // so a removed group's frozen unread can't keep lighting the
        // cross-account dot behind an open conversation.
        val unreadAccountIdHex = boundAccountIdHex()
        val projected = currentProjectedItems(unreadAccountIdHex)
        if (unreadAccountRef != null) {
            appState.updateAccountUnreadProjection(
                accountRef = unreadAccountRef,
                unreadCount = accountUnreadCount(projected, unreadAccountIdHex),
                hasManualUnread = accountHasManualUnread(projected, unreadAccountIdHex),
            )
        }
        // Hidden behind an open conversation: keep folding updates into the
        // backing maps (done by the caller) but defer the projection rebuild +
        // member/preview fan-out until the list returns, then run once. See #6.
        if (!presentationLifetime.beginVisiblePublication()) return
        val all =
            sortChatListItems(projected) { item ->
                accountRef?.let { appState.draftStore.draftedAtSecondsFor(it, item.group.groupIdHex) }
            }
        val visible =
            all.filter { !it.group.archived }.mapIndexed { index, item ->
                item.copy(
                    firstFrameAvatar =
                        if (index < CHAT_LIST_AVATAR_WARM_ROWS) {
                            firstFrameAvatarSeed(item)
                        } else {
                            null
                        },
                )
            }
        val archived = all.filter { it.group.archived }.map { it.copy(firstFrameAvatar = null) }
        val avatarWarmTargets = visible.take(CHAT_LIST_AVATAR_WARM_ROWS)
        items = visible
        archivedItems = archived
        presentedChatListFrame = stagedChatListFrame
        val recoveryGeneration = pendingRecoveryProjectionGeneration.consume()
        if (recoveryGeneration > 0L) {
            if (
                appState.recoveryDiagnostics.recordChatListProjectionPublished(
                    generation = recoveryGeneration,
                    count = visible.size + archived.size,
                )
            ) {
                recoveryProjectionGeneration = recoveryGeneration
            }
        }
        // Limit speculative network work to the recent visible conversations the
        // user is likely to receive from next. The app-state notification stream
        // independently warms each ingested sender/conversation on cold UI-less
        // process starts.
        if (scheduleBackgroundEnrichment) {
            avatarWarmTargets.forEach(::preWarmNotificationAvatars)
        }
        chatsDebug { "recompute visible=${items.size} archived=${archivedItems.size} total=${all.size}" }
        // For any group we don't yet have members cached for, fan out a
        // one-shot members fetch so unnamed titles and the profile sheet's
        // shared-groups list can resolve from local snapshots.
        if (scheduleBackgroundEnrichment) schedulePendingMemberFetches()
        // Likewise, fan out off-main markdown parses for any preview text we
        // haven't tokenized yet; each completion folds back via
        // scheduleRecompute() so a burst coalesces into one rebuild.
        if (scheduleBackgroundEnrichment) {
            schedulePendingPreviewParses()
            schedulePendingMediaKindResolves()
        }
    }

    /**
     * Walk the current chat rows and, for any group without cached members or
     * an in-flight fetch, kick off a `groupMembers` FFI call. On success the
     * cache updates and `scheduleRecompute()` runs so row titles and
     * profile-sheet shared-group intersections see the local member snapshot,
     * with a burst of completions coalesced into one rebuild.
     */
    internal fun requestMemberSnapshots(groupIds: Iterable<String>) {
        schedulePendingMemberFetches(groupIds)
    }

    /**
     * Hydrates the open picker's off-window rows through the existing bounded local roster reader.
     * These rows never enter the native window. Cancellation and both controller lifetimes guard publication.
     */
    internal suspend fun resolveForwardTargetMembers(presented: List<PresentedChatRowFfi>) {
        val account = accountRef ?: return
        val epoch = bindEpoch
        do {
            val cacheEpoch = memberCacheEpoch
            val pending =
                presented.distinctBy { it.row.groupIdHex }.filter {
                    memberSnapshotNeedsFetch(it.row.groupIdHex) && it.row.groupIdHex !in inFlightMemberFetches
                }
            val ids = pending.map { it.row.groupIdHex }
            inFlightMemberFetches.addAll(ids)
            try {
                if (pending.isNotEmpty()) loadForwardTargetMembers(account, pending, epoch, cacheEpoch)
            } finally {
                if (isActiveBindEpoch(epoch)) inFlightMemberFetches.removeAll(ids.toSet())
            }
            // A live roster invalidation supersedes this read; retry under the same picker-owned coroutine.
        } while (isActiveBindEpoch(epoch) && !memberCacheLifetime.isCurrent(cacheEpoch))
    }

    /** Prefers identifier-only local pages; failure falls back to the existing shared permit pool. */
    private suspend fun loadForwardTargetMembers(
        account: String,
        pending: List<PresentedChatRowFfi>,
        epoch: Long,
        cacheEpoch: Long,
    ) {
        val projections = loadInitialMemberIdProjections(account, pending.map { it.row.groupIdHex })
        if (projections != null) {
            val byGroup = projections.associateBy { it.groupIdHex }
            pending.forEach { presentedRow ->
                val projection = byGroup[presentedRow.row.groupIdHex] ?: return@forEach
                applyForwardTargetMembers(
                    presentedRow.row,
                    memberRecordsFromIds(projection.memberIdsHex, boundAccountIdHex()),
                    epoch,
                    cacheEpoch,
                )
            }
        } else {
            coroutineScope {
                pending
                    .map { presentedRow ->
                        async {
                            memberFetchGate.withPermit {
                                val current = isActiveBindEpoch(epoch) && memberCacheLifetime.isCurrent(cacheEpoch)
                                if (!current) return@withPermit
                                val row = presentedRow.row
                                if (!memberSnapshotNeedsFetch(row.groupIdHex)) return@withPermit
                                val members =
                                    runCatchingCancellable { memberSnapshotLoader(account, row.groupIdHex) }.getOrNull()
                                        ?: return@withPermit
                                applyForwardTargetMembers(row, members, epoch, cacheEpoch)
                            }
                        }
                    }.awaitAll()
            }
        }
    }

    /** Uses the native snapshot's conversation kind when the retained window has no context for a roster. */
    private fun applyForwardTargetMembers(
        row: ChatListRowFfi,
        members: List<AppGroupMemberRecordFfi>,
        epoch: Long,
        cacheEpoch: Long,
    ) {
        val count = GroupProjector.uniqueMemberCount(members)
        val unresolvedDirect =
            row.conversationKind == ChatConversationKindFfi.UNKNOWN &&
                count <= 1 &&
                GroupProjector.isUnnamed(row.groupName)
        applyFetchedMemberSnapshot(
            groupIdHex = row.groupIdHex,
            members = members,
            epoch = epoch,
            cacheEpoch = cacheEpoch,
            directConversationOverride =
                unresolvedDirect ||
                    GroupProjector.isDm(
                        row.conversationKind,
                        count,
                        row.groupName,
                    ),
            requestProfileRefresh = false,
        )
    }

    internal fun retryMemberSnapshots(groupIds: Iterable<String>) {
        val targets = groupIds.distinct().toList()
        targets.forEach(::cancelMemberSnapshotRetry)
        if (failedMemberFetches.removeAll(targets.toSet())) memberSnapshotsRevision += 1L
        schedulePendingMemberFetches(targets)
    }

    /** Starts bounded roster reads stamped with both binding and cache lifetimes. */
    private fun schedulePendingMemberFetches(groupIds: Iterable<String> = chatRows.map { it.groupIdHex }) {
        val account = accountRef ?: return
        val epoch = bindEpoch
        val cacheEpoch = memberCacheEpoch
        val liveGroupIds = chatRows.mapTo(mutableSetOf()) { it.groupIdHex }
        val pending =
            groupIds
                .asSequence()
                .distinct()
                .filter { it in liveGroupIds }
                .filter { memberSnapshotNeedsFetch(it) }
                .filterNot { it in inFlightMemberFetches }
                .filterNot { memberFetchRetryJobsByGroup[it]?.isActive == true }
                .filterNot { it in terminalMemberFetches }
                .toList()
        if (pending.isEmpty()) return
        if (failedMemberFetches.removeAll(pending.toSet())) memberSnapshotsRevision += 1L
        inFlightMemberFetches.addAll(pending)
        pending.forEach { groupIdHex ->
            recomputeScope.launch {
                try {
                    memberFetchGate.withPermit {
                        if (!isActiveBindEpoch(epoch)) return@withPermit
                        val members = memberSnapshotLoader(account, groupIdHex)
                        applyFetchedMemberSnapshot(
                            groupIdHex = groupIdHex,
                            members = members,
                            epoch = epoch,
                            cacheEpoch = cacheEpoch,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (throwable: Throwable) {
                    chatsDebug(throwable) {
                        "member fetch failed group=${groupIdHex.take(8)}: " +
                            (throwable.message ?: throwable.javaClass.simpleName)
                    }
                    if (isActiveBindEpoch(epoch)) {
                        markMemberSnapshotFetchFailed(groupIdHex, throwable)
                        scheduleMemberSnapshotRetry(groupIdHex, epoch)
                    }
                } finally {
                    // Only mutate the in-flight set if this job still
                    // belongs to the current bind. A later bind() has
                    // already cleared the set; removing again would be
                    // a no-op but obscures the invariant.
                    if (isActiveBindEpoch(epoch)) {
                        inFlightMemberFetches.remove(groupIdHex)
                        if (
                            !memberCacheLifetime.isCurrent(cacheEpoch) &&
                            memberSnapshotNeedsFetch(groupIdHex)
                        ) {
                            // The chat list can be hidden behind an open
                            // conversation while the system-share picker is
                            // visible. Retry the invalidated targeted fetch
                            // directly: recompute() deliberately skips member
                            // fan-out while hidden, and the picker's effect key
                            // is unchanged until a roster lands.
                            schedulePendingMemberFetches(listOf(groupIdHex))
                        }
                    }
                }
            }
        }
    }

    /** Publishes a roster only while both its binding and invalidation tokens remain current. */
    private fun applyFetchedMemberSnapshot(
        groupIdHex: String,
        members: List<AppGroupMemberRecordFfi>,
        epoch: Long,
        cacheEpoch: Long,
        scheduleRecomputeAfterPublish: Boolean = true,
        directConversationOverride: Boolean? = null,
        requestProfileRefresh: Boolean = true,
    ) {
        if (!isActiveBindEpoch(epoch) || !memberCacheLifetime.isCurrent(cacheEpoch)) return
        val activeAccountIdHex = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex
        val knownSelfRemoval = knownSelfRemovalFor(groupIdHex)
        val directConversationCandidate =
            directConversationOverride ?: directConversationCandidateFor(
                groupIdHex,
                members,
            )
        val selfOnlyDirectRoster =
            isSelfOnlyDirectRoster(
                members = members,
                directConversation = directConversationCandidate,
                activeAccountIdHex = activeAccountIdHex,
            )
        if (
            !memberSnapshotReadyToCache(
                members = members,
                knownSelfRemoval = knownSelfRemoval,
                directConversation = directConversationCandidate,
                activeAccountIdHex = activeAccountIdHex,
                selfOnlyDirectGraceElapsed = groupIdHex in selfOnlyDirectGraceRetryGroups,
            )
        ) {
            if (selfOnlyDirectRoster) selfOnlyDirectGraceRetryGroups.add(groupIdHex)
            markMemberSnapshotFetchFailed(groupIdHex)
            scheduleMemberSnapshotRetry(groupIdHex, epoch)
            return
        }
        if (requestProfileRefresh) {
            members.map { it.memberIdHex }.filter { it.isNotBlank() }.forEach(appState::requestProfile)
        }
        memberCacheByGroup = memberCacheByGroup + (groupIdHex to members)
        failedMemberFetches.remove(groupIdHex)
        presentationMembersByGroup = presentationMembersByGroup - groupIdHex
        cancelMemberSnapshotRetry(groupIdHex)
        memberFetchRetryBackoffTierByGroup.remove(groupIdHex)
        selfOnlyDirectGraceRetryGroups.remove(groupIdHex)
        memberSnapshotsRevision += 1L
        recordMemberDerivedLocalReadyIfComplete()
        // A loaded roster that omits self is known removal evidence (admin
        // eviction / self-leave the engine has already applied). Marking it
        // makes an empty self-only roster suppress the badge too, where the
        // snapshot path alone reads empty as ambiguous.
        if (activeAccountIdHex != null &&
            members.none { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
        ) {
            removedGroupIds = removedGroupIds + groupIdHex
        }
        // Coalesce: a burst of member-fetch completions on account open/switch
        // would otherwise drive N un-debounced full recomputes. Defer into one.
        scheduleRecomputeIfRequested(scheduleRecomputeAfterPublish)
    }

    private fun markMemberSnapshotFetchFailed(
        groupIdHex: String,
        throwable: Throwable? = null,
    ) {
        if (throwable != null && isTerminalMemberFetchFailure(throwable)) terminalMemberFetches += groupIdHex
        if (failedMemberFetches.add(groupIdHex)) memberSnapshotsRevision += 1L
    }

    private fun scheduleMemberSnapshotRetry(
        groupIdHex: String,
        epoch: Long,
    ) {
        memberFetchRetryJobsByGroup[groupIdHex]?.let { existing ->
            if (existing.isActive) return
            memberFetchRetryJobsByGroup.remove(groupIdHex)
        }
        val backoffTier = memberFetchRetryBackoffTierByGroup.getOrDefault(groupIdHex, 0)
        val shouldRetry =
            isActiveBindEpoch(epoch) &&
                groupIdHex !in terminalMemberFetches &&
                memberSnapshotNeedsFetch(groupIdHex) &&
                chatRowsByGroup.containsKey(chatRowKey(groupIdHex))
        if (!shouldRetry) return
        memberFetchRetryBackoffTierByGroup[groupIdHex] =
            if (backoffTier >= MEMBER_FETCH_MAX_BACKOFF_TIER) {
                MEMBER_FETCH_MAX_BACKOFF_TIER
            } else {
                backoffTier + 1
            }
        val retryJob =
            recomputeScope.launch(start = CoroutineStart.LAZY) {
                val currentJob = coroutineContext[Job]
                val shouldFetch =
                    try {
                        delay(memberSnapshotRetryDelay(backoffTier))
                        isActiveBindEpoch(epoch) &&
                            memberSnapshotNeedsFetch(groupIdHex) &&
                            chatRowsByGroup.containsKey(chatRowKey(groupIdHex))
                    } finally {
                        if (memberFetchRetryJobsByGroup[groupIdHex] === currentJob) {
                            memberFetchRetryJobsByGroup.remove(groupIdHex)
                        }
                    }
                if (shouldFetch) schedulePendingMemberFetches(listOf(groupIdHex))
            }
        memberFetchRetryJobsByGroup[groupIdHex] = retryJob
        retryJob.start()
    }

    private fun cancelMemberSnapshotRetry(groupIdHex: String) {
        memberFetchRetryJobsByGroup.remove(groupIdHex)?.cancel()
    }

    /**
     * Walk legacy chat rows whose MDK projection did not include a Markdown
     * document and start the `parseMarkdown` fallback off-main. Current MDK
     * rows bypass this path and publish their projected document on the first
     * frame. A legacy row stays responsive with plaintext until its fallback
     * parse completes; failures cache an empty document to avoid retry storms.
     * The cache is pruned to texts still on screen so live-update churn cannot
     * grow it without bound.
     */
    private fun schedulePendingPreviewParses() {
        if (accountRef == null) return
        val epoch = bindEpoch
        val liveTexts = chatRows.mapNotNullTo(mutableSetOf(), ::chatRowPreviewMarkdownFallbackSource)
        if (previewTokensByText.keys.any { it !in liveTexts }) {
            previewTokensByText = previewTokensByText.filterKeys { it in liveTexts }
        }
        val pending =
            liveTexts
                .filterNot { it in previewTokensByText }
                .filterNot { it in inFlightPreviewParses }
        if (pending.isEmpty()) return
        inFlightPreviewParses.addAll(pending)
        pending.forEach { text ->
            recomputeScope.launch {
                try {
                    val tokens =
                        previewParseGate.withPermit {
                            if (!isActiveBindEpoch(epoch)) return@withPermit null
                            appState.parseMarkdownOrEmpty(text)
                        } ?: return@launch
                    if (!isActiveBindEpoch(epoch)) return@launch
                    previewTokensByText = previewTokensByText + (text to tokens)
                    // Coalesce: a burst of preview-parse completions on account
                    // open/switch would otherwise drive N un-debounced full
                    // recomputes. Defer into one.
                    scheduleRecompute()
                } finally {
                    // Same epoch discipline as the member fetches: a later
                    // bind() already cleared the set, so only the owning
                    // epoch may mutate it.
                    if (isActiveBindEpoch(epoch)) inFlightPreviewParses.remove(text)
                }
            }
        }
    }

    /**
     * Walk the current chat rows and, for any blank kind-9 preview without a
     * cached media fallback or an in-flight resolve, read the latest local
     * timeline record off-main so [projectedPreviewText] can preserve media
     * filename and typed/generic label fallbacks. The chat-list projection
     * stores plaintext only, so imeta tags must be read from the message store
     * (source of truth) on demand.
     */
    private fun schedulePendingMediaKindResolves() {
        if (accountRef == null) return
        val epoch = bindEpoch
        val account = accountRef!!
        val liveMessageIds = chatRows.mapNotNull(::chatRowNeedsMediaKindResolve).toSet()
        if (mediaPreviewFallbackByMessageId.keys.any { it !in liveMessageIds }) {
            mediaPreviewFallbackByMessageId = mediaPreviewFallbackByMessageId.filterKeys { it in liveMessageIds }
        }
        val pendingRows =
            chatRows.filter { row ->
                val messageId = chatRowNeedsMediaKindResolve(row) ?: return@filter false
                messageId !in mediaPreviewFallbackByMessageId && messageId !in inFlightMediaKindResolves
            }
        if (pendingRows.isEmpty()) return
        pendingRows.forEach { row ->
            val messageId = chatRowNeedsMediaKindResolve(row) ?: return@forEach
            inFlightMediaKindResolves.add(messageId)
            val groupIdHex = row.groupIdHex
            recomputeScope.launch {
                try {
                    val page =
                        mediaKindResolveGate.withPermit {
                            if (!isActiveBindEpoch(epoch)) return@withPermit null
                            try {
                                appState.marmotIo(MarmotTraceSection.TIMELINE_READ) {
                                    timelineMessages(
                                        account,
                                        TimelineMessageQueryFfi.page(
                                            groupIdHex = groupIdHex,
                                            search = null,
                                            before = null,
                                            beforeMessageId = null,
                                            after = null,
                                            afterMessageId = null,
                                            limit = 1u,
                                        ),
                                    )
                                }
                            } catch (throwable: Throwable) {
                                rethrowIfCancellation(throwable)
                                null
                            }
                        }
                    if (!isActiveBindEpoch(epoch)) return@launch
                    val fallback =
                        page
                            ?.messages
                            ?.firstOrNull()
                            ?.takeIf { it.messageIdHex == messageId }
                            ?.let { MessageProjector.mediaPreviewFallback(TimelineProjector.toAppMessageRecord(it)) }
                            ?: return@launch
                    if (!isActiveBindEpoch(epoch)) return@launch
                    mediaPreviewFallbackByMessageId = mediaPreviewFallbackByMessageId + (messageId to fallback)
                    scheduleRecompute()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Best-effort. Leave the fallback absent so the next bind
                    // can retry; the row keeps the generic media preview.
                } finally {
                    if (isActiveBindEpoch(epoch)) inFlightMediaKindResolves.remove(messageId)
                }
            }
        }
    }

    private fun applyAccountSwitchLocalSnapshot(snapshot: AccountSwitchLocalSnapshot) {
        accountRef = snapshot.accountRef
        boundAccountRef = snapshot.accountRef
        snapshot.presentedRows?.let(::replacePresentedChatRows) ?: replaceChatRows(snapshot.rows)
        groupRecordsById = snapshot.groups.associateBy { it.groupIdHex }
        applyInitialMemberIdProjections(
            projections = snapshot.memberIds,
            activeAccountIdHex = snapshot.activeAccountIdHex,
            requestProfileRefresh = false,
        )
        hasLoadedLocalSnapshot = true
        isLoading = false
        error = null
        recompute(scheduleBackgroundEnrichment = false)
    }

    init {
        pendingInitialLocalSnapshot?.let(::applyAccountSwitchLocalSnapshot)
    }
}
