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
