package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.ChatHistoryImportStateFfi
import dev.ipf.marmotkit.HistoryImportProvenanceFfi
import dev.ipf.marmotkit.HistoryImportSourceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.state.ImportedHistorySource
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A timeline row labels where imported history ends and says when earlier messages are still arriving. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class ImportedHistoryTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A live row after imported rows is preceded by the label naming their device. */
    @Test
    fun aLiveRowAfterImportedRowsIsLabelled() {
        markers(older = imported("Tablet"), item = live(), state = null)

        compose.onNodeWithText(context.getString(R.string.history_imported_from_device, "Tablet")).assertExists()
        compose.onNodeWithTag(HISTORY_TRANSFER_NOTICE_TAG).assertDoesNotExist()
    }

    /** Rows inside the imported run, and live rows after live rows, draw nothing. */
    @Test
    fun rowsInsideARunDrawNothing() {
        markers(older = imported("Tablet"), item = imported("Tablet"), state = null)
        compose.onNodeWithTag(IMPORTED_HISTORY_BOUNDARY_TAG).assertDoesNotExist()
    }

    /** A source MDK has no name for is called another device. */
    @Test
    fun anUnnamedSourceIsCalledAnotherDevice() {
        markers(older = imported(""), item = live(), state = null)

        compose.onNodeWithText(context.getString(R.string.history_imported_from_another_device)).assertExists()
    }

    /** Each source has its own wording, and a backup is not worded as a device. */
    @Test
    fun aBackupHasItsOwnWording() {
        compose.setContent { WhiteNoiseTheme { ImportedHistoryBoundary(ImportedHistorySource.Backup) } }

        compose.onNodeWithText(context.getString(R.string.history_restored_from_backup)).assertExists()
    }

    /** At the start of the loaded history a transfer under way is announced; elsewhere it is not repeated. */
    @Test
    fun aTransferIsAnnouncedOnlyAtTheStartOfHistory() {
        val receiving = ChatHistoryImportStateFfi.Receiving(false)
        markers(older = null, item = live(), state = receiving, atHistoryStart = true)
        compose.onNodeWithText(context.getString(R.string.history_receiving)).assertExists()
    }

    /** A row that is not at the start of history does not repeat the transfer notice. */
    @Test
    fun aRowInsideTheTimelineDoesNotRepeatTheNotice() {
        markers(older = live("a"), item = live("b"), state = ChatHistoryImportStateFfi.Receiving(false))

        compose.onNodeWithTag(HISTORY_TRANSFER_NOTICE_TAG).assertDoesNotExist()
    }

    /** A failed attempt MDK will retry has its own wording. */
    @Test
    fun aRetryHasItsOwnWording() {
        markers(older = null, item = live(), state = ChatHistoryImportStateFfi.Receiving(true), atHistoryStart = true)

        compose.onNodeWithText(context.getString(R.string.history_retrying)).assertExists()
    }

    /** A settled import shows no transfer notice. */
    @Test
    fun aSettledImportShowsNoTransferNotice() {
        val settled = ChatHistoryImportStateFfi.Imported(HistoryImportSourceFfi("slot", "Tablet"), 1u)
        markers(older = null, item = live(), state = settled, atHistoryStart = true)

        compose.onNodeWithTag(HISTORY_TRANSFER_NOTICE_TAG).assertDoesNotExist()
    }

    /** Without a provided import state, as in builds without linked devices, a row draws nothing extra. */
    @Test
    fun withoutAnImportStateARowDrawsNothing() {
        compose.setContent { WhiteNoiseTheme { ImportedHistoryMarkers(null, live(), atHistoryStart = true) } }

        compose.onNodeWithTag(HISTORY_TRANSFER_NOTICE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(IMPORTED_HISTORY_BOUNDARY_TAG).assertDoesNotExist()
    }

    /** Renders the markers of [item] after [older] with MDK's import [state] for the chat. */
    private fun markers(
        older: TimelineMessage?,
        item: TimelineMessage,
        state: ChatHistoryImportStateFfi?,
        atHistoryStart: Boolean = false,
    ) {
        compose.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalChatHistoryImport provides { state }) {
                    ImportedHistoryMarkers(older, item, atHistoryStart)
                }
            }
        }
    }

    /** A live row. */
    private fun live(id: String = "live"): TimelineMessage = message(timelineRecord(messageId = id, timelineAt = 1uL))

    /** A row imported from a device carrying [label]. */
    private fun imported(label: String): TimelineMessage =
        message(
            timelineRecord(messageId = "imported-$label", timelineAt = 1uL).copy(
                importedFrom = HistoryImportSourceFfi("slot", label),
                historyImportProvenance = HistoryImportProvenanceFfi.Device("slot"),
            ),
        )

    /** Wraps [record] as the timeline row the conversation renders. */
    private fun message(record: dev.ipf.marmotkit.TimelineMessageRecordFfi) =
        TimelineMessage(
            id = "msg:${record.messageIdHex}",
            record = TimelineProjector.toAppMessageRecord(record),
            status = MessageStatus.Received,
            projected = record,
        )
}
