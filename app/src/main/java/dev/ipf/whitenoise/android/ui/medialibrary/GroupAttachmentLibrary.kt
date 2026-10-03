package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.AttachmentHistoryCursor
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Starts a fresh screen owner after resume; stopping releases pages, native handles and observers. */
@Composable
internal fun rememberGroupAttachmentPager(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): GroupAttachmentPager? {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val account = attachmentLibraryAccount(controller.boundAccountRef, appState.activeAccountRef)
    if (account == null || !lifecycleState.isAtLeast(Lifecycle.State.STARTED)) return null
    val group = controller.group.groupIdHex
    val generation = appState.runtimeGeneration
    val runtime = remember(generation) { appState.marmot() }
    val pager =
        remember(controller, account, group, generation) {
            GroupAttachmentPager(
                reader =
                    object : GroupAttachmentReader {
                        /** Native async discovery is already off the host thread. */
                        override suspend fun page(cursor: AttachmentHistoryCursor?): AttachmentPageReadFfi =
                            runtime.attachmentHistoryPage(account, group, 100u, cursor)

                        /** The retained runtime and account are identical to the page owner. */
                        override suspend fun version() = runtime.attachmentHistoryVersion(account, group)
                    },
                isCurrent = {
                    appState.runtimeGeneration == generation &&
                        appState.activeAccountRef == account &&
                        controller.acceptsConversationActionOwner(account, group)
                },
            )
        }
    DisposableEffect(pager) { onDispose { pager.close() } }
    val wakeups = remember(pager) { Channel<Unit>(Channel.CONFLATED) }
    // Block/retention/local visibility updates also need a probe even when the loaded chat window is unchanged.
    LaunchedEffect(pager, controller.timeline, controller.deletedMessageIds, appState.runtimeMirrors.blocks.users) {
        wakeups.send(Unit)
    }
    LaunchedEffect(pager) {
        for (ignored in wakeups) pager.refresh()
    }
    LaunchedEffect(pager) {
        while (true) {
            observeAttachmentChanges(runtime, account, group, wakeups)
            delay(ATTACHMENT_OBSERVER_RETRY_MS)
        }
    }
    return pager
}

/** A still-mounted controller must never read its former account after the active account switches. */
internal fun attachmentLibraryAccount(
    bound: String?,
    active: String?,
): String? = bound?.takeIf { it == active }

/** Keeps acquisition and destruction on the same dispatcher so cancellation cannot drop an owned handle. */
private suspend fun observeAttachmentChanges(
    runtime: MarmotInterface,
    account: String,
    group: String,
    wakeups: Channel<Unit>,
) = withContext(Dispatchers.IO) {
    val subscription = runCatchingCancellable { runtime.subscribeEvents() }.getOrNull()
    try {
        // Subscribe before probing so a mutation during initial discovery cannot be lost.
        wakeups.send(Unit)
        if (subscription != null) {
            runCatchingCancellable {
                while (true) {
                    val event = subscription.next() ?: break
                    if (attachmentProjectionTouched(event, account, group)) wakeups.send(Unit)
                }
            }
        }
    } finally {
        subscription?.let { withContext(NonCancellable) { runCatching { it.destroy() } } }
    }
}

private const val ATTACHMENT_OBSERVER_RETRY_MS = 5_000L

/** Projection wakeups are matched outside the bounded chat window, including old-row invalidations. */
internal fun attachmentProjectionTouched(
    event: MarmotEventFfi,
    account: String,
    group: String,
): Boolean {
    val update = (event as? MarmotEventFfi.ProjectionUpdated)?.update ?: return false
    return update.accountLabel == account && update.update.groupIdHex == group
}

/** Maps accepted native categories to presentation, retaining MDK order, authored indexes and source time. */
internal fun attachmentLibraryTiles(
    entries: List<AttachmentEntryFfi>,
    accountId: String?,
): SharedMediaTiles {
    val visuals = mutableListOf<SharedMediaTile>()
    val voice = mutableListOf<SharedMediaRow>()
    val files = mutableListOf<SharedMediaRow>()
    entries.forEach { entry ->
        val attachment = entry.attachment as? MediaAttachmentOutcomeFfi.Accepted ?: return@forEach
        val index = attachment.attachmentIndex.toInt()
        val mine = entry.sender.equals(accountId, ignoreCase = true)
        when (entry.category) {
            AttachmentCategoryFfi.IMAGE, AttachmentCategoryFfi.VIDEO ->
                visuals +=
                    SharedMediaTile(
                        entry.messageIdHex,
                        index,
                        attachment.reference,
                        mine,
                        entry.timelineAt,
                        entry.sender,
                        isVideo = entry.category == AttachmentCategoryFfi.VIDEO,
                    )
            AttachmentCategoryFfi.AUDIO -> voice += entry.libraryRow(attachment, mine)
            AttachmentCategoryFfi.FILE -> files += entry.libraryRow(attachment, mine)
            AttachmentCategoryFfi.REJECTED -> Unit
        }
    }
    val images = visuals.filterNot { it.isVideo }
    val videos = visuals.filter { it.isVideo }
    return SharedMediaTiles(
        visuals,
        images,
        videos,
        voice,
        files,
        emptyList(),
        canonicalMediaSections(images) { it.recordedAt },
        canonicalMediaSections(videos) { it.recordedAt },
        canonicalMediaSections(voice) { it.recordedAt },
        canonicalMediaSections(files) { it.recordedAt },
        emptyList(),
        voice.isNotEmpty() || files.isNotEmpty(),
        visualSections = canonicalMediaSections(visuals) { it.recordedAt },
    )
}

/** Audio and document rows retain the same authored source slot as visual tiles. */
private fun AttachmentEntryFfi.libraryRow(
    attachment: MediaAttachmentOutcomeFfi.Accepted,
    mine: Boolean,
) = SharedMediaRow(messageIdHex, attachment.attachmentIndex.toInt(), attachment.reference, mine, timelineAt, sender)

/** Consecutive month runs preserve native order even when canonical order and display time disagree. */
private fun <T> canonicalMediaSections(
    items: List<T>,
    timestamp: (T) -> ULong,
): List<MediaMonthSection<T>> {
    val sections = mutableListOf<MediaMonthSection<T>>()
    var start = 0
    while (start < items.size) {
        val month = monthKeyForMedia(timestamp(items[start]))
        var end = start + 1
        while (end < items.size && monthKeyForMedia(timestamp(items[end])) == month) end++
        sections += MediaMonthSection(month, items.subList(start, end), "$month-$start")
        start = end
    }
    return sections
}
