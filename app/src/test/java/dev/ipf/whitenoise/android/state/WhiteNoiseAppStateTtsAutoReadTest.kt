package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhiteNoiseAppStateTtsAutoReadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val accountRef = "account-a"
    private val groupA = "group-a"
    private val groupB = "group-b"

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun isConversationAutoReadUsesResolvedPreferenceState() {
        val appState = testAppState()
        appState.setTtsAutoReadGlobalDefault(true)
        assertTrue(appState.isConversationAutoRead(groupA))

        appState.setConversationAutoReadOverride(groupA, TtsAutoReadOverride.OFF)
        assertFalse(appState.isConversationAutoRead(groupA))

        appState.setConversationAutoReadOverride(groupA, null)
        assertTrue(appState.isConversationAutoRead(groupA))
    }

    @Test
    fun disablingOwnedAutoReadSessionStopsOnlyThatSession() {
        val appState = testAppState()
        val engine = FakeSessionEngine()
        appState.ttsController.attachEngine(engine)

        appState.setConversationAutoReadOverride(groupA, TtsAutoReadOverride.ON)
        assertTrue(
            appState.speakAloudAutoRead(
                groupA,
                listOf(TtsSpeakableEntry("s", "Sender", "Hello from A.")),
                Locale.US,
            ),
        )
        assertTrue(appState.ownsTtsAutoReadSession(groupA))

        appState.setConversationAutoReadOverride(groupB, TtsAutoReadOverride.OFF)
        assertTrue(appState.ownsTtsAutoReadSession(groupA))
        assertTrue(appState.ttsController.state.value is TtsState.Speaking)

        appState.setConversationAutoReadOverride(groupA, TtsAutoReadOverride.OFF)
        assertFalse(appState.ownsTtsAutoReadSession(groupA))
        assertTrue(appState.ttsController.state.value is TtsState.Idle)
    }

    @Test
    fun disablingAutoReadDoesNotStopManualSpeech() {
        val appState = testAppState()
        val engine = FakeSessionEngine()
        appState.ttsController.attachEngine(engine)

        assertTrue(
            appState.speakAloud(
                listOf(TtsSpeakableEntry("s", "Sender", "Manual playback.")),
                Locale.US,
            ),
        )
        assertFalse(appState.ownsTtsAutoReadSession(groupA))

        appState.setConversationAutoReadOverride(groupA, TtsAutoReadOverride.OFF)
        assertTrue(appState.ttsController.state.value is TtsState.Speaking)
    }

    @Test
    fun disablingAnotherChatDoesNotStopOwnedAutoReadSession() {
        val appState = testAppState()
        val engine = FakeSessionEngine()
        appState.ttsController.attachEngine(engine)

        appState.setConversationAutoReadOverride(groupA, TtsAutoReadOverride.ON)
        assertTrue(
            appState.speakAloudAutoRead(
                groupA,
                listOf(TtsSpeakableEntry("s", "Sender", "Hello from A.")),
                Locale.US,
            ),
        )

        appState.setConversationAutoReadOverride(groupB, TtsAutoReadOverride.OFF)
        assertTrue(appState.ownsTtsAutoReadSession(groupA))
    }

    @Test
    fun globalDefaultOffKeepsExplicitOnOwnedSession() {
        val appState = testAppState()
        val engine = FakeSessionEngine()
        appState.ttsController.attachEngine(engine)

        appState.setConversationAutoReadOverride(groupA, TtsAutoReadOverride.ON)
        assertTrue(
            appState.speakAloudAutoRead(
                groupA,
                listOf(TtsSpeakableEntry("s", "Sender", "Explicitly on.")),
                Locale.US,
            ),
        )

        appState.setTtsAutoReadGlobalDefault(false)
        assertTrue(appState.ownsTtsAutoReadSession(groupA))
        assertTrue(appState.ttsController.state.value is TtsState.Speaking)
    }

    @Test
    fun clearingOverrideStopsOwnedSessionWhenGlobalDefaultIsOff() {
        val appState = testAppState()
        val engine = FakeSessionEngine()
        appState.ttsController.attachEngine(engine)

        appState.setConversationAutoReadOverride(groupA, TtsAutoReadOverride.ON)
        assertTrue(
            appState.speakAloudAutoRead(
                groupA,
                listOf(TtsSpeakableEntry("s", "Sender", "No longer inherited on.")),
                Locale.US,
            ),
        )

        appState.setConversationAutoReadOverride(groupA, null)
        assertFalse(appState.ownsTtsAutoReadSession(groupA))
        assertTrue(appState.ttsController.state.value is TtsState.Idle)
    }

    @Test
    fun enablingGlobalDefaultWithoutEngineDoesNotStartSpeech() {
        val appState = testAppState()

        appState.setTtsAutoReadGlobalDefault(true)

        assertTrue(appState.isConversationAutoRead(groupA))
        assertFalse(appState.ownsTtsAutoReadSession(groupA))
        assertTrue(appState.ttsController.state.value is TtsState.Idle)
    }

    @Test
    fun globalDefaultOffStopsInheritedOwnedSession() {
        val appState = testAppState()
        val engine = FakeSessionEngine()
        appState.ttsController.attachEngine(engine)

        appState.setTtsAutoReadGlobalDefault(true)
        assertTrue(
            appState.speakAloudAutoRead(
                groupA,
                listOf(TtsSpeakableEntry("s", "Sender", "Inherited on.")),
                Locale.US,
            ),
        )

        appState.setTtsAutoReadGlobalDefault(false)
        assertFalse(appState.ownsTtsAutoReadSession(groupA))
    }

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(DiscardedDrafts),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = accountRef,
                        accountIdHex = "id-a",
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = accountRef,
        )

    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
