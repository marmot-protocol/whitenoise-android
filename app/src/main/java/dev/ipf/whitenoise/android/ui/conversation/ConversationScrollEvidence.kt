package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/** One measured lazy-list item in a conversation viewport evidence snapshot. */
internal data class ConversationVisibleItemEvidence(
    val index: Int,
    val key: String,
    val offsetPx: Int,
    val sizePx: Int,
)

/** Immutable viewport evidence emitted only when a diagnostic sink is installed. */
internal data class ConversationViewportEvidence(
    val captureRevision: Long,
    val accountRef: String?,
    val mode: ConversationScrollMode,
    val anchor: ConversationScrollAnchor,
    val viewportStartOffsetPx: Int,
    val viewportEndOffsetPx: Int,
    val viewportHeightPx: Int,
    val canScrollForward: Boolean,
    val visibleItems: List<ConversationVisibleItemEvidence>,
)

/** A write requested through the conversation's sole lazy-list writer. */
internal data class ConversationScrollWriteEvidence(
    val animated: Boolean,
    val index: Int,
    val offsetPx: Int,
)

/**
 * Opt-in evidence sink for deterministic conversation viewport regressions.
 *
 * Production leaves this local unset, so observation performs no list reads or
 * callbacks in the normal conversation path. Snapshots contain stable message
 * identifiers and must remain in-memory test evidence rather than logs.
 */
internal interface ConversationScrollEvidenceSink {
    /** Optional test-owned revision whose increments request a phase-bound viewport sample. */
    val viewportCaptureRevision: State<Long>?
        get() = null

    /** Receives one measured production viewport without persisting its identifiers. */
    fun onViewport(snapshot: ConversationViewportEvidence)

    /** Receives one command at the list writer boundary. */
    fun onWrite(write: ConversationScrollWriteEvidence)
}

/** Optional diagnostic observer; null keeps production scrolling free of test callbacks. */
internal val LocalConversationScrollEvidenceSink =
    staticCompositionLocalOf<ConversationScrollEvidenceSink?> { null }
