package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme

/** Real reversed timeline rows with stable synthetic records and no external transport. */
open class ConversationRowSpacingFixtures : GroupSystemReactionTestFixtures() {
    protected fun message(
        index: Int,
        seconds: ULong = index.toULong(),
        mine: Boolean = false,
        text: String = "Message $index",
    ): TimelineMessage {
        val source = fileTimelineMessage(index, "", mine = mine, caption = text, attachments = emptyList())
        return source.copy(
            record = source.record.copy(recordedAt = BASE_TIME + seconds, receivedAt = BASE_TIME + seconds),
            projected = source.projected!!.copy(timelineAt = BASE_TIME + seconds, receivedAt = BASE_TIME + seconds),
        )
    }

    protected fun summary(type: String = "group_avatar_changed"): TimelineMessage {
        val source = activity()
        return source.copy(
            record = source.record.copy(recordedAt = BASE_TIME, receivedAt = BASE_TIME),
            projected =
                source.projected!!.copy(
                    timelineAt = BASE_TIME,
                    receivedAt = BASE_TIME,
                    groupSystem = source.projected!!.groupSystem!!.copy(systemType = type, name = "Evening readers"),
                ),
        )
    }

    /** Neighbor context is chronological; only the actual list emission is reversed, as in production. */
    @Composable
    @Suppress("FunctionNaming")
    protected fun Transcript(
        rows: List<TimelineMessage>,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        firstUnreadId: String? = null,
        controller: ConversationController = pollController,
        height: Dp = 620.dp,
        onMenuChange: (TimelineMessage, Boolean) -> Unit = { _, _ -> },
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale) {
                Surface(Modifier.width(if (rtl) 280.dp else 360.dp).testTag("compact-transcript")) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(height),
                        reverseLayout = true,
                        verticalArrangement = CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT,
                    ) {
                        rows.indices.reversed().forEach { index ->
                            val row = rows[index]
                            item(key = row.id) {
                                RealPollMessage(
                                    row,
                                    menuOpen = false,
                                    onMenuChange = { open -> onMenuChange(row, open) },
                                    older = rows.getOrNull(index - 1) ?: message(250, 0uL),
                                    newer = rows.getOrNull(index + 1),
                                    firstUnreadId = firstUnreadId,
                                    controller = controller,
                                    modifier = Modifier.testTag("host:${row.record.messageIdHex}"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    protected companion object {
        const val BASE_TIME: ULong = 1_700_006_400uL
    }
}
