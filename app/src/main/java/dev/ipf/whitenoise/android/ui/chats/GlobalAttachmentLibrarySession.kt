package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.ipf.marmotkit.AccountAttachmentCursor
import dev.ipf.marmotkit.AccountAttachmentEntryFfi
import dev.ipf.marmotkit.AccountAttachmentPageReadFfi
import dev.ipf.marmotkit.AccountAttachmentQueryFfi
import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentRoleFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.core.MessageSearchConstraints
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.medialibrary.ACCOUNT_ATTACHMENT_PAGE_LIMIT
import dev.ipf.whitenoise.android.ui.medialibrary.AccountAttachmentPager
import dev.ipf.whitenoise.android.ui.medialibrary.AccountAttachmentReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/** The most IDs one native selection list accepts; a larger selection is narrowed on the host instead. */
private const val NATIVE_SELECTION_LIMIT = 100

private const val MILLIS_PER_SECOND = 1_000L
private const val LIBRARY_OBSERVER_RETRY_MS = 5_000L

/**
 * What the Files & media library lists (#2000). [groups] is null for every chat, and empty when the
 * chat, folder or chat-type filters match no chat at all. AND across fields, OR within one, as in search.
 */
internal data class GlobalAttachmentLibraryQuery(
    val groups: Set<String>?,
    val senders: Set<String> = emptySet(),
    val minTimelineAt: ULong? = null,
    val maxTimelineAt: ULong? = null,
    val kinds: Set<GlobalSearchContentKind> = setOf(GlobalSearchContentKind.ANY_ATTACHMENT),
    val needle: String = "",
) {
    /** True when no attachment can match, so the library shows its empty state without a native read. */
    val matchesNothing: Boolean get() = groups?.isEmpty() == true || kinds.isEmpty()

    /**
     * The native query. Selections over MDK's per-list limit are sent as "all" and narrowed by
     * [admits] instead, which keeps each read bounded at the same cost.
     */
    fun toFfi(): AccountAttachmentQueryFfi =
        AccountAttachmentQueryFfi(
            groups = groups?.takeIf { it.size <= NATIVE_SELECTION_LIMIT }?.sorted().orEmpty(),
            senders = senders.takeIf { it.size <= NATIVE_SELECTION_LIMIT }?.sorted().orEmpty(),
            minTimelineAt = minTimelineAt,
            maxTimelineAt = maxTimelineAt,
        )

    /**
     * Whether one native slot belongs in the library: a shared, accepted attachment of a selected kind,
     * in a selected chat and from a selected sender, whose file name contains the typed text. Inline
     * emoji artwork, rejected slots and metadata placeholders are never listed.
     */
    @Suppress("ReturnCount") // A non-accepted slot and a rejected category each end admission early.
    fun admits(entry: AccountAttachmentEntryFfi): Boolean {
        val accepted = entry.entry.attachment as? MediaAttachmentOutcomeFfi.Accepted ?: return false
        val kind = libraryContentKind(entry.entry.category) ?: return false
        return !entry.metadataLimited &&
            entry.entry.role == AttachmentRoleFfi.SHARED &&
            (GlobalSearchContentKind.ANY_ATTACHMENT in kinds || kind in kinds) &&
            (groups == null || entry.groupIdHex.lowercase(Locale.ROOT) in groups) &&
            (senders.isEmpty() || entry.entry.sender.lowercase(Locale.ROOT) in senders) &&
            (
                needle.isEmpty() ||
                    accepted.reference.fileName
                        .lowercase(Locale.ROOT)
                        .contains(needle)
            )
    }
}

/** What the search screen hands the library: its listed items, view kinds and native session. */
internal data class GlobalAttachmentLibraryContent(
    val items: List<GlobalAttachmentItem>,
    val kinds: Set<GlobalSearchContentKind>,
    val loading: Boolean,
    val pager: AccountAttachmentPager?,
)

/** The search content kind a native category is filed under, or null for a rejected slot. */
internal fun libraryContentKind(category: AttachmentCategoryFfi): GlobalSearchContentKind? =
    when (category) {
        AttachmentCategoryFfi.IMAGE, AttachmentCategoryFfi.VIDEO -> GlobalSearchContentKind.IMAGES_VIDEO
        AttachmentCategoryFfi.AUDIO -> GlobalSearchContentKind.VOICE_AUDIO
        AttachmentCategoryFfi.FILE -> GlobalSearchContentKind.FILES_DOCUMENTS
        AttachmentCategoryFfi.REJECTED -> null
    }

/**
 * Builds the library query from the search screen's state. [scopedChatIds] is null when no chat,
 * folder or chat-type filter is set. The typed text matches file names only; message bodies need
 * MDK's search contract, which this browse-first library does not use.
 */
internal fun globalAttachmentLibraryQuery(
    scopedChatIds: Collection<String>?,
    constraints: MessageSearchConstraints?,
    kinds: Set<GlobalSearchContentKind>,
    rawQuery: String,
): GlobalAttachmentLibraryQuery {
    val bounds = constraints?.dateBounds
    return GlobalAttachmentLibraryQuery(
        groups = scopedChatIds?.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) },
        senders = constraints?.senderIds.orEmpty(),
        minTimelineAt = bounds?.let { (it.startEpochMillisInclusive / MILLIS_PER_SECOND).coerceAtLeast(0L).toULong() },
        maxTimelineAt =
            bounds?.let { ((it.endEpochMillisExclusive - 1) / MILLIS_PER_SECOND).coerceAtLeast(0L).toULong() },
        kinds = kinds,
        needle = rawQuery.trim().lowercase(Locale.ROOT),
    )
}

/** The library item for one admitted slot, titled as its chat row is, or null for a non-accepted slot. */
internal fun AccountAttachmentEntryFfi.toLibraryItem(chatTitle: (String) -> String): GlobalAttachmentItem? {
    val accepted = entry.attachment as? MediaAttachmentOutcomeFfi.Accepted ?: return null
    val reference = accepted.reference
    val mediaType =
        reference.mediaType.ifBlank {
            when (entry.category) {
                AttachmentCategoryFfi.IMAGE -> "image/*"
                AttachmentCategoryFfi.VIDEO -> "video/*"
                AttachmentCategoryFfi.AUDIO -> "audio/*"
                else -> "application/octet-stream"
            }
        }
    return GlobalAttachmentItem(
        groupIdHex = groupIdHex,
        chatTitle = chatTitle(groupIdHex),
        messageIdHex = entry.messageIdHex,
        attachmentIndex = accepted.attachmentIndex.toInt(),
        mediaType = mediaType,
        label = reference.fileName.ifBlank { mediaType },
        timelineAt = entry.timelineAt,
        sender = entry.sender,
        sourceMessageIdHex = entry.sourceMessageIdHex,
        reference = reference,
    )
}

/**
 * Opens one library session for [query] while the screen is started, and closes it with every native
 * handle on stop, query change, account switch or runtime replacement. Projection updates for the
 * account and the earliest retention deadline the pages reported both trigger a change probe.
 */
@Composable
internal fun rememberGlobalAttachmentPager(
    appState: WhiteNoiseAppState,
    account: String?,
    query: GlobalAttachmentLibraryQuery,
    enabled: Boolean = true,
): AccountAttachmentPager? {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = account?.takeIf { enabled && it == appState.activeAccountRef }
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    if (active == null || query.matchesNothing || !started) return null
    val generation = appState.runtimeGeneration
    val runtime = remember(generation) { appState.marmot() }
    val pager =
        remember(active, query, generation) {
            val nativeQuery = query.toFfi()
            AccountAttachmentPager(
                reader =
                    object : AccountAttachmentReader {
                        /** Native async discovery is already off the host thread. */
                        override suspend fun page(cursor: AccountAttachmentCursor?): AccountAttachmentPageReadFfi {
                            val limit = ACCOUNT_ATTACHMENT_PAGE_LIMIT
                            return runtime.accountAttachmentHistoryPage(active, nativeQuery, limit, cursor)
                        }

                        /** The retained runtime and account are identical to the page owner. */
                        override suspend fun version() = runtime.accountAttachmentHistoryVersion(active)
                    },
                admits = query::admits,
                isCurrent = { appState.runtimeGeneration == generation && appState.activeAccountRef == active },
            )
        }
    DisposableEffect(pager) { onDispose { pager.close() } }
    val wakeups = remember(pager) { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(pager, appState.runtimeMirrors.blocks.users) { wakeups.send(Unit) }
    LaunchedEffect(pager) {
        for (ignored in wakeups) pager.refresh()
    }
    LaunchedEffect(pager) {
        while (true) {
            observeLibraryChanges(runtime, active, wakeups)
            delay(LIBRARY_OBSERVER_RETRY_MS)
        }
    }
    val expiry = pager.state.nextExpiry
    LaunchedEffect(pager, expiry) {
        val deadline = expiry ?: return@LaunchedEffect
        delay((deadline.toLong() * MILLIS_PER_SECOND - System.currentTimeMillis()).coerceAtLeast(0L))
        wakeups.send(Unit)
    }
    return pager
}

/** Subscribes before probing so a change during the first read is not lost; any chat of the account counts. */
private suspend fun observeLibraryChanges(
    runtime: MarmotInterface,
    account: String,
    wakeups: Channel<Unit>,
) = withContext(Dispatchers.IO) {
    val subscription = runCatchingCancellable { runtime.subscribeEvents() }.getOrNull()
    try {
        wakeups.send(Unit)
        if (subscription != null) {
            runCatchingCancellable {
                while (true) {
                    val event = subscription.next() ?: break
                    if (libraryProjectionTouched(event, account)) wakeups.send(Unit)
                }
            }
        }
    } finally {
        subscription?.let { withContext(NonCancellable) { runCatching { it.destroy() } } }
    }
}

/** "You" for the active account's own attachments, otherwise the sender's profile name. */
internal fun librarySenderLabel(
    appState: WhiteNoiseAppState,
    sender: String,
    you: String,
): String {
    if (sender.equals(appState.activeAccount?.accountIdHex, ignoreCase = true)) return you
    return appState.displayName(sender)
}

/** Any projection update for the account can add, hide or expire a listed attachment. */
internal fun libraryProjectionTouched(
    event: MarmotEventFfi,
    account: String,
): Boolean = (event as? MarmotEventFfi.ProjectionUpdated)?.update?.accountLabel == account
