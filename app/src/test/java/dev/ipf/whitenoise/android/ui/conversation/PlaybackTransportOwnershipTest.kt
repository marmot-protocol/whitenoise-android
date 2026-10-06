package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.hardware.biometrics.BiometricManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.DialogProperties
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.observePlaybackConversationDestination
import dev.ipf.whitenoise.android.state.observePlaybackTransportVisible
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import java.util.Locale

/** Exercises the real selector and account/lock revocation, rather than only a stateless strip fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackTransportOwnershipTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Switching destinations/accounts may retain playback, but removing its source account revokes it. */
    @Test fun removedSourceAccountStopsAndHidesVoiceTransport() {
        val appState = appState()
        setVoice()
        render(appState)
        rule.onNodeWithTag("voice-transport").assertIsDisplayed()
        rule.runOnIdle { setAccounts(appState, emptyList()) }
        rule.onNodeWithTag("voice-transport").assertDoesNotExist()
        rule.runOnIdle { assertNull(VoicePlaybackController.state.value.key) }
    }

    /** A signed-out account never emits even a first frame of its captured source title. */
    @Test fun signedOutSourceDoesNotDisplayCapturedVoiceTitle() {
        val appState = appState()
        setAccounts(appState, appState.accounts.map { it.copy(signedOut = true) })
        setVoice()
        render(appState)
        rule.onNodeWithTag("voice-transport").assertDoesNotExist()
        rule.runOnIdle { assertNull(VoicePlaybackController.state.value.key) }
    }

    /** Active speech and a retained voice session produce exactly one transport; pause restores voice controls. */
    @Test fun activeSpeechSelectsOneTransportThenPausedSpeechYieldsToVoice() {
        val appState = appState()
        appState.ttsController.attachEngine(FakeSessionEngine())
        assertTrue(appState.speakAloud(listOf(TtsSpeakableEntry("m", "Maya", "Speech.")), Locale.US))
        setVoice()
        render(appState)
        rule.onNodeWithTag(TTS_TRANSPORT_BODY_TAG).assertIsDisplayed()
        rule.onNodeWithTag("voice-transport").assertDoesNotExist()
        rule.runOnIdle { appState.ttsController.pause() }
        rule.onNodeWithTag(TTS_TRANSPORT_BODY_TAG).assertDoesNotExist()
        rule.onNodeWithTag("voice-transport").assertIsDisplayed()
        rule.runOnIdle { appState.stopSpeaking() }
    }

    /** Locking the app removes the source label and controls immediately without relying on playback callbacks. */
    @Test fun appLockHidesVoiceTransport() {
        Shadow
            .extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java))
            .setCanAuthenticate(true)
        val appState = appState()
        setVoice()
        render(appState)
        rule.onNodeWithTag("voice-transport").assertIsDisplayed()
        rule.runOnIdle { appState.updateRequireAppUnlock(true) }
        assertTrue(appState.appLockScreenVisible)
        rule.onNodeWithTag("voice-transport").assertDoesNotExist()
    }

    /** A full-screen window keeps its own controls and dismisses before the shared source route runs. */
    @Test fun fullScreenDialogKeepsTransportAndDismissesBeforeReturningToSource() {
        val appState = appState()
        setVoice()
        var visible by mutableStateOf(true)
        var opened = 0
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalShellPlaybackHost provides
                        ShellPlaybackHost(appState) {
                            assertTrue(!visible)
                            opened++
                        },
                ) {
                    PlaybackTransportBar(appState)
                    if (visible) {
                        PlaybackDialog(
                            onDismissRequest = { visible = false },
                            properties =
                                DialogProperties(
                                    usePlatformDefaultWidth = false,
                                    decorFitsSystemWindows = false,
                                ),
                        ) { Text("Modal destination", Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        rule.onNode(hasTestTag("voice-transport") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNode(hasText("Maya") and hasAnyAncestor(isDialog())).performClick()
        rule.runOnIdle { assertEquals(1, opened) }
        rule.onNodeWithTag("voice-transport").assertIsDisplayed()
    }

    /** Audio time changes update the small strip, never the full navigation shell or modal destination tree. */
    @Test fun audioTicksDoNotRecomposeShellObservers() {
        val appState = appState()
        setVoice()
        var commits = 0
        rule.setContent {
            appState.observePlaybackConversationDestination()
            appState.observePlaybackTransportVisible()
            SideEffect { commits++ }
        }
        rule.waitForIdle()
        val before = commits
        rule.runOnIdle {
            val field = VoicePlaybackController::class.java.getDeclaredField("_state").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val state = field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
            state.value = state.value.copy(positionMs = 15_000)
        }
        rule.waitForIdle()
        assertEquals(before, commits)
    }

    @After fun resetPlayer() {
        VoicePlaybackController.stop()
    }

    private fun render(appState: WhiteNoiseAppState) {
        rule.setContent { WhiteNoiseTheme { PlaybackTransportBar(appState, onBodyClick = {}) } }
    }

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(DiscardedDrafts),
            accountIdHexResolver = { null },
            accounts = listOf(AccountSummaryFfi("personal", "id-a", true, false, false, true)),
            activeAccountRef = "personal",
            preferences = context.getSharedPreferences("playback-ownership", Context.MODE_PRIVATE),
        )

    @Suppress("UNCHECKED_CAST")
    private fun setAccounts(
        appState: WhiteNoiseAppState,
        accounts: List<AccountSummaryFfi>,
    ) {
        val field =
            WhiteNoiseAppState::class.java
                .getDeclaredField("accounts\u0024delegate")
                .apply { isAccessible = true }
        val state = field.get(appState) as androidx.compose.runtime.MutableState<List<AccountSummaryFfi>>
        state.value = accounts
    }

    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    /** The retained player flow lets the root observe real session transitions without platform codec work. */
    @Suppress("UNCHECKED_CAST")
    private fun setVoice() {
        val field = VoicePlaybackController::class.java.getDeclaredField("_state").apply { isAccessible = true }
        val state = field.get(VoicePlaybackController) as MutableStateFlow<VoicePlaybackController.PlaybackState>
        state.value =
            VoicePlaybackController.PlaybackState(
                key = "voice",
                ready = true,
                sessionId = 10,
                source = VoicePlaybackSource("personal", "group", "message", "Maya"),
            )
    }
}
