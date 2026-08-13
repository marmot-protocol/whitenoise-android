package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.chats.newchat.selectedMemberAvatarUrl
import dev.ipf.whitenoise.android.ui.chats.newchat.selectedMemberDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileParityTest {
    @Test
    fun sparseCachedProfileFallsBackToDiscoveryMetadataFieldByField() {
        val cached =
            UserProfileMetadataFfi(
                name = "cached-name",
                displayName = "  ",
                about = "Cached about",
                picture = "http://unsafe.example/cached.png",
                banner = null,
                nip05 = "not-an-identifier",
                lud16 = null,
            )
        val discovered =
            UserProfileMetadataFfi(
                name = "discovered-name",
                displayName = "Discovered Name",
                about = "Discovered about",
                picture = "https://example.com/discovered.png",
                banner = "https://example.com/banner.png",
                nip05 = "person@example.com",
                lud16 = "person@example.com",
            )

        val resolved =
            resolveProfileSheetMetadata(
                cached = cached,
                discovered = discovered,
                cachedAvatarUrl = null,
            )

        assertEquals("Discovered Name", resolved.displayName)
        assertEquals("Cached about", resolved.about)
        // Protocol image URLs stay field-authoritative here; MDK owns their
        // scheme/host classification at the network boundary.
        assertEquals("http://unsafe.example/cached.png", resolved.pictureUrl)
        assertEquals("https://example.com/banner.png", resolved.bannerUrl)
        assertEquals("person@example.com", resolved.nip05)
        assertEquals("person@example.com", resolved.lightningAddress)
    }

    @Test
    fun completeCachedProfileRemainsAuthoritativeOverDiscoveryMetadata() {
        val cached =
            UserProfileMetadataFfi(
                name = "cached-name",
                displayName = "Cached Name",
                about = "Cached about",
                picture = "https://example.com/cached.png",
                banner = "https://example.com/cached-banner.png",
                nip05 = "cached@example.com",
                lud16 = "cached@example.com",
            )
        val discovered =
            UserProfileMetadataFfi(
                name = "discovered-name",
                displayName = "Discovered Name",
                about = "Discovered about",
                picture = "https://example.com/discovered.png",
                banner = "https://example.com/discovered-banner.png",
                nip05 = "discovered@example.com",
                lud16 = "discovered@example.com",
            )

        val resolved =
            resolveProfileSheetMetadata(
                cached = cached,
                discovered = discovered,
                cachedAvatarUrl = "https://example.com/presentation.png",
            )

        assertEquals("Cached Name", resolved.displayName)
        assertEquals("Cached about", resolved.about)
        assertEquals("https://example.com/presentation.png", resolved.pictureUrl)
        assertEquals("https://example.com/cached-banner.png", resolved.bannerUrl)
        assertEquals("cached@example.com", resolved.nip05)
        assertEquals("cached@example.com", resolved.lightningAddress)
    }

    @Test
    fun followLoadFailureKeepsPreviousStateAndCancellationPropagates() =
        runTest {
            assertEquals(true, loadProfileFollowing(previous = true) { error("binding unavailable") })
            // An initial read failure stays unknown: reporting "not following"
            // would offer a follow to someone who may already be followed.
            assertNull(loadProfileFollowing(previous = null) { error("binding unavailable") })
            var failure: Throwable? = null
            try {
                loadProfileFollowing(previous = false) { throw CancellationException("closed") }
            } catch (error: Throwable) {
                failure = error
            }
            assertTrue(failure is CancellationException)
        }

    @Test
    fun noActiveAccountStaysUnknownRatherThanAssertingNotFollowing() =
        runTest {
            // The read returns null without an active account, matching the write
            // side, which throws — a false would enable a follow that must fail.
            assertNull(loadProfileFollowing(previous = null) { null })
            assertNull(loadProfileFollowing(previous = true) { null })
            val row = profileFollowRowState(following = null, loading = false, busy = false, creatingChat = false)
            assertFalse(row.enabled)
            assertFalse(row.inProgress)
            assertFalse(row.showsUnfollow)
        }

    @Test
    fun unknownFollowStatusDisablesTheRowWithoutSpinningOrPromisingAFollow() =
        runTest {
            val unknown = loadProfileFollowing(previous = null) { error("binding unavailable") }
            val row =
                profileFollowRowState(
                    following = unknown,
                    loading = false,
                    busy = false,
                    creatingChat = false,
                )
            assertFalse(row.enabled)
            assertFalse(row.inProgress)
            assertFalse(row.showsUnfollow)
        }

    @Test
    fun knownFollowStatusEnablesTheRowAndPicksTheOppositeAction() {
        val following =
            profileFollowRowState(following = true, loading = false, busy = false, creatingChat = false)
        assertTrue(following.enabled)
        assertTrue(following.showsUnfollow)

        val notFollowing =
            profileFollowRowState(following = false, loading = false, busy = false, creatingChat = false)
        assertTrue(notFollowing.enabled)
        assertFalse(notFollowing.showsUnfollow)

        val loading =
            profileFollowRowState(following = null, loading = true, busy = false, creatingChat = false)
        assertFalse(loading.enabled)
        assertTrue(loading.inProgress)
    }

    @Test
    fun selectedDiscoveryCandidateKeepsEphemeralNameAndAvatar() {
        val remoteAvatar = "https://example.com/jack.png"
        val candidate =
            RecipientSearch.Candidate(
                accountIdHex = "b".repeat(64),
                displayName = "Jack",
                npub = "npub1jack",
                searchProfile =
                    UserProfileMetadataFfi(
                        name = "jack",
                        displayName = "Jack",
                        about = null,
                        picture = remoteAvatar,
                        banner = null,
                        nip05 = null,
                        lud16 = null,
                    ),
            )

        assertEquals("Jack", selectedMemberDisplayName(candidate, liveDisplayName = null))
        assertEquals(remoteAvatar, selectedMemberAvatarUrl(candidate, localAvatarUrl = null))
        assertEquals(
            "https://example.com/local.png",
            selectedMemberAvatarUrl(candidate, "https://example.com/local.png"),
        )
    }

    @Test
    fun selectedMemberNameFollowsALateResolvingProfile() {
        // What ContactPickerScreen bakes in when a pasted npub is selected before
        // its profile has loaded.
        val frozen =
            RecipientSearch.Candidate(
                accountIdHex = "c".repeat(64),
                displayName = "npub1abcde…vwxyz",
                npub = "npub1abcdefghijklmnopqrstuvwxyz",
            )

        assertEquals("npub1abcde…vwxyz", selectedMemberDisplayName(frozen, liveDisplayName = null))
        assertEquals("npub1abcde…vwxyz", selectedMemberDisplayName(frozen, liveDisplayName = "  "))
        assertEquals("Camille", selectedMemberDisplayName(frozen, liveDisplayName = "Camille"))
    }
}
