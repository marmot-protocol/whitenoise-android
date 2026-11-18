package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState

/** The durable reading intent behind the conversation's transient list geometry. */
internal sealed interface ConversationScrollMode {
    data object FollowingTail : ConversationScrollMode

    data class ReadingHistory(
        val anchorMessageId: String?,
        val pixelOffset: Int,
    ) : ConversationScrollMode

    data class Restoring(
        val anchorMessageId: String?,
        val pixelOffset: Int,
    ) : ConversationScrollMode

    data class ProgrammaticJump(
        val targetMessageId: String?,
        val reason: ConversationScrollReason,
    ) : ConversationScrollMode
}

/** True while a coordinator command, not the reader, is moving the list. */
internal val ConversationScrollMode.ownsListMovement: Boolean
    get() = this is ConversationScrollMode.Restoring || this is ConversationScrollMode.ProgrammaticJump

internal enum class ConversationScrollReason {
    InitialAnchor,
    SavedRestore,
    LifecycleResume,
    ImeTransition,
    ViewportChange,
    NewMessage,
    ReactionLayout,
    BottomInput,
    Reply,
    Mention,
    NotificationTarget,
    Search,
    FocusMessage,
    MessageExpansion,
    ReadAloudFollow,
    UnreadStart,
    UnreadTail,
    JumpToNewest,
    Send,
}

internal enum class ConversationJumpToNewestOutcome {
    UnreadStart,
    Tail,
    Cancelled,
}

/** Stable logical anchor; [listIndex] is only the fallback when the ids are gone. */
internal data class ConversationScrollAnchor(
    val listIndex: Int,
    val pixelOffset: Int,
    val itemId: String?,
    val messageId: String?,
)

/** Captures the first visible logical message while excluding non-message structural rows. */
internal fun conversationScrollAnchor(
    listState: LazyListState,
    renderedItemIds: List<String>,
    renderedMessageIds: List<String>,
    trailingRowCount: Int = 0,
    timelineViewport: ConversationTimelineViewport? = null,
): ConversationScrollAnchor {
    // The transcript is reversed, so the lowest visible index is the newest row
    // on screen and is also what `scrollToItem` positions against the composer.
    // Anchoring on it keeps capture and restore describing the same edge.
    val timelineSize = renderedItemIds.size
    val visibleTimelineRow =
        (timelineViewport?.readingLayoutInfo() ?: listState.layoutInfo).visibleItemsInfo.firstOrNull { visible ->
            val timelineIndex =
                conversationTimelineIndexForListIndex(visible.index, timelineSize, trailingRowCount)
            timelineIndex in renderedItemIds.indices && timelineIndex in renderedMessageIds.indices
        }
    if (visibleTimelineRow != null) {
        val timelineIndex =
            conversationTimelineIndexForListIndex(visibleTimelineRow.index, timelineSize, trailingRowCount)
        return ConversationScrollAnchor(
            listIndex = visibleTimelineRow.index,
            pixelOffset = -visibleTimelineRow.offset,
            itemId = renderedItemIds[timelineIndex],
            messageId = renderedMessageIds[timelineIndex],
        )
    }
    return ConversationScrollAnchor(
        listIndex = listState.firstVisibleItemIndex,
        pixelOffset = listState.firstVisibleItemScrollOffset,
        itemId = null,
        messageId = null,
    )
}

internal data class ConversationScrollBookmark(
    val anchor: ConversationScrollAnchor,
    val settledMode: ConversationScrollMode,
    internal val intentRevision: Long,
)

/** Stable layout inputs that determine the transcript's visible viewport. */
internal data class ConversationForegroundGeometry(
    val viewportHeightPx: Int,
    val imeBottomPx: Int,
    val bottomChromeHeightPx: Int,
)

internal data class ConversationForegroundSnapshot(
    val scrollBookmark: ConversationScrollBookmark,
    val geometry: ConversationForegroundGeometry,
    val timelineStructure: ConversationTimelineStructure = ConversationTimelineStructure(emptyList(), 0),
)

internal class ConversationForegroundRestoreToken internal constructor(
    internal val revision: Long,
    internal val expectedImeVisible: Boolean,
)

internal data class ConversationTimelineStructure(
    val rowKeys: List<Pair<String, String>>,
    val olderHeaderCount: Int,
    val inlineTopErrorCount: Int = 0,
    val groupRecoveryCount: Int = 0,
)

internal data class ConversationInitialAnchorLayout(
    val viewportHeight: Int,
    val targetItemSize: Int?,
) {
    val isReady: Boolean
        get() = viewportHeight > 0 && targetItemSize != null
}

/** Measured tail and viewport geometry used to settle row or bottom-input changes. */
internal data class ConversationTailLayout(
    val lastRowHeightPx: Int?,
    val tailOffsetPx: Int?,
    val tailSizePx: Int?,
    val viewportEndOffsetPx: Int,
    val viewportStartOffsetPx: Int = 0,
    val beforeContentPaddingPx: Int = 0,
    val viewportSizePx: Int = viewportEndOffsetPx - viewportStartOffsetPx,
    val measuredTailKey: Any? = null,
) {
    val isReady: Boolean
        get() =
            lastRowHeightPx != null &&
                tailOffsetPx != null &&
                tailSizePx != null &&
                viewportEndOffsetPx > 0 &&
                viewportEndOffsetPx > viewportStartOffsetPx &&
                viewportSizePx > 0

    /**
     * In the reversed transcript, before-content padding occupies the
     * composer-adjacent scroll-axis start. The row clears that inset only at
     * [viewportStartOffsetPx] + [beforeContentPaddingPx], normally offset zero.
     */
    val tailClearsViewportStart: Boolean
        get() =
            isReady &&
                (
                    requireNotNull(tailOffsetPx) >= viewportStartOffsetPx + beforeContentPaddingPx ||
                        requireNotNull(tailSizePx) >= viewportSizePx
                )

    val bottomGeometry: ConversationTailBottomGeometry
        get() =
            ConversationTailBottomGeometry(
                viewportStartOffsetPx = viewportStartOffsetPx,
                viewportEndOffsetPx = viewportEndOffsetPx,
                beforeContentPaddingPx = beforeContentPaddingPx,
                viewportSizePx = viewportSizePx,
            )
}

internal enum class ConversationTailSettleTrigger {
    RowHeight,
    BottomGeometry,
}

internal data class ConversationTailBottomGeometry(
    val viewportStartOffsetPx: Int,
    val viewportEndOffsetPx: Int,
    val beforeContentPaddingPx: Int,
    val viewportSizePx: Int,
)

/**
 * Baselines layout state at the hidden initial anchor. Startup materialization
 * is not a post-open change and must not trigger another visible scroll.
 */
internal class ConversationPostInitialReanchorGate {
    private var structure: ConversationTimelineStructure? = null
    private var viewportHeight: Int? = null

    /** Records the hidden initial structure and viewport as the first stable baseline. */
    fun commit(
        structure: ConversationTimelineStructure,
        viewportHeight: Int,
    ) {
        this.structure = structure
        this.viewportHeight = viewportHeight
    }

    /** Returns whether the latest row structure differs from the previously observed structure. */
    fun onStructure(structure: ConversationTimelineStructure): Boolean {
        val previous = this.structure ?: return false
        this.structure = structure
        return structure != previous
    }

    /** Returns whether the usable viewport height differs from the previously observed height. */
    fun onViewportHeight(viewportHeight: Int): Boolean {
        val previous = this.viewportHeight ?: return false
        this.viewportHeight = viewportHeight
        return viewportHeight != previous
    }
}

internal class ConversationScrollIntentToken internal constructor(
    internal val revision: Long,
)
