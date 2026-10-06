package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.audio.VoiceConversationDestination
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
import dev.ipf.whitenoise.android.audio.tts.TtsConversationDestination
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.state.ConversationTimelineSubscriptionHandle
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.retainedPlaybackSource
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class TtsDestinationNavigationTest {
    private val destination =
        TtsConversationDestination(
            accountRef = "account-a",
            groupIdHex = "group-a",
            sessionId = 9L,
            passage = TtsPassage("message-newest", sentenceIndex = 1),
        )
    private val request = TtsDestinationNavigationRequest(3L, "account-a", "group-a", 9L)

    /** A missing or replaced playback session cannot navigate a retained source request. */
    @Test
    fun staleOrReplacedSessionCancelsWithoutRouting() {
        assertEquals(TtsDestinationNavigationStep.Cancelled, resolve(current = null))
        assertEquals(
            TtsDestinationNavigationStep.Cancelled,
            resolve(current = destination.copy(sessionId = 10L)),
        )
        assertEquals(
            TtsDestinationNavigationStep.Cancelled,
            resolve(current = destination.copy(groupIdHex = "group-b")),
        )
    }

    /** Routing first proves account availability and waits for its owned account switch before opening the chat. */
    @Test
    fun sourceAccountIsValidatedAndSwitchedBeforeChatResolution() {
        assertEquals(
            TtsDestinationNavigationStep.MissingAccount,
            resolve(knownAccounts = emptySet()),
        )
        assertEquals(
            TtsDestinationNavigationStep.SwitchAccount("account-a"),
            resolve(activeAccount = "account-b"),
        )
        assertEquals(
            TtsDestinationNavigationStep.AwaitAccountSwitch,
            resolve(
                activeAccount = "account-b",
                navigationRequest = request.copy(accountSwitchRequested = true),
            ),
        )
    }

    /** Sentence advancement cannot steal or restart an already owned account transition. */
    @Test
    fun passageAdvanceWhileAccountSwitchIsPendingKeepsWaitingForTheOwnedSwitch() {
        assertEquals(
            TtsDestinationNavigationStep.AwaitAccountSwitch,
            resolve(
                current = destination.copy(passage = TtsPassage("message-newer", sentenceIndex = 2)),
                activeAccount = "account-b",
                navigationRequest = request.copy(accountSwitchRequested = true),
            ),
        )
    }

    /** Both loaded and direct-load routes use the latest source passage while retaining the request identity. */
    @Test
    fun latestPassageIsUsedForExistingAndDirectlyLoadedConversations() {
        assertEquals(
            TtsDestinationNavigationStep.OpenConversation(
                groupIdHex = "group-a",
                messageIdHex = "message-newest",
                sessionId = 9L,
                requestId = 3L,
            ),
            resolve(availableGroups = setOf("GROUP-A")),
        )
        assertEquals(
            TtsDestinationNavigationStep.LoadConversationDirectly(
                accountRef = "account-a",
                groupIdHex = "group-a",
                messageIdHex = "message-newest",
                sessionId = 9L,
                requestId = 3L,
            ),
            resolve(availableGroups = emptySet()),
        )
    }

    /** Only the currently retained request ID may apply a delayed navigation completion. */
    @Test
    fun onlyTheCurrentRequestOwnsAnAsyncCompletion() {
        assertTrue(request.ownsCompletion(requestId = 3L))
        assertFalse(request.ownsCompletion(requestId = 2L))
        assertFalse(request.copy(requestId = 4L).ownsCompletion(requestId = 3L))
        assertFalse((null as TtsDestinationNavigationRequest?).ownsCompletion(requestId = 3L))
    }

    /** Unrelated account changes and superseded requests cancel routing instead of retargeting it. */
    @Test
    fun onlyTheRequestOwnedAccountTransitionKeepsRoutingAlive() {
        val ownership =
            TtsDestinationAccountSwitchOwnership(
                requestId = request.requestId,
                sourceAccountRef = "account-b",
                targetAccountRef = "account-a",
            )

        assertTrue(ownership.ownsAccountChange("account-b", "account-a", request))
        assertFalse(ownership.ownsAccountChange("account-b", "account-c", request))
        assertFalse(ownership.ownsAccountChange("account-b", "account-a", request.copy(requestId = 4L)))
        assertFalse(ownership.ownsAccountChange("account-c", "account-a", request))
    }

    /** Voice return shares exact account/group validation while retaining its independent player token. */
    @Test fun voiceDestinationUsesCurrentMessageAndRejectsReplacedPlayer() {
        val source =
            dev.ipf.whitenoise.android.audio
                .VoicePlaybackSource("account-a", "group-a", "voice-message", "Maya")
        val voice =
            dev.ipf.whitenoise.android.audio
                .VoiceConversationDestination(source, 7)
        val voiceRequest = request.copy(sessionId = voice.sessionId)

        /**
         * Runs voice and speech owners through the same request while retaining their separate session identity
         * domains.
         */
        fun route(
            current: dev.ipf.whitenoise.android.audio.PlaybackConversationDestination?,
            active: String,
        ) = resolveTtsDestinationNavigation(
            voiceRequest,
            current,
            setOf("account-a", "account-b"),
            active,
            setOf("group-a"),
        )
        assertEquals(TtsDestinationNavigationStep.SwitchAccount("account-a"), route(voice, "account-b"))
        assertEquals(
            TtsDestinationNavigationStep.OpenConversation("group-a", "voice-message", -7, request.requestId),
            route(voice, "account-a"),
        )
        assertEquals(TtsDestinationNavigationStep.Cancelled, route(voice.copy(playerSessionId = 8), "account-a"))
        assertEquals(TtsDestinationNavigationStep.Cancelled, route(destination.copy(sessionId = 7), "account-a"))
        assertEquals(TtsDestinationNavigationStep.Cancelled, route(null, "account-a"))
    }

    /**
     * A pending clip's captured provenance permits conversation return without querying a nonexistent native
     * anchor.
     */
    @Test fun pendingVoiceSourceReturnsToConversationWithoutInventingAMessageAnchor() =
        runBlocking {
            val source = VoicePlaybackSource("account-a", "group-a", "local-uuid", "Maya", focusMessage = false)
            val voice = VoiceConversationDestination(source, 7)
            assertNull(voice.navigationFocusMessageId)
            assertTrue(
                retainedPlaybackSource(voice) { error("Pending local sources must not open native MESSAGE windows") },
            )
            val probe = SourceProbe(null)
            val confirmed = VoiceConversationDestination(source.copy(focusMessage = true), 7)
            assertEquals("local-uuid", confirmed.navigationFocusMessageId)
            assertFalse(retainedPlaybackSource(confirmed) { probe })
            assertEquals(listOf("snapshot", "cancel", "close"), probe.events)
        }

    /** An old source is validated by its exact account/group/message identity, independently of recent history. */
    @Test fun retainedSourceLookupUsesExactOwnerAndClosesItsWindow() =
        runBlocking {
            val row = timelineRecord(destination.messageIdHex, 1uL).copy(groupIdHex = destination.groupIdHex)
            val probe = SourceProbe(timelinePage(row).copy(hasMoreBefore = true, hasMoreAfter = true))
            assertTrue(
                retainedPlaybackSource(destination) { requested ->
                    assertEquals(destination, requested)
                    probe
                },
            )
            assertEquals(listOf("snapshot", "cancel", "close"), probe.events)
        }

    /** Missing, deleted, invalidated, and mismatched rows cannot authorize source navigation. */
    @Test fun unavailableSourceRowsAreRejectedBeforeRouting() =
        runBlocking {
            val row = timelineRecord(destination.messageIdHex, 1uL).copy(groupIdHex = destination.groupIdHex)
            val pages =
                listOf(
                    null,
                    timelinePage(),
                    timelinePage(row.copy(deleted = true)),
                    timelinePage(row.copy(invalidationStatus = "LosingBranch")),
                    timelinePage(row.copy(messageIdHex = "different")),
                    timelinePage(row.copy(groupIdHex = "another-group")),
                )
            pages.forEach { page ->
                val probe = SourceProbe(page)
                assertFalse(retainedPlaybackSource(destination) { probe })
                assertEquals(listOf("snapshot", "cancel", "close"), probe.events)
            }
        }

    /** Cancellation remains cancellation, while the temporary native window is always released. */
    @Test fun cancelledSourceReadReleasesWindowWithoutBecomingMissing() =
        runBlocking {
            val probe = SourceProbe(null, snapshotFailure = CancellationException("cancelled lookup"))
            try {
                retainedPlaybackSource(destination) { probe }
                fail("Cancelled source lookup must propagate")
            } catch (_: CancellationException) {
                assertEquals(listOf("snapshot", "cancel", "close"), probe.events)
            }
        }

    /** Losing the dispatcher return after a completed native probe cannot abandon its acquired window. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationBeforeSourceProbeReturnsToTheShellStillReleasesTheWindow() =
        runTest {
            val workerTasks = ArrayDeque<Runnable>()
            val worker =
                object : CoroutineDispatcher() {
                    /** Holds the worker turn so cancellation can land before its result resumes the shell. */
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        workerTasks.add(block)
                    }
                }
            val row = timelineRecord(destination.messageIdHex, 1uL).copy(groupIdHex = destination.groupIdHex)
            val probe = SourceProbe(timelinePage(row))
            var delivered = false
            val request =
                launch {
                    withContext(worker) { retainedPlaybackSource(destination) { probe } }
                    delivered = true
                }
            runCurrent()
            workerTasks.removeFirst().run()
            request.cancel()
            runCurrent()
            request.join()
            assertFalse(delivered)
            assertEquals(listOf("snapshot", "cancel", "close"), probe.events)
        }

    /** Native cancellation failure cannot bypass final handle release. */
    @Test fun failingSourceWindowCancellationStillClosesTheHandle() =
        runBlocking {
            val probe = SourceProbe(null, cancelFailure = IllegalStateException("cancel unavailable"))
            try {
                retainedPlaybackSource(destination) { probe }
                fail("The failed teardown must reach the caller")
            } catch (_: IllegalStateException) {
                assertEquals(listOf("snapshot", "cancel", "close"), probe.events)
            }
        }

    /** Records the lifecycle of a one-shot source query without any native pointer or read acknowledgement. */
    private class SourceProbe(
        private val page: TimelinePageFfi?,
        private val snapshotFailure: Throwable? = null,
        private val cancelFailure: Throwable? = null,
    ) : ConversationTimelineSubscriptionHandle by ScriptedConversationTimelineSubscription(page) {
        val events = mutableListOf<String>()

        /** Returns the exact scripted window or a cancellation at the read boundary. */
        override fun snapshot(): TimelinePageFfi? {
            events += "snapshot"
            snapshotFailure?.let { throw it }
            return page
        }

        /** Makes awaited native cancellation observable separately from releasing the handle. */
        override suspend fun cancel() {
            events += "cancel"
            cancelFailure?.let { throw it }
        }

        /** Records final resource release even when reading or cancelling the window failed. */
        override fun close() {
            events += "close"
        }
    }

    /** Vary one navigation boundary at a time while holding the request and account inventory explicit. */
    private fun resolve(
        current: TtsConversationDestination? = destination,
        knownAccounts: Set<String> = setOf("account-a"),
        activeAccount: String? = "account-a",
        availableGroups: Set<String> = setOf("group-a"),
        navigationRequest: TtsDestinationNavigationRequest = request,
    ): TtsDestinationNavigationStep =
        resolveTtsDestinationNavigation(
            request = navigationRequest,
            currentDestination = current,
            knownAccountRefs = knownAccounts,
            activeAccountRef = activeAccount,
            availableGroupIds = availableGroups,
        )
}
