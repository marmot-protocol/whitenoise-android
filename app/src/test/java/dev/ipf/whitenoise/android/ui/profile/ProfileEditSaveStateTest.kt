package dev.ipf.whitenoise.android.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditSaveStateTest {
    @Test
    fun loadedDraftIsSaveableOnlyWhileNormalizedMetadataDiffers() {
        val state = ProfileEditSaveState()
        val loaded =
            profileEditMetadata(
                displayName = " Alice ",
                about = " About ",
                picture = " https://example.com/picture.jpg ",
                banner = " https://example.com/banner.jpg ",
                nip05 = " alice@example.com ",
                lud16 = " alice@getalby.com ",
            )

        state.beginLoad(ACCOUNT_A)
        assertFalse(state.canSave(ACCOUNT_A, loaded))
        assertTrue(state.completeLoad(ACCOUNT_A, loaded))
        assertFalse(state.canSave(ACCOUNT_A, loaded))

        val edited = loaded.copy(about = "Changed")
        assertTrue(state.canSave(ACCOUNT_A, edited))
        assertFalse(state.canSave(ACCOUNT_A, loaded))
    }

    @Test
    fun submittedMetadataNormalizesEveryPublishedFieldExactlyOnce() {
        val metadata =
            profileEditMetadata(
                displayName = " Alice ",
                about = "   ",
                picture = " https://example.com/picture.jpg ",
                banner = " https://example.com/banner.jpg ",
                nip05 = " alice@example.com ",
                lud16 = " alice@getalby.com ",
            )

        assertEquals("Alice", metadata.name)
        assertEquals("Alice", metadata.displayName)
        assertNull(metadata.about)
        assertEquals("https://example.com/picture.jpg", metadata.picture)
        assertEquals("https://example.com/banner.jpg", metadata.banner)
        assertEquals("alice@example.com", metadata.nip05)
        assertEquals("alice@getalby.com", metadata.lud16)
    }

    @Test
    fun onlySuccessfulSaveAdvancesBaselineToSubmittedSnapshot() {
        val state = ProfileEditSaveState()
        val loaded = metadata(displayName = "Alice")
        val submitted = metadata(displayName = "Bob")
        state.beginLoad(ACCOUNT_A)
        state.completeLoad(ACCOUNT_A, loaded)

        assertFalse(state.completeSave(ACCOUNT_A, submitted, succeeded = false))
        assertTrue(state.canSave(ACCOUNT_A, submitted))

        assertTrue(state.completeSave(ACCOUNT_A, submitted, succeeded = true))
        assertFalse(state.canSave(ACCOUNT_A, submitted))
    }

    @Test
    fun successfulSaveKeepsEditsMadeInFlightDirty() {
        val state = ProfileEditSaveState()
        val loaded = metadata(displayName = "Alice")
        val submitted = metadata(displayName = "Bob")
        val editedWhileSaving = metadata(displayName = "Carol")
        state.beginLoad(ACCOUNT_A)
        state.completeLoad(ACCOUNT_A, loaded)

        assertTrue(state.completeSave(ACCOUNT_A, submitted, succeeded = true))

        assertTrue(state.canSave(ACCOUNT_A, editedWhileSaving))
    }

    @Test
    fun staleLoadAndSaveCompletionsCannotClaimNewAccountBaseline() {
        val state = ProfileEditSaveState()
        val accountAProfile = metadata(displayName = "Alice")
        val accountBProfile = metadata(displayName = "Bob")
        state.beginLoad(ACCOUNT_A)
        state.beginLoad(ACCOUNT_B)

        assertFalse(state.completeLoad(ACCOUNT_A, accountAProfile))
        assertFalse(state.profileLoaded)
        assertTrue(state.completeLoad(ACCOUNT_B, accountBProfile))
        assertFalse(state.completeSave(ACCOUNT_A, accountAProfile, succeeded = true))
        assertFalse(state.canSave(ACCOUNT_B, accountBProfile))
        assertTrue(state.canSave(ACCOUNT_B, accountBProfile.copy(about = "new")))
    }

    private fun metadata(displayName: String) =
        profileEditMetadata(
            displayName = displayName,
            about = "",
            picture = "",
            banner = "",
            nip05 = "",
            lud16 = "",
        )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
    }
}
