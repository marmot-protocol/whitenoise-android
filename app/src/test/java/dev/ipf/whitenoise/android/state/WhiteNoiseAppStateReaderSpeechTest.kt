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
import dev.ipf.whitenoise.android.audio.tts.TtsSpeechEngine
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceResolution
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

    /** An unadmitted replacement cannot clear an active session owner. */
    @Test
    fun onlyAReplacementThatReachesTheQueueRevokesPriorOwnership() {
        val appState = testAppState()
        val delegate = FakeSessionEngine()
        var rejectSpeech = false
        var rejectedSubmissions = 0
        val engine =
            object : TtsSpeechEngine by delegate {
                override fun speak(
                    text: String,
                    utteranceId: String,
                ): Int {
                    if (!rejectSpeech) return delegate.speak(text, utteranceId)
                    rejectedSubmissions++
                    return TextToSpeech.ERROR
                }

                override fun speak(
                    text: String,
                    utteranceId: String,
                    volume: Float,
                ): Int = speak(text, utteranceId)
            }
        appState.ttsController.attachEngine(engine)
        val entries = listOf(TtsSpeakableEntry("s", "Sender", "Private speech."))
        assertTrue(appState.speakAloudAutoRead("group-a", entries, Locale.US))
        val previous = appState.ttsController.state.value

        assertFalse(appState.speakAloud(emptyList(), Locale.US))
        assertEquals(previous, appState.ttsController.state.value)
        assertTrue(appState.ownsTtsAutoReadSession("group-a"))
        assertTrue(appState.ownsCurrentAccountSpeech())

        rejectSpeech = true
        appState.speakAloud(entries, Locale.US)
        assertTrue(rejectedSubmissions > 0)
        assertFalse(appState.ownsTtsAutoReadSession("group-a"))
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

    /** An offline-language refusal tears the queue down, so the earlier session's ownership must go too. */
    @Test
    fun unsupportedLanguageRefusalReleasesPriorSpeechOwnership() {
        val appState = testAppState()
        val entries = listOf(TtsSpeakableEntry("s", "Sender", "Private speech."))
        appState.ttsController.attachEngine(FakeSessionEngine())
        assertTrue(appState.speakAloud(entries, Locale.US))
        assertTrue(appState.ownsCurrentAccountSpeech())

        val delegate = FakeSessionEngine()
        appState.ttsController.attachEngine(
            object : TtsSpeechEngine by delegate {
                override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_NOT_SUPPORTED
            },
        )
        assertFalse(appState.speakAloud(entries, Locale.US))
        assertFalse(appState.ownsCurrentAccountSpeech())
        assertTrue(delegate.spoken.isEmpty())
    }

    /** An offline voice never substitutes for consent: Android cannot name the engine that answered. */
    @Test
    fun installedOfflineVoiceStillPromptsOnceForAnUnverifiedConnection() =
        runBlocking {
            val appState = testAppState()
            val engine = FakeSessionEngine()
            appState.ttsController.attachEngine(engine)
            val enginePackage = "test.external.reader"
            val voice = TtsVoiceKey(enginePackage, "offline", "en-US")
            val handle =
                TtsEngineHandle(
                    TextToSpeech(context, {}),
                    enginePackage,
                    EngineTrust.Unknown,
                    voiceResolution = TtsVoiceResolution.Empty.copy(effectiveKey = voice),
                )
            val consent = TtsPlaybackTrustConsent(appState) { handle }
            try {
                assertTrue(consent.needsAcknowledgment())
                val pending = async(start = CoroutineStart.UNDISPATCHED) { consent.confirm { true } }
                val request = requireNotNull(consent.pending)
                assertTrue(engine.spoken.isEmpty())
                assertTrue(appState.ttsController.state.value is TtsState.Idle)
                request.respond(false)
                assertFalse(pending.await())
                assertNull(consent.pending)
            } finally {
                handle.release()
            }
        }

    /** Per-connection approval belongs to one account session; a switch (even A-B-A) prompts again. */
    @Test
    fun unverifiedApprovalDoesNotSurviveAnAccountSwitch() =
        runBlocking {
            val appState = testAppState(twoAccounts = true)
            val handle = TtsEngineHandle(TextToSpeech(context, {}), "test.external.reader", EngineTrust.Unknown)
            val consent = TtsPlaybackTrustConsent(appState) { handle }
            try {
                val approved = async(start = CoroutineStart.UNDISPATCHED) { consent.confirm { true } }
                requireNotNull(consent.pending).respond(true)
                assertTrue(approved.await())
                assertFalse(consent.needsAcknowledgment())

                appState.setActiveAccount("account-b")
                assertTrue(consent.needsAcknowledgment())
                val prompted = async(start = CoroutineStart.UNDISPATCHED) { consent.confirm { true } }
                requireNotNull(consent.pending).respond(false)
                assertFalse(prompted.await())

                appState.setActiveAccount("account-a")
                assertTrue(consent.needsAcknowledgment())
            } finally {
                handle.release()
            }
        }

    /** Playback stays unstarted until its current unverified engine is approved. */
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

            fun attach(
                tts: TextToSpeech,
                trust: EngineTrust = EngineTrust.Unknown,
                verified: Boolean = false,
                enginePackage: String = packageName,
            ) {
                val handle = TtsEngineHandle(tts, enginePackage, trust, connectedPackageVerified = verified)
                appState.publishTtsResolution(
                    TtsResolutionResult(TextToSpeech.SUCCESS, emptyList(), enginePackage, handle),
                )
            }

            suspend fun startSpeech(): Boolean {
                if (!appState.confirmTtsPlaybackTrust { true }) return false
                return appState.speakAloud(entries, Locale.US)
            }

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
                assertTrue(appState.ttsController.state.value is TtsState.Idle)
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
                appState.publishTtsResolution(null)
                original.shutdown()
                replacement.shutdown()
            }
        }

    /**
     * An admitted auto-read session cannot survive a swap to an unverified connection: the swap stops the queue and
     * revokes ownership, so the continuation (whose host also requires consent) has nothing left to speak.
     */
    @Test
    fun autoReadSessionDoesNotContinueOntoAnUnverifiedReplacement() =
        runBlocking {
            val appState = testAppState()
            val original = ConsentRecordingTts(context)
            val replacement = ConsentRecordingTts(context)
            val entries = listOf(TtsSpeakableEntry("m1", "Sender", "Private speech."))

            fun attach(
                tts: TextToSpeech,
                verified: Boolean,
            ) {
                val trust = if (verified) EngineTrust.Local else EngineTrust.Unknown
                val handle = TtsEngineHandle(tts, "test.local.reader", trust, connectedPackageVerified = verified)
                appState.publishTtsResolution(
                    TtsResolutionResult(TextToSpeech.SUCCESS, emptyList(), "test.local.reader", handle),
                )
            }
            try {
                shadowOf(context.getSystemService(AudioManager::class.java))
                    .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                attach(original, verified = true)
                assertTrue(appState.speakAloudAutoRead("group-1", entries, Locale.US))
                assertTrue(appState.ownsTtsAutoReadSession("group-1"))
                assertTrue(original.utterances > 0)

                attach(replacement, verified = false)
                shadowOf(android.os.Looper.getMainLooper()).idle()

                assertEquals(0, replacement.utterances)
                assertNull(appState.ttsPlaybackTrust.pending)
                assertFalse(appState.ownsTtsAutoReadSession("group-1"))
                assertTrue(appState.ttsEngineNeedsAcknowledgment())
                assertTrue(appState.ttsController.state.value is TtsState.Idle)
            } finally {
                appState.publishTtsResolution(null)
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

    /** Toggling the policy twice does not revive a projection captured before either change. */
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

    /** Returning to an earlier account does not revive its previous projection. */
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
