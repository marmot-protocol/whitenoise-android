@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
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

/** Pins actual reply placement without enlarging or inheriting the separate file-footer test suite. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h1100dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class MessageBubbleCustomReplyScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val appState = fileFooterAppState(context)
    private val controller =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
        )
    private val composerTextState = ComposerTextState(TextFieldValue(""))
    private val originalTimeZone = TimeZone.getDefault()
    private var originalTimeFormat: String? = null

    /** Uses the same UTC and 12-hour inputs as the shared native-message fixtures. */
    @Before
    fun pinClock() {
        originalTimeFormat = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Releases the controller and restores all process-level clock inputs. */
    @After
    fun releaseFixture() {
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

    /** Real text, captioned-file and standalone-file replies keep their respective Light surfaces. */
    @Test
    fun customReplySurfacesLight() = captureCustomReplySurfaces("light", AppThemeMode.Light)

    /** The same production branches preserve readable custom roles in Dark. */
    @Test
    fun customReplySurfacesDark() = captureCustomReplySurfaces("dark", AppThemeMode.Dark)

    /** AMOLED keeps black interiors and its existing standalone and in-bubble outlines. */
    @Test
    fun customReplySurfacesAmoled() = captureCustomReplySurfaces("amoled", AppThemeMode.Amoled)

    /** Light replies use the global received fill when the conversation has no override. */
    @Test
    fun globalReceivedReplySurfacesLight() {
        captureCustomReplySurfaces("global_received_light", AppThemeMode.Light, useChatReceivedColor = false)
    }

    /** Dark replies resolve the global near-black received fill through the production dispatcher. */
    @Test
    fun globalReceivedReplySurfacesDark() {
        captureCustomReplySurfaces("global_received_dark", AppThemeMode.Dark, useChatReceivedColor = false)
    }

    /** AMOLED resolves the global received outline while retaining its black reply interiors. */
    @Test
    fun globalReceivedReplySurfacesAmoled() {
        captureCustomReplySurfaces("global_received_amoled", AppThemeMode.Amoled, useChatReceivedColor = false)
    }

    /** Runs the actual dispatcher with global colors and an optional winning per-chat received override. */
    private fun captureCustomReplySurfaces(
        name: String,
        mode: AppThemeMode,
        useChatReceivedColor: Boolean = true,
    ) {
        appState.updateThemeMode(mode)
        val theme = BubbleTheme.resolve(mode, mode != AppThemeMode.Light)
        appState.updateGlobalBubbleColor(theme, BubbleSide.Mine, 0xFFFFFFFFL)
        appState.updateGlobalBubbleColor(theme, BubbleSide.Other, 0xFF040404L)
        appState.updateChatBubbleColor(
            controller.group.groupIdHex,
            BubbleSide.Other,
            if (useChatReceivedColor) 0xFF999999L else null,
        )
        val rows = customReplyRows()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = mode != AppThemeMode.Light, amoled = mode == AppThemeMode.Amoled) {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(12.dp),
                ) {
                    rows.forEach { FileMessage(it) }
                }
            }
        }
        rows
            .filter { it.record.direction == "sent" && requireNotNull(it.projected).media.isNotEmpty() }
            .forEach(::cacheCanonicalOwnFiles)
        composeRule.onAllNodesWithText("Parent message").assertCountEquals(rows.size)
        composeRule.onNodeWithText("Custom sent text").assertExists()
        composeRule.onNodeWithText("Custom received text").assertExists()
        composeRule.onNodeWithText("Custom sent caption").assertExists()
        composeRule.onNodeWithText("Custom received caption").assertExists()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/message_bubble_custom_reply_surfaces_$name.png")
    }

    /** Builds independent sent/received rows for the three real reply-placement branches. */
    private fun customReplyRows(): List<TimelineMessage> =
        listOf(false, true).flatMap { mine ->
            val direction = if (mine) "sent" else "received"
            val index = if (mine) 210 else 200
            listOf(
                fileTimelineMessage(
                    index = index,
                    fileName = "",
                    mine = mine,
                    caption = "Custom $direction text",
                    hasReply = true,
                    attachments = emptyList(),
                ),
                fileTimelineMessage(
                    index = index + 1,
                    fileName = "$direction-caption.pdf",
                    mine = mine,
                    caption = "Custom $direction caption",
                    hasReply = true,
                ),
                fileTimelineMessage(
                    index = index + 2,
                    fileName = "$direction-standalone.pdf",
                    mine = mine,
                    hasReply = true,
                ),
            )
        }

    /** Mirrors the successful native handoff for canonical outgoing file rows without network access. */
    private fun cacheCanonicalOwnFiles(item: TimelineMessage) {
        composeRule.runOnIdle {
            repeat(requireNotNull(item.projected).media.size) { attachmentIndex ->
                appState.cacheMediaPlaintext(
                    mediaCacheKey("personal", item.record.groupIdHex, item.record.messageIdHex, attachmentIndex),
                    byteArrayOf(1, 2, 3, 4),
                )
            }
        }
    }

    /** Composes a file message bubble fixture. */
    @Composable
    @Suppress("LongMethod") // Exercises the real MessageBubble interaction and layout contract.
    private fun FileMessage(
        item: TimelineMessage,
        showSenderAvatar: Boolean = false,
        selectionMode: Boolean = false,
        selected: Boolean = false,
    ) {
        MessageBubble(
            item = item,
            controller = controller,
            appState = appState,
            composerTextState = composerTextState,
            highlighted = false,
            selectionMode = selectionMode,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = true,
            selected = selected,
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
