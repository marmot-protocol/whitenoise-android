package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationFailure
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Acceptance matrix for the app-lifetime coordinator contract in issue #2030. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCoordinatorTest {
    @Test
    fun cancelIsIdempotentInEveryNonterminalState() {
        verifyCancellation(
            fixture = fixture(disclosureAccepted = false),
            start = { it.controller.requestStart(ACCOUNT, GROUP, it.draft) },
            expectedState = ConversationDictationState.DisclosureRequired::class.java,
            ownsRecognizer = false,
        )
        verifyCancellation(
            fixture = fixture(platform = MatrixPlatform(hasPermission = false)),
            start = { it.controller.requestStart(ACCOUNT, GROUP, it.draft) },
            expectedState = ConversationDictationState.PermissionRequired::class.java,
            ownsRecognizer = false,
        )
        verifyCancellation(
            fixture = fixture(),
            start = { it.controller.requestStart(ACCOUNT, GROUP, it.draft) },
            expectedState = ConversationDictationState.Starting::class.java,
            ownsRecognizer = true,
        )
        verifyCancellation(
            fixture = fixture(),
            start = {
                it.controller.requestStart(ACCOUNT, GROUP, it.draft)
                it.platform.listener.onReady()
            },
            expectedState = ConversationDictationState.Listening::class.java,
            ownsRecognizer = true,
        )
        verifyCancellation(
            fixture = fixture(),
            start = {
                it.controller.requestStart(ACCOUNT, GROUP, it.draft)
                it.platform.listener.onEndOfSpeech()
            },
            expectedState = ConversationDictationState.Processing::class.java,
            ownsRecognizer = true,
        )
        verifyCancellation(
            fixture = fixture(),
            start = { it.controller.requestProviderActivityStart(ACCOUNT, GROUP, it.draft) },
            expectedState = ConversationDictationState.ProviderActivityRequired::class.java,
            ownsRecognizer = false,
        )
        verifyCancellation(
            fixture = fixture(),
            start = {
                it.controller.requestProviderActivityStart(ACCOUNT, GROUP, it.draft)
                it.controller.beginProviderActivityLaunch(it.controller.providerActivityRequestId)
            },
            expectedState = ConversationDictationState.ProviderActivityActive::class.java,
            ownsRecognizer = false,
        )
    }

    /** Verifies terminal callbacks racing durable-service teardown release each resource exactly once. */
    @Test
    fun durableLifecycleAndTerminalCallbackRaceReleaseResourcesExactlyOnce() {
        val teardownFirst = fixture()
        teardownFirst.controller.requestStart(ACCOUNT, GROUP, teardownFirst.draft)
        val teardownListener = teardownFirst.platform.listener
        val teardownSession = teardownFirst.platform.session
        teardownListener.onReady()

        teardownFirst.controller.onAppBackgrounded()
        teardownFirst.controller.onTaskRemoved()
        assertTrue(teardownFirst.controller.state is ConversationDictationState.Listening)
        assertEquals(0, teardownFirst.releases)
        teardownFirst.controller.cancel()
        teardownListener.onResult("late result")
        teardownListener.onError(ConversationDictationFailure.Unknown)
        teardownFirst.controller.onAppBackgrounded()
        teardownFirst.controller.cancel()

        assertTrue(teardownFirst.controller.state is ConversationDictationState.Idle)
        assertEquals("Keep", teardownFirst.draft.text)
        assertEquals(0, teardownFirst.writes)
        assertEquals(1, teardownFirst.releases)
        assertEquals(1, teardownSession.cancelCalls)
        assertEquals(1, teardownSession.destroyCalls)

        val terminalFirst = fixture()
        terminalFirst.controller.requestStart(ACCOUNT, GROUP, terminalFirst.draft)
        val terminalListener = terminalFirst.platform.listener
        val terminalSession = terminalFirst.platform.session

        terminalFirst.controller.stop()
        terminalListener.onResult("accepted")
        terminalFirst.controller.onAppBackgrounded()
        terminalFirst.controller.cancel()
        terminalListener.onError(ConversationDictationFailure.Unknown)

        assertEquals("Keep accepted", terminalFirst.draft.text)
        assertEquals(1, terminalFirst.writes)
        assertEquals(1, terminalFirst.releases)
        assertEquals(0, terminalSession.cancelCalls)
        assertEquals(1, terminalSession.destroyCalls)
    }

    @Test
    fun permissionLossAndProviderErrorReleaseResourcesAndRejectLateDelivery() {
        listOf(
            ConversationDictationFailure.PermissionDenied,
            ConversationDictationFailure.Unknown,
        ).forEach { failure ->
            val fixture = fixture()
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft)
            val listener = fixture.platform.listener
            val session = fixture.platform.session
            listener.onReady()

            listener.onError(failure)
            listener.onResult("late")

            if (failure == ConversationDictationFailure.PermissionDenied) {
                assertTrue(fixture.controller.state is ConversationDictationState.ProviderActivityRequired)
            } else {
                assertEquals(failure, (fixture.controller.state as ConversationDictationState.Failed).reason)
            }
            assertEquals("Keep", fixture.draft.text)
            assertEquals(0, fixture.writes)
            assertEquals(1, fixture.releases)
            assertEquals(0, session.cancelCalls)
            assertEquals(1, session.destroyCalls)
        }
    }

    @Test
    fun providerActivityResultOwnerSurvivesHostRecreationWithoutRelaunch() {
        val fixture = fixture()
        fixture.controller.requestProviderActivityStart(ACCOUNT, GROUP, fixture.draft)
        val requestId = fixture.controller.providerActivityRequestId
        assertTrue(fixture.controller.beginProviderActivityLaunch(requestId))

        // Launching the external provider stops the host Activity. A recreated
        // Activity observes the same application-owned controller/request id,
        // must not relaunch it, and still receives the registered result.
        fixture.controller.onAppBackgrounded()
        assertTrue(fixture.controller.state is ConversationDictationState.ProviderActivityActive)
        assertFalse(fixture.controller.beginProviderActivityLaunch(requestId))

        fixture.controller.onProviderActivityResult("after recreation")
        fixture.controller.onProviderActivityResult("duplicate")

        assertEquals("Keep after recreation", fixture.draft.text)
        assertEquals(1, fixture.writes)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Verifies legal start, listen, process, success, and empty-result transitions release ownership. */
    @Test
    fun legalRecognitionTransitionsCoverSuccessAndEmptyResult() {
        val success = fixture()
        assertTrue(success.controller.requestStart(ACCOUNT, GROUP, success.draft))
        assertTrue(success.controller.state is ConversationDictationState.Starting)

        success.platform.listener.onReady()
        assertTrue(success.controller.state is ConversationDictationState.Listening)
        success.controller.stop()
        assertTrue(success.controller.state is ConversationDictationState.Processing)
        assertEquals(1, success.platform.session.stopCalls)

        success.platform.listener.onResult("  accepted  ")
        assertTrue(success.controller.state is ConversationDictationState.Idle)
        assertEquals("Keep accepted", success.draft.text)
        assertEquals(1, success.writes)
        assertEquals(listOf(ACCOUNT to GROUP), success.writeTargets)
        assertEquals(1, success.releases)

        val empty = fixture()
        empty.controller.requestStart(ACCOUNT, GROUP, empty.draft)
        empty.platform.listener.onEndOfSpeech()
        assertTrue(empty.controller.state is ConversationDictationState.Processing)
        empty.controller.stop()
        empty.platform.listener.onResult("  ")

        val failure = empty.controller.state as ConversationDictationState.Failed
        assertEquals(ConversationDictationFailure.NoSpeech, failure.reason)
        assertEquals("Keep", empty.draft.text)
        assertEquals(0, empty.writes)
        assertEquals(1, empty.releases)
    }

    /** Verifies duplicate starts are inert and replacement sessions invalidate stale provider callbacks. */
    @Test
    fun duplicateStartIsRejectedAndReplacementInvalidatesOldGeneration() {
        val fixture = fixture()
        assertTrue(fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft))
        val oldListener = fixture.platform.listener
        val oldSession = fixture.platform.session

        assertFalse(fixture.controller.requestStart(ACCOUNT, GROUP, fixture.draft))
        assertEquals(0, oldSession.cancelCalls)
        assertEquals(0, oldSession.destroyCalls)

        assertTrue(fixture.controller.requestStart(ACCOUNT, OTHER_GROUP, fixture.draft))
        val replacementListener = fixture.platform.listener
        assertEquals(1, oldSession.cancelCalls)
        assertEquals(1, oldSession.destroyCalls)
        assertTrue(fixture.controller.isOwnedBy(ACCOUNT, OTHER_GROUP))

        oldListener.onResult("stale")
        assertEquals(0, fixture.writes)
        fixture.controller.stop()
        replacementListener.onResult("replacement")

        assertEquals("Keep replacement", fixture.draft.text)
        assertEquals(listOf(ACCOUNT to OTHER_GROUP), fixture.writeTargets)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Verifies navigation retains the immutable origin while actual origin removal cancels delivery. */
    @Test
    fun navigationKeepsImmutableOriginWhileTargetDisappearanceCancelsDelivery() {
        val navigation = fixture()
        navigation.controller.requestStart(ACCOUNT, GROUP, navigation.draft)
        val listener = navigation.platform.listener

        // Mounting another conversation has no controller command: ownership
        // remains immutable until the origin receives its result.
        assertFalse(navigation.controller.isOwnedBy(ACCOUNT, OTHER_GROUP))
        assertTrue(navigation.controller.isOwnedBy(ACCOUNT, GROUP))
        navigation.controller.stop()
        listener.onResult("origin only")

        assertEquals(listOf(ACCOUNT to GROUP), navigation.writeTargets)
        assertEquals("Keep origin only", navigation.draft.text)

        var targetAvailable = true
        val disappeared = fixture(targetAvailable = { _, _ -> targetAvailable })
        disappeared.controller.requestStart(ACCOUNT, GROUP, disappeared.draft)
        val lateListener = disappeared.platform.listener
        targetAvailable = false
        lateListener.onResult("must not write")

        assertTrue(disappeared.controller.state is ConversationDictationState.Idle)
        assertEquals("Keep", disappeared.draft.text)
        assertEquals(0, disappeared.writes)
        assertEquals(1, disappeared.releases)

        val accountRemoved = fixture()
        accountRemoved.controller.requestStart(ACCOUNT, GROUP, accountRemoved.draft)
        accountRemoved.controller.onAccountUnavailable(ACCOUNT)
        assertTrue(accountRemoved.controller.state is ConversationDictationState.Idle)
        assertEquals(0, accountRemoved.writes)

        val groupRemoved = fixture()
        groupRemoved.controller.requestStart(ACCOUNT, GROUP, groupRemoved.draft)
        groupRemoved.controller.onTargetRemoved(ACCOUNT, GROUP)
        assertTrue(groupRemoved.controller.state is ConversationDictationState.Idle)
        assertEquals(0, groupRemoved.writes)
    }

    @Test
    fun denialUnavailableProviderAndMicrophoneConflictAreDeterministic() {
        val denied = fixture(platform = MatrixPlatform(hasPermission = false))
        denied.controller.requestStart(ACCOUNT, GROUP, denied.draft)
        assertTrue(denied.controller.state is ConversationDictationState.PermissionRequired)
        denied.controller.onPermissionResult(granted = false)
        assertEquals(
            ConversationDictationFailure.PermissionDenied,
            (denied.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0, denied.writes)

        val permanentlyDenied = fixture(platform = MatrixPlatform(hasPermission = false))
        permanentlyDenied.controller.requestStart(ACCOUNT, GROUP, permanentlyDenied.draft)
        permanentlyDenied.controller.onPermissionResult(granted = false, permanentlyDenied = true)
        assertEquals(
            ConversationDictationFailure.PermissionPermanentlyDenied,
            (permanentlyDenied.controller.state as ConversationDictationState.Failed).reason,
        )

        val unavailable = fixture(platform = MatrixPlatform(recognitionAvailable = false))
        unavailable.controller.requestStart(ACCOUNT, GROUP, unavailable.draft)
        assertEquals(
            ConversationDictationFailure.ProviderUnavailable,
            (unavailable.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0, unavailable.releases)

        val providerActivityUnavailable = fixture(platform = MatrixPlatform(providerActivityAvailable = false))
        providerActivityUnavailable.controller.requestProviderActivityStart(
            ACCOUNT,
            GROUP,
            providerActivityUnavailable.draft,
        )
        assertEquals(
            ConversationDictationFailure.ProviderUnavailable,
            (providerActivityUnavailable.controller.state as ConversationDictationState.Failed).reason,
        )

        val microphoneBusy = fixture(tryAcquireMicrophone = { false })
        microphoneBusy.controller.requestStart(ACCOUNT, GROUP, microphoneBusy.draft)
        assertEquals(
            ConversationDictationFailure.MicrophoneInUse,
            (microphoneBusy.controller.state as ConversationDictationState.Failed).reason,
        )
        assertFalse(microphoneBusy.controller.ownsMicrophone)
        assertEquals(0, microphoneBusy.releases)
        assertEquals(0, microphoneBusy.writes)
    }

    private fun verifyCancellation(
        fixture: Fixture,
        start: (Fixture) -> Unit,
        expectedState: Class<*>,
        ownsRecognizer: Boolean,
    ) {
        start(fixture)
        assertTrue(expectedState.isInstance(fixture.controller.state))
        val listener = fixture.platform.listenerOrNull
        val session = fixture.platform.sessionOrNull

        fixture.controller.cancel()
        fixture.controller.cancel()
        listener?.onResult("late")
        listener?.onError(ConversationDictationFailure.Unknown)

        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertEquals("Keep", fixture.draft.text)
        assertEquals(0, fixture.writes)
        assertEquals(if (ownsRecognizer) 1 else 0, fixture.releases)
        assertEquals(if (ownsRecognizer) 1 else 0, session?.cancelCalls ?: 0)
        assertEquals(if (ownsRecognizer) 1 else 0, session?.destroyCalls ?: 0)
    }

    private fun fixture(
        platform: MatrixPlatform = MatrixPlatform(),
        disclosureAccepted: Boolean = true,
        targetAvailable: (String, String) -> Boolean = { _, _ -> true },
        tryAcquireMicrophone: () -> Boolean = { true },
    ): Fixture {
        var accepted = disclosureAccepted
        var draft = TextFieldValue("Keep", TextRange(4))
        var revision = 0L
        var writes = 0
        var releases = 0
        val writeTargets = mutableListOf<Pair<String, String>>()
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, revision) },
                writeDraft = { accountRef, groupIdHex, expected, value ->
                    if (expected != revision) {
                        false
                    } else {
                        draft = value
                        revision += 1L
                        writes += 1
                        writeTargets += accountRef to groupIdHex
                        true
                    }
                },
                targetAvailable = targetAvailable,
                tryAcquireMicrophone = tryAcquireMicrophone,
                releaseMicrophone = { releases += 1 },
                disclosureAccepted = { accepted },
                markDisclosureAccepted = { accepted = true },
                scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
            )
        return Fixture(
            controller = controller,
            platform = platform,
            readDraft = { draft },
            readWrites = { writes },
            readReleases = { releases },
            readWriteTargets = { writeTargets.toList() },
        )
    }

    private data class Fixture(
        val controller: ConversationDictationController,
        val platform: MatrixPlatform,
        private val readDraft: () -> TextFieldValue,
        private val readWrites: () -> Int,
        private val readReleases: () -> Int,
        private val readWriteTargets: () -> List<Pair<String, String>>,
    ) {
        val draft: TextFieldValue
            get() = readDraft()
        val writes: Int
            get() = readWrites()
        val releases: Int
            get() = readReleases()
        val writeTargets: List<Pair<String, String>>
            get() = readWriteTargets()
    }

    private class MatrixPlatform(
        private val hasPermission: Boolean = true,
        private val recognitionAvailable: Boolean = true,
        private val providerActivityAvailable: Boolean = true,
    ) : ConversationDictationPlatform {
        var listenerOrNull: ConversationDictationRecognitionListener? = null
            private set
        var sessionOrNull: MatrixSession? = null
            private set

        val listener: ConversationDictationRecognitionListener
            get() = requireNotNull(listenerOrNull)
        val session: MatrixSession
            get() = requireNotNull(sessionOrNull)

        override fun hasRecordAudioPermission() = hasPermission

        override fun recognitionAvailable() = recognitionAvailable

        override fun recognitionActivityAvailable() = providerActivityAvailable

        @Suppress("MaxLineLength")
        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            this.listenerOrNull = listener
            return MatrixSession().also { sessionOrNull = it }
        }
    }

    private class MatrixSession : ConversationDictationRecognitionSession {
        var stopCalls = 0
        var cancelCalls = 0
        var destroyCalls = 0

        override fun start() = Unit

        override fun stop() {
            stopCalls += 1
        }

        override fun cancel() {
            cancelCalls += 1
        }

        override fun destroy() {
            destroyCalls += 1
        }
    }

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val OTHER_GROUP = "other-group"
    }
}
