package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedRenderedHit
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
class WhiteNoiseAppStateReaderSpeechTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = context.getSharedPreferences("WhiteNoiseAppStateReaderSpeechTest", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun readerCannotOwnSpeechFromAnotherLocalAccount() {
        val appState = testAppState(twoAccounts = true)
        appState.ttsController.attachEngine(FakeSessionEngine())
        assertTrue(appState.speakAloud(listOf(TtsSpeakableEntry("s", "Sender", "Private speech.")), Locale.US))
        assertTrue(appState.ownsCurrentAccountSpeech())
        runBlocking { appState.setActiveAccount("account-b") }
        assertFalse(appState.ownsCurrentAccountSpeech())
    }

    @Test
    fun revokedReaderCannotReplaceAnExistingManualSpeechSession() =
        runBlocking {
            val appState = testAppState()
            val engine = FakeSessionEngine()
            appState.ttsController.attachEngine(engine)
            val entries = listOf(TtsSpeakableEntry("s", "Sender", "Existing manual speech."))
            assertTrue(appState.speakAloud(entries, Locale.US))
            val existing = appState.ttsController.state.value
            val spoken = engine.spoken.size
            assertFalse(appState.speakAloudPrepared(entries, Locale.US, isCurrent = { false }))
            assertEquals(existing, appState.ttsController.state.value)
            assertEquals(spoken, engine.spoken.size)
            assertTrue(appState.ownsCurrentAccountSpeech())
        }

    @Test
    fun conversationPreparedStartRejectsAnUnmappableHitWithoutReadingFromTheTop() =
        runBlocking {
            val appState = testAppState()
            val engine = FakeSessionEngine()
            appState.ttsController.attachEngine(engine)
            assertFalse(
                appState.speakAloudAutoRead(
                    groupIdHex = "group-a",
                    entries = listOf(TtsSpeakableEntry("s", "Sender", "First. Second.")),
                    locale = Locale.US,
                    startSentenceIndex = 1,
                    startRenderedHit = PreparedRenderedHit("missing", "changed", 0),
                    backgroundPreparation = true,
                ),
            )
            assertTrue(engine.spoken.isEmpty())
            assertFalse(appState.ownsTtsAutoReadSession("group-a"))
            assertTrue(appState.ttsController.state.value is TtsState.Idle)
        }

    @Test
    fun explicitUnmappableStartReplacesPriorSpeechButDoesNotSpeakFromTheTop() =
        runBlocking {
            val appState = testAppState()
            val engine = FakeSessionEngine()
            appState.ttsController.attachEngine(engine)
            val entries = listOf(TtsSpeakableEntry("s", "Sender", "First. Second."))
            assertTrue(appState.speakAloud(entries, Locale.US))
            val spoken = engine.spoken.size
            assertFalse(
                appState.speakAloudPrepared(
                    entries,
                    Locale.US,
                    startRenderedHit = PreparedRenderedHit("missing", "changed", 0),
                ),
            )
            assertEquals(spoken, engine.spoken.size)
            assertTrue(appState.ttsController.state.value is TtsState.Idle)
        }

    private fun testAppState(twoAccounts: Boolean = false): WhiteNoiseAppState {
        val accounts =
            if (twoAccounts) {
                listOf("account-a" to "id-a", "account-b" to "id-b")
            } else {
                listOf("account-a" to "id-a")
            }
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(DiscardedDrafts),
            accountIdHexResolver = { null },
            accounts =
                accounts.map { (label, id) ->
                    AccountSummaryFfi(
                        label = label,
                        accountIdHex = id,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    )
                },
            activeAccountRef = "account-a",
            preferences = preferences,
        )
    }

    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
