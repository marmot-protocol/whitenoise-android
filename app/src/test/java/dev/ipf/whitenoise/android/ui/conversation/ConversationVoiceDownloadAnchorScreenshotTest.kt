package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.AutomaticBacklogStoppedException
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.state.awaitOpenedTimelineSubscriptionsClosed
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.conversation.media.LocalVoiceAttachmentPresentationRuntime
import dev.ipf.whitenoise.android.ui.conversation.media.VoiceAttachmentMaterializationRequest
import dev.ipf.whitenoise.android.ui.conversation.media.VoiceAttachmentPresentationRuntime
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVoiceAttachmentSource
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleColumnTestTag
import dev.ipf.whitenoise.android.ui.navigation.MainShellProcessState
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-conversation evidence for voice download-to-playable transitions.
 *
 * The pinned baseline grew the voice row by four pixels exactly when playback
 * exposed its vertically padded speed pill; the stable row key remained and no
 * scroll writer ran. Each regression below therefore records every intermediate
 * lazy viewport and rejects both logical-anchor movement and row-size drift.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
internal class ConversationVoiceDownloadAnchorScreenshotTest : ConversationVoiceDownloadAnchorTestBase() {
    /**
     * Keeps a history reader fixed through materialization, waveform/duration
     * hydration, and a cache-backed chat re-entry without a second download.
     */
    @Test
    @Suppress("LongMethod") // One success path must preserve every intermediate production viewport and cache handoff.
    fun successHydrationAndCacheBackedReentryKeepHistoryViewportStable() {
        val fixture = conversationFixture(voiceIndices = setOf(HISTORY_VOICE_INDEX), idOffset = 0)
        var reconstructedFixture: VoiceConversationFixture? = null
        val voiceId = fixture.voiceMessageIds.single()
        val control = VoiceControl(messageId = voiceId, materializationAttemptCount = 1)
        val runtime = ControlledVoicePresentationRuntime(mapOf(voiceId to control))
        val evidence = RecordingConversationScrollEvidenceSink()
        try {
            awaitConversationCondition { fixture.controller.timeline.size == fixture.records.size }
            val host = showConversation(fixture, runtime, evidence, historyAnchorMessageId = voiceId)

            clickVoiceAction(voiceId, R.string.media_tap_to_download)
            awaitAttachmentOpenIntent(fixture.controller, voiceId)
            control.awaitMaterializationAttempt(0)
            composeRule.waitForIdle()

            val downloading = evidence.awaitAnchor(voiceId)
            assertVoiceActionTarget(voiceId, R.string.media_downloading)
            val transitionStart = evidence.checkpoint()
            composeRule.onNodeWithText("1×").assertDoesNotExist()
            evidence.clearWrites()

            control.succeedMaterialization(0)
            control.awaitHydrationStarted()
            composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText("1×").fetchSemanticsNodes().isNotEmpty()
            }
            assertVoiceActionTarget(voiceId, R.string.voice_message_pause)
            assertViewportStayedFixed("materialization", downloading, evidence, transitionStart)
            assertNoScrollWrites("materialization", evidence)

            val hydrationStart = evidence.checkpoint()
            control.releaseHydration()
            control.awaitHydrationCompleted()
            composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText("0:12", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertViewportStayedFixed("waveform and duration", downloading, evidence, hydrationStart)
            assertNoScrollWrites("voice hydration", evidence)

            val beforeReconstruction = evidence.awaitAnchor(voiceId)
            val restoredSnapshot = host.leave()
            runtime.stopPlayback()
            reconstructedFixture =
                conversationFixture(
                    voiceIndices = setOf(HISTORY_VOICE_INDEX),
                    idOffset = 0,
                )
            val freshFixture = requireNotNull(reconstructedFixture)
            awaitConversationCondition { freshFixture.controller.timeline.size == freshFixture.records.size }
            host.replaceWith(freshFixture, runtime, evidence, restoredSnapshot)
            val reentered = evidence.awaitAnchor(voiceId)
            val cacheHitStart = evidence.checkpoint()
            awaitVoiceAction(voiceId, R.string.voice_message_play)
            assertVoiceActionTarget(voiceId, R.string.voice_message_play)
            assertLogicalAnchor("cache-backed fresh state reconstruction", beforeReconstruction, reentered)
            assertViewportStayedFixed("cache-hit presentation", reentered, evidence, cacheHitStart)
            assertEquals("cache hit must bypass a second source load", 1, control.materializationAttempts)
            assertTimelineOrder(freshFixture, freshFixture.records)
        } finally {
            reconstructedFixture?.let { closeFixture(it, runtime) }
            closeFixture(fixture, runtime)
        }
    }

    /**
     * Records complete production voice rows while the preceding keyed message
     * owns the history anchor, keeping visual proof separate from clipped-anchor coverage.
     */
    @Test
    fun screenshotsShowFullyVisibleDownloadingAndPlayableRows() {
        val fixture = conversationFixture(voiceIndices = setOf(HISTORY_VOICE_INDEX), idOffset = 600)
        val voiceId = fixture.voiceMessageIds.single()
        val precedingId = fixture.records[HISTORY_VOICE_INDEX - 1].messageIdHex
        val control = VoiceControl(messageId = voiceId, materializationAttemptCount = 1)
        val runtime = ControlledVoicePresentationRuntime(mapOf(voiceId to control))
        val evidence = RecordingConversationScrollEvidenceSink()
        try {
            awaitConversationCondition { fixture.controller.timeline.size == fixture.records.size }
            showConversation(fixture, runtime, evidence, historyAnchorMessageId = precedingId)
            evidence.awaitAnchor(precedingId)
            assertVoiceBubbleFullyVisible(voiceId)

            clickVoiceAction(voiceId, R.string.media_tap_to_download)
            awaitAttachmentOpenIntent(fixture.controller, voiceId)
            control.awaitMaterializationAttempt(0)
            assertVoiceActionTarget(voiceId, R.string.media_downloading)
            settleVoiceActionIndication()
            assertVoiceBubbleFullyVisible(voiceId)
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(voiceId))
                .captureRoboImage("src/test/snapshots/voice_anchor_downloading_light.png")

            control.succeedMaterialization(0)
            control.awaitHydrationStarted()
            composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText("1×").fetchSemanticsNodes().isNotEmpty()
            }
            assertVoiceActionTarget(voiceId, R.string.voice_message_pause)
            settleVoiceActionIndication()
            assertVoiceBubbleFullyVisible(voiceId)
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(voiceId))
                .captureRoboImage("src/test/snapshots/voice_anchor_playback_engaged_light.png")
            control.releaseHydration()
            control.awaitHydrationCompleted()
        } finally {
            closeFixture(fixture, runtime)
        }
    }

    /**
     * Recreates the Activity-owned shell adapter while its process owner keeps
     * the exact history snapshot and controller for the restored route.
     */
    @Test
    @Suppress("LongMethod") // One same-process recreation contract spans both shell generations and the real screen.
    fun sameProcessActivityRecreationRetainsShellControllerAndHistorySnapshot() {
        val fixture = conversationFixture(voiceIndices = setOf(HISTORY_VOICE_INDEX), idOffset = 50)
        val voiceId = fixture.voiceMessageIds.single()
        val control = VoiceControl(messageId = voiceId, materializationAttemptCount = 1)
        val runtime = ControlledVoicePresentationRuntime(mapOf(voiceId to control))
        val evidence = RecordingConversationScrollEvidenceSink()
        val appState = fixture.controller.appState
        val processState = MainShellProcessState(appState)
        val savedStateHandle = SavedStateHandle()
        val routeKey = conversationScrollKey(fixture.controller.boundAccountRef, fixture.chat.group.groupIdHex)
        try {
            awaitConversationCondition { fixture.controller.timeline.size == fixture.records.size }
            val firstHolder = MainShellStateHolder(appState, savedStateHandle, processState)
            firstHolder.selectedChat.value = fixture.chat
            firstHolder.persistConversationRoute(fixture.controller.boundAccountRef)
            assertTrue(firstHolder.hasSavedConversationRoute)
            assertEquals(
                fixture.controller.boundAccountRef,
                savedStateHandle.get<String>("main_shell_selected_account_ref"),
            )
            assertEquals(
                fixture.chat.group.groupIdHex,
                savedStateHandle.get<String>("main_shell_selected_group_id"),
            )
            val retainedController =
                firstHolder.conversationController(
                    chatId = fixture.chat.group.groupIdHex,
                    accountRef = requireNotNull(fixture.controller.boundAccountRef),
                    runtimeGeneration = 1,
                    presentationKey = 1,
                    create = { fixture.controller },
                )
            assertSame(fixture.controller, retainedController)
            val host =
                showConversation(
                    fixture = fixture,
                    runtime = runtime,
                    evidence = evidence,
                    historyAnchorMessageId = voiceId,
                    onSaveScrollSnapshot = { snapshot ->
                        if (snapshot == null) {
                            firstHolder.conversationScrollSnapshots.remove(routeKey)
                        } else {
                            firstHolder.conversationScrollSnapshots[routeKey] = snapshot
                        }
                    },
                )
            val beforeRecreation = evidence.awaitAnchor(voiceId)
            val savedSnapshot = host.leave()
            assertEquals(savedSnapshot, firstHolder.conversationScrollSnapshots[routeKey])

            val recreatedSavedState =
                SavedStateHandle(
                    mapOf(
                        "main_shell_selected_account_ref" to fixture.controller.boundAccountRef,
                        "main_shell_selected_group_id" to fixture.chat.group.groupIdHex,
                    ),
                )
            val recreatedHolder = MainShellStateHolder(appState, recreatedSavedState, processState)
            assertTrue(recreatedHolder.hasSavedConversationRoute)
            val reusedController =
                recreatedHolder.conversationController(
                    chatId = fixture.chat.group.groupIdHex,
                    accountRef = requireNotNull(fixture.controller.boundAccountRef),
                    runtimeGeneration = 1,
                    presentationKey = 1,
                    create = { error("same-process recreation must not replace the retained controller") },
                )
            assertSame(fixture.controller, reusedController)
            assertSame(firstHolder.conversationScrollSnapshots, recreatedHolder.conversationScrollSnapshots)
            val restoredSnapshot = requireNotNull(recreatedHolder.conversationScrollSnapshots[routeKey])

            host.replaceWith(fixture, runtime, evidence, restoredSnapshot)
            val afterRecreation = evidence.awaitAnchor(voiceId)
            assertSameViewport("same-process Activity recreation", beforeRecreation, afterRecreation)
            assertTrue(afterRecreation.mode is ConversationScrollMode.ReadingHistory)
        } finally {
            runtime.releaseForCleanup()
            processState.release()
            awaitOpenedTimelineSubscriptionsClosed(fixture.scripted)
            runtime.deletePublishedFiles()
        }
    }

    /**
     * Lets a tail follower consume a genuine incoming append, then proves the
     * overlapping voice hydration issues no additional corrective scroll.
     */
    @Test
    fun tailFollowerHandlesIncomingAppendWithoutVoiceHydrationChase() {
        val fixture = conversationFixture(voiceIndices = setOf(MESSAGE_COUNT - 1), idOffset = 100)
        val voiceId = fixture.voiceMessageIds.single()
        val control = VoiceControl(messageId = voiceId, materializationAttemptCount = 1)
        val runtime = ControlledVoicePresentationRuntime(mapOf(voiceId to control))
        val evidence = RecordingConversationScrollEvidenceSink()
        try {
            awaitConversationCondition { fixture.controller.timeline.size == fixture.records.size }
            showConversation(fixture, runtime, evidence, historyAnchorMessageId = null)
            clickVoiceAction(voiceId, R.string.media_tap_to_download)
            awaitAttachmentOpenIntent(fixture.controller, voiceId)
            control.awaitMaterializationAttempt(0)
            evidence.awaitVisibleMessage(voiceId)
            evidence.clearWrites()

            val incoming = fixture.incomingRecord("tail-incoming")
            awaitConversationCondition { fixture.subscription.nextWindowCallCount >= 1 }
            fixture.subscription.emitWindow(timelinePage(*(fixture.records + incoming).toTypedArray()))
            awaitMountedConversationCondition("incoming tail window") {
                fixture.controller.timeline.size == fixture.records.size + 1
            }
            val afterIncoming = evidence.awaitVisibleMessage(incoming.messageIdHex)
            assertTimelineOrder(fixture, fixture.records + incoming)
            evidence.clearWrites()
            val hydrationStart = evidence.checkpoint()

            control.succeedMaterialization(0)
            control.awaitHydrationStarted()
            control.releaseHydration()
            control.awaitHydrationCompleted()
            composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText("0:12", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            assertViewportStayedFixed("tail voice hydration", afterIncoming, evidence, hydrationStart)
            assertNoScrollWrites("voice hydration after incoming append", evidence)
        } finally {
            closeFixture(fixture, runtime)
        }
    }

    /**
     * Reserves identical narrow/large-font RTL geometry across failure,
     * backlog cancellation, retry, playable, waveform, and duration states.
     */
    @Test
    @Config(sdk = [36], qualifiers = "en-w320dp-h780dp-mdpi")
    @Suppress("LongMethod") // One retry contract must cover failure, cancellation, playback, and hydrated text metrics.
    fun failureCancellationAndRetryKeepLargeFontRtlHistoryViewportStable() {
        val fixture = conversationFixture(voiceIndices = setOf(HISTORY_VOICE_INDEX), idOffset = 200)
        val voiceId = fixture.voiceMessageIds.single()
        val control =
            VoiceControl(
                messageId = voiceId,
                materializationAttemptCount = 3,
                hydratedDurationMs = LONG_DURATION_MS,
            )
        val runtime = ControlledVoicePresentationRuntime(mapOf(voiceId to control))
        val evidence = RecordingConversationScrollEvidenceSink()
        try {
            awaitConversationCondition { fixture.controller.timeline.size == fixture.records.size }
            showConversation(
                fixture = fixture,
                runtime = runtime,
                evidence = evidence,
                historyAnchorMessageId = voiceId,
                fontScale = 2f,
                layoutDirection = LayoutDirection.Rtl,
            )
            clickVoiceAction(voiceId, R.string.media_tap_to_download)
            awaitAttachmentOpenIntent(fixture.controller, voiceId)
            control.awaitMaterializationAttempt(0)
            val downloading = evidence.awaitAnchor(voiceId)
            assertVoiceActionTarget(voiceId, R.string.media_downloading)
            evidence.clearWrites()
            val failureStart = evidence.checkpoint()

            control.failMaterialization(0, IOException("controlled voice failure"))
            awaitVoiceAction(voiceId, R.string.voice_message_failed)
            assertVoiceActionTarget(voiceId, R.string.voice_message_failed)
            assertViewportStayedFixed("download failure", downloading, evidence, failureStart)
            assertNoScrollWrites("download failure", evidence)

            val cancellationStart = evidence.checkpoint()
            clickVoiceAction(voiceId, R.string.voice_message_failed)
            control.awaitMaterializationAttempt(1)
            control.failMaterialization(1, AutomaticBacklogStoppedException())
            awaitVoiceAction(voiceId, R.string.media_tap_to_download)
            assertVoiceActionTarget(voiceId, R.string.media_tap_to_download)
            assertViewportStayedFixed("download cancellation", downloading, evidence, cancellationStart)
            assertNoScrollWrites("download cancellation", evidence)

            val retryStart = evidence.checkpoint()
            clickVoiceAction(voiceId, R.string.media_tap_to_download)
            control.awaitMaterializationAttempt(2)
            control.succeedMaterialization(2)
            control.awaitHydrationStarted()
            control.releaseHydration()
            control.awaitHydrationCompleted()
            composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText("59:59", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertVoiceActionTarget(voiceId, R.string.voice_message_pause)
            assertViewportStayedFixed("retry and hydration", downloading, evidence, retryStart)
            assertNoScrollWrites("retry and hydration", evidence)
            assertEquals(3, control.materializationAttempts)
            assertTimelineOrder(fixture, fixture.records)
        } finally {
            closeFixture(fixture, runtime)
        }
    }

    /**
     * Records the complete narrow, large-font RTL production row with a long
     * duration while the preceding keyed message owns the history anchor.
     */
    @Test
    @Config(sdk = [36], qualifiers = "en-w320dp-h780dp-mdpi")
    fun screenshotShowsFullyVisibleLongDurationLargeFontRtlRow() {
        val fixture = conversationFixture(voiceIndices = setOf(HISTORY_VOICE_INDEX), idOffset = 700)
        val voiceId = fixture.voiceMessageIds.single()
        val precedingId = fixture.records[HISTORY_VOICE_INDEX - 1].messageIdHex
        val control =
            VoiceControl(
                messageId = voiceId,
                materializationAttemptCount = 1,
                hydratedDurationMs = LONG_DURATION_MS,
            )
        val runtime = ControlledVoicePresentationRuntime(mapOf(voiceId to control))
        val evidence = RecordingConversationScrollEvidenceSink()
        try {
            awaitConversationCondition { fixture.controller.timeline.size == fixture.records.size }
            showConversation(
                fixture = fixture,
                runtime = runtime,
                evidence = evidence,
                historyAnchorMessageId = precedingId,
                fontScale = 2f,
                layoutDirection = LayoutDirection.Rtl,
            )
            evidence.awaitAnchor(precedingId)
            assertVoiceBubbleFullyVisible(voiceId)

            clickVoiceAction(voiceId, R.string.media_tap_to_download)
            awaitAttachmentOpenIntent(fixture.controller, voiceId)
            control.awaitMaterializationAttempt(0)
            control.succeedMaterialization(0)
            control.awaitHydrationStarted()
            control.releaseHydration()
            control.awaitHydrationCompleted()
            composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithText("59:59", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertVoiceActionTarget(voiceId, R.string.voice_message_pause)
            settleVoiceActionIndication()
            assertVoiceBubbleFullyVisible(voiceId)
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(voiceId))
                .captureRoboImage("src/test/snapshots/voice_anchor_playback_large_font_rtl.png")
        } finally {
            closeFixture(fixture, runtime)
        }
    }

    /** Advances beyond the finite click indication so screenshots do not race its fade-out. */
    private fun settleVoiceActionIndication() {
        composeRule.mainClock.advanceTimeBy(VOICE_ACTION_INDICATION_SETTLE_MILLIS)
        composeRule.waitForIdle()
    }

    /**
     * Preserves a history reader and chronological uniqueness while two voice
     * notes materialize concurrently with an authoritative incoming window.
     */
    @Test
    fun concurrentVoiceHydrationAndIncomingMessageKeepHistoryIntentAndOrdering() {
        val voiceIndices = setOf(HISTORY_VOICE_INDEX, HISTORY_VOICE_INDEX + 1)
        val fixture = conversationFixture(voiceIndices = voiceIndices, idOffset = 300)
        val firstVoiceId = fixture.voiceMessageIds.first()
        val controls = fixture.voiceMessageIds.associateWith { VoiceControl(it, materializationAttemptCount = 1) }
        val runtime = ControlledVoicePresentationRuntime(controls)
        val evidence = RecordingConversationScrollEvidenceSink()
        try {
            awaitConversationCondition { fixture.controller.timeline.size == fixture.records.size }
            val host = showConversation(fixture, runtime, evidence, historyAnchorMessageId = firstVoiceId)
            val afterExplicitJump = focusWhileVoiceDownloadsAreHeld(fixture, controls, host, evidence)
            val concurrentStart = evidence.checkpoint()

            val incoming = fixture.incomingRecord("history-incoming")
            awaitConversationCondition { fixture.subscription.nextWindowCallCount >= 1 }
            fixture.subscription.emitWindow(timelinePage(*(fixture.records + incoming).toTypedArray()))
            controls.values.forEach { it.succeedMaterialization(0) }
            controls.values.forEach { it.awaitHydrationStarted() }
            awaitMountedConversationCondition("incoming history window") {
                fixture.controller.timeline.size == fixture.records.size + 1
            }
            val afterIncoming = evidence.awaitReadingHistory()
            assertIncomingReanchorFrames(
                expected = afterExplicitJump,
                actual = afterIncoming,
                evidence = evidence,
                checkpoint = concurrentStart,
            )
            evidence.clearWrites()
            val hydrationStart = evidence.checkpoint()

            controls.values.forEach { it.releaseHydration() }
            controls.values.forEach { it.awaitHydrationCompleted() }
            composeRule.waitForIdle()

            assertViewportStayedFixed(
                "voice hydration after concurrent incoming reanchor",
                afterIncoming,
                evidence,
                hydrationStart,
            )
            assertNoScrollWrites("history-mode concurrent hydration", evidence)
            assertTrue(evidence.latestViewport().mode is ConversationScrollMode.ReadingHistory)
            assertTimelineOrder(fixture, fixture.records + incoming)
            assertEquals(
                "both stable voice rows must remain present exactly once",
                fixture.voiceMessageIds,
                fixture.controller.timeline
                    .map { it.record.messageIdHex }
                    .filter(fixture.voiceMessageIds::contains),
            )
        } finally {
            closeFixture(fixture, runtime)
        }
    }

    /** Keeps a new account isolated when an old account's suspended download completes. */
    @Test
    fun staleCompletionAfterAccountSwitchCannotMoveTheNewAccountAnchor() {
        val oldFixture =
            conversationFixture(
                voiceIndices = setOf(HISTORY_VOICE_INDEX),
                idOffset = 400,
                accountRef = "account-a",
            )
        val newFixture =
            conversationFixture(
                voiceIndices = setOf(HISTORY_VOICE_INDEX),
                idOffset = 500,
                accountRef = "account-b",
            )
        val oldVoiceId = oldFixture.voiceMessageIds.single()
        val newVoiceId = newFixture.voiceMessageIds.single()
        val oldControl = VoiceControl(oldVoiceId, materializationAttemptCount = 1)
        val newControl = VoiceControl(newVoiceId, materializationAttemptCount = 1)
        val oldRuntime = ControlledVoicePresentationRuntime(mapOf(oldVoiceId to oldControl))
        val newRuntime = ControlledVoicePresentationRuntime(mapOf(newVoiceId to newControl))
        val oldEvidence = RecordingConversationScrollEvidenceSink()
        val newEvidence = RecordingConversationScrollEvidenceSink()
        try {
            awaitConversationCondition { oldFixture.controller.timeline.size == oldFixture.records.size }
            awaitConversationCondition { newFixture.controller.timeline.size == newFixture.records.size }
            val host = showConversation(oldFixture, oldRuntime, oldEvidence, oldVoiceId)
            clickVoiceAction(oldVoiceId, R.string.media_tap_to_download)
            awaitAttachmentOpenIntent(oldFixture.controller, oldVoiceId)
            oldControl.awaitMaterializationAttempt(0)

            host.replaceWith(newFixture, newRuntime, newEvidence, historySnapshot(newFixture, newVoiceId))
            val newAccountAnchor = newEvidence.awaitAnchor(newVoiceId)
            assertEquals("account-b", newAccountAnchor.accountRef)
            assertTrue(newAccountAnchor.mode is ConversationScrollMode.ReadingHistory)
            newEvidence.clearWrites()
            val staleCompletionStart = newEvidence.checkpoint()

            oldControl.succeedMaterialization(0)
            composeRule.waitForIdle()

            assertViewportStayedFixed(
                "old-account stale completion",
                newAccountAnchor,
                newEvidence,
                staleCompletionStart,
            )
            assertNoScrollWrites("old-account stale completion", newEvidence)
            assertEquals("account-b", newEvidence.latestViewport().accountRef)
        } finally {
            closeFixture(oldFixture, oldRuntime)
            closeFixture(newFixture, newRuntime)
        }
    }

    /** Requires the complete voice bubble to remain inside the measured production transcript. */
    private fun assertVoiceBubbleFullyVisible(messageId: String) {
        val transcript =
            composeRule
                .onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .getUnclippedBoundsInRoot()
        val bubble =
            composeRule
                .onNodeWithTag(messageBubbleColumnTestTag(messageId))
                .getUnclippedBoundsInRoot()
        assertTrue("voice bubble top must be inside the transcript", bubble.top >= transcript.top)
        assertTrue("voice bubble bottom must be inside the transcript", bubble.bottom <= transcript.bottom)
    }

    /**
     * Starts both real row downloads, then applies the newer production focus
     * intent while their external materialization remains deliberately held.
     */
    private fun focusWhileVoiceDownloadsAreHeld(
        fixture: VoiceConversationFixture,
        controls: Map<String, VoiceControl>,
        host: ConversationTestHost,
        evidence: RecordingConversationScrollEvidenceSink,
    ): ConversationViewportEvidence {
        fixture.voiceMessageIds.forEach { voiceId ->
            clickVoiceAction(voiceId, R.string.media_tap_to_download)
            awaitAttachmentOpenIntent(fixture.controller, voiceId)
            controls.getValue(voiceId).awaitMaterializationAttempt(0)
        }
        val downloading = evidence.awaitAnchor(fixture.voiceMessageIds.first())
        val explicitTargetId = fixture.records[HISTORY_VOICE_INDEX - 2].messageIdHex
        evidence.clearWrites()
        host.focus(explicitTargetId, evidence)
        return evidence.latestViewport().also { afterExplicitJump ->
            assertTrue(
                "the newer focus intent must move away from the download-era anchor",
                afterExplicitJump.anchor != downloading.anchor,
            )
            assertTrue(afterExplicitJump.mode is ConversationScrollMode.ReadingHistory)
            fixture.voiceMessageIds.forEach { voiceId ->
                awaitVoiceAction(voiceId, R.string.media_downloading)
            }
            evidence.clearWrites()
        }
    }

    /**
     * Requires every concurrent incoming frame to retain the same logical and
     * pixel anchor while permitting only the production structural reanchor owner.
     */
    private fun assertIncomingReanchorFrames(
        expected: ConversationViewportEvidence,
        actual: ConversationViewportEvidence,
        evidence: RecordingConversationScrollEvidenceSink,
        checkpoint: Int,
    ) {
        assertSameViewport("incoming structural reanchor", expected, actual)
        evidence.viewportsSince(checkpoint).forEachIndexed { index, frame ->
            val permittedOwner =
                when (val mode = frame.mode) {
                    expected.mode -> true
                    is ConversationScrollMode.Restoring ->
                        mode.anchorMessageId == expected.anchor.messageId &&
                            mode.pixelOffset == expected.anchor.pixelOffset
                    else -> false
                }
            assertTrue("incoming frame $index used an unexpected scroll owner: ${frame.mode}", permittedOwner)
            assertSameViewport("incoming concurrent frame $index", expected.copy(mode = frame.mode), frame)
        }
        assertEquals(
            "the incoming row owns one structural history reanchor",
            listOf(
                ConversationScrollWriteEvidence(
                    animated = false,
                    index = actual.anchor.listIndex,
                    offsetPx = actual.anchor.pixelOffset,
                ),
            ),
            evidence.writes,
        )
    }
}

/** Shared real-screen fixture owner kept separate from the focused regression class. */
internal abstract class ConversationVoiceDownloadAnchorTestBase {
    @get:Rule
    val composeRule = createComposeRule()

    protected val context: Context = ApplicationProvider.getApplicationContext()

    /** Renders the production conversation and retains the shell-owned saved history anchor. */
    @Suppress("LongMethod") // The composition host intentionally wires every real screen owner in one place.
    protected fun showConversation(
        fixture: VoiceConversationFixture,
        runtime: VoiceAttachmentPresentationRuntime,
        evidence: RecordingConversationScrollEvidenceSink,
        historyAnchorMessageId: String?,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        onSaveScrollSnapshot: (ConversationScrollSnapshot?) -> Unit = {},
    ): ConversationTestHost {
        val initialSnapshot = historyAnchorMessageId?.let { historySnapshot(fixture, it) }
        val savedScrollSnapshot = mutableStateOf(initialSnapshot)
        val mounted = mutableStateOf(true)
        val activeMount =
            mutableStateOf(
                ConversationMount(
                    fixture = fixture,
                    runtime = runtime,
                    evidence = evidence,
                    savedScrollSnapshot = savedScrollSnapshot,
                    onSaveScrollSnapshot = { snapshot ->
                        savedScrollSnapshot.value = snapshot
                        onSaveScrollSnapshot(snapshot)
                    },
                ),
            )
        val focusMessageId = mutableStateOf<String?>(null)
        val focusMessageRequestId = mutableStateOf(0L)
        composeRule.setContent {
            val mount = activeMount.value
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(systemDensity.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
                LocalVoiceAttachmentPresentationRuntime provides mount.runtime,
                LocalConversationScrollEvidenceSink provides mount.evidence,
            ) {
                if (mounted.value) {
                    WhiteNoiseTheme {
                        ConversationScreen(
                            appState = mount.fixture.controller.appState,
                            chat = mount.fixture.chat,
                            controller = mount.fixture.controller,
                            onBack = {},
                            focusMessageId = focusMessageId.value,
                            focusMessageRequestId = focusMessageRequestId.value,
                            restoredScrollSnapshot = mount.savedScrollSnapshot.value,
                            onSaveScrollSnapshot = mount.onSaveScrollSnapshot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        if (historyAnchorMessageId != null) {
            evidence.awaitAnchor(historyAnchorMessageId)
        } else {
            evidence.awaitVisibleMessage(fixture.records.last().messageIdHex)
        }
        return ConversationTestHost(
            mounted = mounted,
            activeMount = activeMount,
            focusMessageId = focusMessageId,
            focusMessageRequestId = focusMessageRequestId,
        )
    }

    /** Builds the shell-owned logical restore snapshot for one stable message row. */
    protected fun historySnapshot(
        fixture: VoiceConversationFixture,
        messageId: String,
    ): ConversationScrollSnapshot {
        val timelineIndex = fixture.records.indexOfFirst { it.messageIdHex == messageId }
        require(timelineIndex >= 0)
        return ConversationScrollSnapshot(
            firstVisibleItemIndex = timelineIndex + 1,
            firstVisibleItemScrollOffset = HISTORY_OFFSET_PX,
            anchorItemId = "msg:$messageId",
            anchorMessageIdHex = messageId,
        )
    }

    /** Builds one authoritative page with voice attachments at the requested stable row indices. */
    @Suppress("LongMethod") // Controller, account, roster, and attachment fixtures must share one namespace.
    protected fun conversationFixture(
        voiceIndices: Set<Int>,
        idOffset: Int,
        accountRef: String = ConversationTimelineTestIds.ACCOUNT_REF,
    ): VoiceConversationFixture {
        require(voiceIndices.isNotEmpty())
        require(voiceIndices.all { it in 0 until MESSAGE_COUNT })
        val references =
            voiceIndices.associate { index ->
                messageId(index, idOffset) to voiceReference(index, idOffset)
            }
        val records =
            List(MESSAGE_COUNT) { index ->
                val messageId = messageId(index, idOffset)
                timelineRecord(
                    messageId = messageId,
                    timelineAt = (idOffset + index + 1).toULong(),
                    plaintext = if (index in voiceIndices) "incoming voice note $index" else "message-$index",
                ).let { record ->
                    references[messageId]?.let { reference ->
                        record.copy(media = listOf(reference), sourceEpoch = reference.sourceEpoch)
                    } ?: record
                }
            }
        val subscription = ScriptedConversationTimelineSubscription(timelinePage(*records.toTypedArray()))
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(subscription),
                group = conversationTimelineTestGroup(),
            )
        val entryProjection =
            dev.ipf.whitenoise.android.state
                .notificationChatListRow()
                .copy(
                    lastMessage = null,
                    unreadCount = 0uL,
                    hasUnread = false,
                    firstUnreadMessageIdHex = null,
                    lastReadMessageIdHex = records.last().messageIdHex,
                    lastReadTimelineAt = records.last().timelineAt,
                )
        val appState = conversationTimelineTestAppState(scripted.subscriptions, accountRef = accountRef)
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                initialChatListRow = entryProjection,
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
            )
        appState.attachmentOpens.setDestination(
            AttachmentOpenDestination(
                accountRef = requireNotNull(controller.boundAccountRef),
                groupIdHex = controller.group.groupIdHex,
                navigationGeneration = 1L,
            ),
        )
        val chat =
            ChatListItem(
                group = conversationTimelineTestGroup(),
                latest = null,
                otherMemberAccount = null,
                memberCount = 1,
                memberSnapshot = conversationTimelineMemberSnapshot(),
                projection = entryProjection,
            )
        return VoiceConversationFixture(
            controller = controller,
            scripted = scripted,
            subscription = subscription,
            chat = chat,
            records = records,
            references = references,
            idOffset = idOffset,
        )
    }

    /** Selects the action belonging to one stable voice row, even when several are visible. */
    protected fun clickVoiceAction(
        messageId: String,
        descriptionResource: Int,
    ) {
        composeRule.onNode(voiceActionMatcher(messageId, descriptionResource), useUnmergedTree = true).performClick()
    }

    /** Waits until one voice row exposes the requested localized state action. */
    protected fun awaitVoiceAction(
        messageId: String,
        descriptionResource: Int,
    ) {
        val matcher = voiceActionMatcher(messageId, descriptionResource)
        composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(matcher, useUnmergedTree = true).assertExists()
    }

    /** Matches an action by localized label and its message-column ancestor. */
    protected fun voiceActionMatcher(
        messageId: String,
        descriptionResource: Int,
    ): SemanticsMatcher =
        hasContentDescription(context.getString(descriptionResource)) and
            hasAnyAncestor(hasTestTag(messageBubbleColumnTestTag(messageId)))

    /** Requires the localized voice action to retain its full 48dp TalkBack target. */
    protected fun assertVoiceActionTarget(
        messageId: String,
        descriptionResource: Int,
    ) {
        val action =
            composeRule
                .onNode(voiceActionMatcher(messageId, descriptionResource), useUnmergedTree = true)
                .fetchSemanticsNode()
        val minimumTargetPx = composeRule.density.run { 48.dp.toPx() }
        assertTrue("voice action width must remain at least 48dp", action.touchBoundsInRoot.width >= minimumTargetPx)
        assertTrue("voice action height must remain at least 48dp", action.touchBoundsInRoot.height >= minimumTargetPx)
    }

    /** Waits for the durable open intent that drives materialization and replay. */
    protected fun awaitAttachmentOpenIntent(
        controller: ConversationController,
        messageId: String,
    ) {
        composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
            controller.hasAttachmentOpenIntent(messageId, attachmentIndex = 0)
        }
    }

    /** Advances Compose and Robolectric queues while mounted controller work settles. */
    protected fun awaitMountedConversationCondition(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PHASE_TIMEOUT_MILLIS)
        while (System.nanoTime() <= deadlineNanos) {
            composeRule.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("$description did not settle within ${PHASE_TIMEOUT_MILLIS}ms")
    }

    /** Requires every intermediate viewport since [checkpoint] to retain the baseline geometry. */
    protected fun assertViewportStayedFixed(
        phase: String,
        expected: ConversationViewportEvidence,
        evidence: RecordingConversationScrollEvidenceSink,
        checkpoint: Int,
    ) {
        val observed = evidence.viewportsSince(checkpoint).ifEmpty { listOf(evidence.latestViewport()) }
        observed.forEachIndexed { index, actual ->
            assertSameViewport("$phase frame $index", expected, actual)
        }
    }

    /** Requires every visible row and the logical/pixel anchor to stay exact. */
    protected fun assertSameViewport(
        phase: String,
        expected: ConversationViewportEvidence,
        actual: ConversationViewportEvidence,
    ) {
        assertEquals("$phase changed account owner", expected.accountRef, actual.accountRef)
        assertEquals("$phase changed scroll owner", expected.mode, actual.mode)
        assertLogicalAnchor(phase, expected, actual)
        assertEquals("$phase changed viewport start", expected.viewportStartOffsetPx, actual.viewportStartOffsetPx)
        assertEquals("$phase changed viewport end", expected.viewportEndOffsetPx, actual.viewportEndOffsetPx)
        assertEquals("$phase changed viewport height", expected.viewportHeightPx, actual.viewportHeightPx)
        assertEquals("$phase changed physical tail reachability", expected.canScrollForward, actual.canScrollForward)
        assertEquals("$phase moved or resized visible rows", expected.visibleItems, actual.visibleItems)
    }

    /** Compares the issue's primary stable-message identity and pixel-offset contract. */
    protected fun assertLogicalAnchor(
        phase: String,
        expected: ConversationViewportEvidence,
        actual: ConversationViewportEvidence,
    ) {
        assertEquals("$phase changed logical message", expected.anchor.messageId, actual.anchor.messageId)
        assertEquals("$phase changed logical item", expected.anchor.itemId, actual.anchor.itemId)
        assertEquals("$phase changed lazy index", expected.anchor.listIndex, actual.anchor.listIndex)
        assertEquals("$phase changed pixel offset", expected.anchor.pixelOffset, actual.anchor.pixelOffset)
    }

    /** Rejects an automatic list write attributed only to same-row voice presentation state. */
    protected fun assertNoScrollWrites(
        phase: String,
        evidence: RecordingConversationScrollEvidenceSink,
    ) {
        assertEquals(
            "$phase must not issue a list write",
            emptyList<ConversationScrollWriteEvidence>(),
            evidence.writes,
        )
    }

    /** Proves authoritative windows neither duplicate nor reorder any message. */
    protected fun assertTimelineOrder(
        fixture: VoiceConversationFixture,
        expected: List<TimelineMessageRecordFfi>,
    ) {
        assertEquals(
            expected.map { it.messageIdHex },
            fixture.controller.timeline.map { it.record.messageIdHex },
        )
    }

    /** Stops playback/controller work and removes only cache files created by this runtime. */
    protected fun closeFixture(
        fixture: VoiceConversationFixture,
        runtime: ControlledVoicePresentationRuntime,
    ) {
        runtime.releaseForCleanup()
        fixture.controller.onCleared()
        awaitOpenedTimelineSubscriptionsClosed(fixture.scripted)
        runtime.deletePublishedFiles()
    }

    /** One screen generation with its own account, runtime, evidence, and shell snapshot. */
    protected data class ConversationMount(
        val fixture: VoiceConversationFixture,
        val runtime: VoiceAttachmentPresentationRuntime,
        val evidence: RecordingConversationScrollEvidenceSink,
        val savedScrollSnapshot: MutableState<ConversationScrollSnapshot?>,
        val onSaveScrollSnapshot: (ConversationScrollSnapshot?) -> Unit,
    )

    /** Host state that reproduces shell-owned restoration, route replacement, and explicit focus. */
    protected inner class ConversationTestHost(
        private val mounted: MutableState<Boolean>,
        private val activeMount: MutableState<ConversationMount>,
        private val focusMessageId: MutableState<String?>,
        private val focusMessageRequestId: MutableState<Long>,
    ) {
        /** Disposes the active screen and returns the snapshot emitted by its shell callback. */
        fun leave(): ConversationScrollSnapshot {
            composeRule.runOnUiThread { mounted.value = false }
            composeRule.waitForIdle()
            return requireNotNull(activeMount.value.savedScrollSnapshot.value) {
                "history disposal must publish a restore snapshot"
            }
        }

        /** Mounts a fresh account/controller/runtime generation with explicit shell restoration. */
        fun replaceWith(
            fixture: VoiceConversationFixture,
            runtime: VoiceAttachmentPresentationRuntime,
            evidence: RecordingConversationScrollEvidenceSink,
            restoredSnapshot: ConversationScrollSnapshot,
        ) {
            if (mounted.value) {
                composeRule.runOnUiThread { mounted.value = false }
                composeRule.waitForIdle()
            }
            evidence.clearViewports()
            evidence.clearWrites()
            composeRule.runOnUiThread {
                focusMessageId.value = null
                focusMessageRequestId.value = 0L
                activeMount.value =
                    ConversationMount(
                        fixture = fixture,
                        runtime = runtime,
                        evidence = evidence,
                        savedScrollSnapshot = mutableStateOf(restoredSnapshot),
                        onSaveScrollSnapshot = {},
                    )
                mounted.value = true
            }
            composeRule.waitForIdle()
        }

        /** Issues a newer explicit message-focus intent and waits for history ownership to settle. */
        fun focus(
            messageId: String,
            evidence: RecordingConversationScrollEvidenceSink,
        ) {
            composeRule.runOnIdle {
                focusMessageId.value = messageId
                focusMessageRequestId.value += 1L
            }
            try {
                awaitMountedConversationCondition("message focus") { evidence.writes.isNotEmpty() }
            } catch (failure: AssertionError) {
                val controller = activeMount.value.fixture.controller
                throw AssertionError(
                    "focus did not reach the scroll writer; " +
                        "targetLoaded=${controller.timeline.any { it.record.messageIdHex == messageId }}, " +
                        "latestMode=${evidence.latestViewport().mode}",
                    failure,
                )
            }
            composeRule.waitForIdle()
            evidence.awaitReadingHistory()
        }
    }

    /** Authoritative records and media references for one independently namespaced scenario. */
    protected data class VoiceConversationFixture(
        val controller: ConversationController,
        val scripted: ScriptedConversationLiveSubscriptions,
        val subscription: ScriptedConversationTimelineSubscription,
        val chat: ChatListItem,
        val records: List<TimelineMessageRecordFfi>,
        val references: Map<String, MediaAttachmentReferenceFfi>,
        val idOffset: Int,
    ) {
        val voiceMessageIds: List<String>
            get() = records.map { it.messageIdHex }.filter(references::containsKey)

        /** Appends one stable, non-media authoritative record after this scenario's initial window. */
        fun incomingRecord(plaintext: String): TimelineMessageRecordFfi =
            timelineRecord(
                messageId = messageId(records.size, idOffset),
                timelineAt = (idOffset + records.size + 1).toULong(),
                plaintext = plaintext,
            )
    }

    /** One independently controlled materialization and codec-hydration state machine. */
    internal inner class VoiceControl(
        val messageId: String,
        materializationAttemptCount: Int,
        val hydratedDurationMs: Int = HYDRATED_DURATION_MS,
    ) {
        private val attemptStarted = List(materializationAttemptCount) { CompletableDeferred<Unit>() }
        private val attemptResults = List(materializationAttemptCount) { CompletableDeferred<MaterializationResult>() }
        val waveformStarted = CompletableDeferred<Unit>()
        val waveformReleased = CompletableDeferred<Unit>()
        val waveformCompleted = CompletableDeferred<Unit>()
        val durationStarted = CompletableDeferred<Unit>()
        val durationReleased = CompletableDeferred<Unit>()
        val durationCompleted = CompletableDeferred<Unit>()
        private val nextAttempt = AtomicInteger(0)

        val materializationAttempts: Int
            get() = nextAttempt.get()

        /** Claims the next configured attempt and suspends until the test publishes its outcome. */
        suspend fun awaitMaterializationOutcome(): MaterializationResult {
            val index = nextAttempt.getAndIncrement()
            check(index in attemptResults.indices) { "unexpected materialization attempt $index for $messageId" }
            attemptStarted[index].complete(Unit)
            return attemptResults[index].await()
        }

        /** Waits until production reaches the selected materialization attempt. */
        fun awaitMaterializationAttempt(index: Int) {
            composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) { attemptStarted[index].isCompleted }
        }

        /** Releases one controlled attempt into the real cache-publication boundary. */
        fun succeedMaterialization(index: Int) {
            attemptResults[index].complete(MaterializationResult.Success)
        }

        /** Releases one controlled attempt with a failure or explicit cancellation. */
        fun failMaterialization(
            index: Int,
            failure: Throwable,
        ) {
            attemptResults[index].complete(MaterializationResult.Failure(failure))
        }

        /** Waits until both waveform and duration work have observed the published file. */
        fun awaitHydrationStarted() {
            composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
                waveformStarted.isCompleted && durationStarted.isCompleted
            }
        }

        /** Lets waveform and duration complete together after their start has been recorded. */
        fun releaseHydration() {
            waveformReleased.complete(Unit)
            durationReleased.complete(Unit)
        }

        /** Waits until both post-materialization state publications have completed. */
        fun awaitHydrationCompleted() {
            composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
                waveformCompleted.isCompleted && durationCompleted.isCompleted
            }
            composeRule.waitForIdle()
        }

        /** Releases every pending gate so an earlier assertion failure cannot leak Compose work. */
        fun releaseForCleanup() {
            attemptResults.forEach { result ->
                result.complete(MaterializationResult.Failure(CancellationException("voice test cleanup")))
            }
            waveformReleased.complete(Unit)
            durationReleased.complete(Unit)
        }
    }

    /** Thread-safe recorder shared by Compose callbacks and Robolectric's test thread. */
    protected inner class RecordingConversationScrollEvidenceSink : ConversationScrollEvidenceSink {
        private val viewports = CopyOnWriteArrayList<ConversationViewportEvidence>()
        val writes = CopyOnWriteArrayList<ConversationScrollWriteEvidence>()

        override fun onViewport(snapshot: ConversationViewportEvidence) {
            viewports += snapshot
        }

        override fun onWrite(write: ConversationScrollWriteEvidence) {
            writes += write
        }

        /** Returns the current append-only viewport cursor for transient-frame assertions. */
        fun checkpoint(): Int = viewports.size

        /** Returns every viewport emitted after [checkpoint]. */
        fun viewportsSince(checkpoint: Int): List<ConversationViewportEvidence> = viewports.drop(checkpoint)

        /** Clears only command evidence after an intentional initial or incoming anchor write. */
        fun clearWrites() {
            writes.clear()
        }

        /** Clears viewport history before remounting while retaining the same sink instance. */
        fun clearViewports() {
            viewports.clear()
        }

        /** Returns the most recently measured production viewport. */
        fun latestViewport(): ConversationViewportEvidence = requireNotNull(viewports.lastOrNull())

        /** Waits for the requested logical message to own the production read anchor. */
        fun awaitAnchor(messageId: String): ConversationViewportEvidence =
            awaitViewport {
                it.anchor.messageId == messageId
            }

        /** Waits until the requested keyed message is present in the measured lazy viewport. */
        fun awaitVisibleMessage(messageId: String): ConversationViewportEvidence =
            awaitViewport { snapshot -> snapshot.visibleItems.any { it.key == "msg:$messageId" } }

        /** Waits until an explicit jump has settled back into durable history ownership. */
        fun awaitReadingHistory(): ConversationViewportEvidence =
            awaitViewport {
                it.mode is ConversationScrollMode.ReadingHistory
            }

        /** Waits for a viewport satisfying [predicate] and returns that latest stable snapshot. */
        private fun awaitViewport(predicate: (ConversationViewportEvidence) -> Boolean): ConversationViewportEvidence {
            composeRule.waitUntil(timeoutMillis = PHASE_TIMEOUT_MILLIS) {
                viewports.lastOrNull()?.let(predicate) == true
            }
            composeRule.waitForIdle()
            return requireNotNull(viewports.lastOrNull())
        }
    }

    /** Controlled producer outcome before successful cache publication. */
    internal sealed interface MaterializationResult {
        data object Success : MaterializationResult

        data class Failure(
            val failure: Throwable,
        ) : MaterializationResult
    }

    protected companion object {
        const val MESSAGE_COUNT = 36
        const val HISTORY_VOICE_INDEX = 18
        const val HISTORY_OFFSET_PX = 17
        const val VOICE_ACTION_INDICATION_SETTLE_MILLIS = 500L
        const val PHASE_TIMEOUT_MILLIS = 5_000L
        const val HYDRATED_DURATION_MS = 12_345
        const val LONG_DURATION_MS = 3_599_000

        /** Produces deterministic, non-overlapping hex ids for independent test cache namespaces. */
        fun messageId(
            index: Int,
            idOffset: Int,
        ): String = "%064x".format(idOffset + index + 1)

        /** Creates one attachment reference whose cache path is unique to its test scenario and row. */
        fun voiceReference(
            index: Int,
            idOffset: Int,
        ) = MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "%064x".format(idOffset + index + 10_000),
            plaintextSha256 = "%064x".format(idOffset + index + 20_000),
            nonceHex = "%048x".format(idOffset + index + 30_000),
            fileName = "voice-anchor-$idOffset-$index.wav",
            mediaType = "audio/wav",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = (idOffset + index + 1).toULong(),
            dim = null,
            thumbhash = null,
        )
    }
}

/** Routes each message to independent gates while publishing bytes through the real cache. */
internal class ControlledVoicePresentationRuntime(
    private val controls: Map<String, ConversationVoiceDownloadAnchorTestBase.VoiceControl>,
) : VoiceAttachmentPresentationRuntime {
    private val fileOwners = ConcurrentHashMap<String, String>()
    private val publishedFiles = CopyOnWriteArrayList<File>()
    private val mutablePlaybackState = MutableStateFlow(VoicePlaybackController.PlaybackState())
    private val mutablePlaybackFailures = MutableSharedFlow<VoicePlaybackController.PlaybackFailure>()

    override val playbackState: StateFlow<VoicePlaybackController.PlaybackState> = mutablePlaybackState
    override val playbackFailures: SharedFlow<VoicePlaybackController.PlaybackFailure> = mutablePlaybackFailures

    override suspend fun materialize(request: VoiceAttachmentMaterializationRequest): File =
        materializeVoiceAttachmentSource(
            context = request.context,
            messageIdHex = request.messageIdHex,
            attachmentIndex = request.attachmentIndex,
            reference = request.reference,
            resolveSource = {
                when (val result = controls.getValue(request.messageIdHex).awaitMaterializationOutcome()) {
                    ConversationVoiceDownloadAnchorTestBase.MaterializationResult.Success ->
                        AttachmentPlaintext.Bytes(MINIMAL_VOICE_AUDIO_BYTES.copyOf())
                    is ConversationVoiceDownloadAnchorTestBase.MaterializationResult.Failure -> throw result.failure
                }
            },
        ).also { file ->
            fileOwners[file.absolutePath] = request.messageIdHex
            publishedFiles += file
        }

    override suspend fun waveform(file: File): FloatArray {
        val control = controls.getValue(requireNotNull(fileOwners[file.absolutePath]))
        control.waveformStarted.complete(Unit)
        control.waveformReleased.await()
        control.waveformCompleted.complete(Unit)
        return FloatArray(64) { index -> 0.2f + (index % 5) * 0.1f }
    }

    override suspend fun durationMs(file: File): Int {
        val control = controls.getValue(requireNotNull(fileOwners[file.absolutePath]))
        control.durationStarted.complete(Unit)
        control.durationReleased.await()
        control.durationCompleted.complete(Unit)
        return control.hydratedDurationMs
    }

    override suspend fun play(
        key: String,
        file: File,
        ownerKey: String,
    ): VoicePlaybackController.PlaybackStartResult {
        val control = controls.getValue(requireNotNull(fileOwners[file.absolutePath]))
        mutablePlaybackState.value =
            VoicePlaybackController.PlaybackState(
                key = key,
                isPlaying = true,
                durationMs = control.hydratedDurationMs,
                speed = 1f,
            )
        return VoicePlaybackController.PlaybackStartResult.Started
    }

    override fun pause() {
        mutablePlaybackState.value = mutablePlaybackState.value.copy(isPlaying = false)
    }

    override fun seekTo(
        key: String,
        positionMs: Int,
    ) {
        if (mutablePlaybackState.value.key == key) {
            mutablePlaybackState.value = mutablePlaybackState.value.copy(positionMs = positionMs)
        }
    }

    override fun cycleSpeed() {
        val current = mutablePlaybackState.value
        mutablePlaybackState.value = current.copy(speed = if (current.speed == 1f) 1.5f else 1f)
    }

    /** Resets only this fixture's playback state before remount or cleanup. */
    fun stopPlayback() {
        mutablePlaybackState.value = VoicePlaybackController.PlaybackState()
    }

    /** Releases controlled producers and resets the process-scoped fake playback state. */
    fun releaseForCleanup() {
        controls.values.forEach { it.releaseForCleanup() }
        stopPlayback()
    }

    /** Deletes only files this test runtime published under its unique message ids. */
    fun deletePublishedFiles() {
        publishedFiles.distinctBy { it.absolutePath }.forEach { file ->
            check(!file.exists() || file.delete()) { "failed to delete test voice cache ${file.name}" }
        }
    }
}

private val MINIMAL_VOICE_AUDIO_BYTES = byteArrayOf(1)
