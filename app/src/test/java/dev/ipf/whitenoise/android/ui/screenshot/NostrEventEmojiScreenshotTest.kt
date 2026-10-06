package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.LocalReceivedEmoji
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCard
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardKind
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardModel
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventReaderScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class NostrEventEmojiScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun eventArtworkLight() = capture(dark = false, rtl = false, parsed = true, name = "nostr_event_emoji_light")

    @Test fun eventArtworkDark() = capture(dark = true, rtl = false, parsed = true, name = "nostr_event_emoji_dark")

    @Test
    fun eventFallbackArtworkLargeRtl() =
        capture(dark = true, rtl = true, parsed = false, name = "nostr_event_emoji_fallback_large_rtl")

    private fun capture(
        dark: Boolean,
        rtl: Boolean,
        parsed: Boolean,
        name: String,
    ) {
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFEF6C00.toInt()) }
        val local = CustomEmojiSet(listOf(CustomEmoji(":party:", File("party.png"), image.asImageBitmap())))
        val parent = ReceivedEmoji(mapOf(":remote:" to EmojiArt.Bundled(R.drawable.builtin_emoji_wn)), emptySet())
        rule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = if (rtl) 1.6f else 1f) {
                CompositionLocalProvider(
                    LocalCustomEmoji provides local,
                    LocalReceivedEmoji provides parent,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.width(360.dp).height(1000.dp).testTag("event-emoji")) {
                        Column {
                            NostrEventCard(
                                state = NostrEventCardState.Loaded(card("Card $CODES")),
                                authorDisplayName = { CODES },
                                referenceLabel = "nevent1fixture",
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onRetry = {},
                                onCopy = {},
                                onOpen = {},
                            )
                            NostrEventReaderScreen(
                                card = card("Reader $CODES"),
                                authoredReference = "nevent1fixture",
                                document = if (parsed) bodyDocument() else null,
                                parsing = false,
                                authorDisplayName = { CODES },
                                mentionDisplayName = { null },
                                onNostrProfileTap = {},
                                onDismiss = {},
                            )
                        }
                    }
                }
            }
        }
        assertArtwork("Card $CODES")
        assertArtwork(CODES)
        assertArtwork("Reader $CODES")
        // Both the parsed body and parser-fallback text may render local/bundled art only.
        assertArtwork(if (parsed) "Body :party: :wn: :remote: :party:" else "Body $CODES")
        rule.onNodeWithTag("event-emoji").captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun assertArtwork(text: String) {
        val node = rule.onNodeWithText(text)
        node.assertTextEquals(text)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        // :party: and :wn: render; :remote: must ignore the parent, and code stays literal.
        assertEquals(2, layouts.single().placeholderRects.count { it != null })
    }

    private fun card(title: String) =
        NostrEventCardModel(
            kind = NostrEventCardKind.Article,
            eventIdHex = "a".repeat(64),
            authorPubkeyHex = "b".repeat(64),
            createdAt = 1_765_000_000,
            eventKind = 30_023,
            title = title,
            summary = "Preview $CODES",
            metadata = listOf("Metadata $CODES"),
            readerBody = "Body $CODES",
        )

    private fun bodyDocument() =
        MarkdownDocumentFfi(
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Text("Body :party: :wn: :remote: "),
                            MarkdownInlineFfi.Code(":party:"),
                        ),
                    ),
                ),
            truncated = false,
            blankLinesBefore = byteArrayOf(),
        )

    private companion object {
        const val CODES = ":party: :wn: :remote: `:party:`"
    }
}
