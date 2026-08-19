package dev.ipf.whitenoise.android.ui.chats

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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListConnectivityBannerTest {
    @Test
    fun targetsMapTheTwoSignals() {
        assertEquals(
            ConnectivityBannerState.Offline,
            connectivityBannerTarget(hasNetwork = false, relaysConnected = false),
        )
        // Network reported gone while relays still count as connected reads
        // offline — the actionable state wins.
        assertEquals(
            ConnectivityBannerState.Offline,
            connectivityBannerTarget(hasNetwork = false, relaysConnected = true),
        )
        // Device network up but zero relays reachable is the relay-outage
        // case: the banner must say connecting, not hide.
        assertEquals(
            ConnectivityBannerState.Connecting,
            connectivityBannerTarget(hasNetwork = true, relaysConnected = false),
        )
        assertEquals(
            ConnectivityBannerState.Hidden,
            connectivityBannerTarget(hasNetwork = true, relaysConnected = true),
        )
    }

    @Test
    fun steadyStateConnectedNeverPollsOnTheFastCadence() {
        // Steady state — banner hidden, relays connected — must back off, the
        // idle chat list may not wake every two seconds.
        assertEquals(
            CONNECTIVITY_RELAY_STEADY_POLL_MILLIS,
            relayPollDelayMillis(ConnectivityBannerState.Hidden, relaysConnected = true),
        )
    }

    @Test
    fun bannerRelevantStatesKeepTheFastPollCadence() {
        assertEquals(
            CONNECTIVITY_RELAY_POLL_MILLIS,
            relayPollDelayMillis(ConnectivityBannerState.Offline, relaysConnected = false),
        )
        assertEquals(
            CONNECTIVITY_RELAY_POLL_MILLIS,
            relayPollDelayMillis(ConnectivityBannerState.Connecting, relaysConnected = false),
        )
        assertEquals(
            CONNECTIVITY_RELAY_POLL_MILLIS,
            relayPollDelayMillis(ConnectivityBannerState.JustConnected, relaysConnected = true),
        )
        // Hidden but relays down — the debounce window before the banner shows
        // still needs the fast cadence to confirm or clear the problem.
        assertEquals(
            CONNECTIVITY_RELAY_POLL_MILLIS,
            relayPollDelayMillis(ConnectivityBannerState.Hidden, relaysConnected = false),
        )
    }

    @Test
    fun steadyBackoffSleepRunsTheFullWindowWhenNothingChanges() =
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
    fun relayDegradationWakesTheBackoffSleepEarly() =
        runTest {
            val relays = MutableStateFlow(true)
            val woke =
                async {
                    withTimeoutOrNull(CONNECTIVITY_RELAY_STEADY_POLL_MILLIS) {
                        relayPollWakeEvents(
                            displayedStates = MutableStateFlow(ConnectivityBannerState.Hidden),
                            relaysConnected = relays,
                            foregroundResumes = MutableSharedFlow(),
                        ).first()
                    }
                }
            runCurrent()

            relays.value = false

            assertNotNull(woke.await())
        }

    @Test
    fun bannerStateChangeWakesTheBackoffSleepEarly() =
        runTest {
            val displayed = MutableStateFlow(ConnectivityBannerState.Hidden)
            val woke =
                async {
                    withTimeoutOrNull(CONNECTIVITY_RELAY_STEADY_POLL_MILLIS) {
                        relayPollWakeEvents(
                            displayedStates = displayed,
                            relaysConnected = MutableStateFlow(true),
                            foregroundResumes = MutableSharedFlow(),
                        ).first()
                    }
                }
            runCurrent()

            displayed.value = ConnectivityBannerState.Offline

            assertNotNull(woke.await())
        }

    @Test
    fun foregroundResumeWakesTheBackoffSleepEarly() =
        runTest {
            // A sleep started before backgrounding must not be waited out on
            // resume — the poll was a no-op the whole time, so the snapshot it
            // was priced against is stale.
            val resumes = MutableSharedFlow<Unit>()
            val woke =
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

            resumes.emit(Unit)

            assertNotNull(woke.await())
        }

    @Test
    fun relayHealthMapsToConnectivityByConnectedCount() {
        assertEquals(false, relaysConnectedFromHealth(connectedRelays = 0, totalRelays = 4))
        assertEquals(true, relaysConnectedFromHealth(connectedRelays = 1, totalRelays = 4))
        assertEquals(true, relaysConnectedFromHealth(connectedRelays = 4, totalRelays = 4))
        // No configured relays (signed out, bare runtime): nothing to connect
        // to, so nothing to complain about.
        assertEquals(true, relaysConnectedFromHealth(connectedRelays = 0, totalRelays = 0))
    }

    @Test
    fun networkLossInvalidatesTheCachedRelaySignal() {
        assertEquals(false, relaysConnectedOnNetworkChange(isOnline = false, cached = true))
        assertEquals(false, relaysConnectedOnNetworkChange(isOnline = false, cached = false))
        // A network event while online (capabilities change) keeps whatever
        // the last health sample established.
        assertEquals(true, relaysConnectedOnNetworkChange(isOnline = true, cached = true))
        assertEquals(false, relaysConnectedOnNetworkChange(isOnline = true, cached = false))
    }

    @Test
    fun quickNetworkBounceMustEarnTheFlashWithAFreshHealthSample() {
        // Steady connected, no chrome.
        var relays = true
        var displayed = ConnectivityBannerState.Hidden
        // Network lost: the cached relay signal is invalidated with it.
        relays = relaysConnectedOnNetworkChange(isOnline = false, cached = relays)
        displayed =
            connectivityBannerNext(displayed, connectivityBannerTarget(hasNetwork = false, relaysConnected = relays))
        assertEquals(ConnectivityBannerState.Offline, displayed)
        // Network restored before any relay-health poll ran: no premature
        // success flash off the stale cache — the banner keeps working.
        relays = relaysConnectedOnNetworkChange(isOnline = true, cached = relays)
        displayed =
            connectivityBannerNext(displayed, connectivityBannerTarget(hasNetwork = true, relaysConnected = relays))
        assertEquals(ConnectivityBannerState.Connecting, displayed)
        // A fresh post-restore sample proves a relay is back: flash once.
        relays = relaysConnectedFromHealth(connectedRelays = 1, totalRelays = 4)
        displayed =
            connectivityBannerNext(displayed, connectivityBannerTarget(hasNetwork = true, relaysConnected = relays))
        assertEquals(ConnectivityBannerState.JustConnected, displayed)
    }

    @Test
    fun offlineStartupClampsTheOptimisticRelayDefault() {
        // The signals flow seeds relaysConnected optimistically; a device that
        // starts offline must clamp it with the seed's hasNetwork=false write
        // so the first onAvailable cannot flash success without evidence.
        val seeded = relaysConnectedOnNetworkChange(isOnline = false, cached = true)
        assertEquals(false, seeded)
        assertEquals(
            ConnectivityBannerState.Offline,
            connectivityBannerTarget(hasNetwork = false, relaysConnected = seeded),
        )
        val restored = relaysConnectedOnNetworkChange(isOnline = true, cached = seeded)
        assertEquals(
            ConnectivityBannerState.Connecting,
            connectivityBannerTarget(hasNetwork = true, relaysConnected = restored),
        )
    }

    @Test
    fun offlinePollReportingConnectedRelaysCannotResurrectTheSignal() {
        // Pool counts read while offline are stale by definition: even a
        // snapshot claiming live relays stays clamped to false.
        val stalePoll = relaysConnectedFromHealth(connectedRelays = 3, totalRelays = 4)
        assertEquals(true, stalePoll)
        assertEquals(false, relaysConnectedOnNetworkChange(isOnline = false, cached = stalePoll))
    }

    @Test
    fun reachingConnectedFromAProblemStateFlashesOnce() {
        assertEquals(
            ConnectivityBannerState.JustConnected,
            connectivityBannerNext(ConnectivityBannerState.Connecting, ConnectivityBannerState.Hidden),
        )
        assertEquals(
            ConnectivityBannerState.JustConnected,
            connectivityBannerNext(ConnectivityBannerState.Offline, ConnectivityBannerState.Hidden),
        )
        // Already hidden (steady-state connected) stays hidden: no chrome.
        assertEquals(
            ConnectivityBannerState.Hidden,
            connectivityBannerNext(ConnectivityBannerState.Hidden, ConnectivityBannerState.Hidden),
        )
        // The flash itself decays to hidden, never re-flashes.
        assertEquals(
            ConnectivityBannerState.Hidden,
            connectivityBannerNext(ConnectivityBannerState.JustConnected, ConnectivityBannerState.Hidden),
        )
    }

    @Test
    fun problemStatesPassThroughAndSwitchDirectly() {
        assertEquals(
            ConnectivityBannerState.Offline,
            connectivityBannerNext(ConnectivityBannerState.Connecting, ConnectivityBannerState.Offline),
        )
        assertEquals(
            ConnectivityBannerState.Connecting,
            connectivityBannerNext(ConnectivityBannerState.Offline, ConnectivityBannerState.Connecting),
        )
        assertEquals(
            ConnectivityBannerState.Connecting,
            connectivityBannerNext(ConnectivityBannerState.JustConnected, ConnectivityBannerState.Connecting),
        )
    }
}
