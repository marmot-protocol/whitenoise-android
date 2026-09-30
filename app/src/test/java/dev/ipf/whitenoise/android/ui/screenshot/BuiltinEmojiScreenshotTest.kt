package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.whitenoise.android.core.MessageBodyMatch
import dev.ipf.whitenoise.android.core.SnippetHighlight
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.chats.ChatRow
import dev.ipf.whitenoise.android.ui.chats.ChatRowPortFixtures
import dev.ipf.whitenoise.android.ui.chats.ChatRowPreviewLine
import dev.ipf.whitenoise.android.ui.conversation.messages.ReaderSelectablePlainText
import dev.ipf.whitenoise.android.ui.rememberMarkdownPreviewText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class BuiltinEmojiScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private enum class Variant { Light, Dark, LargeRtl }

    @Test fun light() = capture(Variant.Light)

    @Test fun dark() = capture(Variant.Dark)

    @Test fun largeFontRtl() = capture(Variant.LargeRtl)

    @Test fun searchLight() = captureSearch(Variant.Light)

    @Test fun searchDark() = captureSearch(Variant.Dark)

    @Test fun searchLargeRtl() = captureSearch(Variant.LargeRtl)

    private fun captureSearch(mode: Variant) {
        val rtl = mode == Variant.LargeRtl
        val body = ":marmot: matching :wn:"
        val start = body.indexOf("matching")
        val match =
            MessageBodyMatch(
                "g1",
                "emoji-search",
                SnippetHighlight(body, start, start + "matching".length),
                (System.currentTimeMillis() / 1000).toULong(),
            )
        composeRule.setContent {
            val state = ChatRowPortFixtures.state(LocalContext.current)
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (rtl) 1.6f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = mode == Variant.Dark) {
                    Surface(Modifier.width(360.dp).testTag("emoji-search")) {
                        ChatRow(ChatRowPortFixtures.item(), state, onClick = {}, onOpenProfile = {}, bodyMatch = match)
                    }
                }
            }
        }
        val suffix = mode.name.lowercase()
        val snapshot = "src/test/snapshots/builtin_emoji_search_$suffix.png"
        composeRule.onNodeWithTag("emoji-search").captureRoboImage(snapshot)
    }

    private fun capture(mode: Variant) {
        val dark = mode == Variant.Dark
        val rtl = mode == Variant.LargeRtl
        val repeatedEmoji = MarkdownInlineFfi.Text(":marmot::wn: :marmot::marmot: :wn::wn:")
        val malformedFence = "```\n``` trailing text\n:marmot: :wn:\n```\n:wn:"
        val document =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(listOf(repeatedEmoji)),
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("Bold :marmot:"))),
                                MarkdownInlineFfi.Text(" "),
                                MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("Italic :wn:"))),
                            ),
                        ),
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Text("Code: "),
                                MarkdownInlineFfi.Code(":marmot: :wn:"),
                            ),
                        ),
                        MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(":unknown: :WN: مرحبا :wn: نهاية"))),
                        MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "", ":marmot: :wn:"),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(0, 0, 0, 0, 0),
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (rtl) 1.6f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(Modifier.width(360.dp).testTag("builtin-emoji")) {
                        Column(Modifier.padding(16.dp)) {
                            MarkdownMessageBody(document)
                            Text("Plain fallback")
                            ReaderSelectablePlainText(":marmot: :wn: `:wn:`", { _, _, _ -> })
                            ReaderSelectablePlainText(malformedFence, { _, _, _ -> })
                            Text("Chat preview")
                            ChatRowPreviewLine(rememberMarkdownPreviewText(document), FontStyle.Normal)
                        }
                    }
                }
            }
        }
        val variant =
            if (rtl) {
                "large_rtl"
            } else if (dark) {
                "dark"
            } else {
                "light"
            }
        composeRule.onNodeWithTag("builtin-emoji").captureRoboImage("src/test/snapshots/builtin_emoji_$variant.png")
    }
}
