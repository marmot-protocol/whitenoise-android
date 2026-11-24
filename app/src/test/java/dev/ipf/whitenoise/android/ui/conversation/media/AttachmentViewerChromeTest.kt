package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The shared attachment viewer chrome: Close by default, one title style, actions and one overflow slot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentViewerChromeTest {
    @get:Rule val rule = createComposeRule()

    /** A viewer leads with an X labelled Close, which runs the viewer's close callback once. */
    @Test
    fun viewerLeadsWithCloseAndRunsTheCallback() {
        var closes = 0
        rule.setContent { WhiteNoiseTheme { AttachmentViewerChrome(title = "report.pdf", onClose = { closes++ }) } }
        rule.onNodeWithText("report.pdf").assertExists()
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
        assertEquals(1, closes)
    }

    /** A disabled chrome ignores taps on Close while the viewer is committing work. */
    @Test
    fun disabledCloseIgnoresTaps() {
        var closes = 0
        rule.setContent {
            WhiteNoiseTheme { AttachmentViewerChrome(title = "Edit", onClose = { closes++ }, closeEnabled = false) }
        }
        rule.onNodeWithTag(ATTACHMENT_VIEWER_CLOSE_TAG).assertIsNotEnabled().performClick()
        assertEquals(0, closes)
    }

    /** The subtitle, direct actions and the viewer's own overflow all render inside the one row. */
    @Test
    fun subtitleActionsAndOverflowRender() {
        rule.setContent {
            WhiteNoiseTheme {
                AttachmentViewerChrome(
                    title = "Alice",
                    subtitle = "Today · 1 of 3",
                    onClose = {},
                    actions = { IconButton(onClick = {}) { Text("Share") } },
                    overflow = { IconButton(onClick = {}) { Text("More") } },
                )
            }
        }
        rule.onNodeWithText("Today · 1 of 3").assertExists()
        rule.onNodeWithText("Share").assertExists()
        rule.onNodeWithText("More").assertExists()
    }

    /** Every attachment viewer draws its toolbar through the shared chrome, and the document viewer is themed. */
    @Test
    fun everyViewerUsesTheSharedChrome() {
        val viewers =
            mapOf(
                "ui/conversation/media/MediaViewer.kt" to "MediaViewerFrame",
                "ui/conversation/media/DocumentAttachmentViewer.kt" to "DocumentViewerFrame",
                "ui/conversation/media/TextAttachmentReader.kt" to "TextAttachmentReaderTopBar",
                "ui/conversation/media/editor/PhotoEditorScreen.kt" to "PhotoEditorTopBar",
                "ui/conversation/nostr/NostrEventViewers.kt" to "NostrEventReaderHeader",
                "ui/conversation/nostr/NostrVideoPlayer.kt" to "NostrVideoPlayerSurface",
                "ui/conversation/nostr/NostrLinkedImageDialog.kt" to "NostrLinkedImageDialog",
            )
        viewers.forEach { (path, function) ->
            val body = sourceFile(path).readText().functionBody(function)
            assertTrue("$path $function must use the shared viewer chrome", "AttachmentViewerChrome(" in body)
            assertFalse("$path $function must not build a raw app bar", "TopAppBar(" in body)
            assertFalse("$path $function must not draw a back arrow", "ic_arrow_back" in body)
        }
        val document = sourceFile("ui/conversation/media/DocumentAttachmentViewer.kt").readText()
        val frame = document.functionBody("DocumentViewerFrame")
        assertFalse("the document viewer follows the theme, not a forced black frame", "Color.Black" in frame)
    }

    /** One production source by its path below the app package, from the module or the repository root. */
    private fun sourceFile(path: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).first(File::isFile)
}
