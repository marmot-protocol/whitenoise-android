package dev.ipf.whitenoise.android.audio

import android.content.pm.ServiceInfo
import android.speech.SpeechRecognizer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.ui.conversation.composer.ConversationDictationRecovery
import dev.ipf.whitenoise.android.ui.conversation.composer.dictationFailureRecovery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("LargeClass")
class ConversationDictationControllerTest {
    /** A button press while the automatic endpoint drains its tail owns delivery, not the default. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun explicitSendOverridesAutomaticPasteWhileTailIsProcessing() =
        runTest {
            val sent = mutableListOf<String>()
            val platform =
                FakePlatform().apply {
                    pendingCallerAudio = true
                    capturedSilenceMillis = 0L
                }
            val active =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    platform = platform,
                    finishAfterSilenceMillis = { 3_000L },
                    silenceDeliveryMode = { ConversationDictationDeliveryMode.PasteIntoDraft },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        request.beginDispatch().also { if (it) sent += request.payload }
                    },
                )
            active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
            platform.capturedSilenceMillis = 3_000L
            active.scheduler.advanceBy(3_000L)
            assertTrue(active.controller.completionActionsEnabled)
            active.controller.send()
            platform.pendingCallerAudio = false
            platform.listener.onResult("final tail")
            advanceUntilIdle()
            assertEquals(listOf("Draft final tail"), sent)
            assertEquals("", active.drafts.getValue(key()).text)
        }

    /** A deliberate Paste must also override automatic Send before dispatch is claimed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun explicitPasteOverridesAutomaticSendWhileTailIsProcessing() =
        runTest {
            var sends = 0
            val platform =
                FakePlatform().apply {
                    pendingCallerAudio = true
                    capturedSilenceMillis = 0L
                }
            val active =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    platform = platform,
                    finishAfterSilenceMillis = { 3_000L },
                    silenceDeliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = {
                        sends += 1
                        true
                    },
                )
            active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
            platform.capturedSilenceMillis = 3_000L
            active.scheduler.advanceBy(3_000L)
            active.controller.paste()
            platform.pendingCallerAudio = false
            platform.listener.onResult("final tail")
            advanceUntilIdle()
            assertEquals(0, sends)
            assertEquals("Draft final tail", active.drafts.getValue(key()).text)
        }

    /** Explicit Paste completes locally while the automatic Send's membership probe is pending. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun explicitPasteOverridesAutomaticSendDuringAuthoritativeValidation() =
        runTest {
            val validation = CompletableDeferred<ConversationDictationTargetValidation>()
            var sends = 0
            var checks = 0
            val active =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    finishAfterSilenceMillis = { 3_000L },
                    silenceDeliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
                    targetValidationScope = this,
                    targetValidator = { _, _ ->
                        checks++
                        validation.await()
                    },
                    sendTranscriptIfOriginUnchanged = {
                        sends++
                        true
                    },
                )
            active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
            runCurrent()
            active.platform.listener.onResult("dictated")
            active.scheduler.advanceBy(3_000L)
            runCurrent()
            assertEquals(1, checks)
            assertTrue(active.controller.completionActionsEnabled)
            active.controller.paste()
            assertEquals("Draft dictated", active.drafts.getValue(key()).text)
            validation.complete(ConversationDictationTargetValidation.Indeterminate)
            advanceUntilIdle()
            assertEquals(0, sends)
            assertTrue(active.controller.state is ConversationDictationState.Idle)
            assertEquals("Draft dictated", active.drafts.getValue(key()).text)
        }

    /** A failed Send's old membership probe cannot dispatch a later Retry for the same logical session. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun staleValidationCannotDispatchRetriedSend() =
        runTest {
            val original = CompletableDeferred<ConversationDictationTargetValidation>()
            val retried = CompletableDeferred<ConversationDictationTargetValidation>()
            var checks = 0
            var sends = 0
            val active =
                fixture(
                    draft = TextFieldValue(""),
                    targetValidationScope = this,
                    targetValidator = { _, _ ->
                        if (checks++ == 0) original.await() else retried.await()
                    },
                    sendTranscriptIfOriginUnchanged = {
                        sends++
                        it.beginDispatch()
                    },
                )
            active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
            active.controller.send()
            active.platform.listener.onResult("dictated")
            runCurrent()
            val token = requireNotNull(active.controller.notificationSessionToken)
            active.controller.onDurableServiceDestroyed(token)
            assertEquals(
                ConversationDictationFailure.SendBlocked,
                (active.controller.state as ConversationDictationState.Failed).reason,
            )
            active.controller.retry()
            runCurrent()
            assertEquals(2, checks)
            original.complete(ConversationDictationTargetValidation.Available)
            runCurrent()
            assertEquals(0, sends)
            assertTrue(active.controller.state is ConversationDictationState.Processing)
            retried.complete(ConversationDictationTargetValidation.Available)
            advanceUntilIdle()
            assertEquals(1, sends)
            assertTrue(active.controller.state is ConversationDictationState.Idle)
        }

    @Test
    fun refusedReplyStartDoesNotPublishAnActiveSession() {
        ShadowLog.clear()
        DictationDiagnostics.activeSession = 0
        val active = fixture(draft = TextFieldValue(""), targetReplyAvailable = { false })
        assertFalse(
            active.controller.requestStart(
                ACCOUNT,
                GROUP,
                active.drafts.getValue(key()),
                replyToMessageIdHex = REPLY_MESSAGE_ID,
            ),
        )
        assertEquals(0L, DictationDiagnostics.activeSession)
        assertTrue(ShadowLog.getLogsForTag("WNDictation").none { it.msg.contains("event=session_started") })
    }

    @Test
    fun visibilityTraceEmitsOnlyOriginTransitions() {
        val active = fixture(draft = TextFieldValue(""))
        active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
        ShadowLog.clear()
        val lifecycle = DictationDiagnosticLifecycle()
        lifecycle.originVisibility({ active.controller }) { true }
        lifecycle.originVisibility({ active.controller }) { true }
        lifecycle.originVisibility({ active.controller }) { false }
        val entries = ShadowLog.getLogsForTag("WNDictation").mapNotNull { DictationDiagnosticSchema.fields(it.msg) }
        val changes = entries.filter { it["event"] == "origin_visibility" }
        assertEquals(listOf(true, false), changes.map { it["visible"] })
        assertTrue(changes.all { it["session"] == 1L })
    }

    @Test
    fun lateInternalCaptureClosureIsNotARejectedProviderCallback() {
        val active = fixture(draft = TextFieldValue(""), platform = FakePlatform(deferCaptureCompletion = true))
        active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
        active.platform.listener.onReady()
        val session = active.platform.session
        active.controller.stop()
        active.platform.listener.onResult("captured")
        ShadowLog.clear()
        session.completeCapture()
        assertTrue(ShadowLog.getLogsForTag("WNDictation").none { it.msg.contains("event=callback_rejected") })
    }

    @Test
    fun idleVisibilityHooksDoNotInitializeTheController() {
        DictationDiagnostics.activeSession = 0
        val lifecycle = DictationDiagnosticLifecycle()
        lifecycle.originVisibility({ error("Idle controller must remain lazy") }) { true }
        lifecycle.foreground { error("Idle controller must remain lazy") }
    }

    @Test
    fun processingBackgroundAndServiceLossHaveCorrelatedCausalDiagnostics() {
        ShadowLog.clear()
        val active = fixture(draft = TextFieldValue("PRIVATE_DRAFT"))
        active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
        active.platform.listener.onReady()
        active.controller.stop()
        active.controller.onAppBackgrounded()
        assertTrue(active.controller.state is ConversationDictationState.Processing)
        active.controller.onDurableServiceDestroyed(requireNotNull(active.controller.notificationSessionToken))
        val logs = ShadowLog.getLogsForTag("WNDictation").joinToString("\n") { it.msg }
        assertTrue(
            logs.contains(
                "event=app_visibility foreground=false durable=true phase=Processing outcome=continued session=1",
            ),
        )
        assertTrue(logs.contains("event=session_abort reason=service_destroyed accepted=true"))
        assertTrue(logs.contains("event=state_changed session=1 from=Processing to=Idle"))
        val retained = ShadowLog.getLogsForTag("WNDictation").mapNotNull { DictationDiagnosticSchema.fields(it.msg) }
        assertTrue(
            retained.any {
                it["event"] == "app_visibility" && it["phase"] == "Processing" && it["outcome"] == "continued"
            },
        )
        assertTrue(retained.any { it["event"] == "foreground_service_start" && it["requested"] == true })
        assertFalse(logs.contains("PRIVATE_DRAFT"))
        assertFalse(logs.contains(ACCOUNT))
        assertFalse(logs.contains(GROUP))
    }

    @Test
    fun processingTimeoutTraceNamesItsDeadlineAndRecoveryCause() {
        ShadowLog.clear()
        val active = fixture(draft = TextFieldValue(""))
        active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
        active.platform.listener.onReady()
        active.controller.stop()
        active.scheduler.runDelay(20_000L)
        val logs = ShadowLog.getLogsForTag("WNDictation").joinToString("\n") { it.msg }
        assertTrue(logs.contains("event=watchdog_fired session=1 phase=processing accepted=true"))
        assertTrue(logs.contains("event=failure_recovery failure=TimedOut"))
        assertTrue(active.controller.state is ConversationDictationState.Failed)
    }

    @Test
    fun callerAudioDrainAndStaleCallbackHaveDistinctDiagnosticOutcomes() {
        ShadowLog.clear()
        val active = fixture(draft = TextFieldValue(""))
        active.platform.pendingCallerAudio = true
        active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
        val stale = active.platform.listener
        active.platform.listener.onResult("PRIVATE_SPEECH")
        active.scheduler.runDelay(500L)
        active.controller.paste()
        active.scheduler.advanceBy(20_000L)
        active.controller.retry()
        active.scheduler.runDelay(500L)
        active.platform.listener.onReady()
        active.scheduler.runDelay(90_000L)
        stale.onResult("PRIVATE_STALE")
        val logs = ShadowLog.getLogsForTag("WNDictation").joinToString("\n") { it.msg }
        assertTrue(logs.contains("event=watchdog_fired session=1 phase=caller_audio_drain"))
        assertTrue(logs.contains("event=callback_rejected callback_session=1"))
        assertFalse(logs.contains("PRIVATE_"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unacceptedSendRetainsTranscriptAndCapturedReplyWithoutPasting() =
        runTest {
            var dispatched: ConversationDictationSendRequest? = null
            val fixture =
                fixture(
                    draft = TextFieldValue("typed", TextRange(5)),
                    targetReplyAvailable = { true },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = {
                        dispatched = it
                        false
                    },
                )

            fixture.controller.requestStart(
                ACCOUNT,
                GROUP,
                fixture.drafts.getValue(key()),
                replyToMessageIdHex = REPLY_MESSAGE_ID,
            )
            assertEquals(
                REPLY_MESSAGE_ID,
                fixture.controller.state.target
                    ?.replyToMessageIdHex,
            )
            fixture.controller.send()
            fixture.platform.listener.onResult("reply by voice")
            advanceUntilIdle()

            assertEquals(REPLY_MESSAGE_ID, dispatched?.replyToMessageIdHex)
            assertEquals("typed", fixture.drafts.getValue(key()).text)
            val failure = fixture.controller.state as ConversationDictationState.Failed
            assertEquals(ConversationDictationFailure.SendBlocked, failure.reason)
            assertEquals("reply by voice", failure.retainedTranscript)
        }

    @Test
    fun changedReplyIdentityPreventsPasteFromBecomingStandaloneText() {
        var replyAvailable = true
        val fixture =
            fixture(
                draft = TextFieldValue("Keep", TextRange(4)),
                targetReplyAvailable = { replyAvailable },
            )
        fixture.controller.requestStart(
            ACCOUNT,
            GROUP,
            fixture.drafts.getValue(key()),
            replyToMessageIdHex = REPLY_MESSAGE_ID,
        )
        replyAvailable = false

        fixture.controller.paste()
        fixture.platform.listener.onResult("must remain a reply")

        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertEquals(0, fixture.writes)
    }

    @Test
    fun detachedReplyPasteRetainsItsTranscriptWithoutWriting() {
        var replyAvailable: Boolean? = true
        val fixture =
            fixture(
                draft = TextFieldValue("Keep", TextRange(4)),
                targetReplyAvailable = { replyAvailable },
            )
        fixture.controller.requestStart(
            ACCOUNT,
            GROUP,
            fixture.drafts.getValue(key()),
            replyToMessageIdHex = REPLY_MESSAGE_ID,
        )
        replyAvailable = null

        fixture.controller.paste()
        fixture.platform.listener.onResult("must remain a reply")

        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertEquals(0, fixture.writes)
        assertEquals(
            "must remain a reply",
            (fixture.controller.state as ConversationDictationState.Failed).retainedTranscript,
        )
    }

    @Test
    fun nonReplyPasteDoesNotRetargetIntoAMountedReplyComposer() {
        var replyMatches = true
        val fixture =
            fixture(
                draft = TextFieldValue("Keep", TextRange(4)),
                targetReplyAvailable = { replyMatches },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        replyMatches = false

        fixture.controller.paste()
        fixture.platform.listener.onResult("must remain standalone")

        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertEquals(0, fixture.writes)
    }

    @Test
    fun disappearanceOfPinnedProviderRejectsItsLateResultVisibly() {
        val platform = FakePlatform()
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        platform.pinnedProviderPresent = false
        platform.listener.onResult("late")
        assertEquals(
            ConversationDictationFailure.ProviderUnavailable,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun ambiguousProviderRequiresChoiceBeforeMicrophoneAndCancelPreservesDraft() {
        val platform = FakePlatform(needsProviderChoice = true)
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        assertTrue(fixture.controller.state is ConversationDictationState.ProviderSelectionRequired)
        assertFalse(fixture.controller.ownsMicrophone)
        assertTrue(platform.sessions.isEmpty())
        assertEquals(0, platform.callerAudioProbes)
        fixture.controller.cancel()
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertTrue(platform.sessions.isEmpty())
    }

    /** A stale reply must be rejected before a provider chooser can own the dictation gesture. */
    @Test
    fun invalidReplyDoesNotOfferProviderSelection() {
        val platform = FakePlatform(needsProviderChoice = true)
        val fixture = fixture(TextFieldValue("Keep"), targetReplyAvailable = { false }, platform = platform)

        assertFalse(
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()), REPLY_MESSAGE_ID),
        )

        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertFalse(fixture.controller.ownsMicrophone)
        assertTrue(platform.sessions.isEmpty())
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
    }

    /** Choosing a provider must not bypass reply validation on the required next gesture. */
    @Test
    fun replyChangedDuringProviderSelectionIsRejectedOnTheNextGesture() {
        var replyAvailable = true
        val platform = FakePlatform(needsProviderChoice = true)
        val fixture = fixture(TextFieldValue("Keep"), targetReplyAvailable = { replyAvailable }, platform = platform)
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()), REPLY_MESSAGE_ID)
        val pending = fixture.controller.state as ConversationDictationState.ProviderSelectionRequired
        assertEquals(REPLY_MESSAGE_ID, pending.target.replyToMessageIdHex)

        replyAvailable = false
        platform.needsProviderChoice = false
        fixture.controller.onProviderSelected()

        assertFalse(
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()), REPLY_MESSAGE_ID),
        )
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertFalse(fixture.controller.ownsMicrophone)
        assertTrue(platform.sessions.isEmpty())
        assertEquals(0, fixture.writes)
    }

    @Test
    fun selectingProviderPersistsButRequiresANewGestureWithoutCapturingOrChangingDraft() {
        val platform = FakePlatform(needsProviderChoice = true)
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.onProviderSelected()
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertFalse(fixture.controller.ownsMicrophone)
        assertTrue(platform.sessions.isEmpty())
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun staleActivityRequestCannotWriteIntoANewerSession() {
        val fixture = fixture(draft = TextFieldValue("Keep"))
        val controller = fixture.controller
        controller.requestProviderActivityStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val old = controller.providerActivityRequestId
        controller.beginProviderActivityLaunch(old)
        controller.cancel()
        controller.requestProviderActivityStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        controller.beginProviderActivityLaunch(controller.providerActivityRequestId)
        controller.onProviderActivityResult("stale", old)
        controller.onProviderActivityCancelled(old)
        assertTrue(controller.state is ConversationDictationState.ProviderActivityActive)
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
    }

    /** A provider that accepted the session and then failed is the provider's to fix, not the network's. */
    @Test
    fun serverFailureOffersProviderSetupInsteadOfNetworkRetry() {
        assertEquals(
            ConversationDictationFailure.ProviderUnavailable,
            SpeechRecognizer.ERROR_SERVER.toConversationDictationFailure(),
        )
        assertEquals(
            ConversationDictationRecovery.SpeechProviderSetup,
            dictationFailureRecovery(SpeechRecognizer.ERROR_SERVER.toConversationDictationFailure()),
        )
    }

    /** Only the two codes that carry network evidence may still claim a network cause. */
    @Test
    fun onlyTheNetworkCodesKeepNetworkRecovery() {
        assertEquals(
            ConversationDictationFailure.Network,
            SpeechRecognizer.ERROR_NETWORK.toConversationDictationFailure(),
        )
        assertEquals(
            ConversationDictationFailure.Network,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT.toConversationDictationFailure(),
        )
    }

    @Test
    fun cancelDuringRecognizerPreparationDestroysSessionAndFencesLateCallbacks() {
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)))

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val staleListener = fixture.platform.listener
        fixture.controller.cancel()
        staleListener.onReady()
        staleListener.onResult("must not land")

        assertTrue(fixture.platform.session.cancelled)
        assertTrue(fixture.platform.session.destroyed)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun pasteDuringRecognizerPreparationCompletesWithoutWaitingForCapture() {
        val platform = FakePlatform(completePreparationOnStop = true)
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.paste()

        assertEquals(
            ConversationDictationFailure.NoSpeech,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertTrue(platform.session.stopped)
        assertTrue(platform.session.destroyed)
    }

    @Test
    fun sendDuringRecognizerPreparationCompletesWithoutDispatching() {
        val platform = FakePlatform(completePreparationOnStop = true)
        var sendCalls = 0
        val fixture =
            fixture(
                draft = TextFieldValue("Keep", TextRange(4)),
                platform = platform,
                sendTranscriptIfOriginUnchanged = {
                    sendCalls += 1
                    true
                },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.send()

        assertEquals(
            ConversationDictationFailure.NoSpeech,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0, sendCalls)
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun resultPopulatesAnEmptyDraftAndLeavesItEditable() {
        val fixture = fixture(draft = TextFieldValue("", TextRange.Zero))

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.stop()
        fixture.platform.listener.onResult("editable words")

        assertEquals("editable words", fixture.drafts.getValue(key()).text)
        assertEquals(TextRange("editable words".length), fixture.drafts.getValue(key()).selection)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun appOwnedStartPinsTheInAppModeBeforeCreatingARecognizer() {
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)))

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertEquals(
            ConversationDictationMode.InApp,
            fixture.controller.state.target
                ?.mode,
        )
        assertTrue(fixture.platform.session.started)
    }

    @Test
    fun providerBindingFailureDoesNotFallBackToAnImplicitRecognizer() {
        val fixture =
            fixture(
                draft = TextFieldValue("Keep", TextRange(4)),
                platform = FakePlatform(createFailure = ConversationDictationProviderUnavailableException()),
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertEquals(
            ConversationDictationFailure.ProviderUnavailable,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertFalse(fixture.controller.ownsMicrophone)
    }

    @Test
    fun resultIsInsertedAtCapturedSelectionAndNeverSent() {
        val fixture = fixture(draft = TextFieldValue("Hello world", TextRange(6, 11)))

        assertTrue(fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key())))
        fixture.platform.listener.onReady()
        fixture.controller.stop()
        fixture.platform.listener.onResult("Marmot")

        assertEquals("Hello Marmot", fixture.drafts.getValue(key()).text)
        assertEquals(TextRange(12), fixture.drafts.getValue(key()).selection)
        assertEquals(1, fixture.writes)
        assertEquals(1, fixture.controller.completionRevision(ACCOUNT, GROUP))
        assertEquals(0, fixture.controller.pendingSendRevision(ACCOUNT, GROUP))
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
            fixture.controller.stop()
            fixture.platform.listener.onResult("dictated")

            assertEquals(expected, fixture.drafts.getValue(key()).text)
        }
    }

    @Test
    fun resultKeepsAdjacentPunctuationTight() {
        val fixture = fixture(draft = TextFieldValue("Hello,", TextRange(5)))

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.stop()
        fixture.platform.listener.onResult("dictated")

        assertEquals("Hello dictated,", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun concurrentSuffixEditKeepsTheCapturedInsertionAnchor() {
        val fixture = fixture(draft = TextFieldValue("First", TextRange(5)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()
        fixture.edit(key(), TextFieldValue("First plus typed", TextRange(16)))

        fixture.controller.stop()
        fixture.platform.listener.onResult("dictated")

        assertEquals("First dictated plus typed", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun resultAlwaysBelongsToOriginatingConversationAfterNavigation() {
        val fixture = fixture(draft = TextFieldValue("Source ", TextRange(7)))
        fixture.drafts[OTHER_ACCOUNT to OTHER_GROUP] = TextFieldValue("Other", TextRange(5))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()

        fixture.controller.stop()
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
    fun removedSourceConversationRetainsCompletedResultWithoutRecreatingDraft() {
        var targetAvailable = true
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)), targetAvailable = { targetAvailable })
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()
        targetAvailable = false

        fixture.platform.listener.onResult("recover me")

        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertEquals(0, fixture.writes)
        val failure = fixture.controller.state as ConversationDictationState.Failed
        assertEquals("recover me", failure.retainedTranscript)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun nonReplyPasteSurvivesOriginControllerDetachment() =
        runTest {
            var originControllerAttached = true
            val fixture =
                fixture(
                    draft = TextFieldValue("Keep", TextRange(4)),
                    targetReplyAvailable = { originControllerAttached.takeIf { it } },
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onReady()

            originControllerAttached = false
            fixture.controller.paste()
            fixture.platform.listener.onResult("all of it")
            advanceUntilIdle()

            assertEquals("Keep all of it", fixture.drafts.getValue(key()).text)
            assertEquals(1, fixture.writes)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pasteIntoEmptyComposerDoesNotStartRemoteTargetValidation() =
        runTest {
            var validationCalls = 0
            val fixture =
                fixture(
                    draft = TextFieldValue(""),
                    targetValidator = { _, _ ->
                        validationCalls += 1
                        ConversationDictationTargetValidation.Indeterminate
                    },
                    targetValidationScope = this,
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onReady()

            fixture.controller.paste()
            fixture.platform.listener.onResult("complete words")
            advanceUntilIdle()

            assertEquals("complete words", fixture.drafts.getValue(key()).text)
            assertEquals(1, fixture.writes)
            assertEquals(0, validationCalls)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun preTransportSendRejectionRestoresDraftAndPreservesTranscript() =
        runTest {
            val fixture =
                fixture(
                    draft = TextFieldValue("Keep", TextRange(4)),
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        assertTrue(request.beginDispatch())
                        request.onDispatchRejectedBeforeTransport()
                        false
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onReady()

            fixture.controller.send()
            fixture.platform.listener.onResult("all of it")
            advanceUntilIdle()

            assertEquals("Keep", fixture.drafts.getValue(key()).text)
            val failure = fixture.controller.state as ConversationDictationState.Failed
            assertEquals(ConversationDictationFailure.SendBlocked, failure.reason)
            assertEquals("all of it", failure.retainedTranscript)
            fixture.controller.paste()
            assertEquals("Keep all of it", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun detachedNonReplyPasteBypassesRemoteValidationWhileSendStillFailsClosed() =
        runTest {
            listOf(
                ConversationDictationTargetValidation.DefinitelyRemoved,
                ConversationDictationTargetValidation.Indeterminate,
            ).forEach { validation ->
                val pasted =
                    fixture(
                        draft = TextFieldValue("Keep", TextRange(4)),
                        targetReplyAvailable = { null },
                        targetValidator = { _, _ -> validation },
                        targetValidationScope = this,
                    )
                pasted.controller.requestStart(ACCOUNT, GROUP, pasted.drafts.getValue(key()))
                pasted.platform.listener.onReady()
                pasted.controller.paste()
                pasted.platform.listener.onResult("recover me")
                advanceUntilIdle()

                assertEquals("Keep recover me", pasted.drafts.getValue(key()).text)
                assertEquals(1, pasted.writes)
                assertTrue(pasted.controller.state is ConversationDictationState.Idle)

                val sent =
                    fixture(
                        draft = TextFieldValue("Keep", TextRange(4)),
                        targetReplyAvailable = { null },
                        targetValidator = { _, _ -> validation },
                        targetValidationScope = this,
                    )
                sent.controller.requestStart(ACCOUNT, GROUP, sent.drafts.getValue(key()))
                sent.platform.listener.onReady()
                sent.controller.send()
                sent.platform.listener.onResult("recover me")
                advanceUntilIdle()

                assertEquals("Keep", sent.drafts.getValue(key()).text)
                assertEquals(0, sent.writes)
                assertEquals(
                    "recover me",
                    (sent.controller.state as ConversationDictationState.Failed).retainedTranscript,
                )
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun attachedNonReplyPasteBypassesRemoteValidationWhileSendStillFailsClosed() =
        runTest {
            listOf(
                ConversationDictationTargetValidation.DefinitelyRemoved,
                ConversationDictationTargetValidation.Indeterminate,
            ).forEach { validation ->
                val pasted =
                    fixture(
                        draft = TextFieldValue("Keep", TextRange(4)),
                        targetValidator = { _, _ -> validation },
                        targetValidationScope = this,
                    )
                pasted.controller.requestStart(ACCOUNT, GROUP, pasted.drafts.getValue(key()))
                pasted.platform.listener.onReady()
                pasted.controller.paste()
                pasted.platform.listener.onResult("recover me")
                advanceUntilIdle()

                assertEquals("Keep recover me", pasted.drafts.getValue(key()).text)
                assertEquals(1, pasted.writes)
                assertTrue(pasted.controller.state is ConversationDictationState.Idle)

                val sent =
                    fixture(
                        draft = TextFieldValue("Keep", TextRange(4)),
                        targetValidator = { _, _ -> validation },
                        targetValidationScope = this,
                    )
                sent.controller.requestStart(ACCOUNT, GROUP, sent.drafts.getValue(key()))
                sent.platform.listener.onReady()
                sent.controller.send()
                sent.platform.listener.onResult("recover me")
                advanceUntilIdle()

                assertEquals("Keep", sent.drafts.getValue(key()).text)
                assertEquals(0, sent.writes)
                assertEquals(
                    "recover me",
                    (sent.controller.state as ConversationDictationState.Failed).retainedTranscript,
                )
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun authoritativeValidationExceptionAndTimeoutRetainCompletedResults() =
        runTest {
            val validators =
                listOf<suspend (String, String) -> ConversationDictationTargetValidation>(
                    { _, _ -> error("MDK validation failed") },
                    { _, _ -> CompletableDeferred<ConversationDictationTargetValidation>().await() },
                )
            validators.forEach { validator ->
                val fixture =
                    fixture(
                        draft = TextFieldValue("Keep", TextRange(4)),
                        targetValidator = validator,
                        targetValidationScope = this,
                    )
                fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
                fixture.controller.send()
                fixture.platform.listener.onResult("recover me")
                advanceUntilIdle()

                assertEquals("Keep", fixture.drafts.getValue(key()).text)
                val failure = fixture.controller.state as ConversationDictationState.Failed
                assertEquals("recover me", failure.retainedTranscript)
                assertFalse(failure.recognitionIncomplete)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun interruptedProviderActivityValidationRetainsResultAndFencesLateSuccess() =
        runTest {
            val validation = CompletableDeferred<ConversationDictationTargetValidation>()
            val fixture =
                fixture(
                    draft = TextFieldValue("Keep", TextRange(4)),
                    targetValidator = { _, _ -> validation.await() },
                    targetValidationScope = this,
                )
            fixture.controller.requestProviderActivityStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.beginProviderActivityLaunch(fixture.controller.providerActivityRequestId)
            fixture.controller.onProviderActivityResult("recover me")
            runCurrent()

            fixture.controller.onAppBackgrounded()
            validation.complete(ConversationDictationTargetValidation.Available)
            advanceUntilIdle()

            assertEquals("Keep recover me", fixture.drafts.getValue(key()).text)
            assertEquals(1, fixture.writes)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun automaticFallbackKeepsTranscriptWhenTheTargetDisappears() =
        runTest {
            var targetExists = true
            val fixture =
                fixture(
                    draft = TextFieldValue("Original anchor", TextRange(8)),
                    targetValidator = { _, _ ->
                        if (targetExists) {
                            ConversationDictationTargetValidation.Available
                        } else {
                            ConversationDictationTargetValidation.DefinitelyRemoved
                        }
                    },
                    targetValidationScope = this,
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.edit(key(), TextFieldValue("Completely rewritten", TextRange(20)))
            fixture.controller.stop()
            targetExists = false
            fixture.platform.listener.onResult("dictated words")
            advanceUntilIdle()
            assertTrue(fixture.controller.state is ConversationDictationState.Failed)

            assertEquals("Completely rewritten", fixture.drafts.getValue(key()).text)
            assertEquals(0, fixture.writes)
            val failure = fixture.controller.state as ConversationDictationState.Failed
            assertEquals("dictated words", failure.retainedTranscript)
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

    /** A denied enabled audio-focus policy must never start a recognizer or retain the microphone lease. */
    @Test
    fun deniedAudioFocusRestoresPlaybackAndFailsBeforeCapture() {
        val events = mutableListOf<String>()
        val fixture =
            fixture(
                draft = TextFieldValue("typed"),
                tryAcquireMicrophone = {
                    events += "lease"
                    true
                },
                onBeforeRecognition = {
                    events += "pause"
                    throw ConversationDictationAudioFocusDenied()
                },
                releaseMicrophone = { events += "release" },
                onAfterAudioCapture = { events += "restore" },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertEquals(listOf("lease", "pause", "release", "restore"), events)
        assertEquals(
            ConversationDictationFailure.AudioFocusUnavailable,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertFalse(fixture.controller.ownsMicrophone)
        assertTrue(fixture.platform.sessions.isEmpty())
        assertEquals("typed", fixture.drafts.getValue(key()).text)
    }

    /** A settings change after the gesture cannot silently change the active attempt's media policy. */
    @Test
    fun pauseOtherAudioIsCapturedBeforeDeferredServiceReadiness() {
        var pauseOtherAudio = true
        var ready: (() -> Unit)? = null
        var observed: Boolean? = null
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                pauseOtherAudio = { pauseOtherAudio },
                startDurableSession = { _, callback ->
                    ready = callback
                    true
                },
                onBeforeRecognition = { observed = it.pauseOtherAudio },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        pauseOtherAudio = false
        assertTrue(
            fixture.controller.state.target
                ?.pauseOtherAudio == true,
        )
        ready?.invoke()

        assertEquals(true, observed)
        assertTrue(fixture.platform.session.started)
    }

    /** Opting out of external focus never disables the app-owned playback safety handoff. */
    @Test
    fun disabledExternalFocusStillPausesAndRestoresAppPlayback() {
        val events = mutableListOf<String>()
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                pauseOtherAudio = { false },
                onBeforeRecognition = { target ->
                    assertFalse(target.pauseOtherAudio)
                    events += "pause_app_playback"
                },
                onAfterAudioCapture = { events += "restore_app_playback" },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        assertTrue(fixture.platform.session.started)
        fixture.controller.cancel()

        assertEquals(listOf("pause_app_playback", "restore_app_playback"), events)
    }

    @Test
    fun grantedRuntimePermissionStartsRecognizerWithoutTreatingEffectivePrivacyDenialAsAppDenial() {
        val platform = FakePlatform(microphoneAccessOverride = ConversationDictationMicrophoneAccess.Granted)
        val fixture = fixture(draft = TextFieldValue(""), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertTrue(platform.session.started)
        assertEquals(0L, fixture.controller.permissionRequestId)
        assertFalse(fixture.controller.state is ConversationDictationState.PermissionRequired)
        assertFalse(fixture.controller.state is ConversationDictationState.Failed)
    }

    /** Verifies capture closure restores playback before authoritative transcript validation starts. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun doneResumesPlaybackAfterCaptureEndsAndBeforeTranscriptValidation() =
        runTest {
            val events = mutableListOf<String>()
            val platform = FakePlatform(deferCaptureCompletion = true)
            val fixture =
                fixture(
                    draft = TextFieldValue("", TextRange.Zero),
                    platform = platform,
                    targetValidator = { _, _ ->
                        events += "validate"
                        ConversationDictationTargetValidation.Available
                    },
                    targetValidationScope = this,
                    onBeforeRecognition = { events += "pause" },
                    onAfterAudioCapture = { events += "resume" },
                )

            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.stop()
            assertEquals(listOf("pause"), events)
            platform.session.completeCapture()
            assertEquals(listOf("pause", "resume"), events)
            fixture.platform.listener.onResult("dictated words")
            advanceUntilIdle()

            assertEquals(listOf("pause", "resume", "validate"), events)
        }

    /** Provider-owned capture has ended before result processing; the next generation pauses media again. */
    @Test
    fun providerEndOfSpeechResumesMediaDuringProcessingAndRepausesOnRestart() {
        val events = mutableListOf<String>()
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                onBeforeRecognition = { events += "pause" },
                onAfterAudioCapture = { events += "resume" },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onEndOfSpeech()

        assertTrue(fixture.controller.state is ConversationDictationState.Processing)
        assertFalse(fixture.controller.ownsMicrophone)
        assertEquals(listOf("pause", "resume"), events)

        fixture.platform.listener.onResult("first phrase")
        fixture.scheduler.runDelay(500L)
        assertEquals(listOf("pause", "resume", "pause"), events)
        assertTrue(fixture.controller.ownsMicrophone)
    }

    /** A provider may return a final result without an end-of-speech callback. */
    @Test
    fun providerResultWithoutEndOfSpeechResumesMediaBeforeRestart() {
        val events = mutableListOf<String>()
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                onBeforeRecognition = { events += "pause" },
                onAfterAudioCapture = { events += "resume" },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first phrase")

        assertEquals(listOf("pause", "resume"), events)
        assertFalse(fixture.controller.ownsMicrophone)
        fixture.scheduler.runDelay(500L)
        assertEquals(listOf("pause", "resume", "pause"), events)
    }

    /** Terminal provider errors also close their microphone before retry decisions. */
    @Test
    fun providerErrorWithoutEndOfSpeechResumesMedia() {
        val events = mutableListOf<String>()
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                onBeforeRecognition = { events += "pause" },
                onAfterAudioCapture = { events += "resume" },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)

        assertEquals(listOf("pause", "resume"), events)
        assertFalse(fixture.controller.ownsMicrophone)
    }

    /** Provider callbacks cannot release media while White Noise still owns the recorder. */
    @Test
    fun callerOwnedCaptureWaitsForRecorderClosureDespiteProviderResult() {
        val platform =
            FakePlatform(deferCaptureCompletion = true).apply {
                sessionCallerAudioOwnedOverride = true
                pendingCallerAudio = true
                deferCallerAudioFinish = true
            }
        val events = mutableListOf<String>()
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                platform = platform,
                onBeforeRecognition = { events += "pause" },
                onAfterAudioCapture = { events += "resume" },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        platform.listener.onEndOfSpeech()
        platform.listener.onResult("first phrase")

        assertEquals(listOf("pause"), events)
        assertTrue(fixture.controller.ownsMicrophone)
        fixture.controller.stop()
        assertEquals(listOf("pause"), events)
        platform.callerAudioFinishCallback?.invoke()
        assertEquals(listOf("pause", "resume"), events)
    }

    /** Verifies provider end-of-speech cannot bypass caller-owned capture closure. */
    @Test
    fun stopAfterProviderEndWaitsForCallerOwnedCaptureToClose() =
        runTest {
            val platform = FakePlatform(deferCaptureCompletion = true).apply { sessionCallerAudioOwnedOverride = true }
            var resumes = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("", TextRange.Zero),
                    platform = platform,
                    onAfterAudioCapture = { resumes += 1 },
                )

            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            platform.listener.onEndOfSpeech()
            fixture.controller.stop()

            assertEquals(0, resumes)
            platform.session.completeCapture()
            assertEquals(1, resumes)
        }

    /** Verifies repeated cancellation or failure cleanup restores interrupted playback once. */
    @Test
    fun cancelAndFailureEachResumePlaybackExactlyOnce() {
        listOf<(Fixture) -> Unit>(
            { it.controller.cancel() },
            { it.platform.listener.onError(ConversationDictationFailure.Unknown) },
        ).forEach { finish ->
            var resumes = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("", TextRange.Zero),
                    onAfterAudioCapture = { resumes += 1 },
                )

            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            finish(fixture)
            fixture.controller.cancel()

            assertEquals(1, resumes)
        }
    }

    /** Verifies explicit cancellation retains the playback pause until physical capture closes. */
    @Test
    fun cancellationDefersPlaybackUntilCallerOwnedCaptureCloses() {
        val platform = FakePlatform(deferCaptureCompletion = true)
        var resumes = 0
        val fixture =
            fixture(
                draft = TextFieldValue("", TextRange.Zero),
                platform = platform,
                onAfterAudioCapture = { resumes += 1 },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.cancel()

        assertEquals(0, resumes)
        platform.session.completeCapture()
        fixture.controller.cancel()
        assertEquals(1, resumes)
    }

    /** Verifies a terminal provider error is withheld until caller-owned capture closes. */
    @Test
    fun providerErrorDefersPlaybackUntilCallerOwnedCaptureCloses() {
        val platform = FakePlatform(deferCaptureCompletion = true)
        var resumes = 0
        val fixture =
            fixture(
                draft = TextFieldValue("", TextRange.Zero),
                platform = platform,
                onAfterAudioCapture = { resumes += 1 },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        platform.session.providerError(ConversationDictationFailure.Unknown)

        assertEquals(0, resumes)
        platform.session.completeCapture()
        assertEquals(1, resumes)
        assertTrue(fixture.controller.state is ConversationDictationState.Failed)
    }

    /** Verifies Paste and Send restore playback before their longer delivery work begins. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pasteAndSendResumePlaybackAfterCaptureEndsAndBeforeDeliveryWork() =
        runTest {
            listOf<(ConversationDictationController) -> Unit>(
                { it.paste() },
                { it.send() },
            ).forEach { finish ->
                val events = mutableListOf<String>()
                val platform = FakePlatform(deferCaptureCompletion = true)
                val fixture =
                    fixture(
                        draft = TextFieldValue("", TextRange.Zero),
                        platform = platform,
                        targetValidator = { _, _ ->
                            events += "validate"
                            ConversationDictationTargetValidation.Available
                        },
                        targetValidationScope = this,
                        onAfterAudioCapture = { events += "resume" },
                        sendTranscriptIfOriginUnchanged = {
                            events += "send"
                            true
                        },
                    )

                fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
                finish(fixture.controller)
                assertEquals(emptyList<String>(), events)
                platform.session.completeCapture()
                assertEquals(listOf("resume"), events)
                fixture.platform.listener.onResult("dictated words")
                advanceUntilIdle()

                assertEquals("resume", events.first())
            }
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
                    0L
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
    fun acceptingOfflineDisclosureDoesNotApproveACloudProvider() {
        val platform = FakePlatform(providerPackage = OFFLINE_SPEECH_TO_TEXT_PACKAGE)
        var offlineAccepted = false
        var externalAccepted = false
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue(), 0) },
                writeDraft = { _, _, _, _ -> 0L },
                disclosureAccepted = { externalAccepted },
                markDisclosureAccepted = { externalAccepted = true },
                offlineDisclosureAccepted = { offlineAccepted },
                markOfflineDisclosureAccepted = { offlineAccepted = true },
            )

        controller.requestStart(ACCOUNT, GROUP, TextFieldValue())
        assertTrue((controller.state as ConversationDictationState.DisclosureRequired).usesOfflineSpeechToText)
        controller.acceptDisclosure()
        assertTrue(offlineAccepted)
        assertFalse(externalAccepted)

        controller.cancel()
        platform.providerPackage = "com.example.cloud"
        controller.requestStart(ACCOUNT, GROUP, TextFieldValue())
        assertFalse((controller.state as ConversationDictationState.DisclosureRequired).usesOfflineSpeechToText)
        controller.acceptDisclosure()
        assertTrue(externalAccepted)
    }

    /** A missing runtime grant must reach Android before provider discovery can fail closed. */
    @Test
    fun runtimePermissionRequestPrecedesProviderDiscovery() {
        val platform = FakePlatform(hasPermission = false, configured = true, available = false)
        val fixture = fixture(draft = TextFieldValue(""), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertTrue(fixture.controller.state is ConversationDictationState.PermissionRequired)
        assertEquals(1L, fixture.controller.permissionRequestId)
        assertEquals(0, platform.recognitionAvailabilityChecks)
        assertFalse(platform.session.started)
    }

    /** Native mode fails visibly without silently opening an external provider surface. */
    @Test
    fun unresolvedRecognitionServiceFailsWithoutLaunchingProviderActivity() {
        val platform = FakePlatform(hasPermission = false, configured = false, available = false)
        val fixture = fixture(draft = TextFieldValue(""), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertEquals(
            ConversationDictationFailure.ProviderUnavailable,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0L, fixture.controller.providerActivityRequestId)
        assertEquals(0L, fixture.controller.permissionRequestId)
        assertFalse(platform.session.started)
    }

    /** Known privacy or app-op denial must win over an otherwise available Activity-only fallback. */
    @Test
    fun missingSelectedServiceDoesNotBypassKnownMicrophoneDenial() {
        listOf(
            ConversationDictationMicrophoneAccess.MicrophoneMuted to ConversationDictationFailure.MicrophoneMuted,
            ConversationDictationMicrophoneAccess.AppOpDenied to
                ConversationDictationFailure.PermissionPermanentlyDenied,
        ).forEach { (access, failure) ->
            val platform =
                FakePlatform(
                    hasPermission = true,
                    configured = false,
                    available = false,
                    microphoneAccessOverride = access,
                )
            val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

            assertEquals(failure, (fixture.controller.state as ConversationDictationState.Failed).reason)
            assertEquals(0L, fixture.controller.providerActivityRequestId)
            assertEquals(0L, fixture.controller.permissionRequestId)
            assertFalse(platform.session.started)
            assertFalse(fixture.controller.ownsMicrophone)
            assertFalse(fixture.controller.hasDurableSession)
            assertEquals("Keep", fixture.drafts.getValue(key()).text)

            platform.microphoneAccessOverride = ConversationDictationMicrophoneAccess.Granted
            fixture.controller.retry()

            assertEquals(
                ConversationDictationFailure.ProviderUnavailable,
                (fixture.controller.state as ConversationDictationState.Failed).reason,
            )
            assertEquals(0L, fixture.controller.providerActivityRequestId)
            assertEquals(0L, fixture.controller.permissionRequestId)
            assertFalse(platform.session.started)
            assertFalse(fixture.controller.ownsMicrophone)
            assertFalse(fixture.controller.hasDurableSession)
        }
    }

    /** A missing service still fails deterministically when Android has no compatible provider Activity. */
    @Test
    fun missingServiceAndActivityFailsWithoutRuntimePermission() {
        val platform =
            FakePlatform(hasPermission = false, configured = false, available = false, activityAvailable = false)
        val fixture = fixture(draft = TextFieldValue(""), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertEquals(
            ConversationDictationFailure.ProviderUnavailable,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0L, fixture.controller.permissionRequestId)
        assertEquals(0, platform.recognitionAvailabilityChecks)
        assertFalse(platform.session.started)
    }

    /** Permission launch ownership is one-shot and stale callbacks cannot revive a cancelled request. */
    @Test
    fun permissionLaunchIsClaimedOnceAndCancelledCallbacksAreIgnored() {
        val platform = FakePlatform(hasPermission = false)
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val requestId = fixture.controller.permissionRequestId

        assertTrue(fixture.controller.beginPermissionRequest(requestId))
        assertFalse(fixture.controller.beginPermissionRequest(requestId))
        fixture.controller.cancel()
        platform.hasPermission = true
        fixture.controller.onPermissionResult(true)

        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertFalse(platform.session.started)
    }

    /** A settings-owned app-op denial fails closed instead of looping the runtime permission dialog. */
    @Test
    fun appOpDenialIsActionableWithoutRequestingRuntimePermission() {
        val platform =
            FakePlatform(
                hasPermission = true,
                microphoneAccessOverride = ConversationDictationMicrophoneAccess.AppOpDenied,
            )
        val fixture = fixture(draft = TextFieldValue(""), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertEquals(
            ConversationDictationFailure.PermissionPermanentlyDenied,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0L, fixture.controller.permissionRequestId)
        assertEquals(0, platform.recognitionAvailabilityChecks)
        assertFalse(platform.session.started)
    }

    @Test
    fun mutedMicrophoneExplainsSystemPrivacyWithoutStartingSilentCapture() {
        val platform =
            FakePlatform(microphoneAccessOverride = ConversationDictationMicrophoneAccess.MicrophoneMuted)
        val fixture = fixture(draft = TextFieldValue("Keep ", TextRange(5)), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertEquals(
            ConversationDictationFailure.MicrophoneMuted,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0L, fixture.controller.permissionRequestId)
        assertEquals(0L, fixture.controller.providerActivityRequestId)
        assertFalse(platform.session.started)
        assertFalse(fixture.controller.ownsMicrophone)
        assertFalse(fixture.controller.hasDurableSession)
        assertEquals("Keep ", fixture.drafts.getValue(key()).text)
        platform.microphoneAccessOverride = ConversationDictationMicrophoneAccess.Granted
        fixture.controller.retry()
        assertTrue(platform.session.started)
        fixture.controller.stop()
        platform.listener.onResult("recovered words")
        assertEquals("Keep recovered words", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun providerActivityValidationFailureRetainsCompletedResult() =
        runTest {
            val fixture =
                fixture(
                    draft = TextFieldValue("Keep", TextRange(4)),
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.DefinitelyRemoved },
                    targetValidationScope = this,
                )
            fixture.controller.requestProviderActivityStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.beginProviderActivityLaunch(fixture.controller.providerActivityRequestId)

            fixture.controller.onProviderActivityResult("recover me")
            advanceUntilIdle()

            assertEquals("Keep", fixture.drafts.getValue(key()).text)
            assertEquals(0, fixture.writes)
            val failure = fixture.controller.state as ConversationDictationState.Failed
            assertEquals("recover me", failure.retainedTranscript)
        }

    @Test
    fun grantedPermissionRejectedByRecognitionServiceRetriesOnceWithoutProviderActivity() {
        var microphoneReleases = 0
        var durableStops = 0
        val fixture =
            fixture(
                draft = TextFieldValue("Keep", TextRange(4)),
                releaseMicrophone = { microphoneReleases += 1 },
                stopDurableSession = { durableStops += 1 },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val rejectedSession = fixture.platform.session
        fixture.platform.configured = false

        fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)

        assertTrue(fixture.controller.state is ConversationDictationState.Starting)
        assertEquals(0L, fixture.controller.providerActivityRequestId)
        assertFalse(fixture.controller.ownsMicrophone)
        assertTrue(fixture.controller.hasDurableSession)
        assertTrue(rejectedSession.destroyed)
        assertEquals(1, fixture.platform.recognitionConfigurationChecks)
        fixture.scheduler.runDelay(500L)
        assertEquals(2, fixture.platform.sessions.size)
        assertTrue(fixture.controller.ownsMicrophone)

        fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)

        assertEquals(
            ConversationDictationFailure.ProviderAccessRejected,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0L, fixture.controller.providerActivityRequestId)
        assertEquals(2, microphoneReleases)
        assertEquals(1, durableStops)
    }

    @Test
    fun completionDuringPermissionRetryPreservesProviderFailureWithoutDelivery() {
        listOf("stop", "paste", "send").forEach { action ->
            var sends = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Keep"),
                    sendTranscriptIfOriginUnchanged = {
                        sends += 1
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            val stale = fixture.platform.listener
            stale.onError(ConversationDictationFailure.PermissionDenied)

            when (action) {
                "paste" -> fixture.controller.paste()
                "send" -> fixture.controller.send()
                else -> fixture.controller.stop()
            }
            fixture.scheduler.advanceBy(1_000L)
            stale.onReady()
            stale.onResult("late words")

            assertEquals(
                ConversationDictationFailure.ProviderAccessRejected,
                (fixture.controller.state as ConversationDictationState.Failed).reason,
            )
            assertEquals(1, fixture.platform.sessions.size)
            assertEquals(0, fixture.writes)
            assertEquals(0, sends)
            assertEquals("Keep", fixture.drafts.getValue(key()).text)
            assertFalse(fixture.controller.ownsMicrophone)
            assertFalse(fixture.controller.hasDurableSession)
        }
    }

    @Test
    fun fatalErrorAfterCompletionPreservesCauseInsteadOfNoSpeech() {
        listOf(ConversationDictationFailure.PermissionDenied, ConversationDictationFailure.Network).forEach { error ->
            val fixture = fixture(draft = TextFieldValue("Keep"))
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.paste()
            fixture.platform.listener.onError(error)

            val expected =
                if (error == ConversationDictationFailure.PermissionDenied) {
                    ConversationDictationFailure.ProviderAccessRejected
                } else {
                    error
                }
            assertEquals(expected, (fixture.controller.state as ConversationDictationState.Failed).reason)
            assertEquals(0, fixture.writes)
            assertFalse(fixture.controller.ownsMicrophone)
            assertFalse(fixture.controller.hasDurableSession)
        }
    }

    @Test
    fun retryExhaustionRechecksRevokedMicrophoneAccess() {
        listOf(
            ConversationDictationMicrophoneAccess.RuntimePermissionRequired to
                ConversationDictationFailure.PermissionDenied,
            ConversationDictationMicrophoneAccess.AppOpDenied to
                ConversationDictationFailure.PermissionPermanentlyDenied,
            ConversationDictationMicrophoneAccess.MicrophoneMuted to ConversationDictationFailure.MicrophoneMuted,
        ).forEach { (access, expected) ->
            val fixture = fixture(draft = TextFieldValue("Keep"))
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)
            fixture.scheduler.runDelay(500L)
            fixture.platform.microphoneAccessOverride = access
            fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)

            assertEquals(expected, (fixture.controller.state as ConversationDictationState.Failed).reason)
            assertEquals(0, fixture.writes)
            assertFalse(fixture.controller.ownsMicrophone)
        }
    }

    @Test
    fun successfulRetryAndCancellationClearPriorProviderFailure() {
        val fixture = fixture(draft = TextFieldValue("Keep"))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onReady()
        fixture.controller.paste()
        fixture.platform.listener.onResult(null)
        assertEquals(
            ConversationDictationFailure.NoSpeech,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )

        fixture.controller.dismissFailure()
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)
        fixture.controller.cancel()
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.paste()
        fixture.platform.listener.onResult(null)
        assertEquals(
            ConversationDictationFailure.NoSpeech,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
    }

    @Test
    fun sendDuringFailedRecoveryRetainsTextWithoutSilentlyPasting() {
        var sends = 0
        val fixture =
            fixture(
                draft = TextFieldValue("Keep"),
                sendTranscriptIfOriginUnchanged = {
                    sends += 1
                    true
                },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first segment")
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)
        fixture.controller.send()

        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        val failure = fixture.controller.state as ConversationDictationState.Failed
        assertEquals(ConversationDictationFailure.SendBlocked, failure.reason)
        assertEquals("first segment", failure.retainedTranscript)
        assertEquals(ConversationDictationFailure.ProviderAccessRejected, failure.cause)
        assertEquals(0, sends)
        assertEquals(0, fixture.writes)
        assertFalse(fixture.controller.ownsMicrophone)
        assertTrue(fixture.controller.hasDurableSession)
    }

    @Test
    fun emptyRetryCompletionBeforeReadyPreservesOriginalProviderFailure() {
        listOf(false, true).forEach { errorCallback ->
            val fixture = fixture(draft = TextFieldValue("Keep"))
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)
            fixture.scheduler.runDelay(500L)
            fixture.controller.paste()
            if (errorCallback) {
                fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            } else {
                fixture.platform.listener.onResult(null)
            }

            assertEquals(
                ConversationDictationFailure.ProviderAccessRejected,
                (fixture.controller.state as ConversationDictationState.Failed).reason,
            )
            assertEquals(0, fixture.writes)
            assertFalse(fixture.controller.ownsMicrophone)
        }
    }

    @Test
    fun confirmedSilentRetryCompletionPreservesOriginalProviderFailure() {
        val fixture = fixture(draft = TextFieldValue("Keep"))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)
        fixture.scheduler.runDelay(500L)

        fixture.controller.paste()
        val silentRetrySession = fixture.platform.session
        silentRetrySession.callerAudioHasSpeech = false
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult(null)

        assertEquals(1, silentRetrySession.acknowledgedCallerAudio)
        assertEquals(
            ConversationDictationFailure.ProviderAccessRejected,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0, fixture.writes)
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertFalse(fixture.controller.ownsMicrophone)
    }

    @Test
    fun emptyDisconnectCompletionPreservesCauseWithoutSending() {
        val fixture = fixture(draft = TextFieldValue("Keep"))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderDisconnected)
        fixture.controller.send()
        fixture.scheduler.advanceBy(1_000L)

        assertEquals(
            ConversationDictationFailure.ProviderDisconnected,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(0, fixture.writes)
        assertEquals(1, fixture.platform.sessions.size)
        assertFalse(fixture.controller.ownsMicrophone)
    }

    @Test
    fun successfulRetryStillDeliversRecognizedTextOnce() {
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)
        fixture.scheduler.runDelay(500L)
        val recovered = fixture.platform.listener
        recovered.onReady()
        fixture.controller.paste()
        recovered.onResult("recovered words")
        recovered.onResult("duplicate")

        assertEquals("Keep recovered words", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertFalse(fixture.controller.ownsMicrophone)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun successfulRetryStillSendsRecognizedTextExactlyOnce() =
        runTest {
            var sends = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Keep"),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = {
                        sends += 1
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)
            fixture.scheduler.runDelay(500L)
            val recovered = fixture.platform.listener
            recovered.onReady()
            fixture.controller.send()
            recovered.onResult("recovered words")
            recovered.onResult("duplicate")
            advanceUntilIdle()

            assertEquals(1, sends)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            assertFalse(fixture.controller.ownsMicrophone)
        }

    @Test
    fun providerRejectionAfterStopPreservesAccumulatedTextInTheDraft() {
        val fixture = fixture(draft = TextFieldValue("Keep"))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first segment")
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onBeginningOfSpeech()
        fixture.controller.paste()
        fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)

        assertEquals("Keep first segment", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertEquals(1, fixture.writes)
        assertFalse(fixture.controller.ownsMicrophone)
    }

    @Test
    fun rapidEmptyRetryExhaustionPreservesProviderFailureAndUsefulText() {
        listOf(false, true).forEach { withText ->
            val fixture = fixture(draft = TextFieldValue("Keep"))
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            if (withText) {
                fixture.platform.listener.onResult("first segment")
                fixture.scheduler.runDelay(500L)
            }
            fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)
            fixture.scheduler.runDelay(500L)
            repeat(2) {
                fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
                fixture.scheduler.runDelay(500L)
            }
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)

            if (withText) {
                assertEquals("Keep first segment", fixture.drafts.getValue(key()).text)
                assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            } else {
                assertEquals(
                    ConversationDictationFailure.ProviderAccessRejected,
                    (fixture.controller.state as ConversationDictationState.Failed).reason,
                )
            }
            assertEquals(if (withText) 1 else 0, fixture.writes)
            assertEquals(if (withText) "Keep first segment" else "Keep", fixture.drafts.getValue(key()).text)
            assertFalse(fixture.controller.ownsMicrophone)
            assertFalse(fixture.controller.hasDurableSession)
        }
    }

    /** Revoked access must never open another recording surface after recognition fails. */
    @Test
    fun microphoneAccessLostDuringCaptureDoesNotLaunchProviderActivity() {
        listOf(
            ConversationDictationMicrophoneAccess.RuntimePermissionRequired to
                ConversationDictationFailure.PermissionDenied,
            ConversationDictationMicrophoneAccess.AppOpDenied to
                ConversationDictationFailure.PermissionPermanentlyDenied,
            ConversationDictationMicrophoneAccess.MicrophoneMuted to ConversationDictationFailure.MicrophoneMuted,
        ).forEach { (access, failure) ->
            val fixture = fixture(draft = TextFieldValue("Keep"))
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.microphoneAccessOverride = access

            fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)

            assertEquals(failure, (fixture.controller.state as ConversationDictationState.Failed).reason)
            assertEquals(0L, fixture.controller.providerActivityRequestId)
            assertFalse(fixture.controller.ownsMicrophone)
            assertFalse(fixture.controller.hasDurableSession)
            assertEquals("Keep", fixture.drafts.getValue(key()).text)
            assertEquals(0, fixture.writes)
        }
    }

    @Test
    fun providerActivityAlsoWaitsForTheSharedFirstUseDisclosure() {
        var accepted = false
        val controller =
            ConversationDictationController(
                platform = FakePlatform(hasPermission = false),
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue(""), 0) },
                writeDraft = { _, _, _, _ -> 0L },
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
    fun providerActivityReadinessIsBoundedAndNeverAcquiresTheMicrophone() {
        val platform = FakePlatform(deferActivityReadiness = true)
        val events = mutableListOf<ConversationDictationReadinessEvent>()
        var microphoneAcquireCalls = 0
        val fixture =
            fixture(
                draft = TextFieldValue("Keep"),
                platform = platform,
                tryAcquireMicrophone = {
                    microphoneAcquireCalls += 1
                    true
                },
                onReadinessEvent = events::add,
            )

        fixture.controller.requestProviderActivityStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertTrue(fixture.controller.state is ConversationDictationState.CheckingProvider)
        assertEquals(0L, fixture.controller.providerActivityRequestId)
        assertEquals(0, microphoneAcquireCalls)
        assertFalse(fixture.controller.ownsMicrophone)
        assertEquals(ConversationDictationReadinessPhase.CheckingService, events.single().phase)

        fixture.scheduler.runLatest()

        assertEquals(
            ConversationDictationFailure.TimedOut,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(ConversationDictationReadinessPhase.TimedOut, events.last().phase)
        assertTrue(platform.readinessCancelled)
        assertEquals(0, microphoneAcquireCalls)
    }

    @Test
    fun cancelledAndStaleProviderReadinessCallbacksCannotLaunch() {
        val platform = FakePlatform(deferActivityReadiness = true)
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

        fixture.controller.requestProviderActivityStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val staleCallback = platform.activityReadinessCallback
        fixture.controller.cancel()
        staleCallback(true)

        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertEquals(0L, fixture.controller.providerActivityRequestId)
        assertTrue(platform.readinessCancelled)
    }

    @Test
    fun providerReadinessTransitionsToLaunchExactlyOnce() {
        val platform = FakePlatform(deferActivityReadiness = true)
        val events = mutableListOf<ConversationDictationReadinessEvent>()
        val fixture =
            fixture(
                draft = TextFieldValue("Keep"),
                platform = platform,
                onReadinessEvent = events::add,
            )

        fixture.controller.requestProviderActivityStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val callback = platform.activityReadinessCallback
        callback(true)
        callback(true)

        assertTrue(fixture.controller.state is ConversationDictationState.ProviderActivityRequired)
        assertEquals(1L, fixture.controller.providerActivityRequestId)
        assertEquals(
            listOf(
                ConversationDictationReadinessPhase.CheckingService,
                ConversationDictationReadinessPhase.ServiceReady,
            ),
            events.map { it.phase },
        )
        assertFalse(fixture.controller.ownsMicrophone)
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
        firstListener.onError(ConversationDictationFailure.Network)
        assertTrue(fixture.controller.state is ConversationDictationState.Failed)

        fixture.edit(key(), TextFieldValue("Two", TextRange(3)))
        fixture.controller.retry()
        fixture.scheduler.runDelay(500L)
        val secondListener = fixture.platform.listener
        firstListener.onResult("stale")
        fixture.controller.stop()
        secondListener.onResult("fresh")

        assertEquals("Two fresh", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun concurrentPrefixAndSuffixEditsKeepAUniqueContextAnchor() {
        val fixture = fixture(draft = TextFieldValue("Hello brave world", TextRange(6, 11)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()
        fixture.edit(key(), TextFieldValue("Note: Hello brave world!", TextRange(24)))

        fixture.controller.stop()
        fixture.platform.listener.onResult("calm")

        assertEquals("Note: Hello calm world!", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun incompatibleConcurrentEditAppendsAutomaticallyAndPreservesBothValues() {
        val fixture = fixture(draft = TextFieldValue("Original anchor", TextRange(8)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()
        fixture.edit(key(), TextFieldValue("Completely rewritten", TextRange(20)))

        fixture.controller.stop()
        fixture.platform.listener.onResult("dictated words")

        assertEquals("Completely rewritten dictated words", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun duplicateSuccessIsIdempotent() {
        val fixture = fixture(draft = TextFieldValue("Hello", TextRange(5)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val listener = fixture.platform.listener

        fixture.controller.stop()
        listener.onResult("there")
        listener.onResult("again")

        assertEquals("Hello there", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
    }

    /** Verifies that provider-final speech keeps the same logical session alive until Done is requested. */
    @Test
    fun providerFinalBeforeDoneStartsANewGenerationWithoutWriting() {
        var releases = 0
        val fixture =
            fixture(
                draft = TextFieldValue("Draft", TextRange(5)),
                releaseMicrophone = { releases += 1 },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val firstListener = fixture.platform.listener
        val firstSession = fixture.platform.session

        firstListener.onResult("early segment")

        assertEquals("Draft", fixture.drafts.getValue(key()).text)
        assertEquals(0, fixture.writes)
        assertEquals(1, releases)
        assertTrue(firstSession.destroyed)
        fixture.scheduler.runDelay(500L)
        assertEquals(2, fixture.platform.sessions.size)
        assertTrue(fixture.controller.state is ConversationDictationState.Starting)
        assertTrue(fixture.controller.ownsMicrophone)

        firstListener.onResult("stale duplicate")
        fixture.controller.stop()

        assertEquals("Draft early segment", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertEquals(2, releases)
    }

    /** Verifies that manual completion never treats an ordinary pause as implicit consent to finish. */
    @Test
    fun manualFinishIgnoresAPauseLongerThanTwoSeconds() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()

        fixture.scheduler.runThrough(2_500L)

        assertTrue(fixture.controller.state is ConversationDictationState.Listening)
        assertEquals(0, fixture.writes)
    }

    /** Verifies that each supported silence preference commits accumulated speech after its exact threshold. */
    @Test
    fun configuredSilenceThresholdsFinishAccumulatedSpeech() {
        listOf(3_000L, 5_000L, 10_000L).forEach { threshold ->
            val fixture =
                fixture(
                    draft = TextFieldValue(""),
                    finishAfterSilenceMillis = { threshold },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onResult("finished after silence")

            fixture.scheduler.runDelay(threshold)

            assertEquals("finished after silence", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }
    }

    /** A short caller-audio utterance must endpoint before any provider-final text exists. */
    @Test
    fun configuredSilenceFinishesShortCallerAudioAndPastesItsFinalTail() {
        val platform =
            FakePlatform().apply {
                pendingCallerAudio = true
                capturedSilenceMillis = 0L
            }
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                platform = platform,
                finishAfterSilenceMillis = { 3_000L },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.scheduler.advanceBy(2_999L)
        assertFalse(platform.session.stopped)
        platform.capturedSilenceMillis = 3_000L
        fixture.scheduler.advanceBy(1L)

        assertTrue(platform.session.stopped)
        assertTrue(fixture.controller.state is ConversationDictationState.Processing)
        assertEquals(0, fixture.writes)
        platform.pendingCallerAudio = false
        platform.listener.onResult("complete short tail")

        assertEquals("complete short tail", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Automatic endpointing waits for real PCM speech instead of timing out from capture startup. */
    @Test
    fun configuredSilenceDoesNotFinishCallerAudioBeforeSpeech() {
        val platform =
            FakePlatform().apply {
                pendingCallerAudio = true
                capturedSilenceMillis = null
            }
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                platform = platform,
                finishAfterSilenceMillis = { 3_000L },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.scheduler.advanceBy(9_000L)

        assertFalse(platform.session.stopped)
        assertTrue(fixture.controller.state is ConversationDictationState.Starting)
        assertEquals(0, fixture.writes)
    }

    /** Capture-side speech resets the configured interval even when provider callbacks lag. */
    @Test
    fun configuredSilenceRearmsFromResumedCallerAudioSpeech() {
        val platform =
            FakePlatform().apply {
                pendingCallerAudio = true
                capturedSilenceMillis = 0L
            }
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                platform = platform,
                finishAfterSilenceMillis = { 3_000L },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.scheduler.advanceBy(2_500L)
        platform.capturedSilenceMillis = 0L

        fixture.scheduler.advanceBy(500L)
        assertFalse(platform.session.stopped)
        fixture.scheduler.advanceBy(2_999L)
        assertFalse(platform.session.stopped)
        platform.capturedSilenceMillis = 3_000L
        fixture.scheduler.advanceBy(1L)

        assertTrue(platform.session.stopped)
    }

    /** A delayed provider speech callback must keep using the current capture clock and cannot rearm after stop. */
    @Test
    fun configuredSilenceFencesLaggingProviderSpeechCallbacks() {
        val platform =
            FakePlatform().apply {
                pendingCallerAudio = true
                capturedSilenceMillis = 0L
            }
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                platform = platform,
                finishAfterSilenceMillis = { 3_000L },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.scheduler.advanceBy(2_500L)
        platform.capturedSilenceMillis = 0L
        platform.listener.onBeginningOfSpeech()

        fixture.scheduler.advanceBy(2_999L)
        assertFalse(platform.session.stopped)
        platform.capturedSilenceMillis = 3_000L
        fixture.scheduler.advanceBy(1L)

        assertTrue(platform.session.stopped)
        val pendingAfterStop = fixture.scheduler.liveTaskCount()
        platform.listener.onBeginningOfSpeech()
        assertEquals(pendingAfterStop, fixture.scheduler.liveTaskCount())
    }

    /** The capture clock is logical-session state even when a replacement generation has no stream. */
    @Test
    fun configuredSilenceRetainsCallerAudioClockAcrossRecognizerGenerations() {
        val platform =
            FakePlatform().apply {
                pendingCallerAudio = true
                capturedSilenceMillis = 0L
            }
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                platform = platform,
                finishAfterSilenceMillis = { 3_000L },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        platform.sessionCallerAudioOwnedOverride = false
        platform.listener.onResult("first chunk")
        fixture.scheduler.runDelay(500L)
        assertEquals(2, platform.sessions.size)

        platform.capturedSilenceMillis = 3_000L
        fixture.scheduler.advanceBy(2_750L)

        assertTrue(platform.sessions.last().stopped)
        platform.pendingCallerAudio = false
        platform.listener.onResult("final tail")
        assertEquals("first chunk final tail", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
    }

    /**
     * Automatic completion sends its final tail when that is what the silence setting asks for.
     *
     * This is the one path allowed to send without a button press, and only because someone chose
     * it for silence specifically, so it shares the short-tail endpoint and delivery pipeline Paste
     * uses rather than a shortcut of its own.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun configuredSilenceFinishesShortCallerAudioAndSendsItsFinalTail() =
        runTest {
            val sent = mutableListOf<String>()
            val platform =
                FakePlatform().apply {
                    pendingCallerAudio = true
                    capturedSilenceMillis = 0L
                }
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    platform = platform,
                    finishAfterSilenceMillis = { 3_000L },
                    silenceDeliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        sent += request.payload
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            platform.capturedSilenceMillis = 3_000L
            fixture.scheduler.advanceBy(3_000L)
            platform.pendingCallerAudio = false
            platform.listener.onResult("complete short tail")
            advanceUntilIdle()

            assertEquals(listOf("Draft complete short tail"), sent)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** Automatic completion leaves the transcript in the draft unless silence was set to send. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun configuredSilenceLeavesItsTailInTheDraftWhenPasteIsChosen() =
        runTest {
            val sent = mutableListOf<String>()
            val platform =
                FakePlatform().apply {
                    pendingCallerAudio = true
                    capturedSilenceMillis = 0L
                }
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    platform = platform,
                    finishAfterSilenceMillis = { 3_000L },
                    silenceDeliveryMode = { ConversationDictationDeliveryMode.PasteIntoDraft },
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        sent += request.payload
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            platform.capturedSilenceMillis = 3_000L
            fixture.scheduler.advanceBy(3_000L)
            platform.pendingCallerAudio = false
            platform.listener.onResult("complete short tail")
            advanceUntilIdle()

            assertEquals("the default automatic choice must not send", emptyList<String>(), sent)
            assertEquals("Draft complete short tail", fixture.drafts.getValue(key()).text)
        }

    /**
     * A stored send choice governs automatic completion only, never one a person ended by hand.
     *
     * This is the whole point of narrowing the setting: Done finishes the dictation before the
     * silence timer ever fires, so the transcript belongs in the draft even though the stored
     * choice for silence says send.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aStoredSendChoiceDoesNotReachACompletionEndedByHand() =
        runTest {
            val sent = mutableListOf<String>()
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    finishAfterSilenceMillis = { 3_000L },
                    silenceDeliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        sent += request.payload
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.stop()
            fixture.platform.listener.onResult("ended by hand")
            advanceUntilIdle()

            assertEquals("Done must not inherit the silence setting", emptyList<String>(), sent)
            assertEquals("Draft ended by hand", fixture.drafts.getValue(key()).text)
        }

    /** An explicit Paste wins over a stored send choice for the same session. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anExplicitPasteWinsOverAStoredSendChoice() =
        runTest {
            val sent = mutableListOf<String>()
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    finishAfterSilenceMillis = { 3_000L },
                    silenceDeliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        sent += request.payload
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.paste()
            fixture.platform.listener.onResult("chosen by hand")
            advanceUntilIdle()

            assertEquals("a pressed Paste must win", emptyList<String>(), sent)
            assertEquals("Draft chosen by hand", fixture.drafts.getValue(key()).text)
        }

    /** Buffered live speech must not be mistaken for provider silence between 30-second chunks. */
    @Test
    fun bufferedSpeechDefersAutomaticFinishUntilCaptureIsQuietAndTailIsRecognized() {
        val platform =
            FakePlatform().apply {
                pendingCallerAudio = true
                capturedSilenceMillis = 0L
            }
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                platform = platform,
                finishAfterSilenceMillis = { 3_000L },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        platform.listener.onResult("first chunk")
        fixture.scheduler.runDelay(500L)
        platform.listener.onReady()

        fixture.scheduler.runDelay(3_000L)

        assertEquals(0, fixture.writes)
        assertTrue(fixture.controller.state is ConversationDictationState.Listening)
        platform.capturedSilenceMillis = 3_000L
        fixture.scheduler.runDelay(3_000L)
        assertTrue(fixture.controller.state is ConversationDictationState.Processing)
        assertEquals(0, fixture.writes)
        platform.pendingCallerAudio = false
        platform.listener.onResult("last chunk")

        assertEquals("first chunk last chunk", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
    }

    /** Verifies segment spacing, punctuation attachment, and repeated speech across generations. */
    @Test
    fun segmentAccumulatorPreservesPunctuationAndRepeatedSpeechAcrossGenerations() {
        assertEquals("Hello world", appendConversationDictationSegment("Hello ", "world"))
        assertEquals("你好。世界", appendConversationDictationSegment("你好", "。世界"))
        assertEquals("مرحبا؟", appendConversationDictationSegment("مرحبا", "؟"))
        assertEquals("مرحبا،العالم", appendConversationDictationSegment("مرحبا", "،العالم"))
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.platform.listener.onResult("Hello")
        fixture.scheduler.runDelay(500L)
        assertEquals(2, fixture.platform.sessions.size)
        fixture.platform.listener.onResult("Hello")
        fixture.scheduler.runDelay(500L)
        assertEquals(3, fixture.platform.sessions.size)
        fixture.platform.listener.onResult(",")
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onResult("world")
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onResult("world")
        fixture.controller.stop()

        assertEquals("Hello Hello, world world", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
    }

    /** A duplicate callback from a completed generation is stale and must not append twice. */
    @Test
    fun staleDuplicateFinalFromCompletedGenerationIsIgnored() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val firstGeneration = fixture.platform.listener

        firstGeneration.onResult("Hello")
        fixture.scheduler.runDelay(500L)
        assertEquals(2, fixture.platform.sessions.size)
        firstGeneration.onResult("Hello")
        fixture.controller.stop()

        assertEquals("Hello", fixture.drafts.getValue(key()).text)
    }

    /** Stopping capture drains every sealed caller-audio chunk before committing the transcript. */
    @Test
    fun stopDrainsPendingCallerAudioChunksBeforeFinalizing() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.controller.stop()
        fixture.platform.listener.onResult("first")

        assertEquals(1, fixture.platform.sessions.size)
        fixture.scheduler.runDelay(500L)
        assertEquals(2, fixture.platform.sessions.size)
        val draftText = fixture.drafts.getValue(key()).text
        assertTrue(draftText.isEmpty())
        val firstSession = fixture.platform.sessions.first()
        assertEquals(1, firstSession.acknowledgedCallerAudio)

        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("second")

        assertEquals("first second", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        val lastSession = fixture.platform.sessions.last()
        assertEquals(1, lastSession.acknowledgedCallerAudio)
    }

    /** An empty provider final retains its exact 30-second chunk and retries it before delivery. */
    @Test
    fun stopRetriesBlankCallerAudioChunkWithoutLosingItsTail() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(500L)

        fixture.controller.stop()
        val blankSession = fixture.platform.session
        fixture.platform.listener.onResult(null)

        assertEquals(0, blankSession.acknowledgedCallerAudio)
        assertEquals(1, blankSession.retriedCallerAudio)
        assertTrue(fixture.controller.state is ConversationDictationState.Starting)
        assertEquals("", fixture.drafts.getValue(key()).text)

        fixture.scheduler.runDelay(500L)
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("last sentence")

        assertEquals("first last sentence", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Repeated rejection retains the exact speech-bearing chunk instead of skipping possible words. */
    @Test
    fun repeatedNoSpeechRetainsTheExactCallerAudioChunkWhileRecording() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        repeat(2) {
            fixture.platform.session.providerError(ConversationDictationFailure.NoSpeech)
            fixture.scheduler.runDelay(500L)
        }
        fixture.platform.session.providerError(ConversationDictationFailure.NoSpeech)
        assertEquals(3, fixture.platform.sessions.size)
        assertEquals(0, fixture.platform.sessions.sumOf { it.acknowledgedCallerAudio })
        assertEquals(1L, fixture.platform.session.callerAudioChunkId())
        assertTrue(fixture.controller.state is ConversationDictationState.Failed)
        assertTrue(fixture.controller.hasDurableSession)
        fixture.scheduler.advanceBy(10_000L)
        assertEquals(3, fixture.platform.sessions.size)
        fixture.controller.retry()
        fixture.scheduler.runDelay(500L)
        assertEquals(1L, fixture.platform.session.callerAudioChunkId())
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("quiet final words")
        assertEquals("quiet final words", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Recording-time no-speech recovery coalesces later PCM before retrying the rejected chunk. */
    @Test
    fun recordingNoSpeechCoalescesFollowingCallerAudioBeforeRetry() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.platform.session.providerError(ConversationDictationFailure.NoSpeech)

        assertEquals(1, fixture.platform.session.retriedCallerAudioWithFollowingAudio)
        assertEquals(1, fixture.platform.session.retriedCallerAudio)
    }

    /** Recording retries cannot spend the independent retry budget reserved for explicit completion. */
    @Test
    fun recordingRetriesDoNotConsumeExplicitCompletionRetryBudget() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.platform.session.providerError(ConversationDictationFailure.NoSpeech)
        fixture.scheduler.runLatest()
        fixture.controller.paste()

        repeat(2) {
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            fixture.scheduler.runDelay(500L)
        }
        fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)

        assertTrue(fixture.controller.state is ConversationDictationState.Failed)
    }

    /** Capture-confirmed silence advances immediately instead of spending the speech retry budget. */
    @Test
    fun noSpeechAdvancesConfirmedSilentCallerAudioWithoutRetryingIt() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.sessions
            .single()
            .callerAudioHasSpeech = false

        fixture.platform.sessions
            .single()
            .providerError(ConversationDictationFailure.NoSpeech)

        assertEquals(1, fixture.platform.sessions.size)
        fixture.scheduler.runDelay(500L)
        assertEquals(2, fixture.platform.sessions.size)
        assertEquals(
            1,
            fixture.platform.sessions
                .first()
                .acknowledgedCallerAudio,
        )
        assertEquals(
            0,
            fixture.platform.sessions
                .first()
                .retriedCallerAudio,
        )
        assertEquals(
            2L,
            fixture.platform.sessions
                .last()
                .callerAudioChunkId(),
        )
    }

    /** Repeated blank/error finals stop the Paste spinner through the originally selected action. */
    @Test
    fun stopBoundsRetainedCallerAudioRetriesBeforeCompletingRequestedPaste() {
        listOf(
            null,
            ConversationDictationFailure.NoSpeech,
            ConversationDictationFailure.ProviderDisconnected,
            ConversationDictationFailure.RecognizerBusy,
        ).forEach { failureCallback ->
            val fixture = fixture(draft = TextFieldValue(""))
            fixture.platform.pendingCallerAudio = true
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onResult("first")
            fixture.scheduler.runDelay(500L)
            fixture.controller.paste()

            repeat(2) { retry ->
                if (failureCallback != null) {
                    fixture.platform.listener.onError(failureCallback)
                } else {
                    fixture.platform.listener.onResult(null)
                }
                assertTrue(fixture.controller.state is ConversationDictationState.Starting)
                if (
                    failureCallback == ConversationDictationFailure.ProviderDisconnected ||
                    failureCallback == ConversationDictationFailure.RecognizerBusy
                ) {
                    fixture.scheduler.runDelay(if (retry == 0) 500L else 1_000L)
                } else {
                    fixture.scheduler.runDelay(500L)
                }
            }
            if (failureCallback != null) {
                fixture.platform.listener.onError(failureCallback)
            } else {
                fixture.platform.listener.onResult(null)
            }

            assertEquals("", fixture.drafts.getValue(key()).text)
            assertEquals(
                3,
                fixture.platform.sessions
                    .drop(1)
                    .sumOf { it.retriedCallerAudio },
            )
            assertTrue(fixture.controller.hasDurableSession)
            val failed = fixture.controller.state as ConversationDictationState.Failed
            assertEquals("first", failed.retainedTranscript)

            fixture.controller.retry()
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            fixture.scheduler.runDelay(500L)
            fixture.platform.pendingCallerAudio = false
            fixture.platform.listener.onResult("recovered tail")
            assertEquals("first recovered tail", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            assertFalse(fixture.controller.hasDurableSession)
        }
    }

    /** Capture closes before the retained recovery lease narrows to non-microphone work. */
    @Test
    fun retainedAudioFailureKeepsRecoveryUntilExplicitDismissal() {
        var stops = 0
        val fixture = fixture(draft = TextFieldValue(""), stopDurableSession = { stops += 1 })
        fixture.platform.pendingCallerAudio = true
        fixture.platform.deferCallerAudioFinish = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        assertTrue(fixture.controller.state is ConversationDictationState.Failed)
        assertTrue(fixture.controller.hasDurableSession)
        assertTrue(fixture.controller.foregroundMicrophoneRequired)
        requireNotNull(fixture.platform.callerAudioFinishCallback).invoke()
        assertTrue(fixture.controller.hasDurableSession)
        assertFalse(fixture.controller.foregroundMicrophoneRequired)
        assertEquals(0, stops)
        assertTrue(fixture.platform.pendingCallerAudio)
        requireNotNull(fixture.platform.callerAudioFinishCallback).invoke()
        fixture.platform.tracksCallerAudioDisposal = true
        fixture.controller.dismissFailure()
        assertEquals(1, stops)
        assertFalse(fixture.platform.pendingCallerAudio)
    }

    /** The closure watchdog stops a stalled recorder rather than pretending it already closed. */
    @Test
    fun stalledFailureRecorderIsForcedClosedWithoutDiscardingRecovery() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.platform.deferCallerAudioFinish = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        fixture.scheduler.advanceBy(999L)
        assertEquals(0, fixture.platform.forcedCallerAudioClosures)
        assertTrue(fixture.controller.foregroundMicrophoneRequired)
        fixture.scheduler.advanceBy(1L)
        assertEquals(1, fixture.platform.forcedCallerAudioClosures)
        assertFalse(fixture.controller.foregroundMicrophoneRequired)
        assertTrue(fixture.controller.hasDurableSession)
        assertTrue(fixture.platform.pendingCallerAudio)
    }

    /** A closure exception cannot substitute for the native callback's proof. */
    @Test
    fun forcedClosureExceptionDoesNotAcknowledgeMicrophone() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.platform.deferCallerAudioFinish = true
        fixture.platform.forceCaptureFailure = IllegalStateException("native close failed")
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        fixture.scheduler.advanceBy(1_000L)
        assertTrue(fixture.controller.foregroundMicrophoneRequired)
        assertTrue(fixture.controller.hasDurableSession)
        requireNotNull(fixture.platform.callerAudioFinishCallback).invoke()
        assertFalse(fixture.controller.foregroundMicrophoneRequired)
        fixture.controller.dismissFailure()
    }

    /** No queued PCM does not imply that a discarded native recorder has finished closing. */
    @Test
    fun transcriptOnlyFailureForcesDiscardedCaptureClosed() {
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = FakePlatform(deferCaptureCompletion = true))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first segment")
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onBeginningOfSpeech()
        fixture.controller.send()
        assertTrue(fixture.controller.state is ConversationDictationState.Processing)
        assertTrue(fixture.controller.foregroundMicrophoneRequired)
        fixture.platform.tracksCallerAudioDisposal = true
        fixture.platform.discardCaptureActive = true
        fixture.platform.deferDiscardClosure = true
        fixture.platform.deferCallerAudioFinish = true
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        val failed = fixture.controller.state as ConversationDictationState.Failed
        assertEquals("first segment", failed.retainedTranscript)
        assertTrue(failed.recognitionIncomplete)
        assertTrue(fixture.controller.hasDurableSession)
        assertTrue(fixture.controller.foregroundMicrophoneRequired)
        fixture.scheduler.advanceBy(1_000L)
        assertEquals(1, fixture.platform.forcedCallerAudioClosures)
        assertFalse(fixture.controller.foregroundMicrophoneRequired)
        assertTrue(fixture.controller.state === failed)
        fixture.controller.dismissFailure()
    }

    /** Recognition generations share capture ownership, but a new gesture cannot inherit it. */
    @Test
    fun captureBoundaryStartsOncePerLogicalGesture() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        assertEquals(1, fixture.platform.captureSessionsStarted)
        fixture.platform.listener.onResult("first segment")
        fixture.scheduler.runDelay(500L)
        assertEquals(1, fixture.platform.captureSessionsStarted)
        fixture.controller.cancel()
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        assertEquals(2, fixture.platform.captureSessionsStarted)
        fixture.controller.cancel()
    }

    /** Service loss during Send must not turn a recognized prefix into a completed transcript. */
    @Test
    fun serviceLossDuringSendKeepsPartialConfirmationRequired() {
        val fixture = fixture(draft = TextFieldValue("Keep"))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first segment")
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onBeginningOfSpeech()
        fixture.controller.send()
        assertTrue(fixture.controller.state is ConversationDictationState.Processing)
        fixture.controller.onDurableServiceDestroyed(requireNotNull(fixture.controller.notificationSessionToken))
        val failed = fixture.controller.state as ConversationDictationState.Failed
        assertEquals("first segment", failed.retainedTranscript)
        assertTrue(failed.recognitionIncomplete)
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        fixture.controller.dismissFailure()
    }

    /** A completed transcript remains complete when its queued validation is interrupted. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun serviceLossDuringCompletedValidationDoesNotMarkTextIncomplete() =
        runTest {
            val validation = CompletableDeferred<ConversationDictationTargetValidation>()
            val fixture =
                fixture(
                    draft = TextFieldValue("Keep"),
                    targetValidator = { _, _ -> validation.await() },
                    targetValidationScope = this,
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("completed text")
            runCurrent()
            fixture.controller.onDurableServiceDestroyed(requireNotNull(fixture.controller.notificationSessionToken))
            val failed = fixture.controller.state as ConversationDictationState.Failed
            assertEquals("completed text", failed.retainedTranscript)
            assertFalse(failed.recognitionIncomplete)
            validation.complete(ConversationDictationTargetValidation.Available)
            advanceUntilIdle()
            assertTrue(fixture.controller.state === failed)
            fixture.controller.dismissFailure()
        }

    /** Teardown failure cannot hold app audio ownership forever or pretend native closure succeeded. */
    @Test
    fun discardExceptionReleasesAppAudioWithoutAcknowledgingNativeClosure() {
        var available = true
        var releases = 0
        var restored = 0
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                tryAcquireMicrophone = { available.also { available = false } },
                releaseMicrophone = {
                    available = true
                    releases++
                },
                onAfterAudioCapture = { restored++ },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.discardCaptureFailure = IllegalStateException("discard failed")
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        assertEquals(1, releases)
        assertEquals(1, restored)
        assertTrue(fixture.controller.foregroundMicrophoneRequired)
        fixture.platform.discardCaptureFailure = null
        fixture.controller.dismissFailure()
        val oldSession = fixture.platform.session
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.scheduler.advanceBy(500L)
        assertTrue(fixture.platform.session !== oldSession)
        assertTrue(fixture.platform.session.started)
        assertFalse(available)
        fixture.controller.cancel()
        assertEquals(2, releases)
        assertEquals(2, restored)
    }

    /** A native-closure callback treats an unreadable queue as retained instead of crashing Main. */
    @Test
    fun closureStateQueryFailureKeepsRecoveryLeaseWithoutCrashing() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.platform.deferCallerAudioFinish = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        val failed = fixture.controller.state as ConversationDictationState.Failed
        fixture.platform.callerAudioStateFailure = IllegalStateException("capture state failed")
        fixture.scheduler.advanceBy(1_000L)
        assertFalse(fixture.controller.foregroundMicrophoneRequired)
        assertTrue(fixture.controller.hasDurableSession)
        assertTrue(fixture.controller.state === failed)
        fixture.platform.callerAudioStateFailure = null
        fixture.controller.dismissFailure()
    }

    /** Bounded recovery expires once and clears PCM without silently writing a draft. */
    @Test
    fun recoveryExpiresAtThirtyMinutesAndDoesNotSurviveExplicitDismissal() {
        var expired = 0
        var stops = 0
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                onRecoveryExpired = { expired++ },
                stopDurableSession = { stops++ },
            )
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.tracksCallerAudioDisposal = true
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        fixture.scheduler.advanceBy(30 * 60 * 1_000L - 1L)
        assertTrue(fixture.platform.pendingCallerAudio)
        assertTrue(fixture.controller.hasDurableSession)
        fixture.scheduler.advanceBy(1L)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertFalse(fixture.platform.pendingCallerAudio)
        assertEquals(1, fixture.platform.discardedCallerAudio)
        assertEquals(1, stops)
        assertEquals(1, expired)
        assertEquals("", fixture.drafts.getValue(key()).text)
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.pendingCallerAudio = true
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        fixture.controller.dismissFailure()
        fixture.scheduler.advanceBy(30 * 60 * 1_000L)
        assertEquals(1, expired)
    }

    /** A sleeping device cannot reattach expired PCM before its delayed timer is dispatched. */
    @Test
    fun foregroundReturnExpiresRecoveryAfterSleepWithoutReattaching() {
        var starts = 0
        var expired = 0
        val fixture =
            fixture(draft = TextFieldValue(""), onRecoveryExpired = { expired++ }, startDurableSession = { _, ready ->
                starts++
                ready()
                true
            })
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.tracksCallerAudioDisposal = true
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        val delayedExpiry = fixture.scheduler.latestCallback()
        fixture.controller.onDurableServiceDestroyed(requireNotNull(fixture.controller.notificationSessionToken))
        fixture.scheduler.sleepWithoutDispatch(30 * 60 * 1_000L)
        fixture.controller.onAppForegrounded()
        delayedExpiry()
        fixture.controller.onAppForegrounded()
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertFalse(fixture.platform.pendingCallerAudio)
        assertFalse(fixture.controller.hasDurableSession)
        assertEquals(1, fixture.platform.discardedCallerAudio)
        assertEquals(1, starts)
        assertEquals(1, expired)
        assertEquals(0, fixture.writes)
    }

    /** A direct Retry also enforces elapsed expiry when the app-resume callback was withheld. */
    @Test
    fun retryCannotTranscribeExpiredPcmAfterSleep() {
        var expired = 0
        val fixture = fixture(draft = TextFieldValue(""), onRecoveryExpired = { expired++ })
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.tracksCallerAudioDisposal = true
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        val sessions = fixture.platform.sessions.size
        fixture.scheduler.sleepWithoutDispatch(35 * 60 * 1_000L)
        fixture.controller.retry()
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertFalse(fixture.platform.pendingCallerAudio)
        assertEquals(sessions, fixture.platform.sessions.size)
        assertEquals(1, expired)
        assertEquals(0, fixture.writes)
    }

    /** Neither transcript recovery action may dispatch or paste after an undispatched sleep deadline. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun expiredSendFailureCannotRetryOrPasteItsTranscript() =
        runTest {
            for (paste in listOf(false, true)) {
                var sends = 0
                var expired = 0
                val fixture =
                    fixture(
                        draft = TextFieldValue(""),
                        targetValidationScope = this,
                        onRecoveryExpired = { expired++ },
                        sendTranscriptIfOriginUnchanged = {
                            sends++
                            false
                        },
                    )
                fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
                fixture.controller.send()
                fixture.platform.listener.onResult("retained body")
                advanceUntilIdle()
                val failed = fixture.controller.state as ConversationDictationState.Failed
                assertEquals(ConversationDictationFailure.SendBlocked, failed.reason)
                assertEquals("retained body", failed.retainedTranscript)
                assertEquals(1, sends)
                fixture.scheduler.sleepWithoutDispatch(30 * 60 * 1_000L)
                if (paste) fixture.controller.paste() else fixture.controller.retry()
                advanceUntilIdle()
                assertTrue(fixture.controller.state is ConversationDictationState.Idle)
                assertEquals(1, sends)
                assertEquals(1, expired)
                assertEquals(0, fixture.writes)
            }
        }

    /** Expiring an old request permits a fresh capture and fences even a queued old timer callback. */
    @Test
    fun freshStartAfterSleepCannotBeExpiredByOldRecoveryCallback() {
        var expired = 0
        val fixture = fixture(draft = TextFieldValue(""), onRecoveryExpired = { expired++ })
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.tracksCallerAudioDisposal = true
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        val delayedExpiry = fixture.scheduler.latestCallback()
        fixture.scheduler.sleepWithoutDispatch(30 * 60 * 1_000L)
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val replacement = fixture.controller.state
        assertTrue(replacement is ConversationDictationState.Starting)
        delayedExpiry()
        assertTrue(fixture.controller.state === replacement)
        assertTrue(fixture.controller.hasDurableSession)
        assertEquals(1, expired)
    }

    /** Recovery remains usable just before the elapsed deadline, including after deep sleep. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pasteBeforeElapsedDeadlineCompletesAndFencesOldExpiry() =
        runTest {
            var expired = 0
            val fixture =
                fixture(
                    draft = TextFieldValue(""),
                    targetValidationScope = this,
                    onRecoveryExpired = { expired++ },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("retained body")
            advanceUntilIdle()
            assertTrue(fixture.controller.state is ConversationDictationState.Failed)
            val delayedExpiry = fixture.scheduler.latestCallback()
            fixture.scheduler.sleepWithoutDispatch(30 * 60 * 1_000L - 1L)
            fixture.controller.paste()
            advanceUntilIdle()
            fixture.scheduler.sleepWithoutDispatch(1L)
            delayedExpiry()
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            assertEquals("retained body", fixture.drafts.getValue(key()).text)
            assertEquals(0, expired)
            assertEquals(1, fixture.writes)
        }

    /** A failed reattach or recents swipe preserves existing failure text and its expiry. */
    @Test
    fun lostRecoveryRecordCanReattachWithoutOpeningCaptureOrErasingFailure() {
        var starts = 0
        val fixture =
            fixture(draft = TextFieldValue(""), startDurableSession = { _, ready ->
                starts++
                if (starts == 1) ready()
                starts == 1
            })
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("body")
        fixture.scheduler.runDelay(500L)
        fixture.controller.send()
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        val failed = fixture.controller.state as ConversationDictationState.Failed
        fixture.controller.onDurableServiceDestroyed(requireNotNull(fixture.controller.notificationSessionToken))
        fixture.controller.onTaskRemoved()
        fixture.controller.onAppForegrounded()
        assertEquals(failed, fixture.controller.state)
        assertEquals("body", failed.retainedTranscript)
        assertFalse(fixture.controller.hasDurableSession)
        assertFalse(fixture.controller.foregroundMicrophoneRequired)
        assertEquals(2, starts)
        assertEquals("", fixture.drafts.getValue(key()).text)
        fixture.controller.dismissFailure()
    }

    /** Transcript-only failures retain the same native closure barrier as retained-PCM recovery. */
    @Test
    fun transcriptRecoveryReattachesOnlyAfterProviderCaptureClosure() {
        var starts = 0
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                platform = FakePlatform(deferCaptureCompletion = true),
                startDurableSession = { _, ready ->
                    starts++
                    ready()
                    true
                },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.session.completeCapture()
        fixture.platform.listener.onResult("body")
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onBeginningOfSpeech()
        fixture.controller.send()
        assertTrue(fixture.controller.state is ConversationDictationState.Processing)
        fixture.controller.onTargetRemoved(ACCOUNT, GROUP)
        val failed = fixture.controller.state as ConversationDictationState.Failed
        val closingCapture = fixture.platform.session
        fixture.controller.onDurableServiceDestroyed(requireNotNull(fixture.controller.notificationSessionToken))
        fixture.controller.onAppForegrounded()
        assertEquals(1, starts)
        assertTrue(fixture.controller.foregroundMicrophoneRequired)
        assertFalse(fixture.controller.hasDurableSession)
        closingCapture.completeCapture()
        assertEquals(2, starts)
        assertFalse(fixture.controller.foregroundMicrophoneRequired)
        assertTrue(fixture.controller.hasDurableSession)
        assertTrue(fixture.controller.state === failed)
        assertEquals("body", failed.retainedTranscript)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            fixture.controller.foregroundServiceType,
        )
        assertEquals("", fixture.drafts.getValue(key()).text)
        fixture.controller.dismissFailure()
    }

    /** Returning before closure preserves the watchdog and only then reattaches non-microphone recovery. */
    @Test
    fun recoveryReattachWaitsForClosure() = checkDeferredRecoveryReattach(leaveForeground = false)

    /** A deferred foreground reattach must not turn into a fresh background service start. */
    @Test
    fun backgroundingCancelsDeferredReattach() = checkDeferredRecoveryReattach(leaveForeground = true)

    private fun checkDeferredRecoveryReattach(leaveForeground: Boolean) {
        var starts = 0
        val fixture =
            fixture(draft = TextFieldValue(""), startDurableSession = { _, ready ->
                starts++
                ready()
                true
            })
        fixture.platform.pendingCallerAudio = true
        fixture.platform.deferCallerAudioFinish = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        val failed = fixture.controller.state
        fixture.controller.onDurableServiceDestroyed(requireNotNull(fixture.controller.notificationSessionToken))
        fixture.controller.onAppForegrounded()
        assertEquals(1, starts)
        assertFalse(fixture.controller.hasDurableSession)
        assertTrue(fixture.controller.foregroundMicrophoneRequired)
        if (leaveForeground) {
            fixture.controller.onAppBackgrounded()
            fixture.controller.onTaskRemoved()
        }
        fixture.scheduler.advanceBy(1_000L)
        assertEquals(1, fixture.platform.forcedCallerAudioClosures)
        assertFalse(fixture.controller.foregroundMicrophoneRequired)
        assertTrue(fixture.controller.state === failed)
        assertTrue(fixture.platform.pendingCallerAudio)
        assertEquals(if (leaveForeground) 1 else 2, starts)
        if (leaveForeground) fixture.controller.onAppForegrounded()
        assertEquals(2, starts)
        assertTrue(fixture.controller.hasDurableSession)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            fixture.controller.foregroundServiceType,
        )
        assertEquals("", fixture.drafts.getValue(key()).text)
        fixture.controller.dismissFailure()
    }

    /** Retry cannot cancel the watchdog of a recorder that has not acknowledged closure yet. */
    @Test
    fun retryBeforeCaptureClosureStillForcesTheSameRecorderClosed() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.platform.deferCallerAudioFinish = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        fixture.controller.retry()
        fixture.scheduler.advanceBy(1_000L)
        assertEquals(1, fixture.platform.forcedCallerAudioClosures)
        assertFalse(fixture.controller.foregroundMicrophoneRequired)
        assertTrue(fixture.platform.pendingCallerAudio)
        assertTrue(fixture.controller.hasDurableSession)
        fixture.controller.cancel()
    }

    /** Sealed-audio Retry reacquires foreground ownership and fences the previous lease's callbacks. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retainedAudioRetryWaitsForItsOwnForegroundLeaseWithoutRecordingAgain() =
        runTest {
            val ready = mutableListOf<() -> Unit>()
            val sent = mutableListOf<String>()
            var acquisitions = 0
            var stops = 0
            val fixture =
                fixture(
                    draft = TextFieldValue(""),
                    targetValidationScope = this,
                    startDurableSession = { _, callback ->
                        ready += callback
                        true
                    },
                    stopDurableSession = { stops += 1 },
                    tryAcquireMicrophone = {
                        acquisitions += 1
                        true
                    },
                    sendTranscriptIfOriginUnchanged = {
                        sent += it.payload
                        true
                    },
                )
            fixture.platform.pendingCallerAudio = true
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            ready[0]()
            val oldToken = requireNotNull(fixture.controller.notificationSessionToken)
            fixture.platform.listener.onResult("body")
            fixture.scheduler.runDelay(500L)
            fixture.controller.send()
            repeat(2) {
                fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
                fixture.scheduler.runDelay(500L)
            }
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            assertTrue(fixture.controller.hasDurableSession)
            fixture.controller.onDurableServiceDestroyed(oldToken)
            assertFalse(fixture.controller.hasDurableSession)
            assertEquals(0, stops)
            assertTrue(fixture.platform.pendingCallerAudio)
            val generations = fixture.platform.sessions.size

            fixture.controller.retry()
            fixture.scheduler.runDelay(500L)
            assertEquals(2, ready.size)
            assertTrue(fixture.controller.hasDurableSession)
            assertTrue(oldToken != fixture.controller.notificationSessionToken)
            ready[0]()
            fixture.controller.onDurableServiceDestroyed(oldToken)
            fixture.controller.onDurableServiceStartFailed(oldToken)
            assertEquals(generations, fixture.platform.sessions.size)
            assertTrue(fixture.controller.hasDurableSession)
            ready[1]()
            fixture.scheduler.advanceBy(500L)
            assertEquals(1, acquisitions)
            fixture.platform.pendingCallerAudio = false
            fixture.platform.listener.onResult("tail")
            advanceUntilIdle()
            assertCompletedRetainedSend(fixture, sent, stops)
        }

    private fun assertCompletedRetainedSend(
        fixture: Fixture,
        sent: List<String>,
        stops: Int,
    ) {
        assertEquals(listOf("body tail"), sent)
        assertEquals("", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertEquals(1, stops)
    }

    /** A queued old closure cannot narrow a new capture while its logical state is being installed. */
    @Test
    fun oldDiscardClosureCannotClearReplacementMicrophoneBeforeTargetPublication() {
        var onDraftRead: () -> Unit = {}
        var oldClosures = 0
        val fixture = fixture(draft = TextFieldValue(""), onDraftRead = { onDraftRead() })
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val oldSessionId = fixture.controller.state.sessionId
        fixture.platform.pendingCallerAudio = true
        fixture.platform.tracksCallerAudioDisposal = true
        fixture.platform.deferDiscardClosure = true
        fixture.drafts[OTHER_ACCOUNT to OTHER_GROUP] = TextFieldValue("")
        onDraftRead = {
            assertEquals(oldSessionId, fixture.controller.state.sessionId)
            onDraftRead = {}
            requireNotNull(fixture.platform.discardClosureCallback).invoke()
            oldClosures++
        }
        assertTrue(fixture.controller.requestStart(OTHER_ACCOUNT, OTHER_GROUP, TextFieldValue("")))
        assertEquals(1, oldClosures)
        assertEquals(
            OTHER_GROUP,
            fixture.controller.state.target
                ?.groupIdHex,
        )
        assertTrue(fixture.controller.foregroundMicrophoneRequired)
        assertTrue(fixture.controller.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
        requireNotNull(fixture.platform.discardClosureCallback).invoke()
        assertTrue(fixture.controller.foregroundMicrophoneRequired)
        fixture.controller.cancel()
    }

    /** A rejected Retry launch preserves sealed audio and releases its pending foreground lease. */
    @Test
    fun rejectedRetainedAudioRetryKeepsRecoveryDataWithoutForegroundControls() {
        var starts = 0
        var stops = 0
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                startDurableSession = { _, ready ->
                    starts += 1
                    if (starts == 1) ready()
                    starts == 1
                },
                stopDurableSession = { stops += 1 },
            )
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("body")
        fixture.scheduler.runDelay(500L)
        fixture.controller.send()
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        assertTrue(fixture.controller.hasDurableSession)
        fixture.controller.onDurableServiceDestroyed(requireNotNull(fixture.controller.notificationSessionToken))
        fixture.controller.retry()
        fixture.scheduler.runDelay(500L)
        assertTrue(fixture.controller.state is ConversationDictationState.Failed)
        assertEquals("body", (fixture.controller.state as ConversationDictationState.Failed).retainedTranscript)
        assertTrue(fixture.platform.pendingCallerAudio)
        assertFalse(fixture.controller.hasDurableSession)
        assertEquals(1, stops)
        assertEquals("", fixture.drafts.getValue(key()).text)
    }

    /** Recognition recovery cannot silently change the already selected Send into Paste. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun transcriptOnlyRecoveryPreservesSendAfterSealedAudioIsNoLongerPending() =
        runTest {
            val sent = mutableListOf<String>()
            val fixture =
                fixture(
                    draft = TextFieldValue(""),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = {
                        sent += it.payload
                        true
                    },
                )
            fixture.platform.pendingCallerAudio = true
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onResult("recognized body")
            fixture.scheduler.runDelay(500L)
            fixture.controller.send()
            fixture.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
            assertTrue(fixture.controller.state is ConversationDictationState.Failed)
            fixture.platform.pendingCallerAudio = false
            fixture.controller.retry()
            advanceUntilIdle()
            assertEquals(listOf("recognized body"), sent)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** A pipe stall that pre-requeues the chunk still consumes one budget for either provider callback. */
    @Test
    fun stopBoundsAlternatingFailuresAfterCallerAudioWasPreRequeued() {
        listOf<ConversationDictationFailure?>(
            null,
            ConversationDictationFailure.ProviderDisconnected,
        ).forEach { stalledProviderCallback ->
            val fixture = fixture(draft = TextFieldValue(""))
            fixture.platform.pendingCallerAudio = true
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onResult("first")
            fixture.scheduler.runDelay(500L)
            fixture.controller.paste()

            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            fixture.scheduler.runDelay(500L)
            fixture.platform.session.callerAudioRetryAvailable = false
            if (stalledProviderCallback == null) {
                fixture.platform.listener.onResult(null)
            } else {
                fixture.platform.listener.onError(stalledProviderCallback)
            }
            fixture.scheduler.runDelay(500L)
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertEquals(4, fixture.platform.sessions.size)
            assertTrue(fixture.controller.hasDurableSession)
            val failed = fixture.controller.state as ConversationDictationState.Failed
            assertEquals("first", failed.retainedTranscript)
            fixture.controller.dismissFailure()
            assertFalse(fixture.controller.hasDurableSession)
        }
    }

    /** A finishing tail gives the provider time to release capacity before retrying retained PCM. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendBacksOffDisconnectedAndBusyTailWithoutRenderingRetryControls() =
        runTest {
            val sent = mutableListOf<String>()
            val fixture =
                fixture(
                    draft = TextFieldValue(""),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        sent += request.payload
                        true
                    },
                )
            fixture.platform.pendingCallerAudio = true
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onResult("recognized body")
            fixture.scheduler.runDelay(500L)
            fixture.controller.send()

            val disconnectedGeneration = fixture.platform.listener
            disconnectedGeneration.onError(ConversationDictationFailure.ProviderDisconnected)
            assertEquals(2, fixture.platform.sessions.size)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            assertEquals("", fixture.drafts.getValue(key()).text)
            disconnectedGeneration.onError(ConversationDictationFailure.ProviderDisconnected)
            fixture.scheduler.advanceBy(499L)
            assertEquals(2, fixture.platform.sessions.size)
            fixture.scheduler.advanceBy(1L)
            assertEquals(3, fixture.platform.sessions.size)

            fixture.platform.listener.onError(ConversationDictationFailure.RecognizerBusy)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            fixture.scheduler.advanceBy(999L)
            assertEquals(3, fixture.platform.sessions.size)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            fixture.scheduler.advanceBy(1L)
            assertEquals(4, fixture.platform.sessions.size)

            fixture.platform.pendingCallerAudio = false
            fixture.platform.listener.onResult("recovered tail")
            advanceUntilIdle()

            assertEquals(listOf("recognized body recovered tail"), sent)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            assertFalse(fixture.controller.hasDurableSession)
        }

    /** Cancel during retained-tail backoff fences the timeout and discards audio ownership once. */
    @Test
    fun cancelDuringRetainedTailBackoffPreventsAReplacementGeneration() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("recognized body")
        fixture.scheduler.runDelay(500L)
        fixture.controller.paste()
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderDisconnected)
        assertEquals(2, fixture.platform.sessions.size)

        fixture.controller.cancel()
        fixture.scheduler.advanceBy(500L)

        assertEquals(2, fixture.platform.sessions.size)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertFalse(fixture.controller.hasDurableSession)
        assertEquals("", fixture.drafts.getValue(key()).text)
    }

    /** A drained tail finalizes instead of reopening a recognizer when its backoff expires. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retainedTailBackoffRechecksPendingAudioBeforeStarting() =
        runTest {
            val fixture = fixture(draft = TextFieldValue(""), targetValidationScope = this)
            fixture.platform.pendingCallerAudio = true
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onResult("recognized body")
            fixture.scheduler.runDelay(500L)
            fixture.controller.paste()
            fixture.platform.listener.onError(ConversationDictationFailure.ProviderDisconnected)
            assertEquals(2, fixture.platform.sessions.size)

            fixture.platform.pendingCallerAudio = false
            fixture.scheduler.advanceBy(500L)
            advanceUntilIdle()

            assertEquals(2, fixture.platform.sessions.size)
            assertEquals("recognized body", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            assertFalse(fixture.controller.hasDurableSession)
        }

    /** Retry exhaustion never sends partial text and a later retry sends the complete transcript. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stopBoundsRetainedCallerAudioRetriesBeforeCompletingRequestedSend() =
        runTest {
            val sent = mutableListOf<String>()
            val fixture =
                fixture(
                    draft = TextFieldValue(""),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        sent += request.payload
                        true
                    },
                )
            fixture.platform.pendingCallerAudio = true
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onResult("first")
            fixture.scheduler.runDelay(500L)
            fixture.controller.send()

            repeat(2) {
                fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
                fixture.scheduler.runDelay(500L)
            }
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            advanceUntilIdle()

            assertTrue(sent.isEmpty())
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.hasDurableSession)
            val failed = fixture.controller.state as ConversationDictationState.Failed
            assertEquals("first", failed.retainedTranscript)
            assertEquals(ConversationDictationFailure.SendBlocked, failed.reason)
            assertEquals(ConversationDictationFailure.NoSpeech, failed.cause)
            assertTrue(failed.recognitionIncomplete)
            assertTrue(fixture.controller.canRetryRetainedAudio)

            fixture.controller.retry()
            fixture.scheduler.runDelay(500L)
            fixture.platform.pendingCallerAudio = false
            fixture.platform.listener.onResult("recovered tail")
            advanceUntilIdle()

            assertEquals(listOf("first recovered tail"), sent)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            assertFalse(fixture.controller.hasDurableSession)
        }

    /** A confirmed prefix sends once without retranscribing the tail that repeatedly failed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmedRecognizedPrefixBypassesFailedTailWithoutChangingItsOrigin() =
        runTest {
            val sent = mutableListOf<String>()
            val fixture =
                fixture(draft = TextFieldValue(""), targetValidationScope = this, sendTranscriptIfOriginUnchanged = {
                    sent += it.payload
                    true
                })
            failRecognizedTail(fixture, send = true)
            val generations = fixture.platform.sessions.size
            fixture.platform.tracksCallerAudioDisposal = true
            fixture.controller.sendRecognizedText()
            fixture.controller.sendRecognizedText()
            advanceUntilIdle()
            assertEquals(listOf("first"), sent)
            assertEquals(generations, fixture.platform.sessions.size)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertFalse(fixture.platform.pendingCallerAudio)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            assertFalse(fixture.controller.hasDurableSession)
        }

    /** Explicit Paste can recover a prefix from either failed completion choice without another recognizer. */
    @Test
    fun recognizedPrefixPasteRemainsAvailableWhileFailedTailIsRetained() {
        listOf(false, true).forEach { send ->
            val fixture = fixture(draft = TextFieldValue(""))
            failRecognizedTail(fixture, send)
            val generations = fixture.platform.sessions.size
            fixture.platform.tracksCallerAudioDisposal = true
            fixture.controller.paste()
            assertEquals("first", fixture.drafts.getValue(key()).text)
            assertEquals(generations, fixture.platform.sessions.size)
            assertFalse(fixture.platform.pendingCallerAudio)
            assertFalse(fixture.controller.hasDurableSession)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }
    }

    /** Choosing recognized text cannot absorb someone else's changed draft or revive expired recovery. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recognizedPrefixSendPreservesDraftFenceAndElapsedExpiry() =
        runTest {
            listOf("draft", "validation", "expiry").forEach { mode ->
                val sent = mutableListOf<String>()
                val fixture =
                    fixture(
                        draft = TextFieldValue(""),
                        targetValidationScope = this,
                        targetValidator = { _, _ ->
                            if (mode == "validation") {
                                ConversationDictationTargetValidation.Indeterminate
                            } else {
                                ConversationDictationTargetValidation.Available
                            }
                        },
                        sendTranscriptIfOriginUnchanged = {
                            sent += it.payload
                            true
                        },
                    )
                failRecognizedTail(fixture, send = true)
                fixture.platform.tracksCallerAudioDisposal = true
                when (mode) {
                    "expiry" -> fixture.scheduler.sleepWithoutDispatch(30 * 60 * 1_000L)
                    "draft" -> fixture.drafts[key()] = TextFieldValue("Another writer")
                }
                fixture.controller.sendRecognizedText()
                advanceUntilIdle()
                assertTrue(sent.isEmpty())
                assertEquals(if (mode == "draft") "Another writer" else "", fixture.drafts.getValue(key()).text)
                assertFalse(fixture.platform.pendingCallerAudio)
                if (mode == "expiry") {
                    assertTrue(fixture.controller.state is ConversationDictationState.Idle)
                } else {
                    val failed = fixture.controller.state as ConversationDictationState.Failed
                    assertEquals("first", failed.retainedTranscript)
                    assertEquals(ConversationDictationFailure.SendBlocked, failed.reason)
                    // The user confirmed this prefix; its remaining failure concerns delivery validation.
                    assertFalse(failed.recognitionIncomplete)
                }
            }
        }

    /** Android's distinct outcomes preserve timeout retries without labelling a no-match as silence. */
    @Test
    fun noMatchAndSpeechTimeoutHaveDistinctRecoveryOutcomes() {
        assertEquals(
            ConversationDictationFailure.NoMatch,
            SpeechRecognizer.ERROR_NO_MATCH.toConversationDictationFailure(),
        )
        assertEquals(
            ConversationDictationFailure.NoSpeech,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT.toConversationDictationFailure(),
        )
        assertEquals(
            ConversationDictationRecovery.Retry,
            dictationFailureRecovery(ConversationDictationFailure.NoMatch),
        )
    }

    /** A final no-match stops unchanged replay while retaining PCM and requiring an explicit recovery choice. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fullyFedFinalNoMatchRetainsTextAndAudioWithoutAutomaticDelivery() =
        runTest {
            for (send in listOf(false, true)) {
                val sent = mutableListOf<String>()
                val fixture =
                    fixture(
                        draft = TextFieldValue(""),
                        targetValidationScope = this,
                        sendTranscriptIfOriginUnchanged = {
                            sent += it.payload
                            true
                        },
                    )
                fixture.platform.pendingCallerAudio = true
                fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
                fixture.platform.listener.onResult("first")
                fixture.scheduler.runDelay(500L)
                if (send) fixture.controller.send() else fixture.controller.paste()
                val rejected = fixture.platform.session
                rejected.callerAudioFinalChunk = true
                val staleListener = fixture.platform.listener
                staleListener.onError(ConversationDictationFailure.NoMatch)
                advanceUntilIdle()
                val failed = fixture.controller.state as ConversationDictationState.Failed
                assertEquals("first", failed.retainedTranscript)
                assertEquals(ConversationDictationFailure.NoMatch, failed.cause ?: failed.reason)
                assertTrue(failed.recognitionIncomplete)
                assertTrue(fixture.controller.canRetryRetainedAudio)
                assertTrue(fixture.platform.pendingCallerAudio)
                assertTrue(sent.isEmpty())
                assertEquals("", fixture.drafts.getValue(key()).text)
                assertEquals(0, rejected.acknowledgedCallerAudio)
                fixture.scheduler.advanceBy(10_000L)
                assertEquals(2, fixture.platform.sessions.size)
                staleListener.onResult("stale tail")
                assertTrue(fixture.controller.state is ConversationDictationState.Failed)
                fixture.controller.retry()
                fixture.scheduler.runDelay(500L)
                fixture.platform.pendingCallerAudio = false
                fixture.platform.listener.onResult("recovered tail")
                advanceUntilIdle()
                if (send) {
                    assertEquals(listOf("first recovered tail"), sent)
                } else {
                    assertEquals("first recovered tail", fixture.drafts.getValue(key()).text)
                }
                assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            }
        }

    /** Unsealed, nonfinal, incompletely supplied and timed-out input still gets bounded retries. */
    @Test
    fun finalNoMatchPolicyDoesNotReplaceExistingTransientRetries() {
        for (mode in listOf("nonfinal", "incomplete", "timeout")) {
            val fixture = fixture(draft = TextFieldValue(""))
            fixture.platform.pendingCallerAudio = true
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.paste()
            val session = fixture.platform.session
            session.callerAudioFinalChunk = mode != "nonfinal"
            session.callerAudioFeedComplete = mode != "incomplete"
            fixture.platform.listener.onError(
                if (mode == "timeout") ConversationDictationFailure.NoSpeech else ConversationDictationFailure.NoMatch,
            )
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            assertEquals(1, session.retriedCallerAudioWithFollowingAudio)
            assertEquals(0, session.acknowledgedCallerAudio)
            fixture.scheduler.runDelay(500L)
            assertEquals(2, fixture.platform.sessions.size)
        }
    }

    /** The same no-match while recording can gain context from following PCM. */
    @Test
    fun recordingNoMatchStillCoalescesFollowingCallerAudio() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.session.callerAudioFinalChunk = true
        fixture.platform.listener.onError(ConversationDictationFailure.NoMatch)
        assertEquals(1, fixture.platform.session.retriedCallerAudioWithFollowingAudio)
        assertTrue(fixture.controller.state is ConversationDictationState.Starting)
    }

    /** Digital silence is acknowledged before final no-match recovery, never inferred from error 7 alone. */
    @Test
    fun finalNoMatchAcknowledgesOnlyFullyFedKnownSilentAudio() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(500L)
        fixture.controller.paste()
        val session = fixture.platform.session
        session.callerAudioFinalChunk = true
        session.callerAudioHasSpeech = false
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onError(ConversationDictationFailure.NoMatch)
        assertEquals(1, session.acknowledgedCallerAudio)
        assertEquals("first", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Reproduces a recognized segment followed by a provider-rejected nonzero tail. */
    private fun failRecognizedTail(
        fixture: Fixture,
        send: Boolean,
    ) {
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(500L)
        if (send) fixture.controller.send() else fixture.controller.paste()
        repeat(2) {
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            fixture.scheduler.runDelay(500L)
        }
        fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
        assertTrue(fixture.controller.state is ConversationDictationState.Failed)
        assertTrue(fixture.platform.pendingCallerAudio)
        assertEquals("first", (fixture.controller.state as ConversationDictationState.Failed).retainedTranscript)
    }

    /** A resolved chunk cannot consume the retry budget of the next caller-audio chunk. */
    @Test
    fun stopResetsRetainedCallerAudioRetryBudgetForTheNextChunk() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(500L)
        fixture.controller.paste()

        fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
        fixture.scheduler.runDelay(500L)
        fixture.platform.session.callerAudioHasSpeech = false
        fixture.platform.listener.onResult(null)
        fixture.scheduler.runDelay(500L)

        repeat(2) {
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            fixture.scheduler.runDelay(500L)
        }
        fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)

        assertEquals("", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.hasDurableSession)
        val failed = fixture.controller.state as ConversationDictationState.Failed
        assertEquals("first", failed.retainedTranscript)
    }

    /** Confirmed silence-only tail audio is consumed once instead of retrying until the drain timeout. */
    @Test
    fun stopAcknowledgesBlankSilenceOnlyTailAndFinalizesAccumulatedSpeech() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(500L)

        fixture.controller.stop()
        val silentTailSession = fixture.platform.session
        silentTailSession.callerAudioHasSpeech = false
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult(null)

        assertEquals(1, silentTailSession.acknowledgedCallerAudio)
        assertEquals(0, silentTailSession.retriedCallerAudio)
        assertEquals("first", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Even fully fed silence cannot bypass exact-chunk ownership when acknowledgement fails. */
    @Test
    fun unacknowledgedSilentTailIsRetainedForEitherEmptyProviderOutcome() {
        listOf(false, true).forEach { noSpeech ->
            val fixture = fixture(draft = TextFieldValue(""))
            fixture.platform.pendingCallerAudio = true
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.paste()
            val session = fixture.platform.session
            session.callerAudioHasSpeech = false
            session.callerAudioAcknowledgmentAvailable = false
            if (noSpeech) {
                fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            } else {
                fixture.platform.listener.onResult(null)
            }
            assertEquals(0, session.acknowledgedCallerAudio)
            assertEquals(1, session.retriedCallerAudio)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            assertTrue(fixture.controller.hasDurableSession)
            assertEquals("", fixture.drafts.getValue(key()).text)
        }
    }

    /** A normal no-match for a fully supplied silent tail completes the already recognized text. */
    @Test
    fun pasteAcknowledgesNoSpeechSilentTailWithoutReopeningProvider() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(500L)
        fixture.controller.paste()
        val silentTail = fixture.platform.session
        silentTail.callerAudioHasSpeech = false
        fixture.platform.pendingCallerAudio = false

        fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)

        assertEquals(1, silentTail.acknowledgedCallerAudio)
        assertEquals(0, silentTail.retriedCallerAudio)
        assertEquals("first", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Even the first final-drain request lets Android finish the preceding recognizer teardown. */
    @Test
    fun finalDrainWaitsForRecognizerTeardownBeforeCreatingNextGeneration() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.paste()
        fixture.platform.listener.onResult("first")

        assertEquals(1, fixture.platform.sessions.size)
        fixture.scheduler.advanceBy(499L)
        assertEquals(1, fixture.platform.sessions.size)
        fixture.scheduler.advanceBy(1L)
        assertEquals(2, fixture.platform.sessions.size)
    }

    /** An early no-match is not permission to acknowledge silence the provider never received fully. */
    @Test
    fun noSpeechRetainsSilentChunkAfterIncompleteFeed() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.paste()
        val incomplete = fixture.platform.session
        incomplete.callerAudioHasSpeech = false
        incomplete.callerAudioFeedComplete = false
        fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
        assertEquals(0, incomplete.acknowledgedCallerAudio)
        assertEquals(1, incomplete.retriedCallerAudio)
        assertEquals(1, fixture.platform.sessions.size)
        fixture.scheduler.advanceBy(499L)
        assertEquals(1, fixture.platform.sessions.size)
        fixture.scheduler.advanceBy(1L)
        assertEquals(2, fixture.platform.sessions.size)
    }

    /** The logged 300 ms tail may be quiet speech; retry pacing must preserve it on disconnect. */
    @Test
    fun noSpeechThenDisconnectRetriesRetainedTailWithPacing() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.paste()
        fixture.platform.listener.onResult("recognized body")
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
        assertEquals(2, fixture.platform.sessions.size)
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderDisconnected)
        assertEquals(3, fixture.platform.sessions.size)
        fixture.scheduler.runDelay(500L)
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("quiet last words")
        assertEquals("recognized body quiet last words", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Cancelling a delayed final drain prevents a stale callback or timer from starting capture. */
    @Test
    fun cancelFencesDelayedFinalDrainAndItsLateResult() {
        val fixture = fixture(draft = TextFieldValue("Keep"))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.paste()
        val oldListener = fixture.platform.listener
        oldListener.onResult("recognized body")
        fixture.controller.cancel()
        oldListener.onResult("late duplicate")
        fixture.scheduler.advanceBy(1_000L)
        assertEquals(1, fixture.platform.sessions.size)
        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Timeouts retain unrecognized audio and a manual retry receives its own finite drain deadline. */
    @Test
    fun processingTimeoutRetainsTailAndExplicitRetryIsBounded() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("body")
        fixture.scheduler.runDelay(500L)
        fixture.controller.paste()
        fixture.scheduler.advanceBy(20_000L)
        val failed = fixture.controller.state as ConversationDictationState.Failed
        assertEquals("body", failed.retainedTranscript)
        assertEquals("", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.hasDurableSession)
        fixture.controller.retry()
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onReady()
        fixture.scheduler.advanceBy(90_000L)
        assertTrue(fixture.controller.state is ConversationDictationState.Failed)
        assertEquals("", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.hasDurableSession)
    }

    /** A send request with only confirmed silence fails visibly and never dispatches an empty message. */
    @Test
    fun sendAcknowledgesBlankSilenceOnlyAudioAndFailsNoSpeechWithoutDispatching() {
        var sendCalls = 0
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                sendTranscriptIfOriginUnchanged = {
                    sendCalls += 1
                    true
                },
            )
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.controller.send()
        val silentSession = fixture.platform.session
        silentSession.callerAudioHasSpeech = false
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult(null)

        assertEquals(1, silentSession.acknowledgedCallerAudio)
        assertEquals(0, silentSession.retriedCallerAudio)
        assertEquals(0, sendCalls)
        val failed = fixture.controller.state as ConversationDictationState.Failed
        assertEquals(ConversationDictationFailure.NoSpeech, failed.reason)
    }

    /** A transient final-chunk failure retries retained PCM instead of delivering earlier text alone. */
    @Test
    fun stopRetriesDisconnectedCallerAudioChunkWithoutFinalizingPartialText() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(500L)

        fixture.controller.stop()
        val failedSession = fixture.platform.session
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderDisconnected)

        assertEquals(1, failedSession.retriedCallerAudio)
        assertTrue(fixture.controller.state is ConversationDictationState.Starting)
        assertEquals("", fixture.drafts.getValue(key()).text)

        fixture.scheduler.runDelay(500L)
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("recovered tail")

        assertEquals("first recovered tail", fixture.drafts.getValue(key()).text)
    }

    /** Captured tail audio survives a stop request that lands between provider generations. */
    @Test
    fun stopDuringRestartGapSealsAndDrainsCallerAudioTail() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.platform.listener.onResult("first")
        fixture.controller.stop()

        assertEquals(1, fixture.platform.sessions.size)
        fixture.scheduler.runDelay(500L)
        assertEquals(2, fixture.platform.sessions.size)
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("tail")
        assertEquals("first tail", fixture.drafts.getValue(key()).text)
    }

    /** Capture closure releases playback and the logical lease before sealed tail transcription finishes. */
    @Test
    fun restartGapCaptureCloseRestoresPlaybackBeforeTailDrainCompletes() {
        var releases = 0
        var resumes = 0
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                releaseMicrophone = { releases += 1 },
                onAfterAudioCapture = { resumes += 1 },
            )
        fixture.platform.pendingCallerAudio = true
        fixture.platform.deferCallerAudioFinish = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.platform.listener.onResult("first")
        fixture.controller.stop()

        assertEquals(0, releases)
        assertEquals(0, resumes)
        assertEquals("", fixture.drafts.getValue(key()).text)

        checkNotNull(fixture.platform.callerAudioFinishCallback).invoke()

        assertEquals(1, releases)
        assertEquals(1, resumes)
        assertEquals("", fixture.drafts.getValue(key()).text)
        fixture.scheduler.runDelay(500L)
        assertEquals(2, fixture.platform.sessions.size)

        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("tail")

        assertEquals("first tail", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertEquals(1, releases)
        assertEquals(1, resumes)
    }

    /** A stop before the next provider's speech callback still drains its captured caller-audio chunk. */
    @Test
    fun stopBeforeSpeechCallbackDoesNotDiscardCallerAudioTail() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(500L)

        fixture.controller.stop()

        assertEquals(
            0,
            fixture.platform.sessions
                .last()
                .cancelCalls,
        )
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("tail")
        assertEquals("first tail", fixture.drafts.getValue(key()).text)
    }

    /** A stuck capture close retains unresolved PCM within the bound and fences its late callback. */
    @Test
    fun stuckCallerAudioCloseTimesOutAndFencesItsLateCallback() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.platform.pendingCallerAudio = true
        fixture.platform.deferCallerAudioFinish = true
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first")

        fixture.controller.stop()
        fixture.scheduler.runThrough(90_000L)

        assertEquals("", fixture.drafts.getValue(key()).text)
        assertEquals("first", (fixture.controller.state as ConversationDictationState.Failed).retainedTranscript)
        assertTrue(fixture.controller.hasDurableSession)
        val sessionsBeforeLateClose = fixture.platform.sessions.size
        checkNotNull(fixture.platform.callerAudioFinishCallback).invoke()
        assertEquals(sessionsBeforeLateClose, fixture.platform.sessions.size)
    }

    /** The total drain bound survives a ready callback, both during active capture and restart gaps. */
    @Test
    fun drainWatchdogSurvivesReplacementRecognizerReady() {
        listOf(false, true).forEach { stopInRestartGap ->
            var microphoneAcquisitions = 0
            val fixture =
                fixture(
                    draft = TextFieldValue(""),
                    tryAcquireMicrophone = {
                        microphoneAcquisitions += 1
                        true
                    },
                )
            fixture.platform.pendingCallerAudio = true
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onReady()
            if (stopInRestartGap) fixture.platform.listener.onResult("first")
            fixture.controller.stop()
            if (!stopInRestartGap) fixture.platform.listener.onResult("first")
            fixture.scheduler.runDelay(500L)
            fixture.platform.listener.onReady()

            fixture.scheduler.runThrough(90_000L)

            assertEquals("", fixture.drafts.getValue(key()).text)
            assertEquals("first", (fixture.controller.state as ConversationDictationState.Failed).retainedTranscript)
            assertTrue(fixture.controller.hasDurableSession)
            assertEquals(0, fixture.writes)
            assertEquals(1, microphoneAcquisitions)
        }
    }

    /** A one-hour capture remains admitted, while the 65-minute safety bound still fails closed. */
    @Test
    fun oneHourSessionRemainsActiveUntilTheSixtyFiveMinuteDeadline() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()

        fixture.scheduler.runThrough(60L * 60L * 1_000L)

        assertTrue(fixture.controller.state is ConversationDictationState.Listening)
        fixture.scheduler.runThrough(5L * 60L * 1_000L)
        assertTrue(fixture.controller.state is ConversationDictationState.Processing)
        assertTrue(fixture.platform.session.stopped)
    }

    /** Each provider-owned capture releases its microphone lease during the processing gap. */
    @Test
    fun microphoneLeaseTracksProviderCaptureGenerationsAndTeardown() {
        var acquisitions = 0
        var releases = 0
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                tryAcquireMicrophone = {
                    acquisitions += 1
                    true
                },
                releaseMicrophone = { releases += 1 },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(500L)
        fixture.platform.listener.onResult("second")
        fixture.scheduler.runDelay(500L)

        assertEquals(3, acquisitions)
        assertEquals(2, releases)
        assertEquals(3, fixture.platform.sessions.size)
        assertTrue(
            fixture.platform.sessions
                .take(2)
                .all { it.destroyed },
        )

        fixture.controller.cancel()
        fixture.controller.cancel()

        assertEquals(3, releases)
        assertEquals(
            1,
            fixture.platform.sessions
                .last()
                .cancelCalls,
        )
        assertEquals(
            1,
            fixture.platform.sessions
                .last()
                .destroyCalls,
        )
    }

    /** Verifies send-on-finish uses the captured origin revision, text, target, and immutable payload. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendOnFinishUsesImmutableOriginPayloadAfterDurableAcceptance() =
        runTest {
            val sent = mutableListOf<Triple<String, String, String>>()
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        assertEquals(0L, request.expectedDraftRevision)
                        assertEquals("Draft", request.expectedDraftText)
                        sent += Triple(request.accountRef, request.groupIdHex, request.payload)
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            val listener = fixture.platform.listener
            listener.onResult("dictated")
            listener.onResult("duplicate")
            advanceUntilIdle()

            assertEquals(listOf(Triple(ACCOUNT, GROUP, "Draft dictated")), sent)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** The composer empties in the frame the dispatch is claimed, never beside its own pending row. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendOnFinishEmptiesTheComposerWhenTheDispatchIsClaimed() =
        runTest {
            lateinit var drafts: MutableMap<Pair<String, String>, TextFieldValue>
            var draftAtDispatch: String? = null
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        assertTrue(request.beginDispatch())
                        draftAtDispatch = drafts.getValue(key()).text
                        true
                    },
                )
            drafts = fixture.drafts
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            advanceUntilIdle()

            assertEquals("", draftAtDispatch)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** A claimed dispatch that never reports acceptance remains visibly uncertain without duplication. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendOnFinishRestoresTheCapturedDraftWhenDeliveryStaysUnknown() =
        runTest {
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        assertTrue(request.beginDispatch())
                        false
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            advanceUntilIdle()

            assertEquals("Draft", fixture.drafts.getValue(key()).text)
            val failed = fixture.controller.state as ConversationDictationState.Failed
            assertEquals(ConversationDictationFailure.DeliveryUnknown, failed.reason)
            assertEquals("dictated", failed.retainedTranscript)
        }

    /** Explicit Paste wins over the stored automatic-send preference for this one session. */
    @Test
    fun pasteActionOverridesStoredSendPreference() {
        var sendCalls = 0
        val fixture =
            fixture(
                draft = TextFieldValue("Draft", TextRange(5)),
                sendTranscriptIfOriginUnchanged = {
                    sendCalls += 1
                    true
                },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.paste()
        fixture.platform.listener.onResult("dictated")

        assertEquals("Draft dictated", fixture.drafts.getValue(key()).text)
        assertEquals(0, sendCalls)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    /** Provider segment processing is still capture; only an accepted completion action owns a button spinner. */
    @Test
    fun providerSegmentProcessingAcceptsSendWithoutPretendingToPaste() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()
        fixture.platform.listener.onEndOfSpeech()

        assertTrue(fixture.controller.captureInProgress)
        assertEquals(null, fixture.controller.processingDeliveryMode)

        fixture.controller.send()

        assertFalse(fixture.controller.captureInProgress)
        assertEquals(ConversationDictationDeliveryMode.SendOnFinish, fixture.controller.processingDeliveryMode)
    }

    /** An accepted completion action owns one continuous spinner while queued caller audio drains. */
    @Test
    fun sendSpinnerSurvivesStartingListeningAndProcessingAcrossQueuedChunks() {
        val platform = FakePlatform().apply { pendingCallerAudio = true }
        val fixture = fixture(draft = TextFieldValue(""), platform = platform)
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.send()

        platform.listener.onResult("first")
        assertTrue(fixture.controller.state is ConversationDictationState.Starting)
        assertEquals(ConversationDictationDeliveryMode.SendOnFinish, fixture.controller.processingDeliveryMode)

        fixture.scheduler.runDelay(500L)
        platform.listener.onReady()
        assertTrue(fixture.controller.state is ConversationDictationState.Listening)
        assertEquals(ConversationDictationDeliveryMode.SendOnFinish, fixture.controller.processingDeliveryMode)

        platform.listener.onEndOfSpeech()
        assertTrue(fixture.controller.state is ConversationDictationState.Processing)
        assertEquals(ConversationDictationDeliveryMode.SendOnFinish, fixture.controller.processingDeliveryMode)

        platform.pendingCallerAudio = false
        platform.listener.onResult("second")
        assertEquals("", fixture.drafts.getValue(key()).text)
        val failed = fixture.controller.state as ConversationDictationState.Failed
        assertEquals(ConversationDictationFailure.SendBlocked, failed.reason)
        assertEquals("first second", failed.retainedTranscript)
        assertEquals(null, fixture.controller.processingDeliveryMode)
    }

    @Test
    fun firstCompletionChoiceWinsAndDoesNotLeakIntoTheNextSession() {
        var sendCalls = 0
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                sendTranscriptIfOriginUnchanged = {
                    sendCalls += 1
                    true
                },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.controller.paste()
        fixture.controller.send()
        fixture.platform.listener.onResult("first")
        assertEquals("first", fixture.drafts.getValue(key()).text)
        assertEquals(0, sendCalls)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.scheduler.runDelay(500L)
        fixture.controller.send()
        fixture.controller.paste()
        fixture.platform.listener.onResult("second")
        // No delivery scope exists: preserve the chosen Send without performing the later rejected Paste.
        val failed = fixture.controller.state as ConversationDictationState.Failed
        assertEquals(ConversationDictationFailure.SendBlocked, failed.reason)
        assertEquals("second", failed.retainedTranscript)
        assertEquals("first", fixture.drafts.getValue(key()).text)
        fixture.controller.dismissFailure()
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.scheduler.runDelay(500L)
        fixture.controller.paste()
        fixture.controller.send()
        fixture.platform.listener.onResult("third")
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertEquals("first third", fixture.drafts.getValue(key()).text)
        assertEquals(0, sendCalls)
    }

    /** Explicit Send wins over the stored paste preference and retains the immutable-origin gate. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendActionOverridesStoredPastePreference() =
        runTest {
            val sent = mutableListOf<String>()
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        sent += request.payload
                        true
                    },
                )

            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            advanceUntilIdle()

            assertEquals(listOf("Draft dictated"), sent)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failureAfterDispatchRemainsUncertainWithoutAllowingAnotherSend() =
        runTest {
            var sends = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        assertTrue(request.beginDispatch())
                        sends += 1
                        error("unconfirmed dispatch")
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            advanceUntilIdle()

            assertEquals("Draft", fixture.drafts.getValue(key()).text)
            val failed = fixture.controller.state as ConversationDictationState.Failed
            assertEquals(ConversationDictationFailure.DeliveryUnknown, failed.reason)
            assertEquals("dictated", failed.retainedTranscript)
            fixture.controller.send()
            fixture.controller.paste()
            assertEquals(1, sends)
            assertEquals("Draft", fixture.drafts.getValue(key()).text)
            assertFalse(fixture.controller.hasDurableSession)
            assertFalse(fixture.controller.ownsMicrophone)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationBeforeDispatchPreventsAWaitingCommit() =
        runTest {
            val commitLock = CompletableDeferred<Unit>()
            var sends = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft"),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        commitLock.await()
                        if (request.beginDispatch()) sends += 1
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            runCurrent()
            fixture.controller.cancel()
            commitLock.complete(Unit)
            advanceUntilIdle()

            assertEquals(0, sends)
            assertEquals("Draft", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationAfterDispatchCannotHideOrRepeatTheSend() =
        runTest {
            val completion = CompletableDeferred<Boolean>()
            var sends = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft"),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        assertTrue(request.beginDispatch())
                        sends += 1
                        completion.await()
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            runCurrent()
            fixture.controller.cancel()
            fixture.controller.send()
            fixture.controller.paste()

            assertTrue(fixture.controller.deliveryInProgress)
            assertTrue(fixture.controller.blocksNewRequest)
            assertFalse(fixture.controller.completionActionsEnabled)
            completion.complete(true)
            advanceUntilIdle()
            assertEquals(1, sends)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** Involuntary foreground-service loss cannot recall a send after dispatch has been claimed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun serviceTeardownAfterDispatchDoesNotCancelTheSend() =
        runTest {
            val completion = CompletableDeferred<Boolean>()
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft"),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        assertTrue(request.beginDispatch())
                        completion.await()
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            val token = requireNotNull(fixture.controller.notificationSessionToken)
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            runCurrent()

            assertTrue(fixture.controller.deliveryInProgress)
            fixture.controller.onDurableServiceDestroyed(token)
            assertTrue(fixture.controller.deliveryInProgress)

            completion.complete(true)
            advanceUntilIdle()

            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dispatchTimeoutStaysUncertainButPreDispatchFailurePreservesTheDraft() =
        runTest {
            val pending = CompletableDeferred<Boolean>()
            val timedOut =
                fixture(
                    draft = TextFieldValue("Draft"),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        request.beginDispatch()
                        pending.await()
                    },
                )
            timedOut.controller.requestStart(ACCOUNT, GROUP, timedOut.drafts.getValue(key()))
            timedOut.controller.send()
            timedOut.platform.listener.onResult("dictated")
            advanceUntilIdle()
            assertEquals("Draft", timedOut.drafts.getValue(key()).text)
            val timedOutFailure = timedOut.controller.state as ConversationDictationState.Failed
            assertEquals(ConversationDictationFailure.DeliveryUnknown, timedOutFailure.reason)
            assertEquals("dictated", timedOutFailure.retainedTranscript)
            assertFalse(timedOut.controller.hasDurableSession)
            timedOut.controller.onAppForegrounded()
            timedOut.scheduler.advanceBy(30 * 60 * 1_000L)
            assertTrue(timedOut.controller.state === timedOutFailure)
            assertFalse(timedOut.controller.hasDurableSession)

            val rejected =
                fixture(
                    draft = TextFieldValue("Draft"),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { error("draft lookup failed before dispatch") },
                )
            rejected.controller.requestStart(ACCOUNT, GROUP, rejected.drafts.getValue(key()))
            rejected.controller.send()
            rejected.platform.listener.onResult("dictated")
            advanceUntilIdle()
            assertEquals("Draft", rejected.drafts.getValue(key()).text)
            val failure = rejected.controller.state as ConversationDictationState.Failed
            assertEquals(ConversationDictationFailure.SendBlocked, failure.reason)
            assertEquals("dictated", failure.retainedTranscript)
        }

    /** Releases dictation as soon as the pending bubble is visible, without cancelling its transport. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendOnFinishCompletesWhenThePendingBubbleIsPublished() =
        runTest {
            val sendStarted = CompletableDeferred<Unit>()
            val finishSend = CompletableDeferred<Boolean>()
            var durableStops = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    stopDurableSession = { durableStops += 1 },
                    sendTranscriptIfOriginUnchanged = { request ->
                        sendStarted.complete(Unit)
                        assertTrue(request.beginDispatch())
                        request.onPendingShown()
                        finishSend.await()
                    },
                )

            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            runCurrent()

            assertTrue(sendStarted.isCompleted)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            assertFalse(fixture.controller.hasDurableSession)
            assertFalse(fixture.controller.ownsMicrophone)
            assertEquals(1, durableStops)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertEquals(1, fixture.controller.pendingSendRevision(ACCOUNT, GROUP))

            finishSend.complete(true)
            advanceUntilIdle()

            assertFalse(fixture.controller.hasDurableSession)
            assertEquals(1, durableStops)
            assertEquals(1, fixture.controller.pendingSendRevision(ACCOUNT, GROUP))
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** A rejected Send preserves the draft and transcript separately for an explicit retry. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendRejectionAndConcurrentDraftEditNeverFallBackToPaste() =
        runTest {
            var sendCalls = 0
            val rejected =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = {
                        sendCalls += 1
                        false
                    },
                )
            rejected.controller.requestStart(ACCOUNT, GROUP, rejected.drafts.getValue(key()))
            rejected.controller.send()
            rejected.platform.listener.onResult("dictated")
            advanceUntilIdle()

            assertEquals(1, sendCalls)
            assertEquals("Draft", rejected.drafts.getValue(key()).text)
            val failure = rejected.controller.state as ConversationDictationState.Failed
            assertEquals(ConversationDictationFailure.SendBlocked, failure.reason)
            assertEquals("dictated", failure.retainedTranscript)

            val edited =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidator = { _, _ -> ConversationDictationTargetValidation.Available },
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = {
                        sendCalls += 1
                        true
                    },
                )
            edited.controller.requestStart(ACCOUNT, GROUP, edited.drafts.getValue(key()))
            edited.edit(key(), TextFieldValue("Draft changed"))
            edited.controller.send()
            edited.platform.listener.onResult("dictated")
            advanceUntilIdle()

            assertEquals(1, sendCalls)
            assertEquals("Draft changed", edited.drafts.getValue(key()).text)
            val editedFailure = edited.controller.state as ConversationDictationState.Failed
            assertEquals(ConversationDictationFailure.SendBlocked, editedFailure.reason)
            assertEquals("dictated", editedFailure.retainedTranscript)
        }

    /** Retry reuses the preserved text, sends once, and never creates a new recognizer. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retryBlockedSendSendsRetainedTranscriptAgainstUnchangedOriginOnce() =
        runTest {
            var accepted = false
            val sent = mutableListOf<String>()
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        if (accepted && request.beginDispatch()) {
                            sent += request.payload
                            true
                        } else {
                            false
                        }
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            advanceUntilIdle()
            assertEquals(
                ConversationDictationFailure.SendBlocked,
                (fixture.controller.state as ConversationDictationState.Failed).reason,
            )
            val recognizers = fixture.platform.sessions.size
            accepted = true
            fixture.controller.retry()
            advanceUntilIdle()
            fixture.controller.retry()
            assertEquals(recognizers, fixture.platform.sessions.size)
            assertEquals(listOf("Draft dictated"), sent)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
            assertFalse(fixture.controller.hasDurableSession)
        }

    /** A definite pre-transport rollback keeps its exact owned revision eligible for Retry Send. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retryAfterPreTransportRollbackSendsOriginalPayloadOnce() =
        runTest {
            var reject = true
            val sent = mutableListOf<String>()
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        assertTrue(request.beginDispatch())
                        if (reject) {
                            request.onDispatchRejectedBeforeTransport()
                            false
                        } else {
                            sent += request.payload
                            true
                        }
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            advanceUntilIdle()
            assertEquals("Draft", fixture.drafts.getValue(key()).text)
            assertEquals(
                ConversationDictationFailure.SendBlocked,
                (fixture.controller.state as ConversationDictationState.Failed).reason,
            )
            reject = false
            fixture.controller.retry()
            advanceUntilIdle()
            fixture.controller.retry()
            assertEquals(listOf("Draft dictated"), sent)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** A concurrent same-text mutation after rollback cannot be mistaken for this dispatch's revision. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retryCannotAbsorbExternalGenerationDuringRollback() =
        runTest {
            var sends = 0
            lateinit var fixture: Fixture
            fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidationScope = this,
                    onDraftWritten = { value ->
                        if (value.text == "Draft") fixture.edit(key(), value)
                    },
                    sendTranscriptIfOriginUnchanged = { request ->
                        sends++
                        assertTrue(request.beginDispatch())
                        request.onDispatchRejectedBeforeTransport()
                        false
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            advanceUntilIdle()
            fixture.controller.retry()
            advanceUntilIdle()
            assertEquals(1, sends)
            assertEquals("Draft", fixture.drafts.getValue(key()).text)
            assertEquals(
                ConversationDictationFailure.SendBlocked,
                (fixture.controller.state as ConversationDictationState.Failed).reason,
            )
            fixture.controller.paste()
            assertEquals("Draft dictated", fixture.drafts.getValue(key()).text)
            assertEquals(1, sends)
        }

    /** Retained-text retry validates a Send without pretending to capture new audio. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retainedSendRetryShowsProcessingWhileValidationIsPending() =
        runTest {
            val validation = CompletableDeferred<ConversationDictationTargetValidation>()
            var validationCalls = 0
            var sendCalls = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidationScope = this,
                    targetValidator = { _, _ ->
                        if (++validationCalls == 1) {
                            ConversationDictationTargetValidation.Available
                        } else {
                            validation.await()
                        }
                    },
                    sendTranscriptIfOriginUnchanged = { request ->
                        ++sendCalls > 1 && request.beginDispatch()
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")
            runCurrent()
            assertTrue(fixture.controller.state is ConversationDictationState.Failed)
            fixture.controller.retry()
            runCurrent()
            assertTrue(fixture.controller.state is ConversationDictationState.Processing)
            assertFalse(fixture.controller.captureInProgress)
            assertEquals(ConversationDictationDeliveryMode.SendOnFinish, fixture.controller.processingDeliveryMode)
            assertFalse(fixture.controller.completionActionsEnabled)
            assertEquals(1, sendCalls)
            validation.complete(ConversationDictationTargetValidation.Available)
            advanceUntilIdle()
            assertEquals(2, sendCalls)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** Semantic draft mutations after Send cannot publish or be converted into Paste. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun draftChangedWhileExplicitSendDrainsRetainsFailureWithoutDispatch() =
        runTest {
            var sends = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft"),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = {
                        sends++
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.edit(key(), TextFieldValue("New draft"))
            fixture.platform.listener.onResult("dictated")
            advanceUntilIdle()
            assertEquals(0, sends)
            assertEquals("New draft", fixture.drafts.getValue(key()).text)
            assertEquals("dictated", (fixture.controller.state as ConversationDictationState.Failed).retainedTranscript)
            fixture.controller.retry()
            advanceUntilIdle()
            assertEquals(0, sends)
            assertEquals("New draft", fixture.drafts.getValue(key()).text)
            fixture.controller.paste()
            assertEquals("New draft dictated", fixture.drafts.getValue(key()).text)
            assertEquals(0, sends)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** Media mutations advance the semantic revision even when the visible draft text is unchanged. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sameTextSemanticGenerationChangeCannotBeAbsorbedBySendRetry() =
        runTest {
            var sends = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = {
                        sends++
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.edit(key(), TextFieldValue("Draft", TextRange(5)))
            fixture.platform.listener.onResult("dictated")
            advanceUntilIdle()
            fixture.controller.retry()
            advanceUntilIdle()
            assertEquals(0, sends)
            assertEquals("Draft", fixture.drafts.getValue(key()).text)
            assertEquals(
                ConversationDictationFailure.SendBlocked,
                (fixture.controller.state as ConversationDictationState.Failed).reason,
            )
            fixture.controller.paste()
            assertEquals("Draft dictated", fixture.drafts.getValue(key()).text)
            assertEquals(0, sends)
        }

    /** Verifies cancellation invalidates queued validation and prevents a later automatic send. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledSessionCannotDispatchAQueuedAutoSend() =
        runTest {
            var sendCalls = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = {
                        sendCalls += 1
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.send()
            fixture.platform.listener.onResult("dictated")

            fixture.controller.cancel()
            advanceUntilIdle()

            assertEquals(0, sendCalls)
            assertEquals("Draft", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
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
        fixture.scheduler.runDelay(500L)
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

    /** Verifies a configured automatic session still bounds a broken rapid empty-generation loop. */
    @Test
    fun repeatedNoSpeechCallbacksStopAfterABoundedNumberOfRestarts() {
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                finishAfterSilenceMillis = { 3_000L },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        repeat(2) {
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            fixture.scheduler.runDelay(500L)
        }
        fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)

        assertEquals(
            ConversationDictationFailure.NoSpeech,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(3, fixture.platform.sessions.size)
        assertFalse(fixture.controller.hasDurableSession)
    }

    /** Rapid provider NoSpeech callbacks cannot override the user's manual-finish preference. */
    @Test
    fun manualFinishSurvivesRapidNoSpeechUntilDone() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        repeat(5) {
            fixture.platform.listener.onReady()
            fixture.scheduler.advanceBy(1_000L)
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            fixture.scheduler.advanceBy(500L)
        }

        assertTrue(fixture.controller.hasDurableSession)
        assertEquals(6, fixture.platform.sessions.size)

        fixture.controller.stop()
        assertTrue(fixture.platform.session.stopped)
        fixture.platform.listener.onResult("final words")

        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertFalse(fixture.controller.hasDurableSession)
        assertEquals("final words", fixture.drafts.getValue(key()).text)
    }

    @Test
    fun ordinaryNoSpeechSurvivesTwoMinutesWithoutConsumingRapidLoopBudget() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        repeat(54) {
            fixture.platform.listener.onReady()
            fixture.scheduler.advanceBy(2_000L)
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            fixture.scheduler.advanceBy(500L)
        }

        assertTrue(fixture.controller.hasDurableSession)
        assertEquals(54 + 1, fixture.platform.sessions.size)
    }

    @Test
    fun providerDisconnectRetriesAtBoundedBackoffAndNeverReportsNetwork() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        listOf(500L, 1_000L, 2_000L).forEachIndexed { index, delay ->
            fixture.platform.listener.onReady()
            fixture.platform.listener.onError(ConversationDictationFailure.ProviderDisconnected)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
            fixture.scheduler.advanceBy(delay - 1L)
            assertEquals(index + 1, fixture.platform.sessions.size)
            fixture.scheduler.advanceBy(1L)
            assertEquals(index + 2, fixture.platform.sessions.size)
        }
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderDisconnected)

        assertEquals(
            ConversationDictationFailure.ProviderDisconnected,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
    }

    @Test
    fun pasteDuringReconnectFinalizesUsefulTextOnceAndFencesLateRestart() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("keep this")
        fixture.scheduler.advanceBy(500L)
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderDisconnected)

        fixture.controller.paste()
        fixture.controller.send()
        fixture.scheduler.advanceBy(500L)

        assertEquals("keep this", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertEquals(2, fixture.platform.sessions.size)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun delayedRestartRechecksTargetAndMicrophoneBeforeOpeningAnotherGeneration() {
        var targetPresent = true
        val removed = fixture(draft = TextFieldValue(""), targetAvailable = { targetPresent })
        removed.controller.requestStart(ACCOUNT, GROUP, removed.drafts.getValue(key()))
        removed.platform.listener.onResult("retained")
        targetPresent = false
        removed.scheduler.advanceBy(500L)
        assertEquals(
            "retained",
            (removed.controller.state as ConversationDictationState.Failed).retainedTranscript,
        )
        assertEquals(1, removed.platform.sessions.size)

        val revoked = fixture(draft = TextFieldValue(""))
        revoked.controller.requestStart(ACCOUNT, GROUP, revoked.drafts.getValue(key()))
        revoked.platform.listener.onResult("retained")
        revoked.platform.microphoneAccessOverride = ConversationDictationMicrophoneAccess.AppOpDenied
        revoked.scheduler.advanceBy(500L)
        assertEquals("retained", revoked.drafts.getValue(key()).text)
        assertTrue(revoked.controller.state is ConversationDictationState.Idle)
        assertEquals(1, revoked.platform.sessions.size)
    }

    @Test
    fun cancellationAndReplacementInsideDelayedProbesCannotReviveTheOldSession() {
        val cancelled = fixture(draft = TextFieldValue(""))
        cancelled.controller.requestStart(ACCOUNT, GROUP, cancelled.drafts.getValue(key()))
        cancelled.platform.listener.onResult("retained")
        cancelled.platform.onMicrophoneAccessCheck = cancelled.controller::cancel
        cancelled.scheduler.advanceBy(500L)
        assertTrue(cancelled.controller.state is ConversationDictationState.Idle)
        assertEquals(1, cancelled.platform.sessions.size)

        val replacedPlatform = FakePlatform()
        lateinit var replaced: Fixture
        replaced = fixture(draft = TextFieldValue(""), platform = replacedPlatform)
        replaced.drafts[OTHER_ACCOUNT to OTHER_GROUP] = TextFieldValue("Other")
        replaced.controller.requestStart(ACCOUNT, GROUP, replaced.drafts.getValue(key()))
        replaced.platform.listener.onResult("old")
        replacedPlatform.onRecognitionAvailableCheck = {
            replacedPlatform.onRecognitionAvailableCheck = null
            replaced.controller.requestStart(
                OTHER_ACCOUNT,
                OTHER_GROUP,
                replaced.drafts.getValue(OTHER_ACCOUNT to OTHER_GROUP),
            )
        }
        replaced.scheduler.advanceBy(500L)
        assertTrue(replaced.controller.isOwnedBy(ACCOUNT, GROUP))
        assertEquals(2, replaced.platform.sessions.size)
    }

    @Test
    fun configuredSilenceResetsForSubsequentSpeechAndPasteStillWins() {
        val fixture =
            fixture(
                draft = TextFieldValue(""),
                finishAfterSilenceMillis = { 3_000L },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("first")
        fixture.scheduler.advanceBy(500L)
        fixture.platform.listener.onReady()
        fixture.scheduler.advanceBy(1_000L)
        fixture.platform.listener.onBeginningOfSpeech()
        fixture.scheduler.advanceBy(2_500L)
        assertTrue(fixture.controller.state is ConversationDictationState.Listening)
        fixture.platform.listener.onResult("second")
        fixture.controller.paste()

        assertEquals("first second", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
    }

    /** Verifies every generation watchdog preserves already recognized words in the draft. */
    @Test
    fun recognitionWatchdogsPreserveAccumulatedTranscriptInTheDraft() {
        val starting = fixture(draft = TextFieldValue(""))
        starting.controller.requestStart(ACCOUNT, GROUP, starting.drafts.getValue(key()))
        starting.platform.listener.onResult("starting words")
        starting.scheduler.runDelay(500L)
        starting.scheduler.runDelay(10_000L)
        assertEquals("starting words", starting.drafts.getValue(key()).text)
        assertTrue(starting.controller.state is ConversationDictationState.Idle)

        val providerProcessing = fixture(draft = TextFieldValue(""))
        providerProcessing.controller.requestStart(ACCOUNT, GROUP, providerProcessing.drafts.getValue(key()))
        providerProcessing.platform.listener.onResult("provider words")
        providerProcessing.scheduler.runDelay(500L)
        providerProcessing.platform.listener.onEndOfSpeech()
        providerProcessing.scheduler.runDelay(20_000L)
        assertEquals("provider words", providerProcessing.drafts.getValue(key()).text)
        assertTrue(providerProcessing.controller.state is ConversationDictationState.Idle)

        val manualStop = fixture(draft = TextFieldValue(""))
        manualStop.controller.requestStart(ACCOUNT, GROUP, manualStop.drafts.getValue(key()))
        manualStop.platform.listener.onResult("manual words")
        manualStop.scheduler.runDelay(500L)
        manualStop.platform.listener.onBeginningOfSpeech()
        manualStop.controller.stop()
        manualStop.scheduler.runDelay(20_000L)
        assertEquals("manual words", manualStop.drafts.getValue(key()).text)
        assertTrue(manualStop.controller.state is ConversationDictationState.Idle)
    }

    /** Verifies service-backed capture survives UI loss while fallback text is preserved automatically. */
    @Test
    fun backgroundAndTaskRemovalKeepDurableCaptureWhileFallbackTextRemainsSafe() {
        val active = fixture(draft = TextFieldValue("Draft", TextRange(5)))
        active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
        active.controller.onAppBackgrounded()
        active.controller.onTaskRemoved()
        assertTrue(active.controller.state is ConversationDictationState.Starting)
        assertFalse(active.platform.session.cancelled)
        active.controller.cancel()

        val fallback = fixture(draft = TextFieldValue("Anchor", TextRange(3)))
        fallback.controller.requestStart(ACCOUNT, GROUP, fallback.drafts.getValue(key()))
        fallback.edit(key(), TextFieldValue("Rewritten"))
        fallback.controller.stop()
        fallback.platform.listener.onResult("keep me")
        fallback.controller.onAppBackgrounded()
        assertEquals("Rewritten keep me", fallback.drafts.getValue(key()).text)
        assertTrue(fallback.controller.state is ConversationDictationState.Idle)

        val provider = fixture(draft = TextFieldValue("Provider"))
        provider.controller.requestProviderActivityStart(ACCOUNT, GROUP, provider.drafts.getValue(key()))
        provider.controller.beginProviderActivityLaunch(provider.controller.providerActivityRequestId)
        provider.controller.onAppBackgrounded()
        assertTrue(provider.controller.state is ConversationDictationState.ProviderActivityActive)
    }

    /** Verifies service startup rejection and destruction release capture exactly once. */
    @Test
    fun durableServiceFailureAndDestructionReleaseCaptureDeterministically() {
        val rejected =
            fixture(
                draft = TextFieldValue("Keep"),
                startDurableSession = { _, _ -> false },
            )
        rejected.controller.requestStart(ACCOUNT, GROUP, rejected.drafts.getValue(key()))
        assertEquals(
            ConversationDictationFailure.Unknown,
            (rejected.controller.state as ConversationDictationState.Failed).reason,
        )
        assertFalse(rejected.controller.ownsMicrophone)

        var serviceStops = 0
        var microphoneReleases = 0
        val destroyed =
            fixture(
                draft = TextFieldValue("Keep"),
                stopDurableSession = { serviceStops += 1 },
                releaseMicrophone = { microphoneReleases += 1 },
            )
        destroyed.controller.requestStart(ACCOUNT, GROUP, destroyed.drafts.getValue(key()))
        destroyed.controller.onDurableServiceDestroyed(requireNotNull(destroyed.controller.notificationSessionToken))

        assertTrue(destroyed.controller.state is ConversationDictationState.Idle)
        assertEquals(1, microphoneReleases)
        assertEquals(0, serviceStops)
        assertTrue(destroyed.platform.session.cancelled)
    }

    /** The queued service must observe ownership even when Android dispatches it synchronously. */
    @Test
    fun durableOwnershipIsPublishedBeforeServiceStartCanObserveIt() {
        lateinit var controller: ConversationDictationController
        var ownershipObservedByServiceStart = false
        val platform = FakePlatform()
        controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue(""), 0L) },
                writeDraft = { _, _, _, _ -> 0L },
                startDurableSession = { _, ready ->
                    ownershipObservedByServiceStart = controller.hasDurableSession
                    ready()
                    true
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
            )

        controller.requestStart(ACCOUNT, GROUP, TextFieldValue(""))

        assertTrue(ownershipObservedByServiceStart)
        assertTrue(controller.hasDurableSession)
        assertTrue(platform.session.started)
    }

    /** Enqueue success alone cannot acquire the microphone or create a recognizer. */
    @Test
    fun captureWaitsForPromotionAndDuplicateReadyDoesNotRestart() {
        lateinit var ready: () -> Unit
        var acquisitions = 0
        val fixture =
            fixture(
                draft = TextFieldValue("Keep"),
                startDurableSession = { _, callback ->
                    ready = callback
                    true
                },
                tryAcquireMicrophone = {
                    acquisitions++
                    true
                },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        assertTrue(fixture.controller.hasDurableSession)
        assertTrue(fixture.controller.completionActionsEnabled)
        assertFalse(fixture.controller.ownsMicrophone)
        assertTrue(fixture.platform.sessions.isEmpty())
        ready()
        ready()
        assertEquals(1, acquisitions)
        assertEquals(1, fixture.platform.sessions.size)
        assertTrue(fixture.platform.session.started)
    }

    /** Even a synchronous acknowledgement must wait for the enqueue result to be accepted. */
    @Test
    fun synchronousReadyCannotCaptureWhenEnqueueIsRejected() {
        val fixture =
            fixture(
                draft = TextFieldValue("Keep"),
                startDurableSession = { _, ready ->
                    ready()
                    false
                },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        assertTrue(fixture.platform.sessions.isEmpty())
        assertFalse(fixture.controller.ownsMicrophone)
        assertFalse(fixture.controller.hasDurableSession)
        assertEquals(
            ConversationDictationFailure.Unknown,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
    }

    /** Late acknowledgement/destruction from a cancelled session cannot touch its replacement. */
    @Test
    fun stalePromotionCallbacksCannotReviveOrDestroyReplacement() {
        val callbacks = mutableListOf<() -> Unit>()
        val fixture =
            fixture(
                draft = TextFieldValue("Keep"),
                startDurableSession = { _, ready ->
                    callbacks += ready
                    true
                },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val oldToken = requireNotNull(fixture.controller.notificationSessionToken)
        fixture.controller.cancel()
        callbacks[0]()
        assertTrue(fixture.platform.sessions.isEmpty())
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        callbacks[0]()
        fixture.controller.onDurableServiceDestroyed(oldToken)
        fixture.controller.onDurableServiceStartFailed(oldToken)
        assertTrue(fixture.controller.hasDurableSession)
        assertTrue(fixture.platform.sessions.isEmpty())
        callbacks[1]()
        assertEquals(1, fixture.platform.sessions.size)
    }

    @Test
    fun throwingPromotionProbeFailsOnlyItsOwningSession() {
        lateinit var ready: () -> Unit
        val platform = FakePlatform()
        val fixture =
            fixture(
                draft = TextFieldValue("Keep"),
                platform = platform,
                startDurableSession = { _, callback ->
                    ready = callback
                    true
                },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        platform.recognitionAvailableFailure = IllegalStateException("probe failed")

        ready()

        assertEquals(
            ConversationDictationFailure.Unknown,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertFalse(fixture.controller.hasDurableSession)
        assertTrue(fixture.platform.sessions.isEmpty())
    }

    @Test
    fun replacementDuringPromotionProbeCannotBeFailedOrRevivedByOldCallback() {
        val callbacks = mutableListOf<() -> Unit>()
        val platform = FakePlatform()
        lateinit var fixture: Fixture
        fixture =
            fixture(
                draft = TextFieldValue("Keep"),
                platform = platform,
                startDurableSession = { _, callback ->
                    callbacks += callback
                    true
                },
            )
        fixture.drafts[OTHER_ACCOUNT to OTHER_GROUP] = TextFieldValue("Other")
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        platform.onRecognitionAvailableCheck = {
            platform.onRecognitionAvailableCheck = null
            fixture.controller.requestStart(
                OTHER_ACCOUNT,
                OTHER_GROUP,
                fixture.drafts.getValue(OTHER_ACCOUNT to OTHER_GROUP),
            )
        }

        callbacks[0]()

        assertTrue(fixture.controller.isOwnedBy(OTHER_ACCOUNT, OTHER_GROUP))
        assertTrue(fixture.platform.sessions.isEmpty())
        callbacks[1]()
        assertEquals(1, fixture.platform.sessions.size)
    }

    /** A service that never promotes has a finite deadline and releases ownership once. */
    @Test
    fun missingPromotionTimesOutWithoutOpeningMicrophone() {
        lateinit var ready: () -> Unit
        var stops = 0
        val fixture =
            fixture(
                draft = TextFieldValue("Keep"),
                startDurableSession = { _, callback ->
                    ready = callback
                    true
                },
                stopDurableSession = { stops++ },
            )
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.scheduler.advanceBy(2_999L)
        assertTrue(fixture.controller.state is ConversationDictationState.Starting)
        assertTrue(fixture.platform.sessions.isEmpty())
        fixture.scheduler.advanceBy(1L)
        assertEquals(
            ConversationDictationFailure.TimedOut,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        ready()
        fixture.controller.cancel()
        assertEquals(1, stops)
        assertTrue(fixture.platform.sessions.isEmpty())
    }

    /** Cancel/Paste/Send during startup never briefly opens capture or writes an empty result. */
    @Test
    fun completionDuringPromotionDisarmsPendingCapture() {
        listOf<(ConversationDictationController) -> Unit>(
            ConversationDictationController::cancel,
            ConversationDictationController::paste,
            ConversationDictationController::send,
        ).forEach { complete ->
            lateinit var ready: () -> Unit
            var stops = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Keep"),
                    startDurableSession = { _, callback ->
                        ready = callback
                        true
                    },
                    stopDurableSession = { stops++ },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            complete(fixture.controller)
            ready()
            assertTrue(fixture.platform.sessions.isEmpty())
            assertFalse(fixture.controller.hasDurableSession)
            assertEquals(0, fixture.writes)
            assertEquals("Keep", fixture.drafts.getValue(key()).text)
            assertEquals(1, stops)
        }
    }

    /** Permissions, target ownership, and provider availability are fresh after the async wait. */
    @Test
    fun promotionRechecksRevokedAccessTargetAndProvider() {
        listOf("permission", "target", "provider").forEach { revoked ->
            lateinit var ready: () -> Unit
            var availableTarget = true
            val fixture =
                fixture(
                    draft = TextFieldValue("Keep"),
                    startDurableSession = { _, callback ->
                        ready = callback
                        true
                    },
                    targetAvailable = { availableTarget },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            when (revoked) {
                "permission" -> fixture.platform.hasPermission = false
                "target" -> availableTarget = false
                "provider" -> fixture.platform.available = false
            }
            ready()
            assertTrue(fixture.platform.sessions.isEmpty())
            assertFalse(fixture.controller.hasDurableSession)
            assertFalse(fixture.controller.ownsMicrophone)
        }
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
    fun conversationIdentityIsCaseInsensitiveAcrossOwnershipAndRemoval() {
        val fixture = fixture(draft = TextFieldValue("Source", TextRange(6)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        val session = fixture.platform.session

        assertTrue(fixture.controller.isOwnedBy(ACCOUNT.uppercase(), GROUP.uppercase()))
        assertFalse(
            fixture.controller.requestStart(
                ACCOUNT.uppercase(),
                GROUP.uppercase(),
                fixture.drafts.getValue(key()),
            ),
        )
        assertTrue(fixture.platform.session === session)

        fixture.controller.onTargetRemoved(ACCOUNT.uppercase(), GROUP.uppercase())

        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertEquals(1, session.cancelCalls)
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
        fixture.scheduler.runDelay(500L)
        val secondListener = fixture.platform.listener
        firstListener.onResult("stale")
        fixture.controller.stop()
        secondListener.onResult("fresh")

        assertEquals("Source", fixture.drafts.getValue(key()).text)
        assertEquals("Other fresh", fixture.drafts.getValue(OTHER_ACCOUNT to OTHER_GROUP).text)
        assertTrue(firstSession.cancelled)
        assertTrue(firstSession.destroyed)
        assertEquals(1, firstSession.cancelCalls)
        assertEquals(1, firstSession.destroyCalls)
    }

    @Test
    fun differentTargetCannotReplaceActiveRecognizedTextAndCanStartAfterFallbackPaste() {
        val accumulated = fixture(draft = TextFieldValue("Source", TextRange(6)))
        accumulated.drafts[OTHER_ACCOUNT to OTHER_GROUP] = TextFieldValue("Other", TextRange(5))
        accumulated.controller.requestStart(ACCOUNT, GROUP, accumulated.drafts.getValue(key()))
        accumulated.platform.listener.onResult("recover me")

        assertFalse(
            accumulated.controller.requestStart(
                OTHER_ACCOUNT,
                OTHER_GROUP,
                accumulated.drafts.getValue(OTHER_ACCOUNT to OTHER_GROUP),
            ),
        )
        assertTrue(accumulated.controller.isOwnedBy(ACCOUNT, GROUP))

        val fallback = fixture(draft = TextFieldValue("Source", TextRange(6)))
        fallback.drafts[OTHER_ACCOUNT to OTHER_GROUP] = TextFieldValue("Other", TextRange(5))
        fallback.controller.requestStart(ACCOUNT, GROUP, fallback.drafts.getValue(key()))
        fallback.edit(key(), TextFieldValue("Changed", TextRange(7)))
        fallback.controller.stop()
        fallback.platform.listener.onResult("recover me")

        assertTrue(
            fallback.controller.requestStart(
                OTHER_ACCOUNT,
                OTHER_GROUP,
                fallback.drafts.getValue(OTHER_ACCOUNT to OTHER_GROUP),
            ),
        )
        assertEquals("Changed recover me", fallback.drafts.getValue(key()).text)
        assertTrue(fallback.controller.isOwnedBy(OTHER_ACCOUNT, OTHER_GROUP))
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
    fun targetRemovalRetainsAlreadyRecognizedTextForCopy() {
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onResult("recover me")

        fixture.controller.onTargetRemoved(ACCOUNT, GROUP)

        assertEquals("Keep", fixture.drafts.getValue(key()).text)
        assertEquals(0, fixture.writes)
        val failure = fixture.controller.state as ConversationDictationState.Failed
        assertEquals("recover me", failure.retainedTranscript)
        assertEquals(1, fixture.platform.session.destroyCalls)
    }

    @Test
    fun targetRemovalDoesNotDiscardTextAlreadyPreservedInTheDraft() {
        val fixture = fixture(draft = TextFieldValue("Keep", TextRange(4)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.edit(key(), TextFieldValue("Changed", TextRange(7)))
        fixture.controller.stop()
        fixture.platform.listener.onResult("recover me")

        fixture.controller.onTargetRemoved(ACCOUNT, GROUP)

        assertEquals("Changed recover me", fixture.drafts.getValue(key()).text)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun multilineTranscriptReplacesOnlyTheCapturedSelection() {
        val fixture = fixture(draft = TextFieldValue("Before placeholder after", TextRange(7, 18)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.controller.stop()
        fixture.platform.listener.onResult("first line\nsecond line")

        assertEquals("Before first line\nsecond line after", fixture.drafts.getValue(key()).text)
        assertEquals(TextRange(29), fixture.drafts.getValue(key()).selection)
    }

    @Test
    fun longPrefixEditRemapsAnEmptySelectionWithoutScanningTheWholeDraft() {
        val captured = TextFieldValue("left right", TextRange(4))
        val fixture = fixture(draft = captured)
        fixture.controller.requestStart(ACCOUNT, GROUP, captured)
        val prefix = "prefixed ".repeat(2_000)
        fixture.edit(key(), TextFieldValue(prefix + captured.text))

        fixture.controller.stop()
        fixture.platform.listener.onResult("dictated")

        assertEquals(prefix + "left dictated right", fixture.drafts.getValue(key()).text)
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

    @Test
    fun aProviderThatCanUseCallerAudioKeepsTheGestureOnTheInAppControls() {
        val platform = FakePlatform(callerAudio = ConversationDictationCallerAudioRequirement.Supported)
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertEquals(0, platform.callerAudioProbes)
        assertEquals(
            ConversationDictationMode.InApp,
            fixture.controller.state.target
                ?.mode,
        )
        assertTrue(platform.session.started)
    }

    /** A typed capture overflow must destroy the recognizer and surface its cause in the visible session. */
    @Test
    fun callerAudioBufferOverflowFailsTheVisibleSession() {
        val platform = FakePlatform(callerAudio = ConversationDictationCallerAudioRequirement.Supported)
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        platform.listener.onError(ConversationDictationFailure.AudioBufferFull)

        val failed = fixture.controller.state as ConversationDictationState.Failed
        assertEquals(ConversationDictationFailure.AudioBufferFull, failed.reason)
        assertTrue(platform.session.destroyed)
    }

    @Test
    fun aProviderThatCannotUseCallerAudioIsRoutedToItsOwnUiBeforeAnythingStarts() {
        val platform =
            FakePlatform(
                hasPermission = false,
                callerAudio = ConversationDictationCallerAudioRequirement.Unsupported,
            )
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertTrue(fixture.controller.state is ConversationDictationState.ProviderActivityRequired)
        assertEquals(
            ConversationDictationMode.ProviderActivity,
            fixture.controller.state.target
                ?.mode,
        )
        assertTrue(platform.sessions.isEmpty())
        // The provider owns that microphone, so White Noise asks the user for nothing.
        assertEquals(0L, fixture.controller.permissionRequestId)
    }

    @Test
    fun anUnestablishedProviderIsProbedOnceAndThenUsesTheInAppControls() {
        val platform =
            FakePlatform(
                callerAudio = ConversationDictationCallerAudioRequirement.Unknown,
                probeAnswer = ConversationDictationCallerAudioRequirement.Supported,
            )
        val phases = mutableListOf<ConversationDictationReadinessPhase>()
        val fixture =
            fixture(
                draft = TextFieldValue("Keep"),
                platform = platform,
                onReadinessEvent = { phases += it.phase },
            )

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        // A second tap on the same target must not start a second probe.
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertEquals(1, platform.callerAudioProbes)
        assertEquals(
            listOf(
                ConversationDictationReadinessPhase.CheckingService,
                ConversationDictationReadinessPhase.ServiceReady,
            ),
            phases,
        )
        assertTrue(platform.session.started)
        assertEquals(
            ConversationDictationMode.InApp,
            fixture.controller.state.target
                ?.mode,
        )
    }

    @Test
    fun anInconclusiveProbeKeepsInAppCaptureSoTheRealFailureStaysVisible() {
        val platform =
            FakePlatform(
                callerAudio = ConversationDictationCallerAudioRequirement.Unknown,
                probeAnswer = ConversationDictationCallerAudioRequirement.Unknown,
            )
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertEquals(1, platform.callerAudioProbes)
        assertTrue(platform.session.started)
        assertEquals(0L, fixture.controller.providerActivityRequestId)
    }

    @Test
    fun aProbeThePlatformCannotStartKeepsInAppCaptureAndReleasesItsTimeout() {
        val platform =
            FakePlatform(
                callerAudio = ConversationDictationCallerAudioRequirement.Unknown,
                probeAnswer = ConversationDictationCallerAudioRequirement.Unsupported,
            )
        platform.callerAudioProbeFailure = IllegalStateException("no recognizer")
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        // The question was asked, failed, and established nothing, so the gesture stays in-app
        // rather than throwing out of requestStart or waiting on a probe that will never answer.
        assertEquals(1, platform.callerAudioProbes)
        assertTrue(platform.session.started)
        assertFalse(fixture.controller.state is ConversationDictationState.CheckingProvider)
        assertEquals(0L, fixture.controller.providerActivityRequestId)
    }

    @Test
    fun aProbeThatEstablishesRefusalHandsOwnershipToTheProviderReadinessCheck() {
        val platform =
            FakePlatform(
                deferActivityReadiness = true,
                callerAudio = ConversationDictationCallerAudioRequirement.Unknown,
                probeAnswer = ConversationDictationCallerAudioRequirement.Unsupported,
            )
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        // The synchronous answer released the finished probe and started the provider-Activity
        // readiness check, which is the pending work this session now owns.
        assertTrue(platform.callerAudioProbeCancelled)
        assertFalse(platform.readinessCancelled)
        assertTrue(fixture.controller.state is ConversationDictationState.CheckingProvider)

        fixture.controller.cancel()

        assertTrue(platform.readinessCancelled)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }

    @Test
    fun aDivertedGestureShowsOnlyTheProviderActivityAfterItsReadinessCheck() {
        val platform =
            FakePlatform(
                deferActivityReadiness = true,
                callerAudio = ConversationDictationCallerAudioRequirement.Unknown,
                probeAnswer = ConversationDictationCallerAudioRequirement.Unsupported,
            )
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        platform.activityReadinessCallback(true)

        assertTrue(fixture.controller.state is ConversationDictationState.ProviderActivityRequired)
        assertEquals(1L, fixture.controller.providerActivityRequestId)
        assertTrue(platform.sessions.isEmpty())
    }

    @Test
    fun cancellingDuringAProbeStopsItAndFencesItsLateAnswer() {
        val platform =
            FakePlatform(
                callerAudio = ConversationDictationCallerAudioRequirement.Unknown,
                probeAnswer = ConversationDictationCallerAudioRequirement.Unsupported,
                deferCallerAudioProbe = true,
            )
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        assertTrue(fixture.controller.state is ConversationDictationState.CheckingProvider)
        fixture.controller.cancel()
        platform.callerAudioProbeCallback(ConversationDictationCallerAudioRequirement.Unsupported)

        assertTrue(platform.callerAudioProbeCancelled)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        assertEquals(0L, fixture.controller.providerActivityRequestId)
        assertTrue(platform.sessions.isEmpty())
    }

    @Test
    fun aProbeThatNeverAnswersFallsBackToInAppCaptureAndIgnoresItsLateAnswer() {
        val platform =
            FakePlatform(
                callerAudio = ConversationDictationCallerAudioRequirement.Unknown,
                probeAnswer = ConversationDictationCallerAudioRequirement.Unsupported,
                deferCallerAudioProbe = true,
            )
        val fixture = fixture(draft = TextFieldValue("Keep"), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.scheduler.runDelay(6_000L)

        assertTrue(platform.callerAudioProbeCancelled)
        assertTrue(platform.session.started)

        platform.callerAudioProbeCallback(ConversationDictationCallerAudioRequirement.Unsupported)

        assertEquals(0L, fixture.controller.providerActivityRequestId)
        assertEquals(
            ConversationDictationMode.InApp,
            fixture.controller.state.target
                ?.mode,
        )
    }

    @Test
    fun noProbeRunsBeforeTheFirstUseDisclosureIsAccepted() {
        val platform =
            FakePlatform(
                callerAudio = ConversationDictationCallerAudioRequirement.Unknown,
                probeAnswer = ConversationDictationCallerAudioRequirement.Unsupported,
            )
        var disclosureAccepted = false
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue("Keep"), 0) },
                writeDraft = { _, _, _, _ -> error("Routing must not write") },
                disclosureAccepted = { disclosureAccepted },
                markDisclosureAccepted = { disclosureAccepted = true },
            )

        controller.requestStart(ACCOUNT, GROUP, TextFieldValue("Keep"))

        assertEquals(0, platform.callerAudioProbes)
        assertTrue(controller.state is ConversationDictationState.DisclosureRequired)

        controller.acceptDisclosure()

        assertEquals(1, platform.callerAudioProbes)
        assertTrue(controller.state is ConversationDictationState.ProviderActivityRequired)
    }

    /** A rejected Send stores recognized text without a Paste gesture and keeps a single retry payload. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun automaticDraftRecoveryPreservesRejectedSendAndRetriesOnce() =
        runTest {
            var accept = false
            var sends = 0
            val payloads = mutableListOf<String>()
            val f =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        sends++
                        if (accept && request.beginDispatch()) {
                            payloads += request.payload
                            true
                        } else {
                            false
                        }
                    },
                )
            f.controller.requestStart(ACCOUNT, GROUP, f.drafts.getValue(key()))
            f.controller.send()
            f.platform.listener.onResult("recognized")
            advanceUntilIdle()
            assertEquals("Draft recognized", f.drafts.getValue(key()).text)
            assertTrue((f.controller.state as ConversationDictationState.Failed).draftRecovered)
            assertFalse(f.controller.hasDurableSession)
            accept = true
            f.controller.retry()
            advanceUntilIdle()
            f.controller.retry()
            assertEquals(2, sends)
            assertEquals(listOf("Draft recognized"), payloads)
            assertEquals("", f.drafts.getValue(key()).text)
            assertTrue(f.controller.state is ConversationDictationState.Idle)
        }

    /** Unknown delivery preserves the full text but cannot automatically or manually retry transport. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun automaticDraftRecoveryKeepsUnknownDeliveryNonRetryable() =
        runTest {
            var sends = 0
            val f =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = { request ->
                        sends++
                        assertTrue(request.beginDispatch())
                        false
                    },
                )
            f.controller.requestStart(ACCOUNT, GROUP, f.drafts.getValue(key()))
            f.controller.send()
            f.platform.listener.onResult("recognized")
            advanceUntilIdle()
            assertEquals("Draft recognized", f.drafts.getValue(key()).text)
            val failed = f.controller.state as ConversationDictationState.Failed
            assertEquals(ConversationDictationFailure.DeliveryUnknown, failed.reason)
            assertTrue(failed.draftRecovered)
            f.controller.retry()
            advanceUntilIdle()
            assertEquals(1, sends)
            assertFalse(f.controller.hasDurableSession)
        }

    /** Audio Retry appends only its new suffix and never consumes a concurrently edited recovered draft. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun editedRecoveredDraftKeepsRetainedAudioRetryFromSending() =
        runTest {
            val sent = mutableListOf<String>()
            val f =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidationScope = this,
                    sendTranscriptIfOriginUnchanged = {
                        sent += it.payload
                        true
                    },
                )
            failRecognizedTail(f, send = true)
            assertEquals("Draft first", f.drafts.getValue(key()).text)
            f.edit(key(), TextFieldValue("Draft first edited"))
            assertFalse(f.controller.canRetryRecoveredSend)
            f.controller.retry()
            f.scheduler.runDelay(500L)
            f.platform.pendingCallerAudio = false
            f.platform.listener.onResult("suffix")
            advanceUntilIdle()
            assertTrue(sent.isEmpty())
            assertEquals("Draft first edited suffix", f.drafts.getValue(key()).text)
            assertTrue(f.controller.state is ConversationDictationState.Failed)
            assertFalse(f.controller.canRetryRecoveredSend)
        }

    /** Confirmed origin loss cannot create a draft while an indeterminate membership read can preserve it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun automaticRecoveryRespectsRemovedAndIndeterminateOrigins() =
        runTest {
            listOf(
                ConversationDictationTargetValidation.DefinitelyRemoved,
                ConversationDictationTargetValidation.Indeterminate,
            ).forEach { validation ->
                var localAvailable = true
                val f =
                    fixture(
                        draft = TextFieldValue("Draft", TextRange(5)),
                        targetAvailable = { localAvailable },
                        targetValidator = { _, _ -> validation },
                        targetValidationScope = this,
                    )
                f.controller.requestStart(ACCOUNT, GROUP, f.drafts.getValue(key()))
                if (validation == ConversationDictationTargetValidation.DefinitelyRemoved) {
                    localAvailable = false
                    f.controller.onTargetUnavailable(ACCOUNT, GROUP)
                } else {
                    f.controller.send()
                    f.platform.listener.onResult("recognized")
                }
                advanceUntilIdle()
                val expected =
                    if (validation == ConversationDictationTargetValidation.DefinitelyRemoved) {
                        "Draft"
                    } else {
                        "Draft recognized"
                    }
                assertEquals(expected, f.drafts.getValue(key()).text)
            }
        }

    /** Builds a deterministic controller harness with injectable ownership, validation, and delivery seams. */
    private fun fixture(
        draft: TextFieldValue,
        onDraftWritten: (TextFieldValue) -> Unit = {},
        targetAvailable: () -> Boolean = { true },
        targetReplyAvailable: (String?) -> Boolean? = { true },
        targetValidator: (suspend (String, String) -> ConversationDictationTargetValidation)? = null,
        targetValidationScope: CoroutineScope? = null,
        onBeforeRecognition: (ConversationDictationTarget) -> Unit = {},
        onAfterAudioCapture: () -> Unit = {},
        platform: FakePlatform = FakePlatform(),
        tryAcquireMicrophone: () -> Boolean = { true },
        releaseMicrophone: () -> Unit = {},
        startDurableSession: (String, () -> Unit) -> Boolean = { _, ready ->
            ready()
            true
        },
        stopDurableSession: () -> Unit = {},
        finishAfterSilenceMillis: () -> Long? = { null },
        pauseOtherAudio: () -> Boolean = { true },
        silenceDeliveryMode: () -> ConversationDictationDeliveryMode = {
            ConversationDictationDeliveryMode.PasteIntoDraft
        },
        sendTranscriptIfOriginUnchanged: suspend (ConversationDictationSendRequest) -> Boolean = { false },
        onReadinessEvent: (ConversationDictationReadinessEvent) -> Unit = {},
        onRecoveryExpired: () -> Unit = {},
        onDraftRead: () -> Unit = {},
    ): Fixture {
        val scheduler = FakeTimeoutScheduler()
        val drafts = mutableMapOf(key() to draft)
        val revisions = mutableMapOf(key() to 0L)
        var writes = 0
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { account, group ->
                    onDraftRead()
                    ConversationDictationDraftSnapshot(
                        value = drafts.getValue(account to group),
                        revision = revisions[account to group] ?: 0L,
                    )
                },
                writeDraft = { account, group, expectedRevision, value ->
                    val target = account to group
                    if ((revisions[target] ?: 0L) != expectedRevision) {
                        null
                    } else {
                        drafts[target] = value
                        revisions[target] = expectedRevision + 1L
                        writes += 1
                        onDraftWritten(value)
                        expectedRevision + 1L
                    }
                },
                targetAvailable = { _, _ -> targetAvailable() },
                targetReplyAvailable = { _, _, replyToMessageIdHex -> targetReplyAvailable(replyToMessageIdHex) },
                targetValidator = targetValidator,
                targetValidationScope = targetValidationScope,
                onBeforeRecognition = onBeforeRecognition,
                onAfterAudioCapture = onAfterAudioCapture,
                tryAcquireMicrophone = tryAcquireMicrophone,
                releaseMicrophone = releaseMicrophone,
                startDurableSession = startDurableSession,
                stopDurableSession = stopDurableSession,
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                elapsedRealtime = scheduler::now,
                scheduleTimeout = scheduler::schedule,
                finishAfterSilenceMillis = finishAfterSilenceMillis,
                pauseOtherAudio = pauseOtherAudio,
                silenceDeliveryMode = silenceDeliveryMode,
                sendTranscriptIfOriginUnchanged = sendTranscriptIfOriginUnchanged,
                onReadinessEvent = onReadinessEvent,
                onRecoveryExpired = onRecoveryExpired,
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

        /** Simulates an authoritative concurrent draft edit and increments its optimistic revision. */
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
        var providerPackage: String? = null,
        var pinnedProviderPresent: Boolean = true,
        var needsProviderChoice: Boolean = false,
        var hasPermission: Boolean = true,
        var configured: Boolean = true,
        var available: Boolean = true,
        var activityAvailable: Boolean = true,
        private val deferActivityReadiness: Boolean = false,
        var createFailure: Throwable? = null,
        var microphoneAccessOverride: ConversationDictationMicrophoneAccess? = null,
        private val completePreparationOnStop: Boolean = false,
        private val deferCaptureCompletion: Boolean = false,
        // Every existing case keeps the default: a platform whose provider records for itself.
        var callerAudio: ConversationDictationCallerAudioRequirement =
            ConversationDictationCallerAudioRequirement.NotNeeded,
        var probeAnswer: ConversationDictationCallerAudioRequirement =
            ConversationDictationCallerAudioRequirement.Supported,
        private val deferCallerAudioProbe: Boolean = false,
    ) : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener
        var session = FakeSession()
        val listeners = mutableListOf<ConversationDictationRecognitionListener>()
        val sessions = mutableListOf<FakeSession>()
        var readinessCancelled = false
            private set
        var recognitionAvailabilityChecks = 0
            private set
        var recognitionConfigurationChecks = 0
            private set
        var recognitionAvailableFailure: RuntimeException? = null
        var onRecognitionAvailableCheck: (() -> Unit)? = null
        var onMicrophoneAccessCheck: (() -> Unit)? = null
        lateinit var activityReadinessCallback: (Boolean) -> Unit
            private set
        var callerAudioProbes = 0
            private set
        var callerAudioProbeCancelled = false
            private set
        lateinit var callerAudioProbeCallback: (ConversationDictationCallerAudioRequirement) -> Unit
            private set
        var pendingCallerAudio = false
        var sessionCallerAudioOwnedOverride: Boolean? = null
        var currentCallerAudioChunkId = 1L
        var capturedSilenceMillis: Long? = null
        var forcedCallerAudioClosures = 0
        var discardedCallerAudio = 0
        var tracksCallerAudioDisposal = false
        var deferCallerAudioFinish = false
        var callerAudioFinishCallback: (() -> Unit)? = null
        var deferDiscardClosure = false
        var discardClosureCallback: (() -> Unit)? = null
        var discardCaptureActive = false
        var forceCaptureFailure: RuntimeException? = null
        var discardCaptureFailure: RuntimeException? = null
        var callerAudioStateFailure: RuntimeException? = null
        var captureSessionsStarted = 0

        override fun beginCaptureSession() {
            captureSessionsStarted += 1
        }

        /** Simulates a platform that cannot even start the question, such as a recognizer refusal. */
        var callerAudioProbeFailure: RuntimeException? = null

        override fun pinnedProviderStillAvailable(): Boolean = pinnedProviderPresent

        override fun prepareProviderSelection(): Boolean = !needsProviderChoice

        override fun speechProviderPackage(): String? = providerPackage

        override fun hasRecordAudioPermission(): Boolean = hasPermission

        override fun microphoneAccess(): ConversationDictationMicrophoneAccess {
            onMicrophoneAccessCheck?.let { callback ->
                onMicrophoneAccessCheck = null
                callback()
            }
            return microphoneAccessOverride ?: super.microphoneAccess()
        }

        override fun recognitionConfigured(): Boolean {
            recognitionConfigurationChecks += 1
            return configured
        }

        override fun recognitionAvailable(): Boolean {
            recognitionAvailabilityChecks += 1
            onRecognitionAvailableCheck?.invoke()
            recognitionAvailableFailure?.let { throw it }
            return available
        }

        override fun recognitionActivityAvailable(): Boolean = activityAvailable

        override fun checkRecognitionActivity(callback: (Boolean) -> Unit): ConversationDictationTimeoutHandle {
            activityReadinessCallback = callback
            if (!deferActivityReadiness) callback(activityAvailable)
            return ConversationDictationTimeoutHandle { readinessCancelled = true }
        }

        override fun callerAudioRequirement(): ConversationDictationCallerAudioRequirement = callerAudio

        /** Lets lifecycle tests retain caller PCM independently of recognizer readiness. */
        override fun callerAudioHasPending(): Boolean {
            callerAudioStateFailure?.let { throw it }
            return pendingCallerAudio
        }

        /** Supplies capture activity independently of provider callbacks. */
        override fun callerAudioSilenceMillis(): Long? = capturedSilenceMillis

        /** Simulates recorder closure separately from draining the retained caller-audio queue. */
        override fun finishCallerAudioCapture(onClosed: () -> Unit): Boolean {
            if (!pendingCallerAudio && discardClosureCallback == null) return false
            if (deferCallerAudioFinish) {
                callerAudioFinishCallback = onClosed
            } else {
                onClosed()
            }
            return true
        }

        override fun forceCallerAudioCaptureClosure(onClosed: () -> Unit): Boolean {
            forceCaptureFailure?.let { throw it }
            if (!pendingCallerAudio && discardClosureCallback == null) return false
            forcedCallerAudioClosures += 1
            onClosed()
            return true
        }

        override fun discardCallerAudio(onClosed: () -> Unit): Boolean {
            discardCaptureFailure?.let { throw it }
            if (!tracksCallerAudioDisposal || (!pendingCallerAudio && !discardCaptureActive)) return false
            discardedCallerAudio += 1
            pendingCallerAudio = false
            if (deferDiscardClosure) discardClosureCallback = onClosed else onClosed()
            return true
        }

        override fun probeCallerAudioSupport(callback: (ConversationDictationCallerAudioRequirement) -> Unit): ConversationDictationTimeoutHandle {
            callerAudioProbes += 1
            callerAudioProbeFailure?.let { throw it }
            callerAudioProbeCallback = callback
            if (!deferCallerAudioProbe) callback(probeAnswer)
            return ConversationDictationTimeoutHandle { callerAudioProbeCancelled = true }
        }

        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            createFailure?.let { throw it }
            this.listener = listener
            listeners += listener
            session =
                FakeSession(
                    listener,
                    completePreparationOnStop,
                    deferCaptureCompletion,
                    callerAudioOwned = sessionCallerAudioOwnedOverride ?: pendingCallerAudio,
                    callerAudioChunkId = currentCallerAudioChunkId,
                    onCallerAudioAcknowledged = { currentCallerAudioChunkId += 1L },
                )
            sessions += session
            return session
        }
    }

    private class FakeSession(
        private val listener: ConversationDictationRecognitionListener? = null,
        private val completePreparationOnStop: Boolean = false,
        private val deferCaptureCompletion: Boolean = false,
        private val callerAudioOwned: Boolean = false,
        private val callerAudioChunkId: Long? = null,
        private val onCallerAudioAcknowledged: () -> Unit = {},
    ) : ConversationDictationRecognitionSession {
        var started = false
        var stopped = false
        var cancelled = false
        var destroyed = false
        var cancelCalls = 0
        var destroyCalls = 0
        var callerAudioAcknowledgmentAvailable = true
        var acknowledgedCallerAudio = 0
        var retriedCallerAudio = 0
        var retriedCallerAudioWithFollowingAudio = 0
        var callerAudioHasSpeech = true
        var callerAudioFeedComplete = true
        var callerAudioFinalChunk = false
        var callerAudioRetryAvailable = callerAudioOwned
        private val captureFinished = mutableListOf<() -> Unit>()
        private var captureClosed = false
        private var deferredProviderError: ConversationDictationFailure? = null

        /** Marks this fake recognition generation started. */
        override fun start() {
            started = true
        }

        /** Requests a final result while retaining the capture-closure callback. */
        override fun stop(onAudioCaptureFinished: () -> Unit) {
            stopped = true
            registerCaptureFinished(onAudioCaptureFinished)
            if (completePreparationOnStop) listener?.onResult(null)
        }

        /** Requests a final result without observing capture closure. */
        override fun stop() = stop {}

        /** Closes the fake capture and delivers callbacks in platform order. */
        fun completeCapture() {
            captureClosed = true
            captureFinished.toList().also { captureFinished.clear() }.forEach { it() }
            deferredProviderError?.let { error ->
                deferredProviderError = null
                listener?.onError(error)
            }
        }

        /** Emits a provider error now or after the configured deferred capture close. */
        fun providerError(error: ConversationDictationFailure) {
            if (deferCaptureCompletion && !captureClosed) {
                deferredProviderError = error
            } else {
                listener?.onError(error)
            }
        }

        /** Registers a close acknowledgement against the fake capture lifecycle. */
        private fun registerCaptureFinished(callback: () -> Unit) {
            if (captureClosed) {
                callback()
            } else {
                captureFinished += callback
                if (!deferCaptureCompletion) completeCapture()
            }
        }

        /** Records provider cancellation without changing fake capture state. */
        override fun cancel() {
            cancelled = true
            cancelCalls += 1
        }

        /** Cancels provider work and registers capture closure acknowledgement. */
        override fun cancel(onAudioCaptureFinished: () -> Unit) {
            cancel()
            registerCaptureFinished(onAudioCaptureFinished)
        }

        /** Records recognizer destruction without changing fake capture state. */
        override fun destroy() {
            destroyed = true
            destroyCalls += 1
        }

        /** Destroys the recognizer and registers capture closure acknowledgement. */
        override fun destroy(onAudioCaptureFinished: () -> Unit) {
            destroy()
            registerCaptureFinished(onAudioCaptureFinished)
        }

        /** Tracks final-result acknowledgments so tests can verify serial chunk ownership. */
        override fun acknowledgeCallerAudio(): Boolean {
            if (!callerAudioOwned || !callerAudioAcknowledgmentAvailable) return false
            acknowledgedCallerAudio += 1
            onCallerAudioAcknowledged()
            return true
        }

        override fun callerAudioChunkId(): Long? = callerAudioChunkId.takeIf { callerAudioOwned }

        /** Reports deterministic capture-side speech metadata for the owned caller-audio chunk. */
        override fun callerAudioContainsSpeech(): Boolean? = callerAudioHasSpeech.takeIf { callerAudioOwned }

        override fun callerAudioFullyFed(): Boolean = callerAudioOwned && callerAudioFeedComplete

        override fun callerAudioIsFinalChunk(): Boolean = callerAudioOwned && callerAudioFinalChunk

        /** Tracks exact-chunk retry so blank and failed finals cannot consume retained PCM. */
        override fun retryCallerAudio(): Boolean {
            if (!callerAudioOwned || !callerAudioRetryAvailable) return false
            retriedCallerAudio += 1
            return true
        }

        /** Tracks coalescing separately from an exact-chunk retry. */
        override fun retryCallerAudioWithFollowingAudio(): Boolean {
            retriedCallerAudioWithFollowingAudio += 1
            return retryCallerAudio()
        }

        /** Mirrors whether this fake generation successfully owns White Noise-captured audio. */
        override fun usesCallerAudioCapture(): Boolean = callerAudioOwned
    }

    private class FakeTimeoutScheduler {
        private val tasks = mutableListOf<Task>()
        private var currentTimeMillis = 100L

        fun now(): Long = currentTimeMillis

        /** Deep sleep advances elapsed time while Handler callbacks remain undispatched. */
        fun sleepWithoutDispatch(delayMillis: Long) {
            currentTimeMillis += delayMillis
        }

        /** Captures a queued callback so tests can invoke it even after cancellation. */
        fun latestCallback(): () -> Unit = tasks.last { !it.cancelled && !it.ran }.callback

        fun schedule(
            delayMillis: Long,
            callback: () -> Unit,
        ): ConversationDictationTimeoutHandle {
            val task = Task(delayMillis, currentTimeMillis + delayMillis, callback)
            tasks += task
            return ConversationDictationTimeoutHandle { task.cancelled = true }
        }

        fun runLatest() {
            val task = tasks.last { !it.cancelled && !it.ran }
            currentTimeMillis = maxOf(currentTimeMillis, task.dueAtMillis)
            task.ran = true
            task.callback()
        }

        /** Runs the newest live timeout matching [delayMillis]. */
        fun runDelay(delayMillis: Long) {
            val task = tasks.last { !it.cancelled && !it.ran && it.delayMillis == delayMillis }
            currentTimeMillis = maxOf(currentTimeMillis, task.dueAtMillis)
            task.ran = true
            task.callback()
        }

        /** Advances shared monotonic time and drains newly due callbacks in deadline order. */
        fun runThrough(delayMillis: Long) = advanceBy(delayMillis)

        fun advanceBy(delayMillis: Long) {
            val targetTime = currentTimeMillis + delayMillis
            repeat(1_000) {
                val next =
                    tasks
                        .withIndex()
                        .filter { !it.value.cancelled && !it.value.ran && it.value.dueAtMillis <= targetTime }
                        .minWithOrNull(compareBy<IndexedValue<Task>> { it.value.dueAtMillis }.thenBy { it.index })
                        ?.value
                        ?: run {
                            currentTimeMillis = targetTime
                            return
                        }
                currentTimeMillis = next.dueAtMillis
                next.ran = true
                next.callback()
            }
            error("virtual timeout scheduler exceeded its iteration bound")
        }

        /** Counts live callbacks so late provider events cannot silently schedule another completion. */
        fun liveTaskCount(): Int = tasks.count { !it.cancelled && !it.ran }

        private data class Task(
            val delayMillis: Long,
            val dueAtMillis: Long,
            val callback: () -> Unit,
            var cancelled: Boolean = false,
            var ran: Boolean = false,
        )
    }

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
        val REPLY_MESSAGE_ID = "ab".repeat(32)
        const val OTHER_ACCOUNT = "other-account"
        const val OTHER_GROUP = "other-group"

        fun key(): Pair<String, String> = ACCOUNT to GROUP
    }
}
