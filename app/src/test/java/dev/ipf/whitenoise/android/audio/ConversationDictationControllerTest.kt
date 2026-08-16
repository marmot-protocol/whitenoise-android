package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationControllerTest {
    @Test
    fun resultPopulatesAnEmptyDraftAndLeavesItEditable() {
        val fixture = fixture(draft = TextFieldValue("", TextRange.Zero))

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("editable words")

        assertEquals("editable words", fixture.drafts.getValue(key()).text)
        assertEquals(TextRange("editable words".length), fixture.drafts.getValue(key()).selection)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun resultIsInsertedAtCapturedSelectionAndNeverSent() {
        val fixture = fixture(draft = TextFieldValue("Hello world", TextRange(6, 11)))

        assertTrue(fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key())))
        fixture.platform.listener.onReady()
        fixture.platform.listener.onResult("Marmot")

        assertEquals("Hello Marmot", fixture.drafts.getValue(key()).text)
        assertEquals(TextRange(12), fixture.drafts.getValue(key()).selection)
        assertEquals(1, fixture.writes)
        assertEquals(1, fixture.controller.completionRevision(ACCOUNT, GROUP))
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun resultSeparatesTranscriptFromAdjacentUnicodeLetters() {
        val cases =
            listOf(
                "A\uD840\uDC00" to "A dictated \uD840\uDC00",
                "אב" to "א dictated ב",
            )

        cases.forEach { (draft, expected) ->
            val fixture = fixture(draft = TextFieldValue(draft, TextRange(1)))

            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onResult("dictated")

            assertEquals(expected, fixture.drafts.getValue(key()).text)
        }
    }

    @Test
    fun resultKeepsAdjacentPunctuationTight() {
        val fixture = fixture(draft = TextFieldValue("Hello,", TextRange(5)))

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("dictated")

        assertEquals("Hello dictated,", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun concurrentSuffixEditKeepsTheCapturedInsertionAnchor() {
        val fixture = fixture(draft = TextFieldValue("First", TextRange(5)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()
        fixture.edit(key(), TextFieldValue("First plus typed", TextRange(16)))

        fixture.platform.listener.onResult("dictated")

        assertEquals("First dictated plus typed", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun resultAlwaysBelongsToOriginatingConversationAfterNavigation() {
        val fixture = fixture(draft = TextFieldValue("Source ", TextRange(7)))
        fixture.drafts[OTHER_ACCOUNT to OTHER_GROUP] = TextFieldValue("Other", TextRange(5))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()

        fixture.platform.listener.onResult("message")

        assertEquals("Source message", fixture.drafts.getValue(key()).text)
        assertEquals("Other", fixture.drafts.getValue(OTHER_ACCOUNT to OTHER_GROUP).text)
    }

    @Test
    fun cancellationMakesLateCallbacksNoOps() {
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val staleListener = fixture.platform.listener
        staleListener.onReady()

        fixture.controller.cancel()
        staleListener.onResult("discard me")

        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertEquals(0, fixture.writes)
        assertTrue(fixture.platform.session.cancelled)
        assertTrue(fixture.platform.session.destroyed)
    }

    @Test
    fun accountBecomingUnavailableCancelsOwnedSession() {
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()

        fixture.controller.onAccountUnavailable(ACCOUNT)

        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertTrue(fixture.platform.session.cancelled)
    }

    @Test
    fun removedSourceConversationDropsResultWithoutRecreatingDraft() {
        var targetAvailable = true
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)), targetAvailable = { targetAvailable })
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()
        targetAvailable = false

        fixture.platform.listener.onResult("discard me")

        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertEquals(0, fixture.writes)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun playbackIsStoppedBeforeRecognizerStarts() {
        var playbackStopped = false
        val fixture =
            fixture(
                draft = TextFieldValue("", TextRange.Zero),
                onBeforeRecognition = { playbackStopped = true },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertTrue(playbackStopped)
        assertTrue(fixture.platform.session.started)
    }

    @Test
    fun firstUseDisclosureAndPermissionAreExplicitGates() {
        var disclosureAccepted = false
        var disclosureMarked = false
        val platform = FakePlatform(hasPermission = false)
        val drafts = mutableMapOf(key() to TextFieldValue("", TextRange.Zero))
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { account, group ->
                    ConversationDictationDraftSnapshot(drafts.getValue(account to group), 0)
                },
                writeDraft = { account, group, _, value ->
                    drafts[account to group] = value
                    true
                },
                disclosureAccepted = { disclosureAccepted },
                markDisclosureAccepted = {
                    disclosureAccepted = true
                    disclosureMarked = true
                },
                elapsedRealtime = { 100L },
            )

        controller.requestStart(ACCOUNT, GROUP, drafts.getValue(key()))
        assertTrue(controller.state is ConversationDictationState.DisclosureRequired)
        assertFalse(platform.session.started)

        controller.acceptDisclosure()
        assertTrue(disclosureMarked)
        assertTrue(controller.state is ConversationDictationState.PermissionRequired)
        assertEquals(1L, controller.permissionRequestId)

        platform.hasPermission = true
        controller.onPermissionResult(true)
        assertTrue(controller.state is ConversationDictationState.Starting)
        assertTrue(platform.session.started)
    }

    @Test
    fun providerActivityPathUsesProviderUiWithoutAppPermissionOrMicrophoneLease() {
        var microphoneAcquireCalls = 0
        val platform = FakePlatform(hasPermission = false)
        val fixture =
            fixture(
                draft = TextFieldValue("Hello ", TextRange(6)),
                platform = platform,
                tryAcquireMicrophone = {
                    microphoneAcquireCalls += 1
                    true
                },
            )

        assertTrue(
            fixture.controller.requestProviderActivityStart(
                ACCOUNT,
                GROUP,
                fixture.drafts.getValue(key()),
            ),
        )
        assertTrue(fixture.controller.state is ConversationDictationState.ProviderActivityRequired)
        assertEquals(1L, fixture.controller.providerActivityRequestId)
        assertEquals(0, microphoneAcquireCalls)
        assertFalse(fixture.controller.ownsMicrophone)
        assertFalse(platform.session.started)

        assertTrue(fixture.controller.beginProviderActivityLaunch(1L))
        fixture.controller.onProviderActivityResult("provider words")

        assertEquals("Hello provider words", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertEquals(1, fixture.writes)
    }

    @Test
    fun providerActivityAlsoWaitsForTheSharedFirstUseDisclosure() {
        var accepted = false
        val controller =
            ConversationDictationController(
                platform = FakePlatform(hasPermission = false),
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue(""), 0) },
                writeDraft = { _, _, _, _ -> true },
                disclosureAccepted = { accepted },
                markDisclosureAccepted = { accepted = true },
            )

        controller.requestProviderActivityStart(ACCOUNT, GROUP, TextFieldValue(""))
        assertTrue(controller.state is ConversationDictationState.DisclosureRequired)
        assertEquals(0L, controller.providerActivityRequestId)

        controller.acceptDisclosure()

        assertTrue(controller.state is ConversationDictationState.ProviderActivityRequired)
        assertEquals(1L, controller.providerActivityRequestId)
        assertFalse(controller.ownsMicrophone)
    }

    @Test
    fun providerActivityCancellationAndUnavailableProviderAreDeterministic() {
        val cancelled = fixture(draft = TextFieldValue("Keep"))
        cancelled.controller.requestProviderActivityStart(ACCOUNT, GROUP, cancelled.drafts.getValue(key()))
        cancelled.controller.beginProviderActivityLaunch(cancelled.controller.providerActivityRequestId)

        cancelled.controller.onProviderActivityCancelled()
        cancelled.controller.onProviderActivityResult("late")

        assertEquals("Keep", cancelled.drafts.getValue(key()).text)
        assertEquals(0, cancelled.writes)
        assertTrue(cancelled.controller.state is ConversationDictationState.Idle)

        val unavailable =
            fixture(
                draft = TextFieldValue("Keep"),
                platform = FakePlatform(activityAvailable = false),
            )
        unavailable.controller.requestProviderActivityStart(ACCOUNT, GROUP, unavailable.drafts.getValue(key()))

        assertEquals(
            ConversationDictationFailure.ProviderUnavailable,
            (unavailable.controller.state as ConversationDictationState.Failed).reason,
        )

        val empty = fixture(draft = TextFieldValue("Keep", TextRange(4)))
        empty.controller.requestProviderActivityStart(ACCOUNT, GROUP, empty.drafts.getValue(key()))
        empty.controller.beginProviderActivityLaunch(empty.controller.providerActivityRequestId)
        empty.controller.onProviderActivityResult("   ")
        assertEquals("Keep", empty.drafts.getValue(key()).text)
        assertEquals(
            ConversationDictationFailure.NoSpeech,
            (empty.controller.state as ConversationDictationState.Failed).reason,
        )
    }

    @Test
    fun activeProviderActivityKeepsOneStableResultOwnerUntilItReturns() {
        val fixture = fixture(draft = TextFieldValue("Origin", TextRange(6)))
        fixture.controller.requestProviderActivityStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.beginProviderActivityLaunch(fixture.controller.providerActivityRequestId)

        assertFalse(
            fixture.controller.requestStart(
                OTHER_ACCOUNT,
                OTHER_GROUP,
                TextFieldValue("Other"),
            ),
        )
        fixture.controller.onProviderActivityResult("result")

        assertEquals("Origin result", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun staleResultFromReplacedFailedSessionCannotOverwriteNewDraft() {
        val fixture = fixture(draft = TextFieldValue("One", TextRange(3)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val firstListener = fixture.platform.listener
        firstListener.onError(ConversationDictationFailure.NoSpeech)
        assertTrue(fixture.controller.state is ConversationDictationState.Failed)

        fixture.edit(key(), TextFieldValue("Two", TextRange(3)))
        fixture.controller.retry()
        val secondListener = fixture.platform.listener
        firstListener.onResult("stale")
        secondListener.onResult("fresh")

        assertEquals("Two fresh", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun concurrentPrefixAndSuffixEditsKeepAUniqueContextAnchor() {
        val fixture = fixture(draft = TextFieldValue("Hello brave world", TextRange(6, 11)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()
        fixture.edit(key(), TextFieldValue("Note: Hello brave world!", TextRange(24)))

        fixture.platform.listener.onResult("calm")

        assertEquals("Note: Hello calm world!", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun incompatibleConcurrentEditRequiresExplicitReviewAndPreservesBothValues() {
        val fixture = fixture(draft = TextFieldValue("Original anchor", TextRange(8)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()
        fixture.edit(key(), TextFieldValue("Completely rewritten", TextRange(20)))

        fixture.platform.listener.onResult("dictated words")

        val review = fixture.controller.state as ConversationDictationState.ReviewRequired
        assertEquals("dictated words", review.transcript)
        assertEquals("Completely rewritten", fixture.drafts.getValue(key()).text)
        assertEquals(0, fixture.writes)

        fixture.controller.insertReviewAtEnd()

        assertEquals("Completely rewritten dictated words", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun duplicateSuccessIsIdempotent() {
        val fixture = fixture(draft = TextFieldValue("Hello", TextRange(5)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val listener = fixture.platform.listener

        listener.onResult("there")
        listener.onResult("again")

        assertEquals("Hello there", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
    }

    @Test
    fun unavailableProviderAndPermanentPermissionDenialAreDeterministic() {
        val unavailable = fixture(draft = TextFieldValue(""), platform = FakePlatform(available = false))
        unavailable.controller.requestStart(ACCOUNT, GROUP, unavailable.drafts.getValue(key()))
        assertEquals(
            ConversationDictationFailure.ProviderUnavailable,
            (unavailable.controller.state as ConversationDictationState.Failed).reason,
        )

        val denied = fixture(draft = TextFieldValue(""), platform = FakePlatform(hasPermission = false))
        denied.controller.requestStart(ACCOUNT, GROUP, denied.drafts.getValue(key()))
        denied.controller.onPermissionResult(granted = false, permanentlyDenied = true)
        assertEquals(
            ConversationDictationFailure.PermissionPermanentlyDenied,
            (denied.controller.state as ConversationDictationState.Failed).reason,
        )
    }

    @Test
    fun microphoneLeaseRejectsConcurrentCaptureAndReleasesExactlyOnce() {
        var available = false
        var releases = 0
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                tryAcquireMicrophone = { available },
                releaseMicrophone = { releases += 1 },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        assertEquals(
            ConversationDictationFailure.MicrophoneInUse,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0, releases)

        available = true
        fixture.controller.retry()
        val listener = fixture.platform.listener
        fixture.controller.cancel()
        fixture.controller.cancel()
        listener.onError(ConversationDictationFailure.Unknown)

        assertEquals(1, releases)
        assertEquals(1, fixture.platform.session.cancelCalls)
        assertEquals(1, fixture.platform.session.destroyCalls)
    }

    @Test
    fun watchdogBoundsStartingListeningAndProcessing() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.scheduler.runLatest()

        assertEquals(
            ConversationDictationFailure.TimedOut,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertTrue(fixture.platform.session.cancelled)

        fixture.controller.retry()
        fixture.platform.listener.onReady()
        fixture.scheduler.runLatest()
        assertTrue(fixture.controller.state is ConversationDictationState.Processing)
        assertTrue(fixture.platform.session.stopped)

        fixture.scheduler.runLatest()
        assertEquals(
            ConversationDictationFailure.TimedOut,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
    }

    @Test
    fun appBackgroundReleasesActiveCaptureButKeepsExplicitReviewText() {
        val active = fixture(draft = TextFieldValue("Draft", TextRange(5)))
        active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
        active.controller.onAppBackgrounded()
        assertTrue(active.controller.state is ConversationDictationState.Idle)
        assertTrue(active.platform.session.cancelled)

        val review = fixture(draft = TextFieldValue("Anchor", TextRange(3)))
        review.controller.requestStart(ACCOUNT, GROUP, review.drafts.getValue(key()))
        review.edit(key(), TextFieldValue("Rewritten"))
        review.platform.listener.onResult("keep me")
        review.controller.onAppBackgrounded()
        assertTrue(review.controller.state is ConversationDictationState.ReviewRequired)

        val provider = fixture(draft = TextFieldValue("Provider"))
        provider.controller.requestProviderActivityStart(ACCOUNT, GROUP, provider.drafts.getValue(key()))
        provider.controller.beginProviderActivityLaunch(provider.controller.providerActivityRequestId)
        provider.controller.onAppBackgrounded()
        assertTrue(provider.controller.state is ConversationDictationState.ProviderActivityActive)
    }

    @Test
    fun duplicateStartForTheSameTargetAndModeIsRejectedWithoutRestarting() {
        val fixture = fixture(draft = TextFieldValue("Source", TextRange(6)))

        assertTrue(fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key())))
        val session = fixture.platform.session
        assertFalse(
            fixture.controller.requestStart(
                ACCOUNT,
                GROUP,
                fixture.drafts.getValue(key()),
            ),
        )

        assertTrue(fixture.controller.isOwnedBy(ACCOUNT, GROUP))
        assertTrue(fixture.platform.session === session)
        assertEquals(0, session.destroyCalls)
    }

    @Test
    fun differentTargetReplacesActiveGenerationAndRejectsItsLateCallbacks() {
        val fixture = fixture(draft = TextFieldValue("Source", TextRange(6)))
        fixture.drafts[OTHER_ACCOUNT to OTHER_GROUP] = TextFieldValue("Other", TextRange(5))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val firstListener = fixture.platform.listener
        val firstSession = fixture.platform.session

        assertTrue(
            fixture.controller.requestStart(
                OTHER_ACCOUNT,
                OTHER_GROUP,
                fixture.drafts.getValue(OTHER_ACCOUNT to OTHER_GROUP),
            ),
        )
        val secondListener = fixture.platform.listener
        firstListener.onResult("stale")
        secondListener.onResult("fresh")

        assertEquals("Source", fixture.drafts.getValue(key()).text)
        assertEquals("Other fresh", fixture.drafts.getValue(OTHER_ACCOUNT to OTHER_GROUP).text)
        assertTrue(firstSession.cancelled)
        assertTrue(firstSession.destroyed)
        assertEquals(1, firstSession.cancelCalls)
        assertEquals(1, firstSession.destroyCalls)
    }

    @Test
    fun targetRemovalProactivelyReleasesRecognitionAndIgnoresLateResult() {
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val listener = fixture.platform.listener
        listener.onReady()

        fixture.controller.onTargetRemoved(ACCOUNT, GROUP)
        listener.onResult("discard")

        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertEquals(1, fixture.platform.session.cancelCalls)
        assertEquals(1, fixture.platform.session.destroyCalls)
    }

    @Test
    fun multilineTranscriptReplacesOnlyTheCapturedSelection() {
        val fixture = fixture(draft = TextFieldValue("Before placeholder after", TextRange(7, 18)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.platform.listener.onResult("first line\nsecond line")

        assertEquals("Before first line\nsecond line after", fixture.drafts.getValue(key()).text)
        assertEquals(TextRange(29), fixture.drafts.getValue(key()).selection)
    }

    @Test
    fun cancelIsNonDestructiveBeforePermissionAndDuringProcessing() {
        var disclosureAccepted = false
        val disclosure =
            ConversationDictationController(
                platform = FakePlatform(hasPermission = false),
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue("Keep"), 0) },
                writeDraft = { _, _, _, _ -> error("Cancellation must not write") },
                disclosureAccepted = { disclosureAccepted },
                markDisclosureAccepted = { disclosureAccepted = true },
            )
        disclosure.requestStart(ACCOUNT, GROUP, TextFieldValue("Keep"))
        disclosure.cancel()
        assertTrue(disclosure.state is ConversationDictationState.Idle)

        val permission = fixture(draft = TextFieldValue("Keep"), platform = FakePlatform(hasPermission = false))
        permission.controller.requestStart(ACCOUNT, GROUP, permission.drafts.getValue(key()))
        permission.controller.cancel()
        permission.controller.onPermissionResult(granted = true)
        assertTrue(permission.controller.state is ConversationDictationState.Idle)

        val processing = fixture(draft = TextFieldValue("Keep"))
        processing.controller.requestStart(ACCOUNT, GROUP, processing.drafts.getValue(key()))
        val listener = processing.platform.listener
        listener.onEndOfSpeech()
        processing.controller.cancel()
        listener.onResult("discard")
        assertEquals("Keep", processing.drafts.getValue(key()).text)
        assertTrue(processing.controller.state is ConversationDictationState.Idle)
    }

    private fun fixture(
        draft: TextFieldValue,
        targetAvailable: () -> Boolean = { true },
        onBeforeRecognition: () -> Unit = {},
        platform: FakePlatform = FakePlatform(),
        tryAcquireMicrophone: () -> Boolean = { true },
        releaseMicrophone: () -> Unit = {},
    ): Fixture {
        val scheduler = FakeTimeoutScheduler()
        val drafts = mutableMapOf(key() to draft)
        val revisions = mutableMapOf(key() to 0L)
        var writes = 0
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { account, group ->
                    ConversationDictationDraftSnapshot(
                        value = drafts.getValue(account to group),
                        revision = revisions[account to group] ?: 0L,
                    )
                },
                writeDraft = { account, group, expectedRevision, value ->
                    val target = account to group
                    if ((revisions[target] ?: 0L) != expectedRevision) {
                        false
                    } else {
                        drafts[target] = value
                        revisions[target] = expectedRevision + 1L
                        writes += 1
                        true
                    }
                },
                targetAvailable = { _, _ -> targetAvailable() },
                onBeforeRecognition = onBeforeRecognition,
                tryAcquireMicrophone = tryAcquireMicrophone,
                releaseMicrophone = releaseMicrophone,
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                elapsedRealtime = { 100L },
                scheduleTimeout = scheduler::schedule,
            )
        return Fixture(controller, platform, scheduler, drafts, revisions) { writes }
    }

    private data class Fixture(
        val controller: ConversationDictationController,
        val platform: FakePlatform,
        val scheduler: FakeTimeoutScheduler,
        val drafts: MutableMap<Pair<String, String>, TextFieldValue>,
        private val revisions: MutableMap<Pair<String, String>, Long>,
        private val writeCount: () -> Int,
    ) {
        val writes: Int
            get() = writeCount()

        fun edit(
            key: Pair<String, String>,
            value: TextFieldValue,
        ) {
            drafts[key] = value
            revisions[key] = (revisions[key] ?: 0L) + 1L
        }
    }

    @Suppress("MaxLineLength")
    private class FakePlatform(
        var hasPermission: Boolean = true,
        var available: Boolean = true,
        var activityAvailable: Boolean = true,
    ) : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener
        var session = FakeSession()

        override fun hasRecordAudioPermission(): Boolean = hasPermission

        override fun recognitionAvailable(): Boolean = available

        override fun recognitionActivityAvailable(): Boolean = activityAvailable

        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            this.listener = listener
            session = FakeSession()
            return session
        }
    }

    private class FakeSession : ConversationDictationRecognitionSession {
        var started = false
        var stopped = false
        var cancelled = false
        var destroyed = false
        var cancelCalls = 0
        var destroyCalls = 0

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }

        override fun cancel() {
            cancelled = true
            cancelCalls += 1
        }

        override fun destroy() {
            destroyed = true
            destroyCalls += 1
        }
    }

    private class FakeTimeoutScheduler {
        private val tasks = mutableListOf<Task>()

        fun schedule(
            delayMillis: Long,
            callback: () -> Unit,
        ): ConversationDictationTimeoutHandle {
            val task = Task(delayMillis, callback)
            tasks += task
            return ConversationDictationTimeoutHandle { task.cancelled = true }
        }

        fun runLatest() {
            val task = tasks.last { !it.cancelled && !it.ran }
            task.ran = true
            task.callback()
        }

        private data class Task(
            val delayMillis: Long,
            val callback: () -> Unit,
            var cancelled: Boolean = false,
            var ran: Boolean = false,
        )
    }

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val OTHER_ACCOUNT = "other-account"
        const val OTHER_GROUP = "other-group"

        fun key(): Pair<String, String> = ACCOUNT to GROUP
    }
}
