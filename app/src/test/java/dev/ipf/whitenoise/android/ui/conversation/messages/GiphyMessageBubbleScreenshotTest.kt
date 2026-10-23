@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Renders the real message-bubble route for iOS GIPHY envelopes and checks footer ownership. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h730dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class GiphyMessageBubbleScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

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
    private val originalZone = TimeZone.getDefault()
    private var originalTimeFormat: String? = null

    /** Fixes clock inputs so both message timestamps have stable screenshot and semantics text. */
    @Before
    fun pinClock() {
        originalTimeFormat = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Releases controller work and restores process clock settings. */
    @After
    fun tearDown() {
        controller.onCleared()
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, originalTimeFormat)
        TimeZone.setDefault(originalZone)
    }

    /** Direct links and whitespace-wrapped iOS envelopes both render as inline cards. */
    @Test
    fun realGiphyMessagesKeepAttributionAndMetadataTogether() {
        val incoming =
            fileTimelineMessage(
                index = 20,
                fileName = "",
                caption = "https://media3.giphy.com/media/v1.encoded/200_d.gif",
                attachments = emptyList(),
            )
        val outgoing =
            fileTimelineMessage(
                index = 21,
                fileName = "",
                mine = true,
                caption = " \r\nhttps://media.giphy.com/media/def/giphy.gif\r\nvia GIPHY · Long creator attribution\r\n",
                attachments = emptyList(),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GiphyMessage(incoming)
                        GiphyMessage(outgoing)
                    }
                }
            }
        }
        composeRule.onAllNodesWithTag("giphy.attribution", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithTag("giphy.message-footer", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("1:00\u202FAM", useUnmergedTree = true).assertCountEquals(2)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/giphy_real_bubbles_light.png")
    }

    /** A rejected rich envelope remains a link rather than an inert compact-preview label. */
    @Test
    fun rejectedEnvelopeKeepsLink() {
        val url = "https://media3.giphy.com/media/v1.encoded/200_d.gif"
        val body = "$url\nvia GIPHY · Late Night with Seth Meyers\nextra"
        val fixture = fileTimelineMessage(22, "", caption = body, attachments = emptyList())
        val document =
            markdown(body).copy(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Autolink(url, MarkdownAutolinkKindFfi.URI, MarkdownLinkDestinationKindFfi.WEB),
                                MarkdownInlineFfi.Text("\nvia GIPHY · Late Night with Seth Meyers\nextra"),
                            ),
                        ),
                    ),
            )
        val item = fixture.copy(record = fixture.record.copy(contentTokens = document))
        composeRule.setContent {
            WhiteNoiseTheme { Surface { GiphyMessage(item) } }
        }
        composeRule.onAllNodesWithTag("giphy.attribution", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/giphy_fallback_link_light.png")
        composeRule.onNodeWithText(url, substring = true).performFirstLinkClick()
        val opened = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened?.action)
        assertEquals(url, opened?.dataString)
    }

    /** Uses the production MessageBubble with the fixture's authoritative account and conversation. */
    @Composable
    private fun GiphyMessage(item: TimelineMessage) {
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
