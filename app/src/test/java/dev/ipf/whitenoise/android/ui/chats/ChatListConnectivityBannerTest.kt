package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatListConnectionPhase
import dev.ipf.whitenoise.android.state.ChatListConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListConnectivityBannerTest {
    @Test
    fun reducerKeepsInternetAttemptAndReadinessSeparate() {
        val idle = connectionState(ChatListConnectionPhase.Idle)
        val attempting = connectionState(ChatListConnectionPhase.Attempting)
        val ready = connectionState(ChatListConnectionPhase.Ready)

        assertEquals(
            ConnectivityBannerTarget.Offline,
            target(hasValidatedInternet = false, state = attempting),
        )
        assertEquals(
            ConnectivityBannerTarget.NoAttempt,
            target(hasValidatedInternet = true, state = idle),
        )
        assertEquals(
            ConnectivityBannerTarget.Connecting,
            target(hasValidatedInternet = true, state = attempting),
        )
        assertEquals(
            ConnectivityBannerTarget.Connected,
            target(hasValidatedInternet = true, state = ready),
        )
    }

    @Test
    fun inactiveAccountAndStaleRuntimeCannotSupplyStatus() {
        val otherAccount = connectionState(ChatListConnectionPhase.Ready).copy(accountRef = "work")
        val oldRuntime = connectionState(ChatListConnectionPhase.Ready).copy(runtimeGeneration = 8)

        assertEquals(ConnectivityBannerTarget.NoAttempt, target(true, otherAccount))
        assertEquals(ConnectivityBannerTarget.NoAttempt, target(true, oldRuntime))
        assertEquals(ConnectivityBannerTarget.Offline, target(false, otherAccount))
        assertEquals(ConnectivityBannerTarget.Offline, target(false, oldRuntime))
        assertEquals(
            ConnectivityBannerTarget.NoAttempt,
            connectivityBannerTarget(
                hasValidatedInternet = false,
                activeAccountRef = null,
                runtimeGeneration = RUNTIME_GENERATION,
                connectionState = connectionState(ChatListConnectionPhase.Attempting),
            ),
        )
    }

    @Test
    fun firstCollectedProblemFrameIsVisibleWithoutADebounce() {
        assertEquals(
            ConnectivityBannerState.Offline,
            initialConnectivityBannerState(ConnectivityBannerTarget.Offline).displayed,
        )
        assertEquals(
            ConnectivityBannerState.Connecting,
            initialConnectivityBannerState(ConnectivityBannerTarget.Connecting).displayed,
        )
    }

    @Test
    fun onlyExplicitReadinessEarnsAConnectedFlash() {
        val waitingWithoutAnAttempt =
            connectivityBannerNext(problemPresentation(), ConnectivityBannerTarget.NoAttempt)
        assertEquals(
            ConnectivityBannerState.Hidden,
            waitingWithoutAnAttempt.displayed,
        )
        assertEquals(
            ConnectivityBannerState.JustConnected,
            connectivityBannerNext(waitingWithoutAnAttempt, ConnectivityBannerTarget.Connected).displayed,
        )
        assertEquals(
            ConnectivityBannerState.JustConnected,
            connectivityBannerNext(problemPresentation(), ConnectivityBannerTarget.Connected).displayed,
        )
        assertEquals(
            ConnectivityBannerState.JustConnected,
            connectivityBannerNext(
                problemPresentation(ConnectivityBannerState.Offline),
                ConnectivityBannerTarget.Connected,
            ).displayed,
        )
        assertEquals(
            ConnectivityBannerState.Hidden,
            connectivityBannerNext(
                ConnectivityBannerPresentation(
                    ConnectivityBannerState.Hidden,
                    recoveryPending = false,
                    target = ConnectivityBannerTarget.NoAttempt,
                ),
                ConnectivityBannerTarget.Connected,
            ).displayed,
        )
        assertTrue(CONNECTIVITY_BANNER_FLASH_MILLIS <= 1_000L)
    }

    @Test
    fun problemStatesSwitchDirectlyOnAuthoritativeEdges() {
        assertEquals(
            ConnectivityBannerState.Offline,
            connectivityBannerNext(problemPresentation(), ConnectivityBannerTarget.Offline).displayed,
        )
        assertEquals(
            ConnectivityBannerState.Connecting,
            connectivityBannerNext(
                problemPresentation(ConnectivityBannerState.Offline),
                ConnectivityBannerTarget.Connecting,
            ).displayed,
        )
    }

    @Test
    fun steadyStateConnectedNeverPollsOnTheFastCadence() {
        assertEquals(
            CONNECTIVITY_RELAY_STEADY_POLL_MILLIS,
            relayPollDelayMillis(ConnectivityBannerState.Hidden, relaysConnected = true),
        )
    }

    @Test
    fun degradedStateKeepsTheFallbackPollResponsive() {
        assertEquals(
            CONNECTIVITY_RELAY_POLL_MILLIS,
            relayPollDelayMillis(ConnectivityBannerState.Connecting, relaysConnected = false),
        )
        assertEquals(
            CONNECTIVITY_RELAY_POLL_MILLIS,
            relayPollDelayMillis(ConnectivityBannerState.Hidden, relaysConnected = false),
        )
    }

    @Test
    fun steadyBackoffRunsTheFullWindowWithoutAnEdge() =
        runTest {
            val woke =
                withTimeoutOrNull(CONNECTIVITY_RELAY_STEADY_POLL_MILLIS) {
                    relayPollWakeEvents(
                        displayedStates = MutableStateFlow(ConnectivityBannerState.Hidden),
                        relaysConnected = MutableStateFlow(true),
                        foregroundResumes = MutableSharedFlow(),
                    ).first()
                }

            assertNull(woke)
        }

    @Test
    fun relayLossAndForegroundResumeWakeFallbackPolling() =
        runTest {
            val relays = MutableStateFlow(true)
            val resumes = MutableSharedFlow<Unit>()
            val relayWake =
                async {
                    withTimeoutOrNull(CONNECTIVITY_RELAY_STEADY_POLL_MILLIS) {
                        relayPollWakeEvents(
                            displayedStates = MutableStateFlow(ConnectivityBannerState.Hidden),
                            relaysConnected = relays,
                            foregroundResumes = MutableSharedFlow(),
                        ).first()
                    }
                }
            val resumeWake =
                async {
                    withTimeoutOrNull(CONNECTIVITY_RELAY_STEADY_POLL_MILLIS) {
                        relayPollWakeEvents(
                            displayedStates = MutableStateFlow(ConnectivityBannerState.Hidden),
                            relaysConnected = MutableStateFlow(true),
                            foregroundResumes = resumes,
                        ).first()
                    }
                }
            runCurrent()

            relays.value = false
            resumes.emit(Unit)

            assertNotNull(relayWake.await())
            assertNotNull(resumeWake.await())
        }

    @Test
    fun relayHealthIsFallbackEvidenceOnly() {
        assertEquals(false, relaysConnectedFromHealth(connectedRelays = 0, totalRelays = 4))
        assertEquals(true, relaysConnectedFromHealth(connectedRelays = 1, totalRelays = 4))
        assertEquals(true, relaysConnectedFromHealth(connectedRelays = 0, totalRelays = 0))
        assertEquals(false, relaysConnectedOnNetworkChange(isOnline = false, cached = true))
    }

    private fun target(
        hasValidatedInternet: Boolean,
        state: ChatListConnectionState,
    ): ConnectivityBannerTarget =
        connectivityBannerTarget(
            hasValidatedInternet = hasValidatedInternet,
            activeAccountRef = ACTIVE_ACCOUNT,
            runtimeGeneration = RUNTIME_GENERATION,
            connectionState = state,
        )

    private fun connectionState(phase: ChatListConnectionPhase): ChatListConnectionState =
        ChatListConnectionState(
            accountRef = ACTIVE_ACCOUNT,
            runtimeGeneration = RUNTIME_GENERATION,
            bindEpoch = 3L,
            sessionAttemptId = 5L,
            evidenceEpoch = 7L,
            phase = phase,
        )

    private fun problemPresentation(displayed: ConnectivityBannerState = ConnectivityBannerState.Connecting) =
        ConnectivityBannerPresentation(
            displayed = displayed,
            recoveryPending = true,
            target =
                if (displayed == ConnectivityBannerState.Offline) {
                    ConnectivityBannerTarget.Offline
                } else {
                    ConnectivityBannerTarget.Connecting
                },
        )

    private companion object {
        const val ACTIVE_ACCOUNT = "personal"
        const val RUNTIME_GENERATION = 9
    }
}
