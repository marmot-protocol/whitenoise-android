package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.audio.tts.TtsError
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryDirection
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryEdgeState
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.errorTts
import dev.ipf.whitenoise.android.audio.tts.pausedTts
import dev.ipf.whitenoise.android.audio.tts.speakingTts
import dev.ipf.whitenoise.android.ui.chats.ChatListTopBar
import dev.ipf.whitenoise.android.ui.chats.ChatRow
import dev.ipf.whitenoise.android.ui.chats.ChatRowPortFixtures
import dev.ipf.whitenoise.android.ui.conversation.TtsTransportBarContent
import dev.ipf.whitenoise.android.ui.conversation.VoiceTransportBarContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One sheet per theme with the voice and read-aloud players side by side in every state, plus the strip in
 * place under the chat-list header, so both players are reviewed as one visual family.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MediaPlayerBarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun statesAmoled() = sheet("media_player_bar_states_amoled", dark = true, amoled = true)

    @Test fun statesLight() = sheet("media_player_bar_states_light", dark = false)

    @Test fun statesDark() = sheet("media_player_bar_states_dark", dark = true)

    @Test
    @Config(qualifiers = "en-w360dp-h1050dp-mdpi")
    fun statesLargeFontRtl() =
        sheet(
            "media_player_bar_states_large_font_rtl",
            dark = true,
            amoled = true,
            fontScale = 1.6f,
            rtl = true,
        )

    @Test fun voiceInChatListAmoled() = inChatList("media_player_bar_voice_chat_list_amoled", dark = true, voice = true)

    @Test
    fun speechInChatListLight() = inChatList("media_player_bar_speech_chat_list_light", dark = false, voice = false)

    @Test fun voiceInChatListLight() = inChatList("media_player_bar_voice_chat_list_light", dark = false, voice = true)

    @Test
    fun speechInChatListAmoled() = inChatList("media_player_bar_speech_chat_list_amoled", dark = true, voice = false)

    @Test
    fun speechMessageMenuAmoled() {
        composeRule.setContent {
            Themed(dark = true, amoled = true, fontScale = 1f, rtl = false) {
                Surface(color = MaterialTheme.colorScheme.background) { Speech(speaking()) }
            }
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.tts_bar_previous_message)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.tts_bar_next_message)).assertIsDisplayed()
        composeRule
            .onNode(isPopup())
            .captureRoboImage("src/test/snapshots/media_player_bar_speech_message_menu_amoled.png")
    }

    private fun sheet(
        name: String,
        dark: Boolean,
        amoled: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            Themed(dark, amoled, fontScale, rtl) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.width(360.dp).testTag(TAG),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Voice(voiceState(playing = true))
                        Voice(voiceState(playing = false, title = "Maya and the weekend planning group"))
                        Speech(speaking())
                        Speech(paused(), remainingSeconds = 312)
                        Speech(TtsState.Preparing(speaking()))
                        Speech(errorTts(TtsError.Synthesis, chunkIndex = 4, chunkCount = 20, messageCount = 12))
                        Speech(paused(), historyEdge = TtsHistoryEdgeState.Failed(TtsHistoryDirection.Older))
                    }
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun inChatList(
        name: String,
        dark: Boolean,
        voice: Boolean,
    ) {
        val appState = ChatRowPortFixtures.state(context)
        val rows =
            listOf(
                "Maya" to "Voice message",
                "Weekend hikers" to "Sam: Trailhead at 8, bring layers and water.",
                "Design review" to "The new strip sits right under the header.",
                "Family" to "Dinner on Sunday?",
            )
        composeRule.setContent {
            Themed(dark, amoled = dark, fontScale = 1f, rtl = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.testTag(TAG)) {
                    Column(Modifier.fillMaxSize()) {
                        ChatListTopBar(
                            appState = appState,
                            searchOpen = false,
                            searchQuery = "",
                            searchFocusRequester = FocusRequester(),
                            onSearchQueryChange = {},
                            onSearchOpen = {},
                            onSearchClose = {},
                            onMic = {},
                            onOpenSettings = {},
                            onSwitchAccount = {},
                        )
                        if (voice) Voice(voiceState(playing = true, title = "Maya")) else Speech(speaking())
                        Column(Modifier.fillMaxWidth()) {
                            rows.forEach { (title, preview) ->
                                ChatRow(
                                    item = ChatRowPortFixtures.item(preview = preview),
                                    appState = appState,
                                    titleOverride = title,
                                    interactionsEnabled = false,
                                    onClick = {},
                                    onOpenProfile = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name.png")
        appState.stopSpeaking()
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun Themed(
        dark: Boolean,
        amoled: Boolean,
        fontScale: Float,
        rtl: Boolean,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale, content = content)
        }
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun Voice(state: VoicePlaybackController.PlaybackState) {
        VoiceTransportBarContent(
            state = state,
            onPlayingChange = {},
            onStop = {},
            onSeekBy = {},
            onCycleSpeed = {},
            onBodyClick = {},
        )
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun Speech(
        state: TtsState,
        remainingSeconds: Int = 12,
        historyEdge: TtsHistoryEdgeState? = null,
    ) {
        TtsTransportBarContent(
            state = state,
            rateOverride = 1.0f,
            activeRate = 1.0f,
            onPause = {},
            onResume = {},
            onPreviousSentence = {},
            onNextSentence = {},
            onPreviousMessage = {},
            onNextMessage = {},
            onRateSelected = {},
            onStop = {},
            remainingSeconds = remainingSeconds,
            historyEdge = historyEdge,
            onBodyClick = {},
        )
    }

    private fun voiceState(
        playing: Boolean,
        title: String = "Maya",
    ) = VoicePlaybackController.PlaybackState(
        key = "voice",
        isPlaying = playing,
        positionMs = if (playing) 12_000 else 31_000,
        durationMs = 45_000,
        speed = if (playing) 1.5f else 1f,
        ready = true,
        source = VoicePlaybackSource("personal", "group", "message", title),
    )

    private fun speaking(): TtsState =
        speakingTts(
            chunkIndex = 4,
            chunkCount = 20,
            messageIndex = 1,
            messageCount = 12,
            messagePreview = "Alice: The quick brown fox",
            sentenceIndex = 2,
            sentenceCount = 8,
            messageProgressFraction = 0.35f,
        )

    private fun paused(): TtsState =
        pausedTts(
            chunkIndex = 4,
            chunkCount = 20,
            messageIndex = 1,
            messageCount = 12,
            messagePreview = "Alice: The quick brown fox",
            sentenceIndex = 2,
            sentenceCount = 8,
            messageProgressFraction = 0.62f,
        )

    private companion object {
        const val TAG = "media-player-bar"
    }
}
