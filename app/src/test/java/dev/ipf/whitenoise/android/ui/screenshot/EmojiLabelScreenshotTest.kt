package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.EmojiArt
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.LocalReceivedEmoji
import dev.ipf.whitenoise.android.ui.ReceivedEmoji
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.conversation.PollCard
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionPillRow
import dev.ipf.whitenoise.android.ui.conversation.replies.ReplyPreviewCard
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
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class EmojiLabelScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun namesAndQuotesLight() = capture(dark = false, name = "emoji_labels_light")

    @Test
    fun namesAndQuotesDark() = capture(dark = true, name = "emoji_labels_dark")

    @Test
    fun namesAndQuotesLargeRtl() = capture(dark = true, rtl = true, name = "emoji_labels_large_rtl")

    @Test
    fun pollArtworkLight() = capturePoll(rtl = false, name = "emoji_poll_light")

    @Test
    fun pollArtworkLargeRtl() = capturePoll(rtl = true, name = "emoji_poll_large_rtl")

    @Test
    fun reactionArtworkDoesNotBorrowMessageDefinition() {
        val local = localEmoji()
        val enclosing = ReceivedEmoji(mapOf(":remote:" to EmojiArt.Bundled(R.drawable.builtin_emoji_wn)), emptySet())
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalCustomEmoji provides local, LocalReceivedEmoji provides enclosing) {
                    Surface(Modifier.width(360.dp).testTag("reactions")) {
                        ReactionPillRow(
                            tallies = listOf(
                                ReactionTally(":remote:", 1, mine = false),
                                ReactionTally(":party:", 2, mine = true),
                                ReactionTally(":wn:", 1, mine = false),
                            ),
                            enabled = true,
                            onOpenDetails = {},
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("reactions").captureRoboImage("src/test/snapshots/emoji_reaction_source_isolation.png")
    }

    @Test
    fun localArtworkReplacesReceivedArtworkAndKeepsAccessibleText() {
        val local = localEmoji()
        val received = ReceivedEmoji(mapOf(":party:" to EmojiArt.Bundled(R.drawable.builtin_emoji_wn)), emptySet())
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalCustomEmoji provides local, LocalReceivedEmoji provides received) {
                    EmojiLabel(
                        "Ada :party: :unknown: :wn:",
                        Modifier.testTag("name"),
                    )
                }
            }
        }
        rule.onNodeWithTag("name").assertTextEquals("Ada :party: :unknown: :wn:")
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("name").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        val placeholders = layouts.single().placeholderRects.filterNotNull()
        assertEquals(2, placeholders.size)
        val center = placeholders.first().center
        val pixels = rule.onNodeWithTag("name").captureToImage().toPixelMap()
        assertEquals(0xFFEF6C00.toInt(), pixels[center.x.toInt(), center.y.toInt()].toArgb())
    }

    @Test
    fun identityLabelsDoNotRenderAnEnclosingMessagesArtwork() {
        val enclosing = ReceivedEmoji(mapOf(":remote:" to EmojiArt.Bundled(R.drawable.builtin_emoji_wn)), emptySet())
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalReceivedEmoji provides enclosing) {
                    EmojiLabel("Ada :remote:", Modifier.testTag("name"))
                }
            }
        }
        rule.onNodeWithTag("name").assertTextEquals("Ada :remote:")
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("name").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        assertEquals(0, layouts.single().placeholderRects.count { it != null })
    }

    private fun capture(
        dark: Boolean,
        name: String,
        rtl: Boolean = false,
    ) {
        val quote = ReceivedEmoji(mapOf(":remote:" to EmojiArt.Bundled(R.drawable.builtin_emoji_marmot)), emptySet())
        // A different containing message must never redefine a profile name or the quoted message.
        val enclosing = ReceivedEmoji(mapOf(":remote:" to EmojiArt.Bundled(R.drawable.builtin_emoji_wn)), emptySet())
        val local = localEmoji()
        rule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = if (rtl) 1.6f else 1f) {
                CompositionLocalProvider(
                    LocalCustomEmoji provides local,
                    LocalReceivedEmoji provides enclosing,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.width(360.dp).testTag("labels")) {
                        Column(Modifier.padding(16.dp)) {
                            EmojiLabel("Team :party: :wn:", style = MaterialTheme.typography.headlineSmall)
                            EmojiLabel("About :marmot: · :remote: · :unknown: · `:party:`")
                            ContactRow("Ada :party:", "npub1fixture", "ada", null)
                            ReplyPreviewCard(
                                senderTitle = "Ada :party: :remote:",
                                isOwn = false,
                                body = "Quoted :remote: :party: `:remote:`",
                                mediaKind = ReplyMediaKind.None,
                                onClick = null,
                                onDismiss = null,
                                receivedEmoji = quote,
                            )
                            ReplyPreviewCard(
                                senderTitle = "Unloaded source",
                                isOwn = false,
                                body = "Fallback :remote: :unknown:",
                                mediaKind = ReplyMediaKind.None,
                                onClick = null,
                                onDismiss = null,
                            )
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("labels").captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun localEmoji(): CustomEmojiSet {
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFEF6C00.toInt()) }
        return CustomEmojiSet(listOf(CustomEmoji(":party:", File("party.png"), image.asImageBitmap())))
    }

    private fun capturePoll(rtl: Boolean, name: String) {
        val local = localEmoji()
        rule.setContent {
            WhiteNoiseTheme(darkTheme = rtl, fontScale = if (rtl) 1.6f else 1f) {
                CompositionLocalProvider(
                    LocalCustomEmoji provides local,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.width(360.dp).testTag("poll")) {
                        PollCard(
                            poll = PollProjectionFfi(
                                question = "Choose :party: :wn:",
                                options = listOf(
                                    PollOptionResultFfi("a", "Local :party:", 2uL),
                                    PollOptionResultFfi("b", "Builtin :marmot: :unknown:", 1uL),
                                ),
                                pollType = PollTypeFfi.SINGLE_CHOICE,
                                participants = 3uL,
                                localSelection = listOf("a"),
                                creator = "fixture",
                                endsAt = null,
                                open = true,
                            ),
                            canVote = false,
                            onVote = {},
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("poll").captureRoboImage("src/test/snapshots/$name.png")
    }
}
