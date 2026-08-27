package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.UserProfileMetadataFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilePresentationRefreshMergeTest {
    @Test
    fun transientEmptyRefreshKeepsTheLastAuthoritativePresentation() {
        val current = ProfilePresentation(displayName = "Alice", avatarUrl = OLD_AVATAR)

        assertEquals(current, refreshedProfilePresentation(current, profile = null, rawDisplayName = null))
    }

    @Test
    fun nameOnlyRefreshUpdatesInPlaceWithoutClearingTheKnownAvatar() {
        val current = ProfilePresentation(displayName = "Alice", avatarUrl = OLD_AVATAR)

        assertEquals(
            ProfilePresentation(displayName = "Alice updated", avatarUrl = OLD_AVATAR),
            refreshedProfilePresentation(current, profile = null, rawDisplayName = "Alice updated"),
        )
    }

    @Test
    fun explicitAuthoritativeRemovalClearsIdentityFields() {
        val current = ProfilePresentation(displayName = "Alice", avatarUrl = OLD_AVATAR)
        val removed = profile(displayName = null, name = null, picture = null)

        val result = refreshedProfilePresentation(current, profile = removed, rawDisplayName = null)

        assertNull(result.displayName)
        assertNull(result.avatarUrl)
    }

    @Test
    fun newerAuthoritativeProfileReplacesTheRetainedValue() {
        val current = ProfilePresentation(displayName = "Alice", avatarUrl = OLD_AVATAR)
        val newer = profile(displayName = "Alice new", name = "alice", picture = NEW_AVATAR)

        assertEquals(
            ProfilePresentation(displayName = "Alice new", avatarUrl = NEW_AVATAR),
            refreshedProfilePresentation(current, profile = newer, rawDisplayName = null),
        )
    }

    private fun profile(
        displayName: String?,
        name: String?,
        picture: String?,
    ) = UserProfileMetadataFfi(
        name = name,
        displayName = displayName,
        about = null,
        picture = picture,
        nip05 = null,
        lud16 = null,
    )

    private companion object {
        const val OLD_AVATAR = "https://profiles.example/alice-old.jpg"
        const val NEW_AVATAR = "https://profiles.example/alice-new.jpg"
    }
}
