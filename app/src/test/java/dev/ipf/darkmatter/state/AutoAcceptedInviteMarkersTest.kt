package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAcceptedInviteMarkersTest {
    @Test
    fun upsertStoresInviterAndBadgeIsVisibleUntilOpenedOrExpired() {
        val encoded =
            AutoAcceptedInviteMarkers.upsert(
                encoded = emptySet(),
                accountRef = "account-a",
                groupIdHex = "group-a",
                invitedAtMs = 1_000L,
                inviterAccountIdHex = "alice",
            )
        val marker = AutoAcceptedInviteMarkers.markerFor(encoded, "account-a", "GROUP-A")

        assertEquals("alice", marker?.inviterAccountIdHex)
        assertTrue(AutoAcceptedInviteMarkers.badgeVisible(marker, nowMs = 1_000L))
        assertFalse(
            AutoAcceptedInviteMarkers.badgeVisible(
                marker,
                nowMs = 1_000L + AutoAcceptedInviteMarkers.BADGE_TTL_MILLIS + 1,
            ),
        )
    }

    @Test
    fun openingClearsBadgeButKeepsBannerUntilDismissed() {
        val inserted =
            AutoAcceptedInviteMarkers.upsert(
                encoded = emptySet(),
                accountRef = "account-a",
                groupIdHex = "group-a",
                invitedAtMs = 1_000L,
                inviterAccountIdHex = "alice",
            )
        val opened = AutoAcceptedInviteMarkers.markOpened(inserted, "account-a", "group-a")
        val openedMarker = AutoAcceptedInviteMarkers.markerFor(opened, "account-a", "group-a")

        assertFalse(AutoAcceptedInviteMarkers.badgeVisible(openedMarker, nowMs = 1_000L))
        assertEquals("alice", AutoAcceptedInviteMarkers.bannerState(openedMarker)?.inviterAccountIdHex)

        val dismissed = AutoAcceptedInviteMarkers.dismissBanner(opened, "account-a", "group-a")
        assertNull(
            AutoAcceptedInviteMarkers.bannerState(
                AutoAcceptedInviteMarkers.markerFor(dismissed, "account-a", "group-a"),
            ),
        )
    }

    @Test
    fun upsertCanSeedOpenedForAlreadyActiveConversation() {
        val encoded =
            AutoAcceptedInviteMarkers.upsert(
                encoded = emptySet(),
                accountRef = "account-a",
                groupIdHex = "group-a",
                invitedAtMs = 1_000L,
                inviterAccountIdHex = "alice",
                opened = true,
            )
        val marker = AutoAcceptedInviteMarkers.markerFor(encoded, "account-a", "group-a")

        assertFalse(AutoAcceptedInviteMarkers.badgeVisible(marker, nowMs = 1_000L))
        assertEquals("alice", AutoAcceptedInviteMarkers.bannerState(marker)?.inviterAccountIdHex)
    }

    @Test
    fun upsertIgnoresMalformedFieldsContainingSeparator() {
        val encoded =
            AutoAcceptedInviteMarkers.upsert(
                encoded = emptySet(),
                accountRef = "account\u001Fbad",
                groupIdHex = "group-a",
                invitedAtMs = 1_000L,
                inviterAccountIdHex = "alice",
            )

        assertTrue(encoded.isEmpty())
    }

    @Test
    fun removeClearsMarkerForOneAccountAndGroup() {
        val accountA =
            AutoAcceptedInviteMarkers.upsert(
                encoded = emptySet(),
                accountRef = "account-a",
                groupIdHex = "group-a",
                invitedAtMs = 1_000L,
                inviterAccountIdHex = "alice",
            )
        val withAccountB =
            AutoAcceptedInviteMarkers.upsert(
                encoded = accountA,
                accountRef = "account-b",
                groupIdHex = "group-a",
                invitedAtMs = 1_000L,
                inviterAccountIdHex = "bob",
            )

        val removed = AutoAcceptedInviteMarkers.remove(withAccountB, "account-a", "GROUP-A")

        assertNull(AutoAcceptedInviteMarkers.markerFor(removed, "account-a", "group-a"))
        assertEquals("bob", AutoAcceptedInviteMarkers.markerFor(removed, "account-b", "group-a")?.inviterAccountIdHex)
    }
}
