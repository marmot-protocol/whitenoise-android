package dev.ipf.whitenoise.android.ui.conversation.messages

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.mediaCacheKey
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * A `.vcf` sent without the contact picker's caption, as older builds and other clients send it.
 * It stays a file card until its bytes are on this device, then draws the picker's contact card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h560dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class MessageBubbleRawVCardScreenshotTest : MessageBubbleFileAttachmentFixtures() {
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

    /**
     * The cached file becomes a contact card with its footer, a sender's caption that mentions a number stays
     * visible beside the card drawn from the file, and the remote file stays a file card.
     */
    @Test
    fun rawVCardDrawsContactCardOnceItsBytesAreLocal() {
        val cached = fileTimelineMessage(index = 200, fileName = CACHED_FILE, mediaType = "text/x-vcard")
        val captioned =
            fileTimelineMessage(index = 300, fileName = CAPTIONED_FILE, mediaType = "text/x-vcard", caption = PROSE)
        val remote = fileTimelineMessage(index = 360, fileName = REMOTE_FILE, mediaType = "text/x-vcard")
        composeRule.setContent {
            WhiteNoiseTheme {
                Column(Modifier.width(360.dp)) {
                    FileMessage(cached)
                    FileMessage(captioned)
                    FileMessage(remote)
                }
            }
        }
        composeRule.onNodeWithText(CACHED_FILE).assertExists()

        composeRule.runOnIdle {
            listOf(cached, captioned).forEach { item ->
                appState.cacheMediaPlaintext(
                    mediaCacheKey(
                        requireNotNull(controller.boundAccountRef),
                        controller.group.groupIdHex,
                        item.record.messageIdHex,
                        0,
                    ),
                    VCARD.toByteArray(),
                )
            }
        }

        // The vCard parses off the main thread, outside Compose's idling resources.
        composeRule.waitUntil(PARSE_TIMEOUT_MS) {
            composeRule.onAllNodesWithText(CONTACT_NAME).fetchSemanticsNodes().size == 2
        }
        composeRule.onAllNodesWithText(CACHED_FILE).assertCountEquals(0)
        composeRule.onAllNodesWithText(CAPTIONED_FILE).assertCountEquals(0)
        composeRule.onNodeWithText(PROSE, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(REMOTE_FILE).assertExists()
        composeRule.onAllNodesWithText(CACHED_TIME, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onRoot().captureRoboImage(SNAPSHOT_PATH)
    }

    /** Composes one real message bubble for a fixture row. */
    @Composable
    @Suppress("LongMethod") // Exercises the real MessageBubble interaction and layout contract.
    private fun FileMessage(item: TimelineMessage) {
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
            showSenderAvatar = false,
            parseMarkdown = ::markdown,
        )
    }
}

private const val CACHED_FILE = "ada.vcf"
private const val CAPTIONED_FILE = "ada-with-note.vcf"
private const val PROSE = "Please call 555-0100 after six"
private const val REMOTE_FILE = "not-downloaded.vcf"
private const val CONTACT_NAME = "Ada Example"
private const val VCARD = "BEGIN:VCARD\r\nVERSION:2.1\r\nFN:$CONTACT_NAME\r\nTEL;CELL:+1 555 0100\r\nEND:VCARD\r\n"
private const val CACHED_TIME = "1:03\u202FAM"
private const val PARSE_TIMEOUT_MS = 5_000L
private const val SNAPSHOT_PATH = "src/test/snapshots/message_bubble_raw_vcard_contact_light.png"
