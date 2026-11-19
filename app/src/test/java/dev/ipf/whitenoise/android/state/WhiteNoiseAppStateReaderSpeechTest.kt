package dev.ipf.whitenoise.android.state

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsEngineHandle
import dev.ipf.whitenoise.android.audio.tts.TtsResolutionResult
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedRenderedHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        assertTrue(appState.ttsController.state.value is TtsState.Idle)
        assertTrue(appState.ttsController.queuedMessagesSnapshot().isEmpty())
    }

    /** Platform Resume and stale callbacks cannot revive speech after an account round trip. */
    @Test
    fun pausedSpeechIsDiscardedBeforeAccountActivation() =
        runBlocking {
            val appState = testAppState(twoAccounts = true)
            val engine = FakeSessionEngine()
            appState.ttsController.attachEngine(engine)
            assertTrue(appState.speakAloud(listOf(TtsSpeakableEntry("s", "Sender", "Old private speech.")), Locale.US))
            appState.ttsController.pause()
            assertTrue(appState.ttsController.state.value is TtsState.Paused)
            val spoken = engine.spoken.size

            assertTrue(appState.setActiveAccount("account-b"))
            assertTrue(appState.setActiveAccount("account-a"))
            appState.ttsController.resume()
            engine.complete(0)
            assertTrue(appState.ttsController.state.value is TtsState.Idle)
            assertTrue(appState.ttsController.queuedMessagesSnapshot().isEmpty())
            assertFalse(appState.ownsCurrentAccountSpeech())
            assertEquals(spoken, engine.spoken.size)

            assertTrue(
                appState.speakAloud(listOf(TtsSpeakableEntry("s", "Sender", "Fresh private speech.")), Locale.US),
            )
            assertEquals(spoken + 1, engine.spoken.size)
            assertTrue(appState.ownsCurrentAccountSpeech())
            appState.stopSpeaking()
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
            val result =
                runCatching {
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

    @Test
    fun unacknowledgedEngineRequiresConsentBeforePlayback() =
        runBlocking {
            val appState = testAppState()
            val engine = FakeSessionEngine()
            appState.ttsController.attachEngine(engine)
            val entries = listOf(TtsSpeakableEntry("s", "Sender", "Private speech."))
            val handle = TtsEngineHandle(TextToSpeech(context, {}), "test.external.reader", EngineTrust.Unknown)
            var attachedHandle: TtsEngineHandle? = handle
            val consent = TtsPlaybackTrustConsent(appState) { attachedHandle }
            try {
                suspend fun startSpeech() = consent.confirm { true } && appState.speakAloud(entries, Locale.US)

                val dismissed = async(start = CoroutineStart.UNDISPATCHED) { startSpeech() }
                assertTrue(engine.spoken.isEmpty())
                requireNotNull(consent.pending).respond(false)
                assertFalse(dismissed.await())
                assertTrue(consent.needsAcknowledgment())
                assertTrue(engine.spoken.isEmpty())

                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { startSpeech() }
                val cancelledRequest = requireNotNull(consent.pending)
                consent.cancel()
                cancelledRequest.respond(true)
                assertFalse(cancelled.await())
                assertNull(consent.pending)
                assertTrue(consent.needsAcknowledgment())
                assertTrue(engine.spoken.isEmpty())

                val detached = async(start = CoroutineStart.UNDISPATCHED) { startSpeech() }
                val detachedRequest = requireNotNull(consent.pending)
                attachedHandle = null
                detachedRequest.respond(true)
                assertFalse(detached.await())
                assertNull(consent.pending)
                attachedHandle = handle
                assertTrue(consent.needsAcknowledgment())
                assertTrue(engine.spoken.isEmpty())

                val accepted = async(start = CoroutineStart.UNDISPATCHED) { startSpeech() }
                assertTrue(engine.spoken.isEmpty())
                requireNotNull(consent.pending).respond(true)
                assertTrue(accepted.await())
                assertFalse(consent.needsAcknowledgment())
                assertEquals(1, engine.spoken.size)
                appState.stopSpeaking()
            } finally {
                handle.release()
            }
        }

    /** Stored package approval cannot authorize an unverified Android connection or its replacement. */
    @Test
    fun unverifiedReplacementRequiresConsentDespiteSavedPackageApproval() =
        runBlocking {
            val appState = testAppState()
            val packageName = "test.external.reader"
            val entries = listOf(TtsSpeakableEntry("s", "Sender", "Private speech."))
            val original = ConsentRecordingTts(context)
            val replacement = ConsentRecordingTts(context)
            val publish =
                WhiteNoiseAppState::class.java
                    .getDeclaredMethod("publishTtsResolution", TtsResolutionResult::class.java)
                    .apply { isAccessible = true }

            fun attach(
                tts: TextToSpeech,
                trust: EngineTrust = EngineTrust.Unknown,
                verified: Boolean = false,
                enginePackage: String = packageName,
            ) {
                val handle = TtsEngineHandle(tts, enginePackage, trust, connectedPackageVerified = verified)
                publish.invoke(appState, TtsResolutionResult(TextToSpeech.SUCCESS, emptyList(), enginePackage, handle))
            }

            suspend fun startSpeech(): Boolean = appState.confirmTtsPlaybackTrust { true } && appState.speakAloud(entries, Locale.US)

            try {
                shadowOf(context.getSystemService(AudioManager::class.java))
                    .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                appState.acknowledgeTtsTrustWarning(packageName)
                attach(original)
                val accepted = async(start = CoroutineStart.UNDISPATCHED) { startSpeech() }
                assertEquals(0, original.utterances)
                val originalRequest = requireNotNull(appState.ttsPlaybackTrust.pending)
                originalRequest.respond(true)
                assertTrue(accepted.await())
                assertTrue(original.utterances > 0)
                assertTrue(appState.confirmTtsPlaybackTrust { true })
                assertNull(appState.ttsPlaybackTrust.pending)

                val previousStops = original.stops
                attach(replacement)
                assertTrue(original.stops > previousStops)
                assertTrue(appState.ttsController.queuedMessagesSnapshot().isEmpty())
                originalRequest.respond(true)
                val pending = async(start = CoroutineStart.UNDISPATCHED) { startSpeech() }
                assertEquals(0, replacement.utterances)
                requireNotNull(appState.ttsPlaybackTrust.pending).respond(false)
                assertFalse(pending.await())
                assertEquals(0, replacement.utterances)

                attach(replacement, verified = true)
                assertTrue(appState.confirmTtsPlaybackTrust { true })
                attach(replacement, trust = EngineTrust.Local, verified = true, enginePackage = "test.local.reader")
                assertTrue(appState.confirmTtsPlaybackTrust { true })
            } finally {
                publish.invoke(appState, null)
                original.shutdown()
                replacement.shutdown()
            }
        }

    private class ConsentRecordingTts(
        context: Context,
    ) : TextToSpeech(context, {}) {
        var utterances = 0
            private set
        var stops = 0
            private set

        override fun speak(
            text: CharSequence?,
            queueMode: Int,
            params: Bundle?,
            utteranceId: String?,
        ): Int {
            utterances++
            return SUCCESS
        }

        override fun stop(): Int {
            stops++
            return SUCCESS
        }

        override fun setLanguage(locale: Locale?): Int = LANG_COUNTRY_AVAILABLE
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

    @Test
    fun staleProjectionCannotStartAfterAutoReadIsTurnedOffAndBackOn() {
        val appState = testAppState()
        appState.ttsController.attachEngine(FakeSessionEngine())
        appState.setConversationAutoReadOverride("group-a", TtsAutoReadOverride.ON)
        val policyIsCurrent = appState.captureTtsAutoReadPolicy("group-a")
        assertTrue(policyIsCurrent())

        appState.setConversationAutoReadOverride("group-a", TtsAutoReadOverride.OFF)
        assertFalse(policyIsCurrent())
        appState.setConversationAutoReadOverride("group-a", TtsAutoReadOverride.ON)
        assertFalse(policyIsCurrent())
        assertTrue(appState.captureTtsAutoReadPolicy("group-a")())
        assertFalse(
            runBlocking {
                appState.speakAloudAutoRead(
                    "group-a",
                    listOf(TtsSpeakableEntry("old", "Sender", "Stale projection.")),
                    Locale.US,
                    backgroundPreparation = true,
                    canCommit = policyIsCurrent,
                )
            },
        )
        assertFalse(appState.ownsTtsAutoReadSession("group-a"))
        assertTrue(appState.ttsController.state.value is TtsState.Idle)
    }

    @Test
    fun staleProjectionCannotStartAfterSwitchingAwayAndBack() {
        val appState = testAppState(twoAccounts = true)
        appState.ttsController.attachEngine(FakeSessionEngine())
        val accountIsCurrent = appState.captureTtsAccountOwner("account-a")
        assertTrue(accountIsCurrent())

        runBlocking {
            appState.setActiveAccount("account-b")
            assertFalse(accountIsCurrent())
            appState.setActiveAccount("account-a")
            assertFalse(accountIsCurrent())
            assertTrue(appState.captureTtsAccountOwner("account-a")())
            assertFalse(
                appState.speakAloudAutoRead(
                    "group-a",
                    listOf(TtsSpeakableEntry("old", "Sender", "Stale projection.")),
                    Locale.US,
                    backgroundPreparation = true,
                    canCommit = accountIsCurrent,
                ),
            )
        }
        assertFalse(appState.ownsTtsAutoReadSession("group-a"))
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
                        unlinked = false,
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
