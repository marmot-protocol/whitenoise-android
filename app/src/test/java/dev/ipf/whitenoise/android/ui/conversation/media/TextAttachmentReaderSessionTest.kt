package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsPlaybackForegroundService
import dev.ipf.whitenoise.android.audio.tts.TtsPlaybackSessionHost
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudSentenceHighlightRangeKey
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class TextAttachmentReaderSessionTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = TtsPlaybackForegroundService.hostResolver
    private val appState = createAppState()
    private val engine = FakeSessionEngine()
    private var visible by mutableStateOf(true)
    private var service: ServiceController<TtsPlaybackForegroundService>? = null

    @After
    fun releaseSession() {
        appState.stopSpeaking()
        service?.destroy()
        TtsPlaybackForegroundService.hostResolver = resolver
    }

    @Test
    fun readerStartsOneOwnedSessionAndBackgroundControlsRestoreItsPausedCursor() {
        startReaderSession()
        val session = appState.ttsController.state.value.sessionId
        val passage = requireNotNull(appState.ttsController.state.value.passage)
        appState.ttsController.seekToSentence(passage.messageIdHex, 1, passage.projectionId)
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        val spoken = engine.spoken.size
        notificationAction(TtsPlaybackForegroundService.ACTION_PAUSE)
        assertTrue(appState.ttsController.state.value is TtsState.Paused)
        composeRule.runOnIdle { visible = true }
        waitForBody()
        composeRule.waitUntil(5_000) {
            composeRule
                .onNodeWithText(source, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config
                .contains(TtsReadAloudSentenceHighlightRangeKey)
        }
        composeRule
            .onNodeWithText(source, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(TtsReadAloudSentenceHighlightRangeKey))
        assertEquals(session, appState.ttsController.state.value.sessionId)
        assertEquals(
            1,
            appState.ttsController.state.value.passage
                ?.sentenceIndex,
        )
        assertEquals(spoken, engine.spoken.size)
        notificationAction(TtsPlaybackForegroundService.ACTION_PLAY)
        assertTrue(appState.ttsController.state.value is TtsState.Speaking)
        assertEquals(session, appState.ttsController.state.value.sessionId)
        notificationAction(TtsPlaybackForegroundService.ACTION_STOP)
        assertTrue(appState.ttsController.state.value is TtsState.Idle)
    }

    private fun startReaderSession() {
        appState.ttsController.attachEngine(engine)
        installForegroundHost()
        val actions = TextAttachmentNativeActions({ true }) {}
        composeRule.setContent {
            WhiteNoiseTheme {
                if (visible) {
                    TextAttachmentReaderDialog(
                        actions,
                        candidate,
                        appState,
                        "alice",
                        "Alice",
                        "message",
                        0,
                        loadBytes = { source.toByteArray() },
                        onOpenExternal = {},
                        onDismiss = { visible = false },
                    )
                }
            }
        }
        waitForBody()
        composeRule.onNodeWithContentDescription(context.getString(R.string.read_aloud)).performClick()
        composeRule.waitUntil(5_000) { appState.ttsController.state.value is TtsState.Speaking }
        assertTrue(appState.ownsCurrentAccountSpeech())
    }

    private fun waitForBody() {
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(source).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
    }

    private fun installForegroundHost() {
        TtsPlaybackForegroundService.hostResolver = {
            object : TtsPlaybackSessionHost {
                override val controller get() = appState.ttsController

                override fun nextSentence() {
                    controller.skipNextSentence()
                }

                override fun previousSentence() {
                    controller.skipPreviousSentence()
                }

                override fun stopSession() {
                    appState.stopSpeaking()
                }
            }
        }
        service = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
    }

    private fun notificationAction(action: String) {
        val live = requireNotNull(service).get()
        live.onStartCommand(Intent(context, live::class.java).setAction(action), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    private fun createAppState() =
        WhiteNoiseAppState(
            context = context,
            draftStore =
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "account",
                        accountIdHex = "id",
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = "account",
            preferences = context.getSharedPreferences("TextAttachmentReaderSessionTest", Context.MODE_PRIVATE),
        )

    private companion object {
        val candidate = TextAttachmentCandidate("notes.txt", "text/plain", TextAttachmentFormat.PlainText)
        const val source = "First sentence. Second sentence."
    }
}
