package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentCollectionOperation
import dev.ipf.whitenoise.android.state.CollectionChunkState
import dev.ipf.whitenoise.android.state.CollectionItem
import dev.ipf.whitenoise.android.state.CollectionItemKind
import dev.ipf.whitenoise.android.state.CollectionItemState
import dev.ipf.whitenoise.android.state.PlannedCollectionItem
import dev.ipf.whitenoise.android.state.planAttachmentChunks
import dev.ipf.whitenoise.android.state.skipItems
import dev.ipf.whitenoise.android.state.withChunkState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A failed photo send does not take over the composer: the blocked panel is one line with a single Retry, and the
 * explanation, Skip, Cancel and the item list wait behind Details.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class AttachmentCollectionBlockedPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val retried = mutableListOf<String>()

    /** A failed two-photo send shows how many are unsent and one Retry, which retries the failed message. */
    @Test
    fun aFailedTwoPhotoSendIsOneLineWithOneRetry() {
        val operation = failedTwoPhotoSend()
        render(operation)

        composeRule.onNodeWithText("2 of 2 not sent").assertIsDisplayed()
        composeRule.onAllNodesWithText(text(R.string.retry)).assertCountEquals(1)
        listOf(
            text(R.string.attachment_collection_show_items),
            text(R.string.attachment_collection_blocked),
            text(R.string.attachment_collection_skip_failed),
            text(R.string.attachment_collection_cancel),
        ).forEach { hidden -> composeRule.onAllNodesWithText(hidden).assertCountEquals(0) }
        composeRule.onNodeWithTag("conversation.attachment.collection.progress").assertDoesNotExist()

        composeRule.onNodeWithText(text(R.string.retry)).performClick()
        assertEquals(listOf(operation.chunks.single().id), retried)
    }

    /** A skipped item counts as not sent, so one sent, one skipped and one failed reads "2 of 3 not sent". */
    @Test
    fun skippedItemsCountAsNotSent() {
        val planned = List(3) { PlannedCollectionItem("file-$it", CollectionItemKind.DOCUMENT, 1_000L) }
        val operation = operation("three-files", planned)
        val (sent, skipped, failed) = operation.chunks
        render(
            operation
                .withChunkState(sent.id, CollectionChunkState.ACCEPTED, CollectionItemState.PUBLISHED, 0L)
                .skipItems(skipped.itemIds.toSet(), 0L)
                .withChunkState(failed.id, CollectionChunkState.FAILED, CollectionItemState.FAILED, 0L),
        )

        composeRule.onNodeWithText("2 of 3 not sent").assertIsDisplayed()
    }

    /** Details reveals the explanation, Skip, Cancel and each item, and hides them again. */
    @Test
    fun detailsRevealsTheSecondaryChoicesAndItems() {
        render(failedTwoPhotoSend())

        composeRule.onNodeWithText(text(R.string.details)).performClick()
        composeRule.onNodeWithText(text(R.string.attachment_collection_blocked)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.attachment_collection_skip_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.attachment_collection_cancel)).assertIsDisplayed()
        composeRule.onNodeWithTag("conversation.attachment.collection.items").assertIsDisplayed()

        composeRule.onNodeWithText(text(R.string.attachment_collection_hide_details)).performClick()
        composeRule.onNodeWithTag("conversation.attachment.collection.items").assertDoesNotExist()
        composeRule.onAllNodesWithText(text(R.string.attachment_collection_skip_failed)).assertCountEquals(0)
    }

    /** Renders the production panel for [operation], recording which chunks the user retries. */
    private fun render(operation: AttachmentCollectionOperation) {
        val actions = AttachmentCollectionPanelActions({ retried += it }, {}, {}, {}, {})
        composeRule.setContent { WhiteNoiseTheme { AttachmentCollectionProgress(operation, actions) } }
        composeRule.waitForIdle()
    }

    /** Two photos planned into one message whose send failed before anything was accepted. */
    private fun failedTwoPhotoSend(): AttachmentCollectionOperation {
        val planned = List(2) { PlannedCollectionItem("photo-$it", CollectionItemKind.MEDIA, 1_000L) }
        val operation = operation("two-photos", planned)
        return operation.withChunkState(
            operation.chunks.single().id,
            CollectionChunkState.FAILED,
            CollectionItemState.FAILED,
            0L,
        )
    }

    /** A fresh operation [id] planned from [planned] items. */
    private fun operation(
        id: String,
        planned: List<PlannedCollectionItem>,
    ) = AttachmentCollectionOperation(
        id = id,
        accountRef = "account",
        groupIdHex = "group",
        items = planned.map { CollectionItem(it.id, "content://shot/${it.id}", it.kind) },
        chunks = planAttachmentChunks(id, planned),
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

    /** The English text of [id]. */
    private fun text(id: Int): String = context.getString(id)
}
