package dev.ipf.whitenoise.android.state

import android.util.Log
import android.view.Choreographer
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AvatarAssetFfi
import dev.ipf.marmotkit.ChatListRowActionsFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatPinStateFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelectedChatPreviewFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.MediaPreviewFallback
import dev.ipf.whitenoise.android.diagnostics.DmCreationAttempt
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
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
        ownerAccountIdHex = activeAccountIdHex,
        latest = null,
        otherMemberAccount = GroupProjector.otherMemberAccount(members, activeAccountIdHex),
        memberCount = GroupProjector.uniqueMemberCount(members),
        memberSnapshot = members.let(::GroupMemberSnapshot),
        projection = null,
    )
}

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

internal fun isSelfOnlyDirectRoster(
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

internal data class OptimisticChatListPreviewMatch(
    val entryKey: String?,
    val activitySequence: ULong,
)

internal data class RemovedChatRowSnapshot(
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

internal fun logStaleChatListUnreadRejected(
    keptUnread: ULong,
    rejectedUnread: ULong,
) {
    Log.w(
        "DMChats",
        "stale chat-list unread rejected kept=$keptUnread rejected=$rejectedUnread source=client_stale_fold",
    )
}

internal const val MAX_CHAT_LIST_ACTIVITY_SEQUENCE_HISTORY = 64
private const val CHAT_LIST_AVATAR_WARM_ROWS = 24

// Maximum number of `groupMembers` FFI roster reads running at once from the
// chat-list projection. Keeps large accounts from flooding IO at startup while
// still letting shared-group snapshots materialize in the background.
private const val MEMBER_FETCH_FANOUT = 4
internal const val INITIAL_MEMBER_FALLBACK_FANOUT = 8
internal const val INITIAL_MEMBER_FALLBACK_CUTOFF_MS = 500L

private const val NANOS_PER_MILLISECOND = 1_000_000L

private const val MEMBER_FETCH_INITIAL_RETRY_DELAY_MS = 250L
private const val MEMBER_FETCH_MAX_RETRY_DELAY_MS = 300_000L
internal const val MEMBER_FETCH_MAX_BACKOFF_TIER = 11
private const val PREVIEW_PARSE_FANOUT = 4
private const val MEDIA_KIND_RESOLVE_FANOUT = 4

/** Retry cadence while MarmotKit reports a documented not-ready state; well under the connectivity backoff. */
internal const val NOT_READY_RETRY_DELAY_MS = 2_000L

/** A failed account-wide read leaves the picker incomplete, so it reports FAILED and offers Retry. */
internal fun ProfileGroupPickerState.failedWhenAccountWideReadFailed(accountWide: ProfileAccountWideGroups) =
    if (accountWide.readFailed) copy(loadState = ProfileGroupPickerLoadState.FAILED) else this

/** Owns the active account's chat-list projection and live subscription lifecycle. */
class ChatsController private constructor(
    internal val appState: WhiteNoiseAppState,
    internal val memberSnapshotLoader: suspend (String, String) -> List<AppGroupMemberRecordFfi>,
    internal val memberSnapshotRetryDelay: (Int) -> Long,
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
        internal set
    var archivedItems by mutableStateOf<List<ChatListItem>>(emptyList())
        private set
    // Starts true: a new controller has no snapshot yet. ChatsScreen must not
    // paint authoritative EmptyChats until bind() finishes the first local
    // snapshot (issue #1697).

    var isLoading by mutableStateOf(initialLocalSnapshot == null)
        internal set
    internal var slowStartupEpoch by mutableStateOf(-1L)
    val startupTakingLonger: Boolean get() = isLoading && slowStartupEpoch == bindEpoch

    /**
     * True once the bound account's local projection has returned, including
     * an authoritative empty result. Unlike `items.isNotEmpty()`, this remains
     * a valid warm-resume seed for accounts with no conversations.
     */
    var hasLoadedLocalSnapshot by mutableStateOf(initialLocalSnapshot != null)
        internal set
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
        internal set

    /**
     * Monotonic signal for [chatRowsByGroup] membership changes. Incremented when
     * a group id is added, removed, or replaced by snapshot — not on in-place
     * row updates — so foreground provisional opens can reconcile against live
     * backing rows while [items] stays frozen behind an open conversation (#1729).
     * staleness-exempt: this observable projection membership version is consumed by Compose.
     */
    var materializedGroupsRevision by mutableLongStateOf(0L)
        internal set

    /**
     * Counts the membership removals whose cause Android cannot know — a window replacement or native
     * removal that drops a row could be an eviction or a deletion. Additions and local deletions leave it
     * alone, so a full-list read taken before them can still prove what the list holds (#3586).
     * staleness-exempt: this observable membership version is consumed by Compose.
     */
    var ambiguousMembershipRemovalsRevision by mutableLongStateOf(0L)
        internal set

    // Chats this controller deleted locally, in order, so an earlier full-list read can drop exactly those.
    internal val locallyDeletedGroupIds = mutableListOf<String>()

    /** How many local deletions have been recorded, the mark a full-list read captures before it starts. */
    internal val locallyDeletedGroupCount: Int get() = locallyDeletedGroupIds.size

    /** Folded ids of the chats deleted locally after [mark] deletions had been recorded. */
    internal fun locallyDeletedGroupIdsSince(mark: Int): Set<String> = locallyDeletedGroupIds.drop(mark).toSet()

    /** Complete observable forward-picker revision; staleness-exempt: it is a Compose version. */
    var forwardTargetsRevision by mutableLongStateOf(0L)
        private set

    // staleness-exempt: observable member projection version consumed by derived UI.
    var memberSnapshotsRevision by mutableLongStateOf(0L)
        internal set

    internal var accountRef: String? = initialLocalSnapshot?.accountRef ?: initialAccountRef
        internal set
    private var pendingInitialLocalSnapshot = initialLocalSnapshot

    internal fun chatRowKey(groupIdHex: String): String = groupIdHex.lowercase()

    internal fun nextChatActivitySequence(): ULong {
        if (nextActivitySequence != ULong.MAX_VALUE) nextActivitySequence += 1uL
        return nextActivitySequence
    }

    internal val chatRowsByGroup = LinkedHashMap<String, ChatListRowFfi>()
    internal var selectedAvatarAssetsByGroup: Map<String, AvatarAssetFfi> = emptyMap()

    internal var selectedPresentationsByGroup = emptyMap<String, ConversationPresentationFfi>()
    internal var selectedPreviewsByGroup = emptyMap<String, SelectedChatPreviewFfi>()
    internal var selectedActionsByGroup = emptyMap<String, ChatListRowActionsFfi>()
    internal val chatRows: Collection<ChatListRowFfi>
        get() = chatRowsByGroup.values
    internal val invitationReads = mutableMapOf<String, InvitationSettlementRead>()
    internal var groupRecordsById = mapOf<String, AppGroupRecordFfi>()

    // Holds a locally committed rename on its row until the subscription agrees (#2696). A clear
    // stays held until the group record agrees too, because a blank row falls back to its name.
    internal val localGroupNames = LocalGroupFieldAuthority(holdClearAfterAgreement = true)

    // Whole-second activity timestamps need an in-memory tie-break that follows
    // the order local/live activity is accepted. This is bounded to one scalar
    // per materialized row and cleared with the backing projection on bind.
    internal val activitySequenceByGroup = mutableMapOf<String, ULong>()

    // staleness-exempt: ordered activity tie-breaker, not an async publication fence.
    internal var nextActivitySequence = 0uL
    internal val optimisticChatListPreviewByGroup = mutableMapOf<String, OptimisticChatListPreviewState>()
    internal val archiveActions =
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
    internal var memberCacheByGroup: Map<String, List<AppGroupMemberRecordFfi>> = emptyMap()

    // A live group-record update invalidates the authoritative roster, but its
    // last rendered values remain useful for title/avatar continuity while a
    // replacement loads. Keep only the display-shaped values separate from the
    // authoritative cache so business rules cannot consume a stale roster.
    // Cleared on each bind and as soon as a complete replacement arrives.
    internal var presentationMembersByGroup: Map<String, ChatListMemberPresentation> = emptyMap()

    // Groups the active account is known to have been removed/left from, keyed
    // by group id hex. Set on a confirmed self-leave or when a loaded roster
    // omits self; lets [ChatListItem.removedFromGroup] treat a genuinely-empty
    // post-removal roster as real removal (a fetch-failure empty roster, which
    // never adds an id here, stays non-removed). Cleared on every bind.
    internal var removedGroupIds: Set<String> = emptySet()

    // Tracks groups whose member fetch is currently in flight, preventing
    // duplicate FFI work for the same group. Entries are added in
    // [schedulePendingMemberFetches] and removed in the same coroutine's
    // `finally`. Failed or transiently-empty loads schedule a backoff retry;
    // `bind()` clears the set alongside the cache to reset both at once.
    internal val inFlightMemberFetches = mutableSetOf<String>()

    // Exponential-backoff tier per group for roster reads that fail or return a
    // roster too incomplete to cache (e.g. empty while catch-up is still
    // materializing members). The tier is capped, while retries continue until
    // a roster lands or the group/account lifecycle ends, so Unknown cannot
    // become permanent after a finite retry budget.
    internal val memberFetchRetryBackoffTierByGroup = mutableMapOf<String, Int>()
    internal val memberFetchRetryJobsByGroup = mutableMapOf<String, Job>()
    internal val failedMemberFetches = mutableSetOf<String>()
    internal val terminalMemberFetches = mutableSetOf<String>()

    // A self-only direct roster gets one dedicated confirmation read before it
    // becomes authoritative. Keep this separate from the general backoff tier:
    // an earlier error or empty read must not consume the self-only grace retry.
    internal val selfOnlyDirectGraceRetryGroups = mutableSetOf<String>()

    // Widening member snapshots to every group makes the chat-list projection
    // much more useful, but the app should not start one roster FFI call per
    // group on large accounts. Keep at most one fetch in flight per group while
    // bounding simultaneous FFI/IO work across all groups.
    internal val memberFetchGate = Semaphore(MEMBER_FETCH_FANOUT)

    // Parsed markdown for each row's last-message preview, keyed by the exact
    // plaintext (tokens must always describe the text beside them — keying by
    // group or message id would go stale on edits). This is derived UI state
    // over the live rows, not a second store of protocol data: it's pruned to
    // the texts the current rows actually show (in
    // [schedulePendingPreviewParses]) and cleared on bind. A parse failure
    // caches the empty document, which renders as plaintext and stops the
    // row from re-parsing on every recompute.
    internal var previewTokensByText = mapOf<String, MarkdownDocumentFfi>()

    // Resolved media fallbacks for blank chat-list preview rows, keyed by the
    // last message id. Derived UI state over the live rows (pruned to ids still
    // on screen), not a protocol cache — same lifecycle as [previewTokensByText].
    internal var mediaPreviewFallbackByMessageId = mapOf<String, MediaPreviewFallback>()

    // Same single-state invariant as [inFlightMemberFetches], keyed by
    // preview plaintext: pending → in flight (here) → cached.
    internal val inFlightPreviewParses = mutableSetOf<String>()
    internal val inFlightMediaKindResolves = mutableSetOf<String>()
    internal val previewParseGate = Semaphore(PREVIEW_PARSE_FANOUT)
    internal val mediaKindResolveGate = Semaphore(MEDIA_KIND_RESOLVE_FANOUT)

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
    internal val memberCacheLifetime = StalenessGuard()

    /** Current authoritative member-cache token passed through loaders. */
    internal val memberCacheEpoch: Long
        get() = memberCacheLifetime.capture()
    internal var isCleared = false

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
                    chatRows.forEach { settleInvitationFromMdk(it) }
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

    internal fun memberSnapshotNeedsFetch(groupIdHex: String): Boolean = !memberCacheByGroup.containsKey(groupIdHex)

    /** Whether an authoritative roster is cached for [groupIdHex], whether or not the window retains its row. */
    internal fun hasMemberSnapshot(groupIdHex: String): Boolean = memberCacheByGroup.containsKey(groupIdHex)

    // Marmot's `set_group_archived` writes local state + saves but emits no
    // ProjectionUpdated event, so the chat-list snapshot stays stale until the
    // next account switch (issue: unarchive doesn't move chat out of archived
    // section). Callers in ConversationController forward the updated record
    // here via AppState so the chat list reflects the new archived flag.

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

    internal fun boundAccountIdHex(): String? {
        val ref = accountRef ?: return null
        return appState.accounts.firstOrNull { it.label == ref }?.accountIdHex
    }

    /** Projects one retained row with the live window's presentation, preview, actions and caches. */
    internal fun projectChatRow(
        authoritativeRow: ChatListRowFfi,
        activeAccountIdHex: String? = boundAccountIdHex() ?: appState.activeAccount?.accountIdHex,
    ): ChatListItem = rowProjector(activeAccountIdHex).retained(authoritativeRow)

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

    /** Reprojects a committed command for the same session even if its original view has retired. */
    internal fun refreshChatListActionProjection(owner: ChatListActionOwner) {
        if (chatListActionSessionIsCurrent(owner)) scheduleRecompute()
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

    private fun requestGroupProfiles(group: AppGroupRecordFfi) {
        appState.requestProfiles(
            listOfNotNull(group.welcomerAccountIdHex) + group.admins,
        )
    }

    internal fun requestChatRowProfiles(row: ChatListRowFfi) {
        row.lastMessage?.sender?.let(appState::requestProfile)
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

    internal val recomputeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val presentationLifetime = ChatListPresentationLifetime(recomputeScope) { recompute() }

    /** Keeps folded native updates synchronous while coalescing only their visible publication. */
    internal fun scheduleRecompute() = presentationLifetime.schedule()

    /** The window frame whose rows wait for the next visible projection. */
    @Volatile internal var stagedChatListFrame: ChatListFrameMark? = null

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
    internal fun ChatListWindowSet.currentFrameMarkIfLive(): ChatListFrameMark? =
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
        invitationReads.clear()
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
    internal fun recompute(scheduleBackgroundEnrichment: Boolean = true) {
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

    internal fun cancelMemberSnapshotRetry(groupIdHex: String) {
        memberFetchRetryJobsByGroup.remove(groupIdHex)?.cancel()
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
