package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun speakAloudAutoReadStartsAtRequestedSentenceAndKeepsSessionOwnership() {
        val appState = testAppState()
        val engine = FakeSessionEngine()
        appState.ttsController.attachEngine(engine)

        assertTrue(
            appState.speakAloudAutoRead(
                groupIdHex = groupA,
                entries = listOf(TtsSpeakableEntry("s", "Sender", "First. Second. Third.")),
                locale = Locale.US,
                startSentenceIndex = 1,
            ),
        )

        assertEquals(listOf("Sender: Second.", "Third."), engine.spoken.map { it.text })
        assertTrue(appState.ownsTtsAutoReadSession(groupA))
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

    @Test
    fun switchedAccountDoesNotOwnPreviousAccountsAutoReadSession() {
        val appState = testAppStateWithTwoAccounts(activeAccountRef = accountRef)
        val engine = FakeSessionEngine()
        appState.ttsController.attachEngine(engine)

        appState.setConversationAutoReadOverride(groupA, TtsAutoReadOverride.ON)
        assertTrue(
            appState.speakAloudAutoRead(
                groupA,
                listOf(TtsSpeakableEntry("s", "Sender", "Owned on account A.")),
                Locale.US,
            ),
        )
        assertTrue(appState.ownsTtsAutoReadSession(groupA))

        runBlocking { appState.setActiveAccount("account-b") }
        assertFalse(appState.ownsTtsAutoReadSession(groupA))
    }

    @Test
    fun speakAloudClearsAutoReadOwnershipWhileSpeakAloudAutoReadClaimsIt() {
        val appState = testAppState()
        val engine = FakeSessionEngine()
        appState.ttsController.attachEngine(engine)

        appState.setConversationAutoReadOverride(groupA, TtsAutoReadOverride.ON)
        assertTrue(
            appState.speakAloudAutoRead(
                groupA,
                listOf(TtsSpeakableEntry("auto", "Sender", "Auto-read.")),
                Locale.US,
            ),
        )
        assertTrue(appState.ownsTtsAutoReadSession(groupA))

        assertTrue(
            appState.speakAloud(
                listOf(TtsSpeakableEntry("manual", "Sender", "Manual playback.")),
                Locale.US,
            ),
        )
        assertFalse(appState.ownsTtsAutoReadSession(groupA))

        assertTrue(
            appState.speakAloudAutoRead(
                groupA,
                listOf(TtsSpeakableEntry("auto-2", "Sender", "Auto-read again.")),
                Locale.US,
            ),
        )
        assertTrue(appState.ownsTtsAutoReadSession(groupA))
    }

    @Test
    fun removingOwningAccountStopsManualAndPausedSpeech() {
        listOf(false, true).forEach { pauseBeforeRemoval ->
            val appState = testAppState()
            appState.ttsController.attachEngine(FakeSessionEngine())

            assertTrue(
                appState.speakAloud(
                    listOf(TtsSpeakableEntry("manual", "Sender", "Private account text.")),
                    Locale.US,
                ),
            )
            if (pauseBeforeRemoval) {
                appState.ttsController.pause()
                assertTrue(appState.ttsController.state.value is TtsState.Paused)
            }

            appState.stopTtsForRemovedAccount(accountRef)

            assertTrue(appState.ttsController.state.value is TtsState.Idle)
        }
    }

    @Test
    fun removingOwningAccountStopsAutoReadSpeech() {
        val appState = testAppState()
        appState.ttsController.attachEngine(FakeSessionEngine())

        assertTrue(
            appState.speakAloudAutoRead(
                groupA,
                listOf(TtsSpeakableEntry("auto", "Sender", "Private auto-read text.")),
                Locale.US,
            ),
        )

        appState.stopTtsForRemovedAccount(accountRef)

        assertFalse(appState.ownsTtsAutoReadSession(groupA))
        assertTrue(appState.ttsController.state.value is TtsState.Idle)
    }

    @Test
    fun removingAnotherAccountDoesNotStopOwnedManualSpeech() {
        val appState = testAppStateWithTwoAccounts(activeAccountRef = accountRef)
        appState.ttsController.attachEngine(FakeSessionEngine())

        assertTrue(
            appState.speakAloud(
                listOf(TtsSpeakableEntry("manual", "Sender", "Account A text.")),
                Locale.US,
            ),
        )

        appState.stopTtsForRemovedAccount("account-b")

        assertTrue(appState.ttsController.state.value is TtsState.Speaking)
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

    private fun testAppStateWithTwoAccounts(activeAccountRef: String): WhiteNoiseAppState =
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
                    AccountSummaryFfi(
                        label = "account-b",
                        accountIdHex = "id-b",
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = activeAccountRef,
        )

    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
