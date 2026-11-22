package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.HistoryImportProvenanceFfi
import dev.ipf.marmotkit.HistoryImportSourceFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.messages.swipeTestSurface
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The import line closes the imported rows above it, so it never sits between a day heading and that day's rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class TimelineRowImportedHistoryOrderTest {
    @get:Rule val compose = createComposeRule()

    /** A live row on a later day than the imported rows draws the import line above its day heading. */
    @Test
    fun theImportLineComesBeforeTheNextDaysHeading() {
        val surface =
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = true, media = false)
        val imported = surface.item.onDay(1, "imported").importedFrom("Laptop")
        val live = surface.item.onDay(3, "live")

        compose.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) { Row(live, imported, surface.appState, surface.controller) }
            }
        }

        val line = compose.onNodeWithTag(IMPORTED_HISTORY_BOUNDARY_TAG).fetchSemanticsNode().boundsInRoot
        val day = compose.onNodeWithTag(DAY_HEADING_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the import line ($line) must end above the day heading ($day)", line.bottom <= day.top)
    }

    /** Renders the production row for [item] after [older]. */
    @androidx.compose.runtime.Composable
    @Suppress("FunctionNaming")
    private fun Row(
        item: TimelineMessage,
        older: TimelineMessage,
        appState: WhiteNoiseAppState,
        controller: ConversationController,
    ) {
        TimelineRow(
            item = item,
            older = older,
            newer = null,
            transcriptLocale = Locale.US,
            entryUnreadCount = 0,
            entryUnreadDividerRetired = true,
            entryFirstUnreadMessageId = null,
            onMeasured = { _, _ -> },
            appState = appState,
            controller = controller,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = false,
            selected = false,
            onToggleSelection = {},
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = emptyList(),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = false,
            onActionMenuOpenChange = {},
            onQuickReactionsSave = {},
            onReplyPreviewClick = {},
            composerGate = ComposerGate.COMPOSER,
            collapseLongMessages = false,
        )
    }

    /** This row as message [id], recorded at noon UTC on [day] days after the epoch. */
    private fun TimelineMessage.onDay(
        day: Int,
        id: String,
    ): TimelineMessage {
        val at = day.toULong() * SECONDS_PER_DAY + SECONDS_PER_DAY / 2uL
        return copy(
            id = "msg:$id",
            record = record.copy(messageIdHex = id, recordedAt = at),
            projected = requireNotNull(projected).copy(messageIdHex = id, timelineAt = at),
        )
    }

    /** This row as one MDK imported from the account's device named [label]. */
    private fun TimelineMessage.importedFrom(label: String): TimelineMessage =
        copy(
            projected =
                requireNotNull(projected).copy(
                    importedFrom = HistoryImportSourceFfi("slot", label),
                    historyImportProvenance = HistoryImportProvenanceFfi.Device("slot"),
                ),
        )

    private companion object {
        const val DAY_HEADING_TAG = "conversation.date.inline"
        const val SECONDS_PER_DAY = 86_400uL
    }
}
