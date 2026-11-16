package dev.ipf.whitenoise.android.ui.conversation.messages

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/** A message that is one custom emoji shows it at sticker size, while every other shape keeps its bubble. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1600dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class LoneCustomEmojiScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    private enum class Variant { Light, Dark, LargeRtl }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val appState = fileFooterAppState(context)
    private val controller =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
        )
    private val defaultTimeZone: TimeZone = TimeZone.getDefault()
    private var originalTimeFormat: String? = null

    /** Pins the 12-hour UTC clock the footers render. */
    @Before
    fun pinClock() {
        originalTimeFormat = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Releases the controller and restores the clock preferences. */
    @After
    fun tearDown() {
        try {
            controller.onCleared()
        } finally {
            Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, originalTimeFormat)
            TimeZone.setDefault(defaultTimeZone)
        }
    }

    /** Lone emoji and kept bubbles in the light theme. */
    @Test fun light() = capture(Variant.Light)

    /** Lone emoji and kept bubbles in the dark theme. */
    @Test fun dark() = capture(Variant.Dark)

    /** Lone emoji keep sticker size and mirror their alignment with large text in RTL. */
    @Test fun largeFontRtl() = capture(Variant.LargeRtl)

    /** Only the three lone emoji with ready artwork drop their bubble. */
    @Test
    fun onlyLoneEmojiWithArtworkLeaveTheBubble() {
        render(Variant.Light)
        composeRule.waitForIdle()
        assertEquals(3, composeRule.onAllLoneEmoji())
    }

    /** A failed lone-emoji send keeps its bubble, so Retry and Discard stay reachable. */
    @Test
    fun failedSendKeepsRetryAndDiscard() {
        val failed = message(7, ":marmot:", mine = true).copy(status = MessageStatus.Failed)
        composeRule.setContent { WhiteNoiseTheme { Bubble(failed) } }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllLoneEmoji())
        composeRule.onNodeWithContentDescription(context.getString(R.string.retry)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.discard_failed_message)).assertExists()
    }

    /** Entering text selection swaps a lone emoji back to its bubble text, and leaving restores it. */
    @Test
    fun textSelectionShowsTheBubbleText() {
        var selecting by mutableStateOf(false)
        val lone = message(8, ":marmot_wave:")
        composeRule.setContent { WhiteNoiseTheme { Bubble(lone, textSelectionMode = selecting) } }
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllLoneEmoji())

        selecting = true
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllLoneEmoji())
        composeRule.onNodeWithText(":marmot_wave:", substring = true, useUnmergedTree = true).assertExists()

        selecting = false
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllLoneEmoji())
    }

    /** Captures lone built-in and local emoji beside unresolved, worded and reply messages that keep their bubble. */
    private fun capture(variant: Variant) {
        render(variant)
        val snapshot = "src/test/snapshots/lone_custom_emoji_${variant.snapshot}.png"
        composeRule.onNodeWithTag(GALLERY).captureRoboImage(snapshot)
    }

    /** Renders six real bubbles: three lone emoji with artwork and three messages that must keep their bubble. */
    private fun render(variant: Variant) {
        val rtl = variant == Variant.LargeRtl
        val messages =
            listOf(
                message(1, ":marmot_wave:"),
                message(2, ":marmot:", mine = true),
                message(3, " :party: "),
                message(4, ":unknown:"),
                message(5, ":marmot_wave: hi"),
                message(6, ":marmot:", hasReply = true),
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (rtl) 1.6f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalCustomEmoji provides localParty(),
            ) {
                WhiteNoiseTheme(darkTheme = variant == Variant.Dark) {
                    Surface(Modifier.fillMaxWidth().testTag(GALLERY)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            messages.forEach { Bubble(it) }
                        }
                    }
                }
            }
        }
    }

    /** A text message with no attachments; [text] is its whole body. */
    private fun message(
        index: Int,
        text: String,
        mine: Boolean = false,
        hasReply: Boolean = false,
    ): TimelineMessage = fileTimelineMessage(index, "unused", mine, text, hasReply, fileNames = emptyList())

    /** The device's own `:party:` file, a smooth image rather than built-in pixel art. */
    private fun localParty(): CustomEmojiSet {
        val image =
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(PARTY_ORANGE)
                for (x in 8 until 24) for (y in 8 until 24) setPixel(x, y, PARTY_PURPLE)
            }
        return CustomEmojiSet(listOf(CustomEmoji(":party:", File("party.png"), image.asImageBitmap())))
    }

    /** Composes the real message bubble for [item], optionally in text selection mode. */
    @Composable
    @Suppress("FunctionNaming")
    private fun Bubble(
        item: TimelineMessage,
        textSelectionMode: Boolean = false,
    ) {
        MessageBubble(
            item = item,
            controller = controller,
            appState = appState,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = textSelectionMode,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = true,
            selected = false,
            onToggleSelection = {},
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = emptyList(),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = false,
            onActionMenuOpenChange = {},
            onQuickReactionsSave = {},
            onReplyPreviewClick = {},
            showSenderAvatar = false,
            parseMarkdown = ::markdown,
        )
    }

    private val Variant.snapshot: String
        get() = name.lowercase().replace("largertl", "large_rtl")

    private companion object {
        const val GALLERY = "lone-emoji-gallery"
        const val PARTY_ORANGE = 0xFFEF6C00.toInt()
        const val PARTY_PURPLE = 0xFF6A1B9A.toInt()
    }
}

/** How many lone custom emoji the current content shows. */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllLoneEmoji(): Int =
    onAllNodes(
        androidx.compose.ui.test
            .hasTestTag("lone-custom-emoji"),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
        .size
