package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ui.common.clampViewerPageIndex
import kotlinx.coroutines.flow.distinctUntilChanged

// One page of the full-screen media viewer. Unlike the original single-album
// viewer (one fixed messageIdHex + mine for the whole pager), each page now
// carries its own message context so the pager can span attachments from
// different messages — the cross-message gallery the shared-media grids open.
// The save/share/decrypt paths read the CURRENT page's descriptor. A page from another chat (the
// account-wide library, #2000) names its group, so equal message IDs in two chats stay distinct.
internal data class MediaViewerPage(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
    val mine: Boolean,
    val sender: String,
    val recordedAt: ULong,
    val groupIdHex: String? = null,
)

/** Exact visible-page identity handed to the external Android sharing boundary. */
internal data class MediaViewerShareRequest(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val reference: MediaAttachmentReferenceFfi,
    val mine: Boolean,
)

internal data class MediaViewerGallery(
    val pages: List<MediaViewerPage>,
    val startIndex: Int,
)

private data class MediaViewerPageKey(
    val messageIdHex: String,
    val attachmentIndex: Int,
    val groupIdHex: String?,
)

/** The pager's page identity: message slot, plus the chat for a page that names one. */
private fun MediaViewerPage.key(): MediaViewerPageKey = MediaViewerPageKey(messageIdHex, attachmentIndex, groupIdHex)

/** The saveable pager key; a single-chat page keeps its existing form, a cross-chat page adds its group. */
private fun MediaViewerPage.saveableKey(): String {
    val slot = "${messageIdHex.length}:$messageIdHex:$attachmentIndex"
    return groupIdHex?.let { "$slot:$it" } ?: slot
}

internal data class MediaViewerPagerSelection(
    val pagerState: PagerState,
    val currentPageIndex: Int,
    val currentPage: MediaViewerPage,
)

/** Preserves the selected logical attachment while an authoritative gallery replaces its references. */
@Composable
internal fun rememberMediaViewerPagerSelection(
    pages: List<MediaViewerPage>,
    startIndex: Int,
): MediaViewerPagerSelection {
    require(pages.isNotEmpty()) { "Media viewer pages must not be empty" }
    val initialPageIndex = clampViewerPageIndex(startIndex, pages.size)
    var visiblePageKey by remember { mutableStateOf(pages[initialPageIndex].key()) }
    var visiblePageIndex by remember { mutableIntStateOf(initialPageIndex) }
    val preservedIndex = pages.indexOfFirst { it.key() == visiblePageKey }
    val restoredIndex =
        if (preservedIndex >= 0) {
            preservedIndex
        } else {
            clampViewerPageIndex(visiblePageIndex, pages.size)
        }
    // Shared-media projection is asynchronous. Recreate the pager at the
    // preserved attachment as part of the same composition where the fallback
    // list expands, so neither content nor metadata can bind to the old numeric
    // index for a frame.
    val pagerState =
        key(pages) {
            rememberPagerState(
                initialPage = restoredIndex,
                pageCount = { pages.size },
            )
        }
    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val settledIndex = clampViewerPageIndex(page, pages.size)
                visiblePageIndex = settledIndex
                visiblePageKey = pages[settledIndex].key()
            }
    }
    val pagerPageIndex = clampViewerPageIndex(pagerState.currentPage, pages.size)
    val currentPageIndex =
        pages
            .indexOfFirst { it.key() == visiblePageKey }
            .takeIf { it >= 0 }
            ?: pagerPageIndex
    return MediaViewerPagerSelection(
        pagerState = pagerState,
        currentPageIndex = currentPageIndex,
        currentPage = pages[currentPageIndex],
    )
}

/** Renders pager pages with stable logical keys and exposes the settled page to accessibility. */
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
@Composable
internal fun StableMediaViewerPager(
    pages: List<MediaViewerPage>,
    selection: MediaViewerPagerSelection,
    modifier: Modifier,
    pagePositionDescription: String?,
    userScrollEnabled: Boolean,
    pageContent: @Composable (page: MediaViewerPage, isCurrent: Boolean) -> Unit,
) {
    val currentPageKey = selection.currentPage.key()
    val pagerModifier =
        if (pagePositionDescription == null) {
            modifier
        } else {
            modifier.semantics { stateDescription = pagePositionDescription }
        }
    val pageContentLayoutDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        HorizontalPager(
            state = selection.pagerState,
            modifier = pagerModifier,
            key = { page -> pages[clampViewerPageIndex(page, pages.size)].saveableKey() },
            userScrollEnabled = userScrollEnabled,
        ) { page ->
            val pageDescriptor = pages[clampViewerPageIndex(page, pages.size)]
            CompositionLocalProvider(LocalLayoutDirection provides pageContentLayoutDirection) {
                pageContent(pageDescriptor, pageDescriptor.key() == currentPageKey)
            }
        }
    }
}

/**
 * Select the gallery opened by an inline visual attachment.
 *
 * Visual messages use chronological message traversal with authored attachment slots.
 * The current message pages are merged when the asynchronous
 * shared projection has not caught up yet, which keeps optimistic own sends
 * openable.
 */
internal fun visualMediaViewerGallery(
    conversationVisualPages: List<MediaViewerPage>,
    messagePages: List<MediaViewerPage>,
    tappedAttachmentIndex: Int,
): MediaViewerGallery {
    val tappedPage =
        messagePages.firstOrNull { it.attachmentIndex == tappedAttachmentIndex }
            ?: messagePages.firstOrNull()
    return when {
        tappedPage == null -> MediaViewerGallery(conversationVisualPages, 0)
        else -> {
            val currentMessageId = tappedPage.messageIdHex
            val projectedKeys = conversationVisualPages.mapTo(HashSet()) { it.messageIdHex to it.attachmentIndex }
            val currentMessageFullyProjected =
                messagePages.all { (it.messageIdHex to it.attachmentIndex) in projectedKeys }
            val pages =
                if (currentMessageFullyProjected) {
                    conversationVisualPages
                } else {
                    val currentPages = messagePages
                    val otherPages = conversationVisualPages.filterNot { it.messageIdHex == currentMessageId }
                    val insertAt =
                        otherPages
                            .indexOfFirst { it.recordedAt > tappedPage.recordedAt }
                            .takeIf { it >= 0 }
                            ?: otherPages.size
                    otherPages.subList(0, insertAt) + currentPages + otherPages.subList(insertAt, otherPages.size)
                }
            val startIndex =
                pages
                    .indexOfFirst {
                        it.messageIdHex == currentMessageId && it.attachmentIndex == tappedAttachmentIndex
                    }.coerceAtLeast(0)
            MediaViewerGallery(pages, startIndex)
        }
    }
}
