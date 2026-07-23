package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListConnectivityBannerTest {
    @Test
    fun targetsMapTheTwoSignals() {
        assertEquals(
            ConnectivityBannerState.Offline,
            connectivityBannerTarget(hasNetwork = false, liveStreamConnected = false),
        )
        // Network reported gone while a socket lingers still reads offline —
        // the actionable state wins.
        assertEquals(
            ConnectivityBannerState.Offline,
            connectivityBannerTarget(hasNetwork = false, liveStreamConnected = true),
        )
        assertEquals(
            ConnectivityBannerState.Connecting,
            connectivityBannerTarget(hasNetwork = true, liveStreamConnected = false),
        )
        assertEquals(
            ConnectivityBannerState.Hidden,
            connectivityBannerTarget(hasNetwork = true, liveStreamConnected = true),
        )
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
