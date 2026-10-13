package dev.ipf.whitenoise.android.ui.conversation.nostr

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w400dp-h1000dp-mdpi")
class NostrEventMediaLinkTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun ordinaryNoteImageAutolinkOpensTheImageActionOnlyAfterTap() {
        val url = "https://cdn.example/photo.jpg"
        val opened = mutableListOf<String>()
        render(MarkdownInlineFfi.Autolink(url, MarkdownAutolinkKindFfi.URI, MarkdownLinkDestinationKindFfi.WEB), opened)
        composeRule.runOnIdle { assertTrue(opened.isEmpty()) }
        composeRule.onNodeWithText(url).performTouchInput { click(Offset(10f, center.y)) }
        composeRule.runOnIdle { assertEquals(listOf("image:$url"), opened) }
    }

    @Test
    fun labelledNoteVideoLinkRetainsConfirmationThenOpensTheVideoAction() {
        val url = "https://cdn.example/clip.mp4"
        val opened = mutableListOf<String>()
        render(
            MarkdownInlineFfi.Link(
                dest = url,
                title = null,
                children = listOf(MarkdownInlineFfi.Text("another-video.example")),
                classification = MarkdownLinkDestinationKindFfi.WEB,
            ),
            opened,
        )
        composeRule.onNodeWithText("another-video.example").performTouchInput { click(Offset(10f, center.y)) }
        composeRule.runOnIdle { assertTrue(opened.isEmpty()) }
        val label = ApplicationProvider.getApplicationContext<Context>().getString(R.string.link_confirm_open)
        composeRule.onNodeWithText(label).performClick()
        composeRule.runOnIdle { assertEquals(listOf("video:$url"), opened) }
    }

    private fun render(
        inline: MarkdownInlineFfi,
        opened: MutableList<String>,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                NostrEventReaderScreen(
                    card =
                        NostrEventCardModel(
                            kind = NostrEventCardKind.Note,
                            eventIdHex = "a".repeat(64),
                            authorPubkeyHex = "b".repeat(64),
                            createdAt = 1,
                            eventKind = 1,
                            title = null,
                            summary = "Note",
                            readerBody = "Note",
                        ),
                    document =
                        MarkdownDocumentFfi(
                            listOf(MarkdownBlockFfi.Paragraph(listOf(inline))),
                            false,
                            byteArrayOf(),
                        ),
                    parsing = false,
                    authorDisplayName = { "Alex" },
                    mentionDisplayName = { null },
                    onNostrProfileTap = {},
                    onDismiss = {},
                    onViewImage = { opened += "image:$it" },
                    onPlayVideo = { opened += "video:$it" },
                )
            }
        }
    }
}
