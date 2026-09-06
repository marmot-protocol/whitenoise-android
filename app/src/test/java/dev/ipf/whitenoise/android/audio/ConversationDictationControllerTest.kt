package dev.ipf.whitenoise.android.audio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("LargeClass")
class ConversationDictationControllerTest {
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun authoritativeValidationDropsResultWhenInactiveOriginGroupWasRemoved() =
        runTest {
            val fixture =
                fixture(
                    draft = TextFieldValue("Keep", TextRange(4)),
                    targetValidator = { _, _ -> false },
                    targetValidationScope = this,
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.platform.listener.onReady()

            fixture.controller.stop()
            fixture.platform.listener.onResult("discard me")
            advanceUntilIdle()

            assertEquals("Keep", fixture.drafts.getValue(key()).text)
            assertEquals(0, fixture.writes)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reviewInsertionRevalidatesRemovedOriginGroup() =
        runTest {
            var targetExists = true
            val fixture =
                fixture(
                    draft = TextFieldValue("Original anchor", TextRange(8)),
                    targetValidator = { _, _ -> targetExists },
                    targetValidationScope = this,
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.edit(key(), TextFieldValue("Completely rewritten", TextRange(20)))
            fixture.controller.stop()
            fixture.platform.listener.onResult("dictated words")
            advanceUntilIdle()
            assertTrue(fixture.controller.state is ConversationDictationState.ReviewRequired)

            targetExists = false
            fixture.controller.insertReviewAtEnd()
            advanceUntilIdle()

            assertEquals("Completely rewritten", fixture.drafts.getValue(key()).text)
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

    /** An Activity-only provider remains usable without requesting unrelated app microphone access. */
    @Test
    fun unresolvedRecognitionServiceUsesProviderActivityWithoutRuntimePermission() {
        val platform = FakePlatform(hasPermission = false, configured = false, available = false)
        val fixture = fixture(draft = TextFieldValue(""), platform = platform)

        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        assertTrue(fixture.controller.state is ConversationDictationState.ProviderActivityRequired)
        assertEquals(1L, fixture.controller.providerActivityRequestId)
        assertEquals(0L, fixture.controller.permissionRequestId)
        assertEquals(0, platform.recognitionAvailabilityChecks)
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

            assertTrue(fixture.controller.state is ConversationDictationState.ProviderActivityRequired)
            assertEquals(1L, fixture.controller.providerActivityRequestId)
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

    @Test
    fun grantedPermissionRejectedByRecognitionServiceFallsBackToProviderActivity() {
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

        fixture.platform.listener.onError(ConversationDictationFailure.PermissionDenied)

        assertTrue(fixture.controller.state is ConversationDictationState.ProviderActivityRequired)
        assertEquals(1L, fixture.controller.providerActivityRequestId)
        assertFalse(fixture.controller.ownsMicrophone)
        assertFalse(fixture.controller.hasDurableSession)
        assertTrue(rejectedSession.destroyed)
        assertEquals(1, microphoneReleases)
        assertEquals(1, durableStops)
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
    fun incompatibleConcurrentEditRequiresExplicitReviewAndPreservesBothValues() {
        val fixture = fixture(draft = TextFieldValue("Original anchor", TextRange(8)))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
        fixture.platform.listener.onReady()
        fixture.edit(key(), TextFieldValue("Completely rewritten", TextRange(20)))

        fixture.controller.stop()
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
        assertEquals(0, releases)
        assertTrue(firstSession.destroyed)
        assertEquals(2, fixture.platform.sessions.size)
        assertTrue(fixture.controller.state is ConversationDictationState.Starting)

        firstListener.onResult("stale duplicate")
        fixture.controller.stop()

        assertEquals("Draft early segment", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertEquals(1, releases)
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
        assertEquals(2, fixture.platform.sessions.size)
        fixture.platform.listener.onResult("Hello")
        assertEquals(3, fixture.platform.sessions.size)
        fixture.platform.listener.onResult(",")
        fixture.platform.listener.onResult("world")
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
        assertEquals(2, fixture.platform.sessions.size)
        firstGeneration.onResult("Hello")
        fixture.controller.stop()

        assertEquals("Hello", fixture.drafts.getValue(key()).text)
    }

    /** Verifies that recognizer churn retains one microphone lease and releases it only at logical teardown. */
    @Test
    fun microphoneLeaseSurvivesGenerationsAndReleasesOnceAtLogicalTeardown() {
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
        fixture.platform.listener.onResult("second")

        assertEquals(1, acquisitions)
        assertEquals(0, releases)
        assertEquals(3, fixture.platform.sessions.size)
        assertTrue(
            fixture.platform.sessions
                .take(2)
                .all { it.destroyed },
        )

        fixture.controller.cancel()
        fixture.controller.cancel()

        assertEquals(1, releases)
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
                    targetValidator = { _, _ -> true },
                    targetValidationScope = this,
                    deliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
                    sendTranscriptIfOriginUnchanged = { request ->
                        assertEquals(0L, request.expectedDraftRevision)
                        assertEquals("Draft", request.expectedDraftText)
                        sent += Triple(request.accountRef, request.groupIdHex, request.payload)
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.stop()
            val listener = fixture.platform.listener
            listener.onResult("dictated")
            listener.onResult("duplicate")
            advanceUntilIdle()

            assertEquals(listOf(Triple(ACCOUNT, GROUP, "Draft dictated")), sent)
            assertEquals("", fixture.drafts.getValue(key()).text)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** Explicit Paste wins over the stored automatic-send preference for this one session. */
    @Test
    fun pasteActionOverridesStoredSendPreference() {
        var sendCalls = 0
        val fixture =
            fixture(
                draft = TextFieldValue("Draft", TextRange(5)),
                deliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
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
        fixture.controller.send()
        fixture.controller.paste()
        fixture.platform.listener.onResult("second")
        // No delivery scope exists: the chosen Send must retain the result for review, never paste it.
        assertTrue(fixture.controller.state is ConversationDictationState.ReviewRequired)
        assertEquals("first", fixture.drafts.getValue(key()).text)
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
                    targetValidator = { _, _ -> true },
                    targetValidationScope = this,
                    deliveryMode = { ConversationDictationDeliveryMode.PasteIntoDraft },
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
    fun failureAfterDispatchRetainsTranscriptWithoutAllowingAnotherSendOrInsert() =
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

            assertEquals(
                "dictated",
                (fixture.controller.state as ConversationDictationState.DeliveryUnknown).transcript,
            )
            fixture.controller.send()
            fixture.controller.paste()
            fixture.controller.insertReviewAtEnd()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dispatchTimeoutIsUnknownButPreDispatchFailureRemainsRecoverable() =
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
            assertTrue(timedOut.controller.state is ConversationDictationState.DeliveryUnknown)
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
            assertTrue(rejected.controller.state is ConversationDictationState.ReviewRequired)
        }

    /** Verifies foreground-service ownership remains active until asynchronous delivery accepts or rejects. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendOnFinishKeepsDurableOwnershipUntilTheCommitFinishes() =
        runTest {
            val sendStarted = CompletableDeferred<Unit>()
            val finishSend = CompletableDeferred<Boolean>()
            var durableStops = 0
            val fixture =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidator = { _, _ -> true },
                    targetValidationScope = this,
                    deliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
                    stopDurableSession = { durableStops += 1 },
                    sendTranscriptIfOriginUnchanged = {
                        sendStarted.complete(Unit)
                        finishSend.await()
                    },
                )

            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.stop()
            fixture.platform.listener.onResult("dictated")
            runCurrent()

            assertTrue(sendStarted.isCompleted)
            assertTrue(fixture.controller.hasDurableSession)
            assertFalse(fixture.controller.ownsMicrophone)
            assertEquals(0, durableStops)

            finishSend.complete(true)
            advanceUntilIdle()

            assertFalse(fixture.controller.hasDurableSession)
            assertEquals(1, durableStops)
            assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        }

    /** Verifies rejected sends and concurrent edits preserve both the draft and transcript for review. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendRejectionAndConcurrentDraftEditFailClosedToReview() =
        runTest {
            var sendCalls = 0
            val rejected =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidator = { _, _ -> true },
                    targetValidationScope = this,
                    deliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
                    sendTranscriptIfOriginUnchanged = {
                        sendCalls += 1
                        false
                    },
                )
            rejected.controller.requestStart(ACCOUNT, GROUP, rejected.drafts.getValue(key()))
            rejected.controller.stop()
            rejected.platform.listener.onResult("dictated")
            advanceUntilIdle()

            assertEquals(1, sendCalls)
            assertEquals("Draft", rejected.drafts.getValue(key()).text)
            assertTrue(rejected.controller.state is ConversationDictationState.ReviewRequired)

            val edited =
                fixture(
                    draft = TextFieldValue("Draft", TextRange(5)),
                    targetValidator = { _, _ -> true },
                    targetValidationScope = this,
                    deliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
                    sendTranscriptIfOriginUnchanged = {
                        sendCalls += 1
                        true
                    },
                )
            edited.controller.requestStart(ACCOUNT, GROUP, edited.drafts.getValue(key()))
            edited.edit(key(), TextFieldValue("Draft changed"))
            edited.controller.stop()
            edited.platform.listener.onResult("dictated")
            advanceUntilIdle()

            assertEquals(1, sendCalls)
            assertEquals("Draft changed", edited.drafts.getValue(key()).text)
            assertTrue(edited.controller.state is ConversationDictationState.ReviewRequired)
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
                    deliveryMode = { ConversationDictationDeliveryMode.SendOnFinish },
                    sendTranscriptIfOriginUnchanged = {
                        sendCalls += 1
                        true
                    },
                )
            fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))
            fixture.controller.stop()
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

    /** Verifies repeated empty generations terminate instead of spinning the recognizer indefinitely. */
    @Test
    fun repeatedNoSpeechCallbacksStopAfterABoundedNumberOfRestarts() {
        val fixture = fixture(draft = TextFieldValue(""))
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        repeat(8) {
            fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)
            assertTrue(fixture.controller.state is ConversationDictationState.Starting)
        }
        fixture.platform.listener.onError(ConversationDictationFailure.NoSpeech)

        assertEquals(
            ConversationDictationFailure.NoSpeech,
            (fixture.controller.state as ConversationDictationState.Failed).reason,
        )
        assertEquals(9, fixture.platform.sessions.size)
        assertFalse(fixture.controller.hasDurableSession)
    }

    /** Verifies every generation watchdog retains already recognized words for explicit review. */
    @Test
    fun recognitionWatchdogsRetainAccumulatedTranscriptForReview() {
        val starting = fixture(draft = TextFieldValue(""))
        starting.controller.requestStart(ACCOUNT, GROUP, starting.drafts.getValue(key()))
        starting.platform.listener.onResult("starting words")
        starting.scheduler.runDelay(10_000L)
        assertEquals(
            "starting words",
            (starting.controller.state as ConversationDictationState.ReviewRequired).transcript,
        )

        val providerProcessing = fixture(draft = TextFieldValue(""))
        providerProcessing.controller.requestStart(ACCOUNT, GROUP, providerProcessing.drafts.getValue(key()))
        providerProcessing.platform.listener.onResult("provider words")
        providerProcessing.platform.listener.onEndOfSpeech()
        providerProcessing.scheduler.runDelay(20_000L)
        assertEquals(
            "provider words",
            (providerProcessing.controller.state as ConversationDictationState.ReviewRequired).transcript,
        )

        val manualStop = fixture(draft = TextFieldValue(""))
        manualStop.controller.requestStart(ACCOUNT, GROUP, manualStop.drafts.getValue(key()))
        manualStop.platform.listener.onResult("manual words")
        manualStop.platform.listener.onBeginningOfSpeech()
        manualStop.controller.stop()
        manualStop.scheduler.runDelay(20_000L)
        assertEquals(
            "manual words",
            (manualStop.controller.state as ConversationDictationState.ReviewRequired).transcript,
        )
    }

    /** Verifies service-backed capture survives UI loss while retained review text remains non-destructive. */
    @Test
    fun backgroundAndTaskRemovalKeepDurableCaptureWhileReviewTextRemainsSafe() {
        val active = fixture(draft = TextFieldValue("Draft", TextRange(5)))
        active.controller.requestStart(ACCOUNT, GROUP, active.drafts.getValue(key()))
        active.controller.onAppBackgrounded()
        active.controller.onTaskRemoved()
        assertTrue(active.controller.state is ConversationDictationState.Starting)
        assertFalse(active.platform.session.cancelled)
        active.controller.cancel()

        val review = fixture(draft = TextFieldValue("Anchor", TextRange(3)))
        review.controller.requestStart(ACCOUNT, GROUP, review.drafts.getValue(key()))
        review.edit(key(), TextFieldValue("Rewritten"))
        review.controller.stop()
        review.platform.listener.onResult("keep me")
        review.controller.onAppBackgrounded()
        assertTrue(review.controller.state is ConversationDictationState.ReviewRequired)

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
                startDurableSession = { false },
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
        destroyed.controller.onDurableServiceDestroyed()

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
                writeDraft = { _, _, _, _ -> true },
                startDurableSession = {
                    ownershipObservedByServiceStart = controller.hasDurableSession
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

    /** Builds a deterministic controller harness with injectable ownership, validation, and delivery seams. */
    private fun fixture(
        draft: TextFieldValue,
        targetAvailable: () -> Boolean = { true },
        targetValidator: (suspend (String, String) -> Boolean)? = null,
        targetValidationScope: CoroutineScope? = null,
        onBeforeRecognition: () -> Unit = {},
        platform: FakePlatform = FakePlatform(),
        tryAcquireMicrophone: () -> Boolean = { true },
        releaseMicrophone: () -> Unit = {},
        startDurableSession: () -> Boolean = { true },
        stopDurableSession: () -> Unit = {},
        finishAfterSilenceMillis: () -> Long? = { null },
        deliveryMode: () -> ConversationDictationDeliveryMode = {
            ConversationDictationDeliveryMode.PasteIntoDraft
        },
        sendTranscriptIfOriginUnchanged: suspend (ConversationDictationSendRequest) -> Boolean = { false },
        onReadinessEvent: (ConversationDictationReadinessEvent) -> Unit = {},
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
                targetValidator = targetValidator,
                targetValidationScope = targetValidationScope,
                onBeforeRecognition = onBeforeRecognition,
                tryAcquireMicrophone = tryAcquireMicrophone,
                releaseMicrophone = releaseMicrophone,
                startDurableSession = startDurableSession,
                stopDurableSession = stopDurableSession,
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                elapsedRealtime = { 100L },
                scheduleTimeout = scheduler::schedule,
                finishAfterSilenceMillis = finishAfterSilenceMillis,
                deliveryMode = deliveryMode,
                sendTranscriptIfOriginUnchanged = sendTranscriptIfOriginUnchanged,
                onReadinessEvent = onReadinessEvent,
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
        var hasPermission: Boolean = true,
        var configured: Boolean = true,
        var available: Boolean = true,
        var activityAvailable: Boolean = true,
        private val deferActivityReadiness: Boolean = false,
        var createFailure: Throwable? = null,
        var microphoneAccessOverride: ConversationDictationMicrophoneAccess? = null,
    ) : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener
        var session = FakeSession()
        val listeners = mutableListOf<ConversationDictationRecognitionListener>()
        val sessions = mutableListOf<FakeSession>()
        var readinessCancelled = false
            private set
        var recognitionAvailabilityChecks = 0
            private set
        lateinit var activityReadinessCallback: (Boolean) -> Unit
            private set

        override fun hasRecordAudioPermission(): Boolean = hasPermission

        override fun microphoneAccess(): ConversationDictationMicrophoneAccess = microphoneAccessOverride ?: super.microphoneAccess()

        override fun recognitionConfigured(): Boolean = configured

        override fun recognitionAvailable(): Boolean {
            recognitionAvailabilityChecks += 1
            return available
        }

        override fun recognitionActivityAvailable(): Boolean = activityAvailable

        override fun checkRecognitionActivity(callback: (Boolean) -> Unit): ConversationDictationTimeoutHandle {
            activityReadinessCallback = callback
            if (!deferActivityReadiness) callback(activityAvailable)
            return ConversationDictationTimeoutHandle { readinessCancelled = true }
        }

        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            createFailure?.let { throw it }
            this.listener = listener
            listeners += listener
            session = FakeSession()
            sessions += session
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

        /** Runs the newest live timeout matching [delayMillis]. */
        fun runDelay(delayMillis: Long) {
            val task = tasks.last { !it.cancelled && !it.ran && it.delayMillis == delayMillis }
            task.ran = true
            task.callback()
        }

        /** Runs every live timeout due no later than [delayMillis]. */
        fun runThrough(delayMillis: Long) {
            tasks
                .filter { !it.cancelled && !it.ran && it.delayMillis <= delayMillis }
                .forEach { task ->
                    task.ran = true
                    task.callback()
                }
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
