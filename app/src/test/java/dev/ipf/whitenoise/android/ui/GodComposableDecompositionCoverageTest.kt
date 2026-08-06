package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GodComposableDecompositionCoverageTest {
    @Test
    fun conversationMediaWorkLivesOutsideConversationScreenComposition() {
        val screenSource = source("conversation/ConversationScreen.kt").readText()
        val sender = source("conversation/ConversationMediaSender.kt").readText()

        assertTrue(
            "ConversationScreen must remember one media sender holder",
            "rememberConversationMediaSender(" in screenSource,
        )
        listOf(
            "sendSharedContact",
            "sendVoiceAttachment",
            "readImageAttachment",
            "readPickedDocuments",
            "readPickedImages",
            "sendStagedAttachments",
        ).forEach { helper ->
            assertFalse("$helper must not remain in ConversationScreen", "fun $helper(" in screenSource)
            assertTrue("ConversationMediaSender module must own $helper", "fun $helper(" in sender)
        }
        assertFalse(
            "the unused legacy sendPickedMedia path should be deleted",
            "fun sendPickedMedia(" in screenSource || "fun sendPickedMedia(" in sender,
        )
    }

    @Test
    fun conversationUiTreeLivesInNamedRestartScopes() {
        val screenSource = source("conversation/ConversationScreen.kt").readText()
        val screenBody = screenSource

        val componentFiles =
            mapOf(
                "ConversationTopBar" to "conversation/ConversationTopBar.kt",
                "ConversationBottomBar" to "conversation/ConversationBottomBar.kt",
                "TimelineRow" to "conversation/TimelineRow.kt",
            )
        componentFiles.forEach { (component, path) ->
            assertTrue("ConversationScreen must delegate to $component", "$component(" in screenBody)
            assertTrue(
                "$component must be a named composable restart scope",
                source(path).readText().contains("internal fun $component("),
            )
        }
        assertFalse("ConversationScreen must not own TopAppBar rendering", "TopAppBar(" in screenBody)
        assertFalse("ConversationScreen must not own composer rendering", "ComposerBar(" in screenBody)
        assertFalse("ConversationScreen must not own message bubble rendering", "MessageBubble(" in screenBody)
    }

    @Test
    fun messageBubbleMediaPartitioningLivesInRememberedHolder() {
        val bubbleBody = source("conversation/messages/MessageBubble.kt").readText().functionBody("MessageBubble")
        val mediaHolder = source("conversation/messages/BubbleMedia.kt").readText()

        assertTrue("MessageBubble must use the extracted media holder", "rememberBubbleMedia(" in bubbleBody)
        assertTrue("The extracted holder must be immutable", "@Immutable" in mediaHolder)
        assertFalse(
            "MessageBubble must not partition every media class in its own composition scope",
            "MediaReferenceSupport.isImageMedia(ref)" in bubbleBody ||
                "MediaReferenceSupport.isAudioMedia(ref)" in bubbleBody ||
                "MediaReferenceSupport.isVideoMedia(ref)" in bubbleBody,
        )
    }

    @Test
    fun messageBubbleUiBlocksLiveInNamedRestartScopes() {
        val bubbleSource = source("conversation/messages/MessageBubble.kt").readText()
        val bubbleBody = bubbleSource.functionBody("MessageBubble")
        val componentsSource = source("conversation/messages/BubbleContentBlocks.kt").readText()

        listOf("BubbleMediaBlocks", "BubbleBodyFooterAndRetry").forEach { component ->
            assertTrue("MessageBubble must delegate to $component", "$component(" in bubbleBody)
            assertTrue(
                "$component must be a named composable restart scope",
                componentsSource.contains("internal fun $component(") ||
                    componentsSource.contains("internal fun ColumnScope.$component("),
            )
        }
        assertFalse("media UI must not remain an inline composable lambda", "val mediaBlocks:" in bubbleBody)
        assertFalse(
            "body/footer UI must not remain an inline composable lambda",
            "val bodyFooterAndRetry:" in bubbleBody,
        )
    }

    private fun source(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/$relativePath"),
        ).firstOrNull { it.exists() }
            ?: error("Missing source file: $relativePath")
}
