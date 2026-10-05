package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedRenderedHit
import kotlinx.coroutines.CancellationException
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

    /** Removes persisted platform speech preferences so each ownership test starts independently. */
    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    /** Switching accounts immediately removes the reader's authority over the old account's speech. */
    @Test
    fun readerCannotOwnSpeechFromAnotherLocalAccount() {
        val appState = testAppState(twoAccounts = true)
        appState.ttsController.attachEngine(FakeSessionEngine())
        assertTrue(appState.speakAloud(listOf(TtsSpeakableEntry("s", "Sender", "Private speech.")), Locale.US))
        assertTrue(appState.ownsCurrentAccountSpeech())
        runBlocking { appState.setActiveAccount("account-b") }
        assertFalse(appState.ownsCurrentAccountSpeech())
    }

    /** A reader revoked before preparation leaves an existing manual queue and its ownership untouched. */
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

    /** An A-B-A activation cannot restore the ownership of a request already preparing private speech. */
    @Test
    fun accountRoundTripDuringPreparationCannotCommitOldSpeech() =
        runBlocking {
            val appState = testAppState(twoAccounts = true)
            val engine = FakeSessionEngine()
            appState.ttsController.attachEngine(engine)
            var switched = false
            val started =
                appState.speakAloudPrepared(
                    listOf(TtsSpeakableEntry("s", "Sender", "Old account request.")),
                    Locale.US,
                    isCurrent = {
                        if (!switched && appState.ttsController.state.value is TtsState.Preparing) {
                            switched = true
                            runBlocking {
                                assertTrue(appState.setActiveAccount("account-b"))
                                assertTrue(appState.setActiveAccount("account-a"))
                            }
                        }
                        true
                    },
                )
            assertTrue("The production account round trip must run during preparation", switched)
            assertFalse("Returning to account A must not revive its old prepared request", started)
            assertTrue(engine.spoken.isEmpty())
            assertFalse(appState.ownsCurrentAccountSpeech())
            assertTrue(appState.ttsController.state.value is TtsState.Idle)
            assertTrue(
                appState.speakAloudPrepared(
                    listOf(TtsSpeakableEntry("s", "Sender", "Fresh account request.")),
                    Locale.US,
                ),
            )
            assertEquals(1, engine.spoken.size)
            assertTrue(appState.ownsCurrentAccountSpeech())
        }

    /** A superseded preparation cannot stop a replacement manual queue or clear its account ownership. */
    @Test
    fun replacementSpeechKeepsOwnershipWhenOldPreparationIsRejected() =
        runBlocking {
            val appState = testAppState()
            val engine = FakeSessionEngine()
            appState.ttsController.attachEngine(engine)
            var replacementSession = 0L
            val started =
                appState.speakAloudPrepared(
                    listOf(TtsSpeakableEntry("s", "Sender", "Old request.")),
                    Locale.US,
                    isCurrent = {
                        if (appState.ttsController.state.value is TtsState.Preparing) {
                            assertTrue(
                                appState.speakAloud(
                                    listOf(TtsSpeakableEntry("s", "Sender", "Replacement.")),
                                    Locale.US,
                                ),
                            )
                            replacementSession = appState.ttsController.state.value.sessionId
                            false
                        } else {
                            true
                        }
                    },
                )
            assertFalse(started)
            assertTrue(appState.ownsCurrentAccountSpeech())
            assertTrue(appState.ttsController.state.value is TtsState.Speaking)
            assertEquals(replacementSession, appState.ttsController.state.value.sessionId)
            assertEquals(1, engine.spoken.size)
        }

    /** Cancellation after foreground acquisition releases only the abandoned preparation's ownership. */
    @Test
    fun cancelledPreparationReleasesAccountOwnership() =
        runBlocking {
            val appState = testAppState()
            val engine = FakeSessionEngine()
            appState.ttsController.attachEngine(engine)
            val result = runCatching {
                appState.speakAloudPrepared(
                    listOf(TtsSpeakableEntry("s", "Sender", "Cancelled request.")),
                    Locale.US,
                    isCurrent = {
                        if (appState.ttsController.state.value is TtsState.Preparing) {
                            throw CancellationException("fixture cancelled preparation")
                        }
                        true
                    },
                )
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertTrue(appState.ttsController.state.value is TtsState.Idle)
            assertFalse(appState.ownsCurrentAccountSpeech())
            assertTrue(engine.spoken.isEmpty())
        }

    /** An unmappable conversation start neither guesses the top nor acquires an auto-read session. */
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

    /** Explicit replacement may stop the old queue, but an invalid hit never speaks a guessed sentence. */
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

    /** Creates account labels without a network/runtime or persistent protocol/draft cache. */
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
        /** Fixture drafts are never retained outside the test's transient AppState. */
        override fun read(): Map<String, String> = emptyMap()

        /** Deliberately persists nothing, keeping protocol/draft ownership outside this fixture. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
