package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Test

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
