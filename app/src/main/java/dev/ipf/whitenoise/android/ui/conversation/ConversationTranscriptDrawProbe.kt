package dev.ipf.whitenoise.android.ui.conversation

import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage

/** Test-only content evidence from recorded transcript layers and actual Android root draws; never persisted. */
internal object ConversationTranscriptDrawProbe {
    @Volatile
    var observer: ((ConversationTranscriptDraw) -> Unit)? = null
        set(value) {
            field = value
            lastPainted = null
            rootFrame = null
            if (value == null) compositionRowsOverride = null
        }

    /** Freezes production composition for negative controls without changing the controller. */
    var compositionRowsOverride: ((ConversationController, List<TimelineMessage>) -> List<TimelineMessage>)? = null
    private var lastPainted: ConversationTranscriptDraw? = null
    private var rootFrame: RootFrame? = null

    /** A hidden or disposed route has no reusable visible transcript layer in subsequent root traversals. */
    fun retire(controller: ConversationController) {
        if (lastPainted?.controller === controller) lastPainted = null
        if (rootFrame?.painted?.controller === controller) rootFrame?.painted = null
    }

    /** Leaves ordinary builds unchanged; an explicitly installed test can paint an older bounded composition. */
    fun rowsForComposition(controller: ConversationController, rows: List<TimelineMessage>): List<TimelineMessage> =
        if (isObserving()) compositionRowsOverride?.invoke(controller, rows) ?: rows else rows

    /** Freezes composed rows so newer controller publications cannot alter painted-layer evidence. */
    fun composedRows(rows: List<TimelineMessage>): List<TimelineMessage> = if (isObserving()) rows.toList() else rows

    /** Starts one real OnDraw traversal, retaining cached layer evidence if the transcript is not re-recorded. */
    fun beginRootDraw(): (() -> Unit)? {
        val callback = observer
        if (!isObserving() || callback == null) return null
        val frame = RootFrame(lastPainted, SystemClock.uptimeMillis())
        rootFrame = frame
        return {
            if (rootFrame === frame) rootFrame = null
            if (observer === callback) {
                frame.painted?.let { callback(it.copy(rootDrawAtUptimeMs = frame.atUptimeMs)) }
            }
        }
    }

    /** Records what drawContent actually painted, even if a later root traversal reuses its display list. */
    fun drawn(
        controller: ConversationController,
        rows: List<TimelineMessage>,
        layout: LazyListLayoutInfo,
    ) {
        if (!isObserving()) return
        val evidence =
            ConversationTranscriptDraw(
                controller = controller,
                messageIds = rows.map { it.record.messageIdHex },
                visibleItemKeys = layout.visibleItemsInfo.map { it.key },
                visibleItemOffsets = layout.visibleItemsInfo.associate { it.key to it.offset },
            )
        lastPainted = evidence
        rootFrame?.painted = evidence
    }

    /** No records, keys, allocations or callbacks are produced without an admitted test observer. */
    private fun isObserving(): Boolean =
        (BuildConfig.DEBUG || BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) && observer != null

    /** One traversal owns its evidence even if its posted completion runs after a subsequent traversal. */
    private class RootFrame(var painted: ConversationTranscriptDraw?, val atUptimeMs: Long)
}

/** In-memory fixture content; keys never enter diagnostics, traces or public reports. */
internal data class ConversationTranscriptDraw(
    val controller: ConversationController,
    val messageIds: List<String>,
    val visibleItemKeys: List<Any>,
    val visibleItemOffsets: Map<Any, Int>,
    val rootDrawAtUptimeMs: Long = 0L,
)
