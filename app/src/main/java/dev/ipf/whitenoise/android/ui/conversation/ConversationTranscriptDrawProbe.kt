package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage

/** Test-only content evidence from the actual transcript's drawContent boundary; never logged or persisted. */
internal object ConversationTranscriptDrawProbe {
    @Volatile
    var observer: ((ConversationTranscriptDraw) -> Unit)? = null

    /** Freezes painted rows during composition so a newer controller publication cannot alter draw evidence. */
    fun composedRows(rows: List<TimelineMessage>): List<TimelineMessage> =
        if (observer != null && (BuildConfig.DEBUG || BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS)) {
            rows.toList()
        } else {
            rows
        }

    /** Captures the composition's bounded rows only when an instrumented test explicitly installs an observer. */
    fun drawn(
        controller: ConversationController,
        rows: List<TimelineMessage>,
        layout: LazyListLayoutInfo,
    ) {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) return
        val callback = observer ?: return
        callback(
            ConversationTranscriptDraw(
                controller = controller,
                messageIds = rows.map { it.record.messageIdHex },
                visibleItemKeys = layout.visibleItemsInfo.map { it.key },
                visibleItemOffsets = layout.visibleItemsInfo.associate { it.key to it.offset },
            ),
        )
    }
}

/** In-memory fixture content; keys never enter diagnostics, traces or public reports. */
internal data class ConversationTranscriptDraw(
    val controller: ConversationController,
    val messageIds: List<String>,
    val visibleItemKeys: List<Any>,
    val visibleItemOffsets: Map<Any, Int>,
)
