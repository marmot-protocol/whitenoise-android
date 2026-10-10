package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListConnectionPhase
import dev.ipf.whitenoise.android.state.ChatListConnectionState
import dev.ipf.whitenoise.android.state.ConnectivitySignals
import dev.ipf.whitenoise.android.state.beginReadinessRefresh
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatListConnectivityLifecycleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A STOP/START cycle with no relay sample still revalidates once on resume, with no visible banner. */
    @Test
    fun healthyStopStartWithZeroRelaySampleRevalidatesWithoutRenderingAConnectivityTransition() {
        val fixture = HealthyResumeFixture()
        composeRule.setContent { fixture.Content() }
        composeRule.waitForIdle()
        val revalidationsBeforeResume = fixture.revalidationCount
        val foregroundBeforeResume = fixture.foregroundCount
        val relayBeforeResume = fixture.relayCount

        composeRule.runOnUiThread {
            fixture.lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            fixture.lifecycleOwner.handle(Lifecycle.Event.ON_STOP)
            fixture.lifecycleOwner.handle(Lifecycle.Event.ON_START)
            fixture.lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()

        assertEquals(revalidationsBeforeResume + 1, fixture.revalidationCount)
        assertEquals(foregroundBeforeResume + 1, fixture.foregroundCount)
        assertEquals("resume must not manufacture a relay edge", relayBeforeResume, fixture.relayCount)
        assertTrue(fixture.renderedStates.isNotEmpty())
        assertTrue(
            "no committed warm-resume state is Connecting or JustConnected",
            fixture.renderedStates.all { it == ConnectivityBannerState.Hidden },
        )
        assertEquals(ChatListConnectionPhase.Validating, fixture.connectionState.value.phase)
        composeRule.onNodeWithTag(CHAT_LIST_INLINE_CONNECTIVITY_TAG).assertIsNotDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.connectivity_connecting)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.connectivity_connected)).assertDoesNotExist()
    }

    /**
     * The steady connected poll keeps ticking at its 15s cadence in the foreground, stops entirely
     * through STOP however long that lasts, and resumes with one fresh sample on START rather than
     * waiting out another full cadence (#2815).
     */
    @Test
    fun steadyConnectedPollSuspendsThroughStopAndResumesWithOneFreshSample() {
        val lifecycleOwner = StartedLifecycleOwner()
        var refreshCount = 0
        val relaysConnectedFlow = MutableStateFlow(true)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                val foregroundEpoch = rememberConnectivityForegroundEpoch()
                RelayConnectivityPollingEffect(
                    effectOwner = Unit,
                    displayed = ConnectivityBannerState.Hidden,
                    foregroundEpoch = foregroundEpoch,
                    connectivitySignals = { ConnectivitySignals(hasValidatedInternet = true, relaysConnected = true) },
                    relaysConnectedFlow = relaysConnectedFlow,
                    refreshRelayConnectivity = { refreshCount += 1 },
                    revalidateConnectionReadiness = {},
                )
            }
        }
        composeRule.waitForIdle()
        // The steady cadence races a merged flow inside withTimeoutOrNull; this harness's virtual
        // scheduler can advance through that race once while otherwise idle, so the exact count this
        // far is not the claim under test — only the two deltas below are.
        val countBeforeStop = refreshCount

        composeRule.runOnUiThread {
            lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handle(Lifecycle.Event.ON_STOP)
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(CONNECTIVITY_RELAY_STEADY_POLL_MILLIS * 4)
        composeRule.waitForIdle()
        assertEquals("no poll during STOP, however long it lasts", countBeforeStop, refreshCount)

        composeRule.runOnUiThread {
            lifecycleOwner.handle(Lifecycle.Event.ON_START)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertEquals(
            "resume takes exactly one fresh sample immediately, not after another interval",
            countBeforeStop + 1,
            refreshCount,
        )
    }

    /**
     * The fast 2s problem-state cadence is suspended the same way, and the readiness nudge it can
     * trigger never fires while stopped even though relays read down the whole time (#2815).
     */
    @Test
    fun fastDisconnectedPollAndReadinessNudgeSuspendThroughStop() {
        val lifecycleOwner = StartedLifecycleOwner()
        var refreshCount = 0
        var revalidateCount = 0
        val relaysConnectedFlow = MutableStateFlow(false)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                val foregroundEpoch = rememberConnectivityForegroundEpoch()
                RelayConnectivityPollingEffect(
                    effectOwner = Unit,
                    displayed = ConnectivityBannerState.Offline,
                    foregroundEpoch = foregroundEpoch,
                    connectivitySignals = {
                        ConnectivitySignals(hasValidatedInternet = true, relaysConnected = false)
                    },
                    relaysConnectedFlow = relaysConnectedFlow,
                    refreshRelayConnectivity = { refreshCount += 1 },
                    revalidateConnectionReadiness = { revalidateCount += 1 },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, refreshCount)
        assertTrue("relays down with usable internet nudges readiness", revalidateCount >= 1)
        val revalidationsBeforeStop = revalidateCount

        composeRule.runOnUiThread {
            lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handle(Lifecycle.Event.ON_STOP)
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val refreshesAtStop = refreshCount
        composeRule.mainClock.advanceTimeBy(CONNECTIVITY_RELAY_POLL_MILLIS * 10)
        composeRule.waitForIdle()
        assertEquals("no fast-cadence poll during STOP", refreshesAtStop, refreshCount)
        assertEquals("no readiness nudge during STOP", revalidationsBeforeStop, revalidateCount)

        composeRule.runOnUiThread {
            lifecycleOwner.handle(Lifecycle.Event.ON_START)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertEquals("resume takes one fresh sample", refreshesAtStop + 1, refreshCount)
        assertTrue(
            "resume re-nudges readiness against the current condition",
            revalidateCount > revalidationsBeforeStop,
        )
    }

    /**
     * A retained composition whose effect owner changes while stopped — the chat-list controller
     * being replaced by an account switch — still polls nothing until START, then takes one fresh
     * sample under the new owner (#2815).
     */
    @Test
    fun ownerReplacedWhileStoppedStillWaitsForStartBeforePolling() {
        val lifecycleOwner = StartedLifecycleOwner()
        var refreshCount = 0
        var owner by mutableStateOf("account-a")
        val relaysConnectedFlow = MutableStateFlow(true)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                val foregroundEpoch = rememberConnectivityForegroundEpoch()
                RelayConnectivityPollingEffect(
                    effectOwner = owner,
                    displayed = ConnectivityBannerState.Hidden,
                    foregroundEpoch = foregroundEpoch,
                    connectivitySignals = { ConnectivitySignals(hasValidatedInternet = true, relaysConnected = true) },
                    relaysConnectedFlow = relaysConnectedFlow,
                    refreshRelayConnectivity = { refreshCount += 1 },
                    revalidateConnectionReadiness = {},
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, refreshCount)

        composeRule.runOnUiThread {
            lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handle(Lifecycle.Event.ON_STOP)
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val countAtStop = refreshCount
        composeRule.runOnUiThread { owner = "account-b" }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(CONNECTIVITY_RELAY_STEADY_POLL_MILLIS * 2)
        composeRule.waitForIdle()
        assertEquals("the new owner's effect still polls nothing while stopped", countAtStop, refreshCount)

        composeRule.runOnUiThread {
            lifecycleOwner.handle(Lifecycle.Event.ON_START)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertEquals("the new owner takes its own fresh sample on resume", countAtStop + 1, refreshCount)
    }

    /** Retained readiness and source counters around the production lifecycle effects. */
    private class HealthyResumeFixture {
        val lifecycleOwner = StartedLifecycleOwner()
        val connectionState =
            mutableStateOf(
                ChatListConnectionState(
                    accountRef = ACCOUNT,
                    runtimeGeneration = RUNTIME_GENERATION,
                    bindEpoch = 2L,
                    sessionAttemptId = 3L,
                    evidenceEpoch = 4L,
                    phase = ChatListConnectionPhase.Ready,
                ),
            )
        var foregroundCount = 0
        var relayCount = 0
        val revalidationCount: Int get() = foregroundCount + relayCount
        val renderedStates = mutableListOf<ConnectivityBannerState>()

        /** Mounts explicit foreground/relay callbacks and records every committed banner state. */
        @Composable
        @Suppress("FunctionNaming")
        fun Content() {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                val foregroundEpoch = rememberConnectivityForegroundEpoch()
                ConnectivityEdgeRefreshEffects(
                    effectOwner = connectionState,
                    activeAccountRef = ACCOUNT,
                    runtimeGeneration = RUNTIME_GENERATION,
                    hasValidatedInternet = true,
                    relaysConnected = false,
                    foregroundEpoch = foregroundEpoch,
                    revalidateConnectionReadiness = {
                        error("explicit foreground and relay sources must be used")
                    },
                    revalidateOnForeground = {
                        foregroundCount += 1
                        connectionState.value = connectionState.value.beginReadinessRefresh(presentAttempt = false)
                    },
                    revalidateOnRelaySample = {
                        relayCount += 1
                        connectionState.value = connectionState.value.beginReadinessRefresh(presentAttempt = false)
                    },
                )
                val target =
                    connectivityBannerTarget(
                        hasValidatedInternet = true,
                        activeAccountRef = ACCOUNT,
                        runtimeGeneration = RUNTIME_GENERATION,
                        connectionState = connectionState.value,
                    )
                val displayed =
                    rememberConnectivityBannerPresentation(
                        owner = connectionState,
                        accountRef = ACCOUNT,
                        runtimeGeneration = RUNTIME_GENERATION,
                        target = target,
                    ).displayed
                SideEffect { renderedStates.add(displayed) }
                WhiteNoiseTheme { ChatListInlineConnectivityIndicator(displayed) }
            }
        }
    }

    /** A [LifecycleOwner] test double that starts already resumed, so tests drive STOP/START directly. */
    private class StartedLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle = registry

        init {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        /** Dispatches one lifecycle event to every observer registered on this owner. */
        fun handle(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    private companion object {
        const val ACCOUNT = "personal"
        const val RUNTIME_GENERATION = 9
    }
}
