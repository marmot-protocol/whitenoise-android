package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.audio.AttachmentSpeechDestination
import dev.ipf.whitenoise.android.audio.AttachmentSpeechOwner
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsController
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryPager
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Pins speech-account ownership and auto-read teardown at the read-aloud owner, without a full AppState. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppReadAloudTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val host = TestHost()
    private lateinit var readAloud: AppReadAloud

    @Before
    fun setUp() {
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        readAloud = AppReadAloud(context, host)
        host.readAloud = readAloud
        readAloud.ttsController.attachEngine(FakeSessionEngine())
    }

    @After
    fun tearDown() {
        readAloud.ttsController.stop()
        host.close()
    }

    @Test
    fun manualSpeechBelongsToTheAccountThatStartedIt() {
        assertTrue(readAloud.speakAloud(listOf(entry("Manual.")), Locale.US))

        assertEquals(ACCOUNT_A, readAloud.ttsSpeechAccountRef)
        assertNull(readAloud.ttsAutoReadSessionKey)
        assertTrue(readAloud.ownsCurrentAccountSpeech())
        assertEquals(0, host.continuationsCreated)

        host.activeAccountRef = ACCOUNT_B
        assertFalse(readAloud.ownsCurrentAccountSpeech())
        assertTrue(readAloud.ttsController.state.value is TtsState.Speaking)
    }

    @Test
    fun removingAnotherAccountKeepsSpeechAndRemovingTheOwnerStopsIt() {
        assertTrue(readAloud.speakAloud(listOf(entry("Private.")), Locale.US))
        val destination = attachmentDestination()
        readAloud.attachmentSpeechDestination.value = destination

        readAloud.stopTtsForRemovedAccount(ACCOUNT_B)

        assertTrue(readAloud.ttsController.state.value is TtsState.Speaking)
        assertEquals(ACCOUNT_A, readAloud.ttsSpeechAccountRef)
        assertEquals(destination, readAloud.attachmentSpeechDestination.value)

        readAloud.stopTtsForRemovedAccount(ACCOUNT_A)

        assertTrue(readAloud.ttsController.state.value is TtsState.Idle)
        assertNull(readAloud.ttsSpeechAccountRef)
        assertNull(readAloud.attachmentSpeechDestination.value)
    }

    @Test
    fun speechWithoutARecordedOwnerStopsWhenAnyAccountIsRemoved() {
        assertTrue(readAloud.ttsController.speak(listOf(entry("Unowned.")), Locale.US))
        assertNull(readAloud.ttsSpeechAccountRef)

        readAloud.stopTtsForRemovedAccount(ACCOUNT_B)

        assertTrue(readAloud.ttsController.state.value is TtsState.Idle)
    }

    @Test
    fun autoReadSessionIsScopedToItsAccountAndConversation() {
        assertTrue(readAloud.speakAloudAutoRead(GROUP_A, listOf(entry("Backlog.")), Locale.US))

        assertEquals("$ACCOUNT_A|$GROUP_A", readAloud.ttsAutoReadSessionKey)
        assertEquals(ACCOUNT_A, readAloud.ttsSpeechAccountRef)
        assertTrue(readAloud.ownsTtsAutoReadSession(GROUP_A))
        assertTrue(readAloud.ownsTtsAutoReadSession(GROUP_A.uppercase()))
        assertFalse(readAloud.ownsTtsAutoReadSession(GROUP_B))
        assertEquals(1, host.continuationsCreated)
        val source = requireNotNull(readAloud.ttsHistorySession.conversationSource.value)
        assertEquals(ACCOUNT_A, source.accountRef)
        assertEquals(GROUP_A, source.groupIdHex)

        // Another account's view of the same group never owns the session.
        host.activeAccountRef = ACCOUNT_B
        assertFalse(readAloud.ownsTtsAutoReadSession(GROUP_A))
    }

    @Test
    fun removingTheOwningAccountTearsDownTheAutoReadSession() {
        assertTrue(readAloud.speakAloudAutoRead(GROUP_A, listOf(entry("Backlog.")), Locale.US))
        readAloud.attachmentSpeechDestination.value = attachmentDestination()

        readAloud.stopTtsForRemovedAccount(ACCOUNT_A)

        assertTrue(readAloud.ttsController.state.value is TtsState.Idle)
        assertNull(readAloud.ttsAutoReadSessionKey)
        assertNull(readAloud.ttsSpeechAccountRef)
        assertNull(readAloud.ttsHistorySession.conversationSource.value)
        assertNull(readAloud.attachmentSpeechDestination.value)
        assertFalse(readAloud.ownsTtsAutoReadSession(GROUP_A))
    }

    @Test
    fun turningOffTheOwnedConversationEndsOnlyThatSession() {
        readAloud.setConversationAutoReadOverride(GROUP_A, TtsAutoReadOverride.ON)
        readAloud.setConversationAutoReadOverride(GROUP_B, TtsAutoReadOverride.ON)
        assertTrue(readAloud.speakAloudAutoRead(GROUP_A, listOf(entry("Backlog.")), Locale.US))
        val session = readAloud.ttsController.state.value.sessionId

        readAloud.setConversationAutoReadOverride(GROUP_B, TtsAutoReadOverride.OFF)
        assertTrue(readAloud.ownsTtsAutoReadSession(GROUP_A))
        val playing = readAloud.ttsController.state.value
        assertTrue(playing is TtsState.Speaking)
        assertEquals(session, playing.sessionId)

        readAloud.setConversationAutoReadOverride(GROUP_A, TtsAutoReadOverride.OFF)

        assertTrue(readAloud.ttsController.state.value is TtsState.Idle)
        assertNull(readAloud.ttsAutoReadSessionKey)
        assertNull(readAloud.ttsSpeechAccountRef)
        assertNull(readAloud.ttsHistorySession.conversationSource.value)
    }

    @Test
    fun manualSpeechReplacesAnAutoReadSessionAndKeepsItsAccount() {
        assertTrue(readAloud.speakAloudAutoRead(GROUP_A, listOf(entry("Backlog.")), Locale.US))

        assertTrue(readAloud.speakAloud(listOf(entry("Manual.")), Locale.US))

        assertNull(readAloud.ttsAutoReadSessionKey)
        assertFalse(readAloud.ownsTtsAutoReadSession(GROUP_A))
        assertNull(readAloud.ttsHistorySession.conversationSource.value)
        assertEquals(ACCOUNT_A, readAloud.ttsSpeechAccountRef)
    }

    @Test
    fun stopSpeakingRevokesEveryOwnershipCredential() {
        assertTrue(readAloud.speakAloudAutoRead(GROUP_A, listOf(entry("Backlog.")), Locale.US))
        readAloud.attachmentSpeechDestination.value = attachmentDestination()

        readAloud.stopSpeaking()

        assertTrue(readAloud.ttsController.state.value is TtsState.Idle)
        assertNull(readAloud.ttsAutoReadSessionKey)
        assertNull(readAloud.ttsSpeechAccountRef)
        assertNull(readAloud.ttsHistorySession.conversationSource.value)
        assertNull(readAloud.attachmentSpeechDestination.value)
        assertFalse(readAloud.ownsCurrentAccountSpeech())
    }

    private fun entry(text: String) = TtsSpeakableEntry("message-${text.hashCode()}", "Sender", text)

    private fun attachmentDestination() =
        AttachmentSpeechDestination(
            AttachmentSpeechOwner(ACCOUNT_A, GROUP_A, "message", attachmentIndex = 0),
            sessionId = readAloud.ttsController.state.value.sessionId,
        )

    private class TestHost : AppReadAloudHost {
        lateinit var readAloud: AppReadAloud
        var continuationsCreated = 0
        private val scopes = mutableListOf<CoroutineScope>()

        override var activeAccountRef: String? = ACCOUNT_A
        override val accountSwitchHandoff = AccountSwitchLocalSnapshotHandoff()
        override val mutationsScope = mainScope()
        override val appLockScreenVisible = false

        override fun createHistoryScope(): CoroutineScope = mainScope()

        // The continuation's native subscription never runs: its scope has no scheduler driving it.
        override fun createAutoReadContinuation(): TtsAutoReadContinuation {
            continuationsCreated += 1
            return TtsAutoReadContinuation(
                IdleContinuationHost(readAloud.ttsController),
                CoroutineScope(StandardTestDispatcher()),
            )
        }

        override fun historyPager(
            accountRef: String?,
            groupIdHex: String,
        ): TtsHistoryPager? = null

        fun close() = scopes.forEach { it.cancel() }

        private fun mainScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also(scopes::add)
    }

    private class IdleContinuationHost(
        override val controller: TtsController,
    ) : TtsAutoReadContinuationHost {
        override val appendDeferred = false

        override fun owns(
            account: String,
            group: String,
            session: Long,
        ) = false

        override suspend fun awaitAppendReadiness() = Unit

        override suspend fun awaitTailAttachment() = Unit

        override fun allowsAppend() = false

        override suspend fun open(
            account: String,
            group: String,
        ): ConversationTimelineSubscriptionHandle = awaitCancellation()

        override suspend fun project(record: TimelineMessageRecordFfi): TtsSpeakableEntry? = null
    }

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val GROUP_A = "group-a"
        const val GROUP_B = "group-b"
    }
}
