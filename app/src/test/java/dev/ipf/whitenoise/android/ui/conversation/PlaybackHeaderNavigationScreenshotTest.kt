package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsSpokenTextSpan
import dev.ipf.whitenoise.android.audio.tts.TtsTextRange
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.currentPlaybackConversationDestination
import dev.ipf.whitenoise.android.ui.chats.ChatListBodyFrame
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.chats.ChatRowPortFixtures
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.navigation.CONVERSATION_ROUTE_TRANSITION_MILLIS
import dev.ipf.whitenoise.android.ui.navigation.ConversationRouteAnimatedContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Real headers, scaffold and player retain their vertical order across the conversation/list round trip. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PlaybackHeaderNavigationScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val appState = ChatRowPortFixtures.state(context)
    private val chat = ChatRowPortFixtures.item()
    private val controller = ConversationController(appState, chat.group)
    private val conversation = mutableStateOf(true)

    /** Voice starts under the group header and returns under the account picker without moving either header. */
    @Test fun voiceHeaderOrderSurvivesBackAndReopenLight() {
        render()
        val before = headerBounds()
        rule.runOnIdle { publishVoice() }
        assertHeaderBeforePlayer(before, "voice-transport")
        capture("voice_conversation_light")
        roundTrip("voice-transport", "voice_chat_list_light")
        rule.runOnIdle { VoicePlaybackController.stop() }
        assertEquals(before, headerBounds())
    }

    /** Read-aloud preserves the same order at large RTL text in dark theme, including after Stop. */
    @Test fun speechHeaderOrderSurvivesBackAndReopenLargeRtl() {
        render(dark = true, rtl = true)
        val before = headerBounds()
        startPlayback(speech = true)
        assertHeaderBeforePlayer(before, TTS_TRANSPORT_BODY_TAG)
        capture("speech_conversation_dark_rtl")
        roundTrip(TTS_TRANSPORT_BODY_TAG, "speech_chat_list_dark_rtl")
        rule.runOnIdle { appState.stopSpeaking() }
        assertEquals(before, headerBounds())
    }

    /** Released read-aloud keeps the outgoing player and transcript height stable during Back. */
    @Test fun speechBackRetainsOutgoingChromeLargeRtl() {
        verifyBackChrome(speech = true)
    }

    /** Voice playback follows the same retained-screen transition as released read-aloud. */
    @Test fun voiceBackRetainsOutgoingChromeLight() {
        verifyBackChrome(speech = false)
    }

    /** Samples real route animation before settlement, then exercises return through the player body. */
    private fun verifyBackChrome(speech: Boolean) {
        rule.mainClock.autoAdvance = false
        render(dark = speech, rtl = speech, animated = true)
        startPlayback(speech)
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { }
        val player = rule.onNodeWithTag("playback.conversation.player").getUnclippedBoundsInRoot()
        val content = rule.onNodeWithTag("playback.conversation.content").getUnclippedBoundsInRoot()
        assertTrue(player.bottom > player.top)
        rule.onNodeWithContentDescription(context.getString(dev.ipf.whitenoise.android.R.string.back)).performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(64L)
        rule.runOnIdle { }
        val exitingPlayer = rule.onNodeWithTag("playback.conversation.player").getUnclippedBoundsInRoot()
        val exitingContent = rule.onNodeWithTag("playback.conversation.content").getUnclippedBoundsInRoot()
        assertEquals(player.top, exitingPlayer.top)
        assertEquals(player.bottom, exitingPlayer.bottom)
        assertEquals(content.top, exitingContent.top)
        capture(if (speech) "speech_back_midpoint_dark_rtl" else "voice_back_midpoint_light")
        rule.mainClock.advanceTimeBy(CONVERSATION_ROUTE_TRANSITION_MILLIS.toLong())
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { }
        rule.onNodeWithTag("playback.conversation.player").assertDoesNotExist()
        rule.onNodeWithTag("playback.list.player").assertIsDisplayed()
        if (speech) {
            rule
                .onNodeWithTag(TTS_TRANSPORT_BODY_TAG)
                .assertHasClickAction()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
        } else {
            rule.onNodeWithText("Review group").performClick()
        }
        assertTrue(conversation.value)
        rule.runOnIdle { }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { }
        rule.mainClock.advanceTimeBy(CONVERSATION_ROUTE_TRANSITION_MILLIS.toLong())
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { }
        val reopenedPlayer = rule.onNodeWithTag("playback.conversation.player").getUnclippedBoundsInRoot()
        assertEquals(player.top, reopenedPlayer.top)
        assertEquals(player.bottom, reopenedPlayer.bottom)
    }

    /** Starts the actual speech queue or retained voice flow without codec/network side effects. */
    private fun startPlayback(speech: Boolean) {
        rule.runOnIdle {
            if (speech) {
                val engine = FakeSessionEngine()
                appState.ttsController.attachEngine(engine)
                assertTrue(
                    appState.speakAloud(
                        listOf(speechEntry()),
                        Locale.US,
                    ),
                )
                appState.ttsHistorySession.onConversationSessionStarted(
                    ChatRowPortFixtures.ACCOUNT_REF,
                    chat.group.groupIdHex,
                )
                // Return requires the actual projected message coordinates, not an ad-hoc speech preview.
                assertEquals("message", checkNotNull(appState.currentPlaybackConversationDestination()).messageIdHex)
            } else {
                publishVoice()
            }
        }
    }

    /** Maps the spoken body to one rendered canonical message, as the real conversation projection does. */
    private fun speechEntry(): TtsSpeakableEntry {
        val text = "Read this message."
        return TtsSpeakableEntry(
            senderKey = "peer",
            senderDisplayName = "Maya",
            text = text,
            messageIdHex = "message",
            spokenTextSpans =
                listOf(
                    TtsSpokenTextSpan(
                        TtsTextRange(0, text.length),
                        TtsVisibleTextSpan("body", 0, text.length),
                    ),
                ),
            projectionId = "message",
            visibleLeaves = mapOf("body" to text),
        )
    }

    /** Uses the actual Back button and player body action to change the rendered destination. */
    private fun roundTrip(
        playerTag: String,
        listSnapshot: String,
    ) {
        val conversationHeader = headerBounds()
        rule.onNodeWithContentDescription(context.getString(dev.ipf.whitenoise.android.R.string.back)).performClick()
        val listHeader = headerBounds()
        assertHeaderBeforePlayer(listHeader, playerTag)
        capture(listSnapshot)
        if (playerTag == "voice-transport") {
            rule.onNodeWithText("Review group").performClick()
        } else {
            rule
                .onNodeWithTag(playerTag)
                .assertHasClickAction()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        assertTrue(conversation.value)
        assertEquals(conversationHeader, headerBounds())
        assertHeaderBeforePlayer(conversationHeader, playerTag)
    }

    /** A player must occupy its own region below the stable header and above screen content. */
    private fun assertHeaderBeforePlayer(
        expected: androidx.compose.ui.unit.DpRect,
        playerTag: String,
    ) {
        assertEquals(expected, headerBounds())
        val player = rule.onNodeWithTag(playerTag).getUnclippedBoundsInRoot()
        val content = rule.onNodeWithTag("playback.content").getUnclippedBoundsInRoot()
        assertTrue(expected.bottom <= player.top)
        assertTrue(player.bottom <= content.top)
    }

    /** Measures the entire real header, including its status-bar insets. */
    private fun headerBounds() = rule.onNodeWithTag("playback.header").getUnclippedBoundsInRoot()

    /** Records both screen placements of each distinct playback type. */
    private fun capture(name: String) {
        rule.onNodeWithTag("playback.screen").captureRoboImage("src/test/snapshots/playback_header_$name.png")
    }

    /** Mounts production headers and optionally the released full-screen route animation. */
    private fun render(
        dark: Boolean = false,
        rtl: Boolean = false,
        animated: Boolean = false,
    ) {
        val focus = FocusRequester()
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (rtl) 1.5f else 1f) {
                    Surface(
                        modifier = Modifier.fillMaxSize().testTag("playback.screen"),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        if (animated) {
                            val transition =
                                updateTransition(
                                    targetState = if (conversation.value) "conversation" else null,
                                    label = "playback route",
                                )
                            ConversationRouteAnimatedContent(
                                transition = transition,
                                routeForwardDirection = if (rtl) -1 else 1,
                                suppressMotion = false,
                                contentKey = { it ?: "chat-list" },
                            ) { destination ->
                                PlaybackScreen(destination != null, focus)
                            }
                        } else {
                            PlaybackScreen(conversation.value, focus)
                        }
                    }
                }
            }
        }
    }

    /** Each retained route owns its full scaffold so playback cannot reflow its departing transcript. */
    @Composable
    @Suppress("FunctionNaming")
    private fun PlaybackScreen(
        inConversation: Boolean,
        focus: FocusRequester,
    ) {
        val route = if (inConversation) "conversation" else "list"
        WhiteNoiseScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (inConversation) {
                    ConversationHeaderFrame(
                        header = { Box(Modifier.testTag("playback.header")) { GroupHeader(focus) } },
                        playbackTransport = { Box(Modifier.testTag("playback.$route.player")) { Player() } },
                    )
                } else {
                    Box(Modifier.testTag("playback.header")) { AccountHeader(focus) }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                if (inConversation) {
                    Box(Modifier.testTag("playback.$route.content")) {
                        Text("Conversation messages", Modifier.testTag("playback.content"))
                    }
                } else {
                    ChatListBodyFrame(
                        ttsTransport = { Box(Modifier.testTag("playback.$route.player")) { Player() } },
                    ) {
                        Text("Recent conversations", Modifier.testTag("playback.content"))
                    }
                }
            }
        }
    }

    /** Keeps the production profile/account quick picker above the chat-list body frame. */
    @Composable
    @Suppress("FunctionNaming")
    private fun AccountHeader(focus: FocusRequester) {
        ChatListTopBar(
            appState = appState,
            searchOpen = false,
            searchQuery = "",
            searchFocusRequester = focus,
            onSearchQueryChange = {},
            onSearchOpen = {},
            onSearchClose = {},
            onMic = {},
            onOpenSettings = {},
            onSwitchAccount = {},
        )
    }

    /** Renders the authoritative local group title and the standard Back/details actions. */
    @Composable
    @Suppress("FunctionNaming")
    private fun GroupHeader(focus: FocusRequester) {
        ConversationTopBar(
            selectionMode = false,
            selectedCount = 0,
            onCloseSelection = {},
            searchOpen = false,
            searchQuery = "",
            onSearchQueryChange = {},
            onClearSearch = {},
            onCloseSearch = {},
            onSearchAction = {},
            searchFocusRequester = focus,
            appState = appState,
            controller = controller,
            groupTitleCopy = GroupTitleCopy.Default,
            openedAsDmHint = false,
            openDetailsDescription = "Open group details",
            onOpenDetails = {},
            onBack = { conversation.value = false },
        )
    }

    /** Exercises the actual voice-versus-speech selector and its source-return body action. */
    @Composable
    @Suppress("FunctionNaming")
    private fun Player() {
        PlaybackTransportBar(appState, onBodyClick = { conversation.value = true })
    }

    /** Seeds only the retained platform-player flow, avoiding codec work in a layout test. */
    @Suppress("UNCHECKED_CAST")
    private fun publishVoice() {
        val field = VoicePlaybackController::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val state = field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
        state.value =
            VoicePlaybackController.PlaybackState(
                key = "voice",
                ready = true,
                isPlaying = true,
                durationMs = 45_000,
                sessionId = 11,
                source =
                    VoicePlaybackSource(
                        ChatRowPortFixtures.ACCOUNT_REF,
                        chat.group.groupIdHex,
                        "message",
                        "Review group",
                    ),
            )
    }

    /** Retires fixture controllers and process-wide voice playback even after a failed assertion. */
    @After fun close() {
        appState.stopSpeaking()
        VoicePlaybackController.stop()
        controller.onCleared()
    }
}
