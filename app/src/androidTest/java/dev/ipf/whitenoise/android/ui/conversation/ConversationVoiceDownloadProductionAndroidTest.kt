package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshot
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.AutomaticBacklogStoppedException
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationGroupStateSubscriptionHandle
import dev.ipf.whitenoise.android.state.ConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ConversationTimelineSubscriptionHandle
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.LocalVoiceAttachmentPresentationRuntime
import dev.ipf.whitenoise.android.ui.conversation.media.VoiceAttachmentMaterializationRequest
import dev.ipf.whitenoise.android.ui.conversation.media.VoiceAttachmentPresentationRuntime
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVoiceAttachmentSource
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleColumnTestTag
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device/emulator acceptance for production ConversationScreen voice downloads.
 *
 * The companion renderer tests isolate Android text metrics. These tests keep
 * the actual conversation controller, LazyColumn, scroll coordinator,
 * MediaVoiceBubble, and injectable materialization/runtime effects in the path.
 */
@RunWith(AndroidJUnit4::class)
class ConversationVoiceDownloadProductionAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A newer explicit history jump remains authoritative when the held download completes. */
    @Test
    @Suppress("LongMethod") // One production-owner sequence spans load, intent, hydration, and geometry assertions.
    fun productionHistoryJumpWinsOverMaterializationAndHydrationCompletion() {
        val fixture = instrumentedConversationFixture(setOf(DEVICE_HISTORY_VOICE_INDEX), idOffset = 1_000)
        val voiceId = fixture.voiceMessageIds.single()
        val control = InstrumentedVoiceControl(voiceId)
        val runtime = InstrumentedVoiceRuntime(mapOf(voiceId to control))
        val evidence = InstrumentedConversationEvidence()
        val focusMessageId = mutableStateOf<String?>(null)
        val focusRequestId = mutableStateOf(0L)
        try {
            fixture.awaitLoaded()
            showProductionConversation(
                fixture = fixture,
                runtime = runtime,
                evidence = evidence,
                restoredSnapshot = instrumentedHistorySnapshot(fixture, voiceId),
                focusMessageId = focusMessageId,
                focusRequestId = focusRequestId,
            )
            evidence.awaitAnchor(composeRule, voiceId)

            clickVoiceAction(voiceId, R.string.media_tap_to_download)
            control.awaitMaterializationStart(composeRule)
            assertVoiceActionTarget(voiceId, R.string.media_downloading)
            val downloadAnchor = evidence.latestViewport()
            val writeCheckpoint = evidence.writes.size
            val targetId = fixture.records[DEVICE_HISTORY_VOICE_INDEX - 5].messageIdHex
            composeRule.runOnIdle {
                focusMessageId.value = targetId
                focusRequestId.value += 1L
            }
            val afterNewerIntent =
                evidence.awaitReadingHistoryAfter(
                    composeRule = composeRule,
                    writeCheckpoint = writeCheckpoint,
                    previousAnchor = downloadAnchor.anchor,
                )
            evidence.clearWrites()
            val completionCheckpoint = evidence.checkpoint()

            control.completeMaterialization()
            control.awaitHydrationStart(composeRule)
            control.completeHydration()
            control.awaitHydrationCompletion(composeRule)
            composeRule.waitUntil(timeoutMillis = DEVICE_TIMEOUT_MS) {
                composeRule.onAllNodesWithText("59:59", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            evidence.assertViewportStayedFixed(afterNewerIntent, completionCheckpoint)
            assertTrue(evidence.latestViewport().mode is ConversationScrollMode.ReadingHistory)
            assertEquals(emptyList<ConversationScrollWriteEvidence>(), evidence.writes)
        } finally {
            closeInstrumentedFixture(fixture, runtime)
        }
    }

    /** Incoming-tail ownership is the sole scroll; two concurrent completions cannot add another. */
    @Test
    @Suppress("LongMethod") // One production-owner sequence spans append, concurrent hydration, and final geometry.
    fun productionTailStaysExactAcrossConcurrentVoiceCompletionAfterIncomingAppend() {
        val voiceIndices = setOf(DEVICE_MESSAGE_COUNT - 2, DEVICE_MESSAGE_COUNT - 1)
        val fixture = instrumentedConversationFixture(voiceIndices, idOffset = 2_000)
        val controls = fixture.voiceMessageIds.associateWith { InstrumentedVoiceControl(it) }
        val runtime = InstrumentedVoiceRuntime(controls)
        val evidence = InstrumentedConversationEvidence()
        try {
            fixture.awaitLoaded()
            showProductionConversation(
                fixture = fixture,
                runtime = runtime,
                evidence = evidence,
                restoredSnapshot = null,
            )
            evidence.awaitFollowingTailAt(composeRule, fixture.records.last().messageIdHex)
            fixture.voiceMessageIds.forEach { voiceId ->
                clickVoiceAction(voiceId, R.string.media_tap_to_download)
                controls.getValue(voiceId).awaitMaterializationStart(composeRule)
            }
            evidence.clearWrites()

            val incoming = fixture.incomingRecord("device incoming tail")
            fixture.subscription.emit(
                TimelinePageFfi(
                    messages = fixture.records + incoming,
                    hasMoreBefore = false,
                    hasMoreAfter = false,
                ),
            )
            composeRule.waitUntil(timeoutMillis = DEVICE_TIMEOUT_MS) {
                fixture.controller.timeline.size == fixture.records.size + 1
            }
            val afterIncoming = evidence.awaitFollowingTailAt(composeRule, incoming.messageIdHex)
            evidence.clearWrites()
            val completionCheckpoint = evidence.checkpoint()

            controls.values.forEach(InstrumentedVoiceControl::completeMaterialization)
            controls.values.forEach { it.awaitHydrationStart(composeRule) }
            controls.values.forEach(InstrumentedVoiceControl::completeHydration)
            controls.values.forEach { it.awaitHydrationCompletion(composeRule) }
            composeRule.waitForIdle()

            evidence.assertViewportStayedFixed(afterIncoming, completionCheckpoint)
            assertTrue(evidence.latestViewport().mode is ConversationScrollMode.FollowingTail)
            assertFalse(evidence.latestViewport().canScrollForward)
            assertEquals(emptyList<ConversationScrollWriteEvidence>(), evidence.writes)
        } finally {
            closeInstrumentedFixture(fixture, runtime)
        }
    }

    /**
     * Keeps real failure, cancellation, retry, cache re-entry, and a faithful
     * saved-route owner reconstruction from issuing a voice-driven list write.
     * This discards every production process owner except the serialized route
     * keys and the existing disk cache; it does not claim an OS-killed process.
     */
    @Test
    @Suppress("LongMethod") // One device contract must span all real materialization outcomes and cold-owner rebuild.
    fun productionFailureCancellationRetryAndFreshOwnerCacheReentryStayAnchored() {
        val idOffset = 3_000
        val fixture = instrumentedConversationFixture(setOf(DEVICE_HISTORY_VOICE_INDEX), idOffset)
        val voiceId = fixture.voiceMessageIds.single()
        val control = InstrumentedVoiceControl(voiceId, materializationAttemptCount = 3)
        val runtime = InstrumentedVoiceRuntime(mapOf(voiceId to control))
        val evidence = InstrumentedConversationEvidence()
        val firstSavedState = SavedStateHandle()
        val firstHolder = MainShellStateHolder(fixture.appState, firstSavedState)
        var firstControllerClosed = false
        var firstHolderReleased = false
        var reconstructedFixture: InstrumentedConversationFixture? = null
        var reconstructedRuntime: InstrumentedVoiceRuntime? = null
        var reconstructedHolder: MainShellStateHolder? = null
        var reconstructedChats: ChatsController? = null
        var reconstructedControllerOwned = false
        var productionHost: InstrumentedConversationHost? = null
        try {
            fixture.awaitLoaded()
            firstHolder.selectedChat.value = fixture.chat
            firstHolder.persistConversationRoute(fixture.controller.boundAccountRef)
            val host =
                showProductionConversation(
                    fixture = fixture,
                    runtime = runtime,
                    evidence = evidence,
                    restoredSnapshot = instrumentedHistorySnapshot(fixture, voiceId),
                )
            productionHost = host
            evidence.awaitAnchor(composeRule, voiceId)

            clickVoiceAction(voiceId, R.string.media_tap_to_download)
            control.awaitMaterializationStart(composeRule, attempt = 0)
            assertVoiceActionTarget(voiceId, R.string.media_downloading)
            val downloading = evidence.latestViewport()
            evidence.clearWrites()
            val failureCheckpoint = evidence.checkpoint()

            control.failMaterialization(0, IOException("controlled device voice failure"))
            assertVoiceActionTarget(voiceId, R.string.voice_message_failed)
            evidence.assertViewportStayedFixed(downloading, failureCheckpoint)
            assertEquals(emptyList<ConversationScrollWriteEvidence>(), evidence.writes)

            evidence.clearWrites()
            val cancellationCheckpoint = evidence.checkpoint()
            clickVoiceAction(voiceId, R.string.voice_message_failed)
            control.awaitMaterializationStart(composeRule, attempt = 1)
            control.failMaterialization(1, AutomaticBacklogStoppedException())
            assertVoiceActionTarget(voiceId, R.string.media_tap_to_download)
            evidence.assertViewportStayedFixed(downloading, cancellationCheckpoint)
            assertEquals(emptyList<ConversationScrollWriteEvidence>(), evidence.writes)

            evidence.clearWrites()
            val retryCheckpoint = evidence.checkpoint()
            clickVoiceAction(voiceId, R.string.media_tap_to_download)
            control.awaitMaterializationStart(composeRule, attempt = 2)
            control.succeedMaterialization(2)
            control.awaitHydrationStart(composeRule)
            control.completeHydration()
            control.awaitHydrationCompletion(composeRule)
            composeRule.waitUntil(timeoutMillis = DEVICE_TIMEOUT_MS) {
                composeRule.onAllNodesWithText("59:59", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertVoiceActionTarget(voiceId, R.string.voice_message_pause)
            evidence.assertViewportStayedFixed(downloading, retryCheckpoint)
            assertEquals(emptyList<ConversationScrollWriteEvidence>(), evidence.writes)
            assertEquals(3, control.materializationAttempts)

            val savedSnapshot = requireNotNull(host.leave())
            assertEquals(voiceId, savedSnapshot.anchorMessageIdHex)
            val savedAccountRef =
                requireNotNull(firstSavedState.get<String>("main_shell_selected_account_ref"))
            val savedGroupId = requireNotNull(firstSavedState.get<String>("main_shell_selected_group_id"))
            assertEquals(fixture.controller.boundAccountRef, savedAccountRef)
            assertEquals(fixture.chat.group.groupIdHex, savedGroupId)

            runtime.releaseForCleanup()
            fixture.controller.onCleared()
            firstControllerClosed = true
            firstHolder.release()
            firstHolderReleased = true

            val rebuilt =
                instrumentedConversationFixture(
                    voiceIndices = setOf(DEVICE_HISTORY_VOICE_INDEX),
                    idOffset = idOffset,
                    clearVoiceCacheOnCreate = false,
                )
            reconstructedFixture = rebuilt
            rebuilt.awaitLoaded()
            val cacheControl = InstrumentedVoiceControl(voiceId, materializationAttemptCount = 0)
            val cacheRuntime = InstrumentedVoiceRuntime(mapOf(voiceId to cacheControl))
            reconstructedRuntime = cacheRuntime
            val restoredHolder =
                MainShellStateHolder(
                    rebuilt.appState,
                    SavedStateHandle(
                        mapOf(
                            "main_shell_selected_account_ref" to savedAccountRef,
                            "main_shell_selected_group_id" to savedGroupId,
                        ),
                    ),
                )
            reconstructedHolder = restoredHolder
            val restoredChats =
                ChatsController(
                    appState = rebuilt.appState,
                    initialAccountRef = savedAccountRef,
                    initialLocalSnapshot = instrumentedLocalSnapshot(rebuilt, savedAccountRef),
                    memberSnapshotLoader = { _, _ -> emptyList() },
                )
            reconstructedChats = restoredChats
            restoredHolder.restoreConversationIfReady(restoredChats, savedAccountRef)
            val restoredChat = requireNotNull(restoredHolder.selectedChat.value)
            assertEquals(savedGroupId, restoredChat.group.groupIdHex)
            val restoredController =
                restoredHolder.conversationController(
                    chatId = savedGroupId,
                    accountRef = savedAccountRef,
                    runtimeGeneration = 1,
                    presentationKey = 1,
                    create = { rebuilt.controller },
                )
            reconstructedControllerOwned = true
            assertSame(rebuilt.controller, restoredController)

            val cacheEvidence = InstrumentedConversationEvidence()
            host.replaceWith(
                fixture = rebuilt.copy(chat = restoredChat),
                runtime = cacheRuntime,
                evidence = cacheEvidence,
                restoredSnapshot = null,
            )
            val routeOnlyTail = cacheEvidence.awaitFollowingTailAt(composeRule, rebuilt.records.last().messageIdHex)
            val focusWriteCheckpoint = cacheEvidence.writes.size
            host.focus(voiceId)
            val cacheAnchor =
                cacheEvidence.awaitReadingHistoryAfter(
                    composeRule = composeRule,
                    writeCheckpoint = focusWriteCheckpoint,
                    previousAnchor = routeOnlyTail.anchor,
                )
            assertTrue(
                "centered focus must keep the cache-backed voice row visible",
                cacheAnchor.visibleItems.any { it.key == "msg:$voiceId" },
            )
            cacheEvidence.clearWrites()
            val cacheCheckpoint = cacheEvidence.checkpoint()
            cacheControl.awaitHydrationStart(composeRule)
            assertVoiceActionTarget(voiceId, R.string.voice_message_play)
            cacheControl.completeHydration()
            cacheControl.awaitHydrationCompletion(composeRule)

            cacheEvidence.assertViewportStayedFixed(cacheAnchor, cacheCheckpoint)
            assertEquals(emptyList<ConversationScrollWriteEvidence>(), cacheEvidence.writes)
            assertEquals(0, cacheControl.materializationAttempts)
            assertEquals(
                rebuilt.records.map(TimelineMessageRecordFfi::messageIdHex),
                rebuilt.controller.timeline.map { it.record.messageIdHex },
            )
        } finally {
            productionHost?.leave()
            runtime.releaseForCleanup()
            reconstructedRuntime?.releaseForCleanup()
            if (!firstControllerClosed) fixture.controller.onCleared()
            reconstructedHolder?.release()
            if (!reconstructedControllerOwned) reconstructedFixture?.controller?.onCleared()
            reconstructedChats?.onCleared()
            if (!firstHolderReleased) firstHolder.release()
            runtime.deleteFiles()
            reconstructedRuntime?.deleteFiles()
        }
    }

    /** Installs the real conversation with only its platform work boundary controlled. */
    private fun showProductionConversation(
        fixture: InstrumentedConversationFixture,
        runtime: VoiceAttachmentPresentationRuntime,
        evidence: InstrumentedConversationEvidence,
        restoredSnapshot: ConversationScrollSnapshot?,
        focusMessageId: MutableState<String?> = mutableStateOf(null),
        focusRequestId: MutableState<Long> = mutableStateOf(0L),
    ): InstrumentedConversationHost {
        val mounted = mutableStateOf(true)
        val activeMount =
            mutableStateOf(
                InstrumentedConversationMount(
                    fixture = fixture,
                    runtime = runtime,
                    evidence = evidence,
                    restoredSnapshot = mutableStateOf(restoredSnapshot),
                ),
            )
        composeRule.setContent {
            val mount = activeMount.value
            CompositionLocalProvider(
                LocalVoiceAttachmentPresentationRuntime provides mount.runtime,
                LocalConversationScrollEvidenceSink provides mount.evidence,
            ) {
                if (mounted.value) {
                    WhiteNoiseTheme {
                        ConversationScreen(
                            appState = mount.fixture.appState,
                            chat = mount.fixture.chat,
                            controller = mount.fixture.controller,
                            onBack = {},
                            focusMessageId = focusMessageId.value,
                            focusMessageRequestId = focusRequestId.value,
                            restoredScrollSnapshot = mount.restoredSnapshot.value,
                            onSaveScrollSnapshot = { mount.restoredSnapshot.value = it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return InstrumentedConversationHost(
            mounted = mounted,
            activeMount = activeMount,
            focusMessageId = focusMessageId,
            focusRequestId = focusRequestId,
        )
    }

    /** Clicks one localized action under the requested production message row. */
    private fun clickVoiceAction(
        messageId: String,
        descriptionResource: Int,
    ) {
        composeRule
            .onNode(voiceActionMatcher(context, messageId, descriptionResource), useUnmergedTree = true)
            .performClick()
    }

    /** Requires the localized production action to own a full 48dp TalkBack target. */
    private fun assertVoiceActionTarget(
        messageId: String,
        descriptionResource: Int,
    ) {
        val matcher = voiceActionMatcher(context, messageId, descriptionResource)
        composeRule.waitUntil(timeoutMillis = DEVICE_TIMEOUT_MS) {
            composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        val action =
            composeRule
                .onNode(matcher, useUnmergedTree = true)
                .fetchSemanticsNode()
        val minimumTargetPx = composeRule.density.run { 48.dp.toPx() }
        assertTrue(action.touchBoundsInRoot.width >= minimumTargetPx)
        assertTrue(action.touchBoundsInRoot.height >= minimumTargetPx)
    }

    /** Releases Compose work before deleting only this test runtime's temporary files. */
    private fun closeInstrumentedFixture(
        fixture: InstrumentedConversationFixture,
        runtime: InstrumentedVoiceRuntime,
    ) {
        runtime.releaseForCleanup()
        composeRule.waitForIdle()
        fixture.controller.onCleared()
        runtime.deleteFiles()
    }

    /** One mounted production-screen generation and its shell-owned restore value. */
    private data class InstrumentedConversationMount(
        val fixture: InstrumentedConversationFixture,
        val runtime: VoiceAttachmentPresentationRuntime,
        val evidence: InstrumentedConversationEvidence,
        val restoredSnapshot: MutableState<ConversationScrollSnapshot?>,
    )

    /** Replaces every process-owned screen input while retaining the Compose test Activity. */
    private inner class InstrumentedConversationHost(
        private val mounted: MutableState<Boolean>,
        private val activeMount: MutableState<InstrumentedConversationMount>,
        private val focusMessageId: MutableState<String?>,
        private val focusRequestId: MutableState<Long>,
    ) {
        /** Disposes the real screen and returns the snapshot emitted by its production callback. */
        fun leave(): ConversationScrollSnapshot? {
            composeRule.runOnIdle { mounted.value = false }
            composeRule.waitForIdle()
            return activeMount.value.restoredSnapshot.value
        }

        /** Mounts a fully fresh app/controller/runtime generation with no retained process snapshot. */
        fun replaceWith(
            fixture: InstrumentedConversationFixture,
            runtime: VoiceAttachmentPresentationRuntime,
            evidence: InstrumentedConversationEvidence,
            restoredSnapshot: ConversationScrollSnapshot?,
        ) {
            if (mounted.value) leave()
            evidence.clearViewports()
            evidence.clearWrites()
            composeRule.runOnIdle {
                focusMessageId.value = null
                focusRequestId.value = 0L
                activeMount.value =
                    InstrumentedConversationMount(
                        fixture = fixture,
                        runtime = runtime,
                        evidence = evidence,
                        restoredSnapshot = mutableStateOf(restoredSnapshot),
                    )
                mounted.value = true
            }
            composeRule.waitForIdle()
        }

        /** Issues a new production message-focus request after route-only restoration has settled. */
        fun focus(messageId: String) {
            composeRule.runOnIdle {
                focusMessageId.value = messageId
                focusRequestId.value += 1L
            }
        }
    }
}

/** A production-screen fixture with a controllable authoritative timeline subscription. */
private data class InstrumentedConversationFixture(
    val appState: WhiteNoiseAppState,
    val controller: ConversationController,
    val subscription: InstrumentedTimelineSubscription,
    val chat: ChatListItem,
    val records: List<TimelineMessageRecordFfi>,
    val references: Map<String, MediaAttachmentReferenceFfi>,
    val idOffset: Int,
) {
    val voiceMessageIds: List<String>
        get() = records.map(TimelineMessageRecordFfi::messageIdHex).filter(references::containsKey)

    /** Waits for the controller's initial real subscription snapshot. */
    fun awaitLoaded() {
        val deadline = System.nanoTime() + DEVICE_TIMEOUT_MS * 1_000_000
        while (controller.timeline.size != records.size && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        check(controller.timeline.size == records.size) { "instrumented conversation did not load" }
    }

    /** Creates one authoritative non-media append after the initial window. */
    fun incomingRecord(plaintext: String): TimelineMessageRecordFfi =
        instrumentedTimelineRecord(
            index = records.size,
            idOffset = idOffset,
            plaintext = plaintext,
            media = null,
        )
}

/** Builds the same controller/subscription path used by production ConversationScreen. */
@Suppress("LongMethod") // Account, controller, roster, and authoritative timeline must remain one coherent fixture.
private fun instrumentedConversationFixture(
    voiceIndices: Set<Int>,
    idOffset: Int,
    clearVoiceCacheOnCreate: Boolean = true,
): InstrumentedConversationFixture {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val group = instrumentedGroup()
    val references =
        voiceIndices
            .associateWith { instrumentedVoiceReference(it, idOffset) }
            .mapKeys { (index, _) -> instrumentedMessageId(index, idOffset) }
    val records =
        List(DEVICE_MESSAGE_COUNT) { index ->
            instrumentedTimelineRecord(
                index = index,
                idOffset = idOffset,
                plaintext = if (index in voiceIndices) "instrumented voice $index" else "message $index",
                media = references[instrumentedMessageId(index, idOffset)],
            )
        }
    if (clearVoiceCacheOnCreate) {
        references.forEach { (messageId, reference) ->
            val exactCacheFile = instrumentedVoiceCacheFile(context, messageId, reference)
            check(!exactCacheFile.exists() || exactCacheFile.delete())
        }
    }
    val subscription =
        InstrumentedTimelineSubscription(
            TimelinePageFfi(
                messages = records,
                hasMoreBefore = false,
                hasMoreAfter = false,
            ),
        )
    val groupSubscription = InstrumentedGroupSubscription(group)
    val liveSubscriptions =
        ConversationLiveSubscriptions(
            openTimeline = { _, _, _ -> subscription },
            openGroupState = { _, _ -> groupSubscription },
        )
    val appState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InstrumentedDraftPersistence()),
            accountIdHexResolver = { DEVICE_ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = DEVICE_ACCOUNT_REF,
                        accountIdHex = DEVICE_ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = DEVICE_ACCOUNT_REF,
        ).also { state ->
            state.liveSubscriptionOverrides.conversation = liveSubscriptions
            MediaAutoDownloadNetwork.entries.forEach { network ->
                state.setMediaAutoDownload(MediaAutoDownloadType.Audio, network, enabled = false)
            }
        }
    val members = instrumentedMemberSnapshot()
    val controller =
        ConversationController(
            appState = appState,
            initialGroup = group,
            initialMemberSnapshot = members,
            groupRosterReader = { _, _ -> instrumentedRoster() },
            startOnConstruction = true,
        )
    appState.attachmentOpens.setDestination(
        AttachmentOpenDestination(
            accountRef = DEVICE_ACCOUNT_REF,
            groupIdHex = DEVICE_GROUP_ID,
            navigationGeneration = idOffset.toLong(),
        ),
    )
    return InstrumentedConversationFixture(
        appState = appState,
        controller = controller,
        subscription = subscription,
        chat =
            ChatListItem(
                group = group,
                latest = null,
                otherMemberAccount = null,
                memberCount = 1,
                memberSnapshot = members,
                projection = instrumentedChatListRow(records.last()),
            ),
        records = records,
        references = references,
        idOffset = idOffset,
    )
}

/** Captures a real history restore target using production list-index ownership. */
private fun instrumentedHistorySnapshot(
    fixture: InstrumentedConversationFixture,
    messageId: String,
): ConversationScrollSnapshot {
    val timelineIndex = fixture.records.indexOfFirst { it.messageIdHex == messageId }
    check(timelineIndex >= 0)
    return ConversationScrollSnapshot(
        firstVisibleItemIndex = timelineIndex + 1,
        firstVisibleItemScrollOffset = DEVICE_HISTORY_OFFSET_PX,
        anchorItemId = "msg:$messageId",
        anchorMessageIdHex = messageId,
    )
}

/** Rebuilds the local projection consumed after all previous process owners are discarded. */
private fun instrumentedLocalSnapshot(
    fixture: InstrumentedConversationFixture,
    accountRef: String,
): AccountSwitchLocalSnapshot =
    AccountSwitchLocalSnapshot(
        accountRef = accountRef,
        activeAccountIdHex = DEVICE_ACCOUNT_ID,
        rows = listOf(requireNotNull(fixture.chat.projection)),
        groups = listOf(fixture.chat.group),
        memberIds = emptyList(),
        profiles = emptyList(),
    )

/** Lightweight authoritative row that makes the saved group route resolvable from local state. */
private fun instrumentedChatListRow(lastRecord: TimelineMessageRecordFfi): ChatListRowFfi =
    ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = DEVICE_GROUP_ID,
        archived = false,
        pendingConfirmation = false,
        title = "Instrumented voice group",
        groupName = "Instrumented voice group",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = lastRecord.messageIdHex,
        lastReadTimelineAt = lastRecord.timelineAt,
        conversationCreatedAt = 1uL,
        activitySortAt = lastRecord.timelineAt,
        updatedAt = lastRecord.timelineAt,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.GROUP,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

/** Exact fixture-owned cache path; cleanup never touches another account's or message's file. */
private fun instrumentedVoiceCacheFile(
    context: Context,
    messageId: String,
    reference: MediaAttachmentReferenceFfi,
): File =
    File(
        File(context.cacheDir, MediaCacheDirs.VOICE),
        "$messageId-0-${reference.sourceEpoch}.wav",
    )

/** Matches a localized voice action only beneath one stable production message column. */
private fun voiceActionMatcher(
    context: Context,
    messageId: String,
    descriptionResource: Int,
): SemanticsMatcher =
    hasContentDescription(context.getString(descriptionResource)) and
        hasAnyAncestor(hasTestTag(messageBubbleColumnTestTag(messageId)))

/** Scriptable full-window source consumed by the real ConversationController pump. */
private class InstrumentedTimelineSubscription(
    private val initial: TimelinePageFfi,
) : ConversationTimelineSubscriptionHandle {
    private val windows = Channel<TimelinePageFfi>(Channel.UNLIMITED)

    /** Supplies the controller's synchronous first window before its live pump starts. */
    override fun snapshot(): TimelinePageFfi = initial

    /** Suspends the live pump until the fixture emits a full window or closes the source. */
    override suspend fun nextWindow(): TimelinePageFfi? = windows.receiveCatching().getOrNull()

    /** Returns an empty terminal page so backward pagination cannot add fixture rows. */
    override suspend fun paginateBackwards(count: UInt): TimelinePageFfi =
        TimelinePageFfi(
            messages = emptyList(),
            hasMoreBefore = false,
            hasMoreAfter = false,
        )

    /** Keeps forward pagination terminal so tail assertions cannot consume synthetic pages. */
    override suspend fun paginateForwards(count: UInt): TimelinePageFfi =
        TimelinePageFfi(
            messages = emptyList(),
            hasMoreBefore = false,
            hasMoreAfter = false,
        )

    /** Ends the live stream and unblocks a controller pump waiting in [nextWindow]. */
    override fun close() {
        windows.close()
    }

    /** Publishes one complete authoritative window to the real controller. */
    fun emit(page: TimelinePageFfi) {
        check(windows.trySend(page).isSuccess)
    }
}

/** Stable group state that remains suspended until the controller disposes it. */
private class InstrumentedGroupSubscription(
    private val group: AppGroupRecordFfi,
) : ConversationGroupStateSubscriptionHandle {
    private val closed = CompletableDeferred<Unit>()

    /** Supplies the stable group generation used for the fixture controller's initial projection. */
    override fun snapshot(): AppGroupRecordFfi = group

    /** Keeps the group-update pump suspended until the fixture closes its subscription. */
    override suspend fun next(): AppGroupRecordFfi? {
        closed.await()
        return null
    }

    /** Releases the suspended group pump without publishing a replacement generation. */
    override fun close() {
        closed.complete(Unit)
    }
}

/** Per-message gates for materialization plus waveform/duration publication. */
private class InstrumentedVoiceControl(
    val messageId: String,
    materializationAttemptCount: Int = 1,
) {
    private val materializationStarted = List(materializationAttemptCount) { CompletableDeferred<Unit>() }
    private val materializationResults =
        List(materializationAttemptCount) { CompletableDeferred<InstrumentedMaterializationResult>() }
    val waveformStarted = CompletableDeferred<Unit>()
    val durationStarted = CompletableDeferred<Unit>()
    val hydrationReleased = CompletableDeferred<Unit>()
    val waveformCompleted = CompletableDeferred<Unit>()
    val durationCompleted = CompletableDeferred<Unit>()
    private val nextMaterializationAttempt = AtomicInteger(0)

    val materializationAttempts: Int
        get() = nextMaterializationAttempt.get()

    /** Claims the next configured attempt and waits for its controlled source outcome. */
    suspend fun awaitMaterializationOutcome(): InstrumentedMaterializationResult {
        val attempt = nextMaterializationAttempt.getAndIncrement()
        check(attempt in materializationResults.indices) {
            "unexpected materialization attempt $attempt for $messageId"
        }
        materializationStarted[attempt].complete(Unit)
        return materializationResults[attempt].await()
    }

    /** Waits until MediaVoiceBubble reaches the injected external-work boundary. */
    fun awaitMaterializationStart(
        rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>,
        attempt: Int = 0,
    ) {
        rule.waitUntil(timeoutMillis = DEVICE_TIMEOUT_MS) { materializationStarted[attempt].isCompleted }
    }

    /** Releases the held materialization result. */
    fun completeMaterialization() {
        succeedMaterialization(0)
    }

    /** Publishes successful source bytes for one controlled attempt. */
    fun succeedMaterialization(attempt: Int) {
        materializationResults[attempt].complete(InstrumentedMaterializationResult.Success)
    }

    /** Publishes a real failure or cancellation through the cache-publication boundary. */
    fun failMaterialization(
        attempt: Int,
        failure: Throwable,
    ) {
        materializationResults[attempt].complete(InstrumentedMaterializationResult.Failure(failure))
    }

    /** Waits for both production hydration effects to consume the published file. */
    fun awaitHydrationStart(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>) {
        rule.waitUntil(timeoutMillis = DEVICE_TIMEOUT_MS) {
            waveformStarted.isCompleted && durationStarted.isCompleted
        }
    }

    /** Releases both real MediaVoiceBubble hydration effects. */
    fun completeHydration() {
        hydrationReleased.complete(Unit)
    }

    /** Waits until waveform and duration state publications both complete. */
    fun awaitHydrationCompletion(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>) {
        rule.waitUntil(timeoutMillis = DEVICE_TIMEOUT_MS) {
            waveformCompleted.isCompleted && durationCompleted.isCompleted
        }
        rule.waitForIdle()
    }

    /** Prevents a failed assertion from leaking a suspended coroutine into the next test. */
    fun releaseForCleanup() {
        materializationResults.forEach { result ->
            result.complete(
                InstrumentedMaterializationResult.Failure(
                    AutomaticBacklogStoppedException(),
                ),
            )
        }
        hydrationReleased.complete(Unit)
    }
}

/** Runtime fake that controls only platform work while production owns all presentation state. */
private class InstrumentedVoiceRuntime(
    private val controls: Map<String, InstrumentedVoiceControl>,
) : VoiceAttachmentPresentationRuntime {
    private val fileOwners = ConcurrentHashMap<String, String>()
    private val publishedFiles = CopyOnWriteArrayList<File>()
    private val mutablePlaybackState = MutableStateFlow(VoicePlaybackController.PlaybackState())
    private val mutablePlaybackFailures = MutableSharedFlow<VoicePlaybackController.PlaybackFailure>()

    override val playbackState: StateFlow<VoicePlaybackController.PlaybackState> = mutablePlaybackState
    override val playbackFailures: SharedFlow<VoicePlaybackController.PlaybackFailure> = mutablePlaybackFailures

    /** Resolves the controlled attempt, then publishes its bytes through the production cache writer. */
    override suspend fun materialize(request: VoiceAttachmentMaterializationRequest): File =
        materializeVoiceAttachmentSource(
            context = request.context,
            messageIdHex = request.messageIdHex,
            attachmentIndex = request.attachmentIndex,
            reference = request.reference,
            resolveSource = {
                when (val result = controls.getValue(request.messageIdHex).awaitMaterializationOutcome()) {
                    InstrumentedMaterializationResult.Success ->
                        AttachmentPlaintext.Bytes(DEVICE_MINIMAL_AUDIO_BYTES.copyOf())
                    is InstrumentedMaterializationResult.Failure -> throw result.failure
                }
            },
        ).also { file ->
            fileOwners[file.absolutePath] = request.messageIdHex
            publishedFiles += file
        }

    /** Records waveform work on the owning message and holds publication at the shared hydration gate. */
    override suspend fun waveform(file: File): FloatArray {
        val control = controlFor(file)
        control.waveformStarted.complete(Unit)
        control.hydrationReleased.await()
        control.waveformCompleted.complete(Unit)
        return FloatArray(64) { index -> 0.2f + (index % 5) * 0.1f }
    }

    /** Records duration work on the owning message and publishes only after hydration is released. */
    override suspend fun durationMs(file: File): Int {
        val control = controlFor(file)
        control.durationStarted.complete(Unit)
        control.hydrationReleased.await()
        control.durationCompleted.complete(Unit)
        return DEVICE_LONG_DURATION_MS
    }

    /** Publishes a deterministic playing state without starting platform audio playback. */
    override suspend fun play(
        key: String,
        file: File,
        ownerKey: String,
    ): VoicePlaybackController.PlaybackStartResult {
        mutablePlaybackState.value =
            VoicePlaybackController.PlaybackState(
                key = key,
                isPlaying = true,
                durationMs = DEVICE_LONG_DURATION_MS,
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
        mutablePlaybackState.value = mutablePlaybackState.value.copy(speed = 1.5f)
    }

    /** Releases all held platform work so the composition can finish disposal. */
    fun releaseForCleanup() {
        controls.values.forEach(InstrumentedVoiceControl::releaseForCleanup)
        mutablePlaybackState.value = VoicePlaybackController.PlaybackState()
    }

    /** Deletes only files published by this runtime after Compose work is idle. */
    fun deleteFiles() {
        publishedFiles.distinctBy { it.absolutePath }.forEach { file ->
            check(!file.exists() || file.delete())
        }
    }

    /** Resolves a remounted cache file without retaining the previous runtime's owner map. */
    private fun controlFor(file: File): InstrumentedVoiceControl {
        val messageId =
            fileOwners[file.absolutePath]
                ?: controls.keys.singleOrNull { messageId -> file.name.startsWith("$messageId-") }
        return controls.getValue(requireNotNull(messageId))
    }
}

/** Controlled plaintext-source result used by the production cache publisher. */
private sealed interface InstrumentedMaterializationResult {
    data object Success : InstrumentedMaterializationResult

    data class Failure(
        val failure: Throwable,
    ) : InstrumentedMaterializationResult
}

/** Thread-safe in-memory viewport/writer evidence from the production list owner. */
private class InstrumentedConversationEvidence : ConversationScrollEvidenceSink {
    private val viewports = CopyOnWriteArrayList<ConversationViewportEvidence>()
    val writes = CopyOnWriteArrayList<ConversationScrollWriteEvidence>()

    override fun onViewport(snapshot: ConversationViewportEvidence) {
        viewports += snapshot
    }

    override fun onWrite(write: ConversationScrollWriteEvidence) {
        writes += write
    }

    /** Returns the append-only viewport cursor for transition-frame checks. */
    fun checkpoint(): Int = viewports.size

    /** Clears only writer evidence after an intentional production jump. */
    fun clearWrites() {
        writes.clear()
    }

    /** Clears viewport history before a fully fresh process-owner generation is mounted. */
    fun clearViewports() {
        viewports.clear()
    }

    /** Returns the latest device-measured production viewport. */
    fun latestViewport(): ConversationViewportEvidence = requireNotNull(viewports.lastOrNull())

    /** Waits for one stable message to become the production logical anchor. */
    fun awaitAnchor(
        composeRule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>,
        messageId: String,
    ): ConversationViewportEvidence =
        awaitViewport(composeRule, "message $messageId never became the logical anchor") {
            it.anchor.messageId == messageId
        }

    /** Waits until production tail ownership and physical-end geometry agree. */
    fun awaitFollowingTailAt(
        composeRule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>,
        messageId: String,
    ): ConversationViewportEvidence =
        awaitViewport(composeRule, "tail never settled with message $messageId visible") { viewport ->
            viewport.visibleItems.any { it.key == "msg:$messageId" } &&
                viewport.mode is ConversationScrollMode.FollowingTail &&
                !viewport.canScrollForward
        }

    /** Waits for a post-write anchor and durable ReadingHistory ownership. */
    fun awaitReadingHistoryAfter(
        composeRule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>,
        writeCheckpoint: Int,
        previousAnchor: ConversationScrollAnchor,
    ): ConversationViewportEvidence =
        awaitViewport(composeRule, "newer history intent never settled after writer $writeCheckpoint") {
            writes.size > writeCheckpoint &&
                it.mode is ConversationScrollMode.ReadingHistory &&
                it.anchor != previousAnchor
        }

    /** Requires every device frame after [checkpoint] to keep identical lazy geometry. */
    fun assertViewportStayedFixed(
        expected: ConversationViewportEvidence,
        checkpoint: Int,
    ) {
        val observed = viewports.drop(checkpoint).ifEmpty { listOf(latestViewport()) }
        observed.forEach { actual ->
            assertEquals(expected.accountRef, actual.accountRef)
            assertEquals(expected.mode, actual.mode)
            assertEquals(expected.anchor, actual.anchor)
            assertEquals(expected.viewportStartOffsetPx, actual.viewportStartOffsetPx)
            assertEquals(expected.viewportEndOffsetPx, actual.viewportEndOffsetPx)
            assertEquals(expected.viewportHeightPx, actual.viewportHeightPx)
            assertEquals(expected.canScrollForward, actual.canScrollForward)
            assertEquals(expected.visibleItems, actual.visibleItems)
        }
    }

    /** Advances Compose-owned scrolling and reports the final viewport plus writer evidence on timeout. */
    private fun awaitViewport(
        composeRule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>,
        conditionDescription: String,
        predicate: (ConversationViewportEvidence) -> Boolean,
    ): ConversationViewportEvidence {
        try {
            composeRule.waitUntil(timeoutMillis = DEVICE_TIMEOUT_MS) {
                viewports.lastOrNull()?.let(predicate) == true
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError(
                "$conditionDescription; latest=${viewports.lastOrNull()}; writes=${writes.toList()}",
                failure,
            )
        }
        composeRule.waitForIdle()
        return latestViewport().also { latest ->
            check(predicate(latest)) {
                "$conditionDescription changed after idleness; latest=$latest; writes=${writes.toList()}"
            }
        }
    }
}

/** One deterministic authoritative message with optional voice attachment metadata. */
private fun instrumentedTimelineRecord(
    index: Int,
    idOffset: Int,
    plaintext: String,
    media: MediaAttachmentReferenceFfi?,
): TimelineMessageRecordFfi {
    val messageId = instrumentedMessageId(index, idOffset)
    return TimelineMessageRecordFfi(
        messageIdHex = messageId,
        sourceMessageIdHex = messageId,
        direction = "received",
        groupIdHex = DEVICE_GROUP_ID,
        sender = DEVICE_PEER_ID,
        plaintext = plaintext,
        contentTokens =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks = emptyList(),
            ),
        kind = 9uL,
        tags = emptyList(),
        timelineAt = (idOffset + index + 1).toULong(),
        receivedAt = (idOffset + index + 1).toULong(),
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = listOfNotNull(media),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
        sourceEpoch = media?.sourceEpoch,
        retentionSeconds = null,
        retentionExpiresAt = null,
    )
}

/** Unique voice reference that cannot collide with another instrumented cache namespace. */
private fun instrumentedVoiceReference(
    index: Int,
    idOffset: Int,
): MediaAttachmentReferenceFfi =
    MediaAttachmentReferenceFfi(
        locators = emptyList(),
        ciphertextSha256 = "%064x".format(idOffset + index + 10_000),
        plaintextSha256 = "%064x".format(idOffset + index + 20_000),
        nonceHex = "%048x".format(idOffset + index + 30_000),
        fileName = "voice-device-$idOffset-$index.wav",
        mediaType = "audio/wav",
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = (idOffset + index + 1).toULong(),
        dim = null,
        thumbhash = null,
    )

/** Stable single-member group used by production controller ownership tests. */
private fun instrumentedGroup(): AppGroupRecordFfi =
    AppGroupRecordFfi(
        groupIdHex = DEVICE_GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = true,
        name = "Instrumented voice group",
        description = "",
        admins = listOf(DEVICE_ACCOUNT_ID),
        relays = emptyList(),
        nostrGroupIdHex = "04".repeat(32),
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia =
            AppGroupEncryptedMediaComponentFfi(
                componentId = 0x8008u,
                component = "marmot.group.encrypted-media.v1",
                required = true,
                version = EncryptedMediaVersionFfi.V1,
                mediaFormat = "encrypted-media-v1",
                allowedLocatorKinds = listOf("blossom-v1"),
                defaultBlobEndpoints =
                    listOf(
                        AppBlobEndpointFfi(
                            locatorKind = "blossom-v1",
                            baseUrl = "https://blossom.example",
                        ),
                    ),
            ),
        disappearingMessageSecs = 0uL,
        archived = false,
        pendingConfirmation = false,
        unrecoverable = false,
        selfMembership = SelfMembershipFfi.MEMBER,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        disbanding = false,
        disbandRequest = null,
        disbanded = false,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )

/** Loaded local member snapshot for the instrumented conversation. */
private fun instrumentedMemberSnapshot(): GroupMemberSnapshot =
    GroupMemberSnapshot(
        listOf(
            AppGroupMemberRecordFfi(
                memberIdHex = DEVICE_ACCOUNT_ID,
                account = DEVICE_ACCOUNT_REF,
                local = true,
            ),
        ),
    )

/** Matching authoritative roster returned by the real controller. */
private fun instrumentedRoster(): GroupRosterFfi =
    GroupRosterFfi(
        groupIdHex = DEVICE_GROUP_ID,
        members =
            listOf(
                GroupMemberDetailsFfi(
                    memberIdHex = DEVICE_ACCOUNT_ID,
                    account = DEVICE_ACCOUNT_REF,
                    local = true,
                    isAdmin = true,
                    isSelf = true,
                    npub = "npub-instrumented-self",
                    displayName = null,
                ),
            ),
        epoch = 0uL,
        rosterRevision = 0uL,
        selfMembership = SelfMembershipFfi.MEMBER,
        memberCount = 1u,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
    )

/** In-memory drafts keep instrumented conversation setup off persistent user storage. */
private class InstrumentedDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

private const val DEVICE_MESSAGE_COUNT = 36
private const val DEVICE_HISTORY_VOICE_INDEX = 18
private const val DEVICE_HISTORY_OFFSET_PX = 17
private const val DEVICE_LONG_DURATION_MS = 3_599_000
private const val DEVICE_TIMEOUT_MS = 10_000L
private val DEVICE_MINIMAL_AUDIO_BYTES = byteArrayOf(1)
private const val DEVICE_ACCOUNT_REF = "instrumented-account"
private val DEVICE_ACCOUNT_ID = "12a0" + "00".repeat(30)
private val DEVICE_PEER_ID = "34b0" + "00".repeat(30)
private val DEVICE_GROUP_ID = "56c0" + "00".repeat(30)

/** Produces one deterministic 32-byte hex message id. */
private fun instrumentedMessageId(
    index: Int,
    idOffset: Int,
): String = "%064x".format(idOffset + index + 1)
