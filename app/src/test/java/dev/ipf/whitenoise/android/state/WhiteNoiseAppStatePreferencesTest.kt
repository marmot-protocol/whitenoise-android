package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhiteNoiseAppStatePreferencesTest {
    private val preferences
        get() =
            RuntimeEnvironment
                .getApplication()
                .applicationContext
                .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun allowChatScreenshotsDefaultsOn() {
        assertTrue(ChatScreenshotPreferences.readAllowChatScreenshots(preferences))
    }

    @Test
    fun allowChatScreenshotsPersistsRoundTrip() {
        ChatScreenshotPreferences.writeAllowChatScreenshots(preferences, true)
        assertTrue(ChatScreenshotPreferences.readAllowChatScreenshots(preferences))

        ChatScreenshotPreferences.writeAllowChatScreenshots(preferences, false)
        assertFalse(ChatScreenshotPreferences.readAllowChatScreenshots(preferences))
    }

    @Test
    fun allowChatScreenshotsContextReaderUsesAppPreferences() {
        val context = RuntimeEnvironment.getApplication().applicationContext

        ChatScreenshotPreferences.writeAllowChatScreenshots(preferences, false)

        assertFalse(ChatScreenshotPreferences.readAllowChatScreenshots(context))
    }

    @Test
    fun longMessageCollapseDefaultsOnPerAccountGroup() {
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-a"))
    }

    @Test
    fun longMessageCollapsePersistsPerAccountGroup() {
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "account-a", "group-a", false)

        assertFalse(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-a"))
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-b"))
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-b", "group-a"))
    }

    @Test
    fun longMessageCollapseReenabledReturnsToDefault() {
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "account-a", "group-a", false)
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "account-a", "group-a", true)

        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-a"))
    }

    @Test
    fun longMessageCollapseNormalizesIds() {
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, " account-a ", " GROUP-A ", false)

        assertFalse(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-a"))
    }

    @Test
    fun longMessageCollapseIgnoresBlankAccountOrGroup() {
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "", "group-a", false)
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "account-a", "   ", false)

        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-a"))
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "", "group-a"))
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", ""))
    }

    @Test
    fun contactNicknameDefaultsAbsent() {
        assertEquals(null, ContactNicknamePreferences.readNickname(preferences, "account-a", "contact-a"))
    }

    @Test
    fun contactNicknamePersistsPerAccountAndContact() {
        ContactNicknamePreferences.writeNickname(preferences, "account-a", "CONTACT-A", "Alex Cousin")
        ContactNicknamePreferences.writeNickname(preferences, "account-b", "contact-a", "Alex Coworker")
        ContactNicknamePreferences.writeNickname(preferences, "account-a", "contact-b", "Other Alex")

        assertEquals("Alex Cousin", ContactNicknamePreferences.readNickname(preferences, "account-a", "contact-a"))
        assertEquals("Alex Coworker", ContactNicknamePreferences.readNickname(preferences, "account-b", "contact-a"))
        assertEquals("Other Alex", ContactNicknamePreferences.readNickname(preferences, "account-a", "contact-b"))
    }

    @Test
    fun contactNicknameBlankClearsOverride() {
        ContactNicknamePreferences.writeNickname(preferences, "account-a", "contact-a", "Alex Cousin")
        ContactNicknamePreferences.writeNickname(preferences, "account-a", "contact-a", "   ")

        assertEquals(null, ContactNicknamePreferences.readNickname(preferences, "account-a", "contact-a"))
    }

    @Test
    fun contactNicknameClearAllForAccountDoesNotClearSimilarPrefixes() {
        ContactNicknamePreferences.writeNickname(preferences, "a", "contact-a", "short")
        ContactNicknamePreferences.writeNickname(preferences, "a:long", "contact-a", "long")

        assertTrue(ContactNicknamePreferences.clearAllForAccount(preferences, "a"))

        assertEquals(null, ContactNicknamePreferences.readNickname(preferences, "a", "contact-a"))
        assertEquals("long", ContactNicknamePreferences.readNickname(preferences, "a:long", "contact-a"))
        assertFalse(ContactNicknamePreferences.clearAllForAccount(preferences, "missing"))
    }

    @Test
    fun contactNotesDefaultsAbsent() {
        assertEquals(null, ContactNotesPreferences.readNotes(preferences, "account-a", "contact-a"))
    }

    @Test
    fun contactNotesPersistsPerAccountAndContact() {
        ContactNotesPreferences.writeNotes(preferences, "account-a", "CONTACT-A", "Met at conference")
        ContactNotesPreferences.writeNotes(preferences, "account-b", "contact-a", "Coworker on project X")
        ContactNotesPreferences.writeNotes(preferences, "account-a", "contact-b", "Other notes")

        assertEquals("Met at conference", ContactNotesPreferences.readNotes(preferences, "account-a", "contact-a"))
        assertEquals("Coworker on project X", ContactNotesPreferences.readNotes(preferences, "account-b", "contact-a"))
        assertEquals("Other notes", ContactNotesPreferences.readNotes(preferences, "account-a", "contact-b"))
    }

    @Test
    fun contactNotesBlankClearsOverride() {
        ContactNotesPreferences.writeNotes(preferences, "account-a", "contact-a", "Remember birthday")
        ContactNotesPreferences.writeNotes(preferences, "account-a", "contact-a", "   ")

        assertEquals(null, ContactNotesPreferences.readNotes(preferences, "account-a", "contact-a"))
    }

    @Test
    fun contactNotesPreservesMultiline() {
        val notes = "Line one\nLine two\n\nLine four"
        ContactNotesPreferences.writeNotes(preferences, "account-a", "contact-a", notes)

        assertEquals(notes, ContactNotesPreferences.readNotes(preferences, "account-a", "contact-a"))
    }

    @Test
    fun contactNotesClearAllForAccountDoesNotClearSimilarPrefixes() {
        ContactNotesPreferences.writeNotes(preferences, "a", "contact-a", "short")
        ContactNotesPreferences.writeNotes(preferences, "a:long", "contact-a", "long")

        assertTrue(ContactNotesPreferences.clearAllForAccount(preferences, "a"))

        assertEquals(null, ContactNotesPreferences.readNotes(preferences, "a", "contact-a"))
        assertEquals("long", ContactNotesPreferences.readNotes(preferences, "a:long", "contact-a"))
        assertFalse(ContactNotesPreferences.clearAllForAccount(preferences, "missing"))
    }

    @Test
    fun contactNotesAccessPolicyUsesActiveAccountAndIgnoresSelf() {
        val accounts = listOf(account("account-a", "self-a"), account("account-b", "self-b"))

        assertNull(contactNicknameAccountRefForAccess("account-a", accounts, "self-a"))
        ContactNotesPreferences.writeNotes(preferences, "account-a", "contact-a", "Notes for Alex")
        ContactNotesPreferences.writeNotes(preferences, "account-b", "contact-a", "Other account notes")

        val activeAccountForContact = contactNicknameAccountRefForAccess("account-a", accounts, "contact-a")
        val otherAccountForContact = contactNicknameAccountRefForAccess("account-b", accounts, "contact-a")

        assertEquals(
            "Notes for Alex",
            ContactNotesPreferences.readNotes(preferences, activeAccountForContact, "contact-a"),
        )
        assertEquals(
            "Other account notes",
            ContactNotesPreferences.readNotes(preferences, otherAccountForContact, "contact-a"),
        )
    }

    @Test
    fun contactNicknameAccessPolicyUsesActiveAccountAndIgnoresSelf() {
        val accounts = listOf(account("account-a", "self-a"), account("account-b", "self-b"))

        assertEquals(
            "account-a",
            contactNicknameAccountRefForAccess("account-a", accounts, "contact-a"),
        )
        assertEquals(
            "account-a",
            contactNicknameAccountRefForAccess("account-a", accounts, "CONTACT-A"),
        )
        assertNull(contactNicknameAccountRefForAccess(null, accounts, "contact-a"))
        assertNull(contactNicknameAccountRefForAccess("account-a", accounts, "self-a"))
        assertNull(contactNicknameAccountRefForAccess("account-a", accounts, "SELF-A"))

        ContactNicknamePreferences.writeNickname(preferences, "account-a", "contact-a", "Alex Cousin")
        ContactNicknamePreferences.writeNickname(preferences, "account-b", "contact-a", "Alex Coworker")

        val activeAccountForContact = contactNicknameAccountRefForAccess("account-a", accounts, "contact-a")
        val otherAccountForContact = contactNicknameAccountRefForAccess("account-b", accounts, "contact-a")

        assertEquals(
            "Alex Cousin",
            ContactNicknamePreferences.readNickname(preferences, activeAccountForContact, "contact-a"),
        )
        assertEquals(
            "Alex Coworker",
            ContactNicknamePreferences.readNickname(preferences, otherAccountForContact, "contact-a"),
        )
    }

    @Test
    fun profilePresentationRevisionTracksProfileAndNicknameCountersSeparately() {
        assertEquals(
            ProfilePresentationRevision(profiles = 1, contactNicknames = 0),
            ProfilePresentationRevision(1, 0),
        )
        assertFalse(ProfilePresentationRevision(1, 0) == ProfilePresentationRevision(0, 1))
    }

    private fun account(
        label: String,
        accountIdHex: String,
    ): AccountSummaryFfi =
        AccountSummaryFfi(
            label = label,
            accountIdHex = accountIdHex,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )
}
