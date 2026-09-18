package dev.ipf.whitenoise.android.ui.conversation.messages

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.media.fileAttachmentCardTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * A captioned file as MarmotKit 0.10 projects it: attachments as outcomes, no imeta tags. The caption
 * must render on both sides, and the time and delivery state sit on the caption, not in the file card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h480dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class MessageBubbleCaptionedFileScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val appState = fileFooterAppState(context)
    private val controller =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
        )
    private val composerTextState = ComposerTextState(TextFieldValue(""))
    private var originalTimeFormat: String? = null
    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    /** Pins the 12-hour and UTC clock inputs so the footer times are stable. */
    @Before
    fun setDeterministicClockPreferences() {
        originalTimeFormat = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Releases controller work and restores the clock preferences changed by setup. */
    @After
    fun tearDownFixture() {
        try {
            controller.onCleared()
        } finally {
            try {
                Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, originalTimeFormat)
            } finally {
                TimeZone.setDefault(originalTimeZone)
            }
        }
    }

    /** Both directions keep their caption, and the caption carries the footer below the card. */
    @Test
    fun captionedFileProjectedWithoutEngineTagsKeepsItsCaptionAndFooterOnIt() {
        val sent =
            fileTimelineMessage(
                index = 300,
                fileName = SENT_FILE,
                mine = true,
                caption = SENT_CAPTION,
                engineEchoesTags = false,
            )
        val received =
            fileTimelineMessage(
                index = 360,
                fileName = RECEIVED_FILE,
                caption = RECEIVED_CAPTION,
                engineEchoesTags = false,
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Column(
                    Modifier
                        .width(360.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FileMessage(sent)
                    FileMessage(received, showSenderAvatar = true)
                }
            }
        }
        composeRule.waitForIdle()

        val sentCard = fileAttachmentCardTestTag(sent.record.messageIdHex, 0)
        val receivedCard = fileAttachmentCardTestTag(received.record.messageIdHex, 0)
        composeRule.onNodeWithText(SENT_CAPTION, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(RECEIVED_CAPTION, useUnmergedTree = true).assertIsDisplayed()
        assertNodeBelowCard(sentCard, text = SENT_CAPTION)
        assertNodeBelowCard(sentCard, text = SENT_TIME)
        assertDescriptionBelowCard(sentCard, description = "Sent")
        assertNodeBelowCard(receivedCard, text = RECEIVED_CAPTION)
        assertNodeBelowCard(receivedCard, text = RECEIVED_TIME)
        assertCardNamesOnlyTheFile(sentCard, SENT_FILE)
        assertCardNamesOnlyTheFile(receivedCard, RECEIVED_FILE)
        composeRule.onRoot().captureRoboImage(SNAPSHOT_PATH)
    }

    /** The card's merged text is the filename alone; time and state have moved to the caption. */
    private fun assertCardNamesOnlyTheFile(
        cardTag: String,
        fileName: String,
    ) {
        val mergedText =
            composeRule
                .onNodeWithTag(cardTag)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text }
        assertEquals(1, mergedText.count { it == fileName })
        assertTrue("no time inside $cardTag: $mergedText", mergedText.none { it.endsWith("AM") || it.endsWith("PM") })
    }

    /** Verifies a caption-owned footer element sits under the file card rather than inside it. */
    private fun assertNodeBelowCard(
        cardTag: String,
        text: String,
    ) {
        val cardBounds = composeRule.onNodeWithTag(cardTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val nodeBounds = composeRule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("'$text' must sit below the file card", nodeBounds.top >= cardBounds.bottom)
    }

    /** Verifies a caption-owned glyph sits under the file card rather than inside it. */
    private fun assertDescriptionBelowCard(
        cardTag: String,
        description: String,
    ) {
        val cardBounds = composeRule.onNodeWithTag(cardTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val nodeBounds =
            composeRule.onNodeWithContentDescription(description, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("'$description' must sit below the file card", nodeBounds.top >= cardBounds.bottom)
    }

    /** Composes one real message bubble for a fixture row. */
    @Composable
    @Suppress("LongMethod") // Exercises the real MessageBubble interaction and layout contract.
    private fun FileMessage(
        item: TimelineMessage,
        showSenderAvatar: Boolean = false,
    ) {
        MessageBubble(
            item = item,
            controller = controller,
            appState = appState,
            composerTextState = composerTextState,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = false,
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
            composerGate = ComposerGate.COMPOSER,
            inviteMutationInFlight = false,
            onJoinInvite = {},
            onDeclineInvite = {},
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
            showSenderAvatar = showSenderAvatar,
            parseMarkdown = ::markdown,
        )
    }
}

private const val SENT_FILE = "story.pdf"
private const val SENT_CAPTION = "Chapter three, final draft"
private const val SENT_TIME = "1:05\u202FAM"
private const val RECEIVED_FILE = "agenda.pdf"
private const val RECEIVED_CAPTION = "Agenda for Thursday"
private const val RECEIVED_TIME = "1:06\u202FAM"
private const val SNAPSHOT_PATH = "src/test/snapshots/message_bubble_file_caption_without_engine_tags_light.png"
