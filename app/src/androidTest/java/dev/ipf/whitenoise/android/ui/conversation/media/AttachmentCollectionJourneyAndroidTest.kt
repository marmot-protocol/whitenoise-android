package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentCollectionJournal
import dev.ipf.whitenoise.android.state.AttachmentCollectionOperation
import dev.ipf.whitenoise.android.state.AttachmentCollectionRegistry
import dev.ipf.whitenoise.android.state.CollectionChunkState
import dev.ipf.whitenoise.android.state.CollectionItem
import dev.ipf.whitenoise.android.state.CollectionItemKind
import dev.ipf.whitenoise.android.state.CollectionItemState
import dev.ipf.whitenoise.android.state.CollectionOperationPhase
import dev.ipf.whitenoise.android.state.PlannedCollectionItem
import dev.ipf.whitenoise.android.state.planAttachmentChunks
import dev.ipf.whitenoise.android.state.withChunkState
import dev.ipf.whitenoise.android.ui.conversation.canReadCollectionUri
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The #2576 collection journey on a device: 100 mixed provider URIs from the test APK's generated-document
 * provider are staged, reviewed on the virtualized grid, selected, reordered and kept across saved-state
 * restoration, and a journaled operation is read back by a fresh registry as a restarted process would.
 * Real chunked sends against relays and process eviction are covered by the manual MED-045 run.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentCollectionJourneyAndroidTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testPackage = instrumentation.context.packageName

    /** A generated provider document of a few bytes named [name]. */
    private fun provided(name: String): Uri = GeneratedDocumentProvider.uri(testPackage, DOCUMENT_BYTES, name)

    /** Text of a resource in the app under test. */
    private fun string(
        id: Int,
        vararg args: Any,
    ): String = context.getString(id, *args)

    /** Stages 70 photos and 30 documents, edits the collection, and checks it survives state restoration. */
    @Test
    fun hundredMixedProviderItemsSurviveRestorationWithOrderAndSelection() {
        val media = List(MEDIA_COUNT) { PendingMediaSlot("slot-$it", provided("photo-$it.jpg")) }
        val documents = List(DOCUMENT_COUNT) { provided("report-$it.pdf") }
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                var slots by rememberSaveable(stateSaver = PendingMediaSlotListSaver) { mutableStateOf(media) }
                var docs by rememberSaveable { mutableStateOf(documents.map(Uri::toString)) }
                MediaPreviewContent(
                    mediaSlots = slots,
                    documentUris = docs.map(Uri::parse),
                    chatTitle = "Journey",
                    onClose = {},
                    onSend = { _, onResult -> onResult(false) },
                    onRemoveMediaAt = {},
                    onRemoveDocumentAt = {},
                    onAddPhotos = {},
                    onAddDocuments = {},
                    onCollectionChange = { order ->
                        slots = order.media
                        docs = order.documents.map(Uri::toString)
                    },
                )
            }
        }
        val total = MEDIA_COUNT + DOCUMENT_COUNT
        composeRule.onNodeWithTag("conversation.media.collection.grid").assertIsDisplayed()
        val composed = composeRule.onAllNodesWithTag("conversation.media.collection.cell").fetchSemanticsNodes().size
        assertTrue("the grid composes only visible cells", composed in 1 until total)

        composeRule
            .onNodeWithContentDescription(string(R.string.media_collection_item_photo, 1, total))
            .performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.media_collection_move_later)).performClick()
        composeRule
            .onNodeWithTag("conversation.media.collection.count")
            .assertTextEquals(string(R.string.media_collection_selected_count, 1, total))

        restoration.emulateSavedInstanceStateRestore()

        composeRule
            .onNodeWithTag("conversation.media.collection.count")
            .assertTextEquals(string(R.string.media_collection_selected_count, 1, total))
        composeRule
            .onNodeWithContentDescription(string(R.string.media_collection_item_photo, 2, total))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule.onNodeWithTag("conversation.media.collection.grid").performScrollToIndex(total - 1)
        composeRule
            .onNodeWithContentDescription(
                string(R.string.media_collection_item_document, "report-29.pdf", total, total),
                substring = true,
            ).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
    }

    /** A journaled operation reads back paused in a fresh registry, and a revoked source reads as unrecoverable. */
    @Test
    fun journaledOperationRestoresPausedAndUnreadableSourcesAreNamed() {
        val root = File(context.cacheDir, "collection-journey-${System.nanoTime()}").apply { mkdirs() }
        try {
            val planned =
                List(MEDIA_COUNT + DOCUMENT_COUNT) { index ->
                    val kind = if (index < MEDIA_COUNT) CollectionItemKind.MEDIA else CollectionItemKind.DOCUMENT
                    PlannedCollectionItem("item-$index", kind, DOCUMENT_BYTES)
                }
            val chunks = planAttachmentChunks("journey", planned)
            val operation =
                AttachmentCollectionOperation(
                    id = "journey",
                    accountRef = "account",
                    groupIdHex = "group",
                    items =
                        planned.mapIndexed { index, item ->
                            val uri = if (index == 0) REVOKED_URI else provided("item-$index").toString()
                            CollectionItem(item.id, uri, item.kind)
                        },
                    chunks = chunks,
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                ).withChunkState(chunks[1].id, CollectionChunkState.ACCEPTED, CollectionItemState.PUBLISHED, 1L)
                    .copy(updatedAtMs = System.currentTimeMillis())
            assertTrue(AttachmentCollectionJournal(root).write(operation))

            val restarted = AttachmentCollectionRegistry(AttachmentCollectionJournal(root))
            val restored = restarted.journaledFor("account", "group").single()
            assertEquals(CollectionOperationPhase.PAUSED, restored.phase)
            assertEquals(operation.items.map { it.id }, restored.items.map { it.id })
            assertEquals(operation.chunks.map { it.id }, restored.chunks.map { it.id })
            assertFalse(canReadCollectionUri(context, restored.items[0].uri))
            assertTrue(canReadCollectionUri(context, restored.items[1].uri))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val MEDIA_COUNT = 70
        const val DOCUMENT_COUNT = 30
        const val DOCUMENT_BYTES = 64L
        const val REVOKED_URI = "content://revoked.invalid/0"
    }
}
