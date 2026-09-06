package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_FULL_SCREEN_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageFullScreenView
import dev.ipf.whitenoise.android.ui.conversation.messages.ReaderTextSelectionController
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberReaderTextSelectionController
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MessageFullScreenMarkdownScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var selectionController: ReaderTextSelectionController

    @Test
    fun richReaderLight() {
        render(darkTheme = false, fontScale = 1f, layoutDirection = LayoutDirection.Ltr)

        composeRule
            .onNodeWithTag(MESSAGE_FULL_SCREEN_TAG)
            .captureRoboImage("src/test/snapshots/message_full_screen_markdown_light.png")
    }

    @Test
    fun richReaderDarkLargeRtl() {
        render(darkTheme = true, fontScale = 1.6f, layoutDirection = LayoutDirection.Rtl)

        composeRule
            .onNodeWithTag(MESSAGE_FULL_SCREEN_TAG)
            .captureRoboImage("src/test/snapshots/message_full_screen_markdown_dark_large_rtl.png")
    }

    /** Captures a native Markdown selection in the full-screen reader. */
    @Test
    fun richReaderNativeSelection() {
        render(darkTheme = false, fontScale = 1f, layoutDirection = LayoutDirection.Ltr)

        composeRule.onNodeWithText("Release notes").performTouchInput { longClick() }
        composeRule.runOnIdle {
            assertEquals("Release", selectionController.selectedText(RAW_MARKDOWN))
        }
        composeRule
            .onNodeWithTag(MESSAGE_FULL_SCREEN_TAG)
            .captureRoboImage("src/test/snapshots/message_full_screen_markdown_selection.png")
    }

    /** Renders the production full-screen Markdown reader under a chosen environment. */
    private fun render(
        darkTheme: Boolean,
        fontScale: Float,
        layoutDirection: LayoutDirection,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
                LocalTextContextMenuToolbarProvider provides HiddenSelectionToolbar,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    val controller = rememberReaderTextSelectionController(RAW_MARKDOWN)
                    selectionController = controller
                    MessageFullScreenView(
                        senderDisplayName = "Wise Bee",
                        senderSeed = "wise-bee",
                        senderAvatarUrl = null,
                        body = RAW_MARKDOWN,
                        bodyMarkdownDocument = richDocument(),
                        mentionDisplayName = null,
                        isGroupMember = null,
                        onNostrProfileTap = null,
                        onCopyMarkdownLink = {},
                        timeText = "11:22",
                        showStatus = false,
                        status = MessageStatus.Received,
                        canReply = true,
                        canReact = true,
                        canDelete = false,
                        onReply = {},
                        onReact = {},
                        onCopy = {},
                        onDelete = {},
                        onDismiss = {},
                        bottomBar = {},
                        selectionController = controller,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Excludes host-owned toolbar chrome while retaining the native selection session. */
    private object HiddenSelectionToolbar : TextContextMenuProvider {
        @Suppress("MaxLineLength")
        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider): Nothing = awaitCancellation()
    }

    private fun richDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(0, 1, 0, 1, 0),
            blocks =
                listOf(
                    MarkdownBlockFfi.Heading(
                        level = 1u,
                        inlines = listOf(MarkdownInlineFfi.Text("Release notes")),
                    ),
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Text("The reader keeps "),
                            MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("important"))),
                            MarkdownInlineFfi.Text(" formatting and "),
                            MarkdownInlineFfi.Link(
                                dest = "https://example.com/spec",
                                title = null,
                                children = listOf(MarkdownInlineFfi.Text("safe links")),
                                classification = MarkdownLinkDestinationKindFfi.WEB,
                            ),
                            MarkdownInlineFfi.Text("."),
                        ),
                    ),
                    MarkdownBlockFfi.BlockQuote(
                        blocks =
                            listOf(
                                MarkdownBlockFfi.Paragraph(
                                    listOf(MarkdownInlineFfi.Text("Viewport changes must not flatten the document.")),
                                ),
                            ),
                        blankLinesBefore = byteArrayOf(),
                    ),
                    MarkdownBlockFfi.ListBlock(
                        kind = MarkdownListKindFfi.Bullet(marker = "-"),
                        tight = false,
                        items =
                            listOf(
                                listItem("Headings and emphasis"),
                                listItem("Quotes, lists, and code"),
                            ),
                    ),
                    MarkdownBlockFfi.CodeBlock(
                        kind = MarkdownCodeBlockKindFfi.FENCED,
                        info = "kotlin",
                        content = "val richText = true\n",
                    ),
                ),
        )

    private fun listItem(text: String) =
        MarkdownListItemFfi(
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))),
            checked = null,
            blankLinesBefore = byteArrayOf(),
        )

    private companion object {
        const val RAW_MARKDOWN = "# Release notes\nThe reader keeps **important** formatting."
    }
}
