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

    /** Clears the real private preference file so defaults and persistence assertions do not depend on test order. */
    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    /** Unset choices follow the legacy reply direction, while explicit physical bindings survive recreation. */
    @Test fun swipePreferencesRetainPhysicalBindingsAndResetDefaults() {
        val first = SwipePreferences(preferences)
        assertEquals(SwipeAction.Reply, first.state.resolved(SwipeBinding.MessageRight, false))
        assertEquals(SwipeAction.Reply, first.state.resolved(SwipeBinding.MessageLeft, true))
        assertEquals(SwipeAction.Off, first.state.chatLeft)
        first.set(SwipeBinding.MessageLeft, SwipeAction.Reply)
        first.set(SwipeBinding.MessageRight, SwipeAction.Forward)
        first.set(SwipeBinding.ChatLeft, SwipeAction.MuteUnmute)
        val recreated = SwipePreferences(preferences)
        assertEquals(SwipeAction.Reply, recreated.state.resolved(SwipeBinding.MessageLeft, false))
        assertEquals(SwipeAction.Forward, recreated.state.resolved(SwipeBinding.MessageRight, true))
        assertEquals(SwipeAction.MuteUnmute, recreated.state.chatLeft)
        recreated.reset()
        assertEquals(SwipePreferenceState(), SwipePreferences(preferences).state)
    }

    /** Unknown versions and wrong-domain choices cannot silently enable a chat command. */
    @Test fun unknownSwipeValuesFallBackIndependently() {
        preferences
            .edit()
            .putString("swipe_action_MessageLeft", "future")
            .putString("swipe_action_ChatRight", "Reply")
            .putString("swipe_action_ChatLeft", "PinUnpin")
            .commit()
        val state = SwipePreferences(preferences).state
        assertEquals(SwipeAction.Default, state.messageLeft)
        assertEquals(SwipeAction.Off, state.chatRight)
        assertEquals(SwipeAction.PinUnpin, state.chatLeft)
    }

    /** An absent screenshot preference retains the existing enabled default. */
    @Test
    fun allowChatScreenshotsDefaultsOn() {
        assertTrue(ChatScreenshotPreferences.readAllowChatScreenshots(preferences))
    }

    /** Both explicit screenshot choices survive reads from the same private preference store. */
    @Test
    fun allowChatScreenshotsPersistsRoundTrip() {
        ChatScreenshotPreferences.writeAllowChatScreenshots(preferences, true)
        assertTrue(ChatScreenshotPreferences.readAllowChatScreenshots(preferences))

        ChatScreenshotPreferences.writeAllowChatScreenshots(preferences, false)
        assertFalse(ChatScreenshotPreferences.readAllowChatScreenshots(preferences))
    }

    /** The Context overload reads the same app-owned preference namespace as the direct store API. */
    @Test
    fun allowChatScreenshotsContextReaderUsesAppPreferences() {
        val context = RuntimeEnvironment.getApplication().applicationContext

        ChatScreenshotPreferences.writeAllowChatScreenshots(preferences, false)

        assertFalse(ChatScreenshotPreferences.readAllowChatScreenshots(context))
    }

    /** An unset account/conversation choice keeps long-message collapsing enabled. */
    @Test
    fun longMessageCollapseDefaultsOnPerAccountGroup() {
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-a"))
    }

    /** Disabling collapse affects only the exact account/conversation pair. */
    @Test
    fun longMessageCollapsePersistsPerAccountGroup() {
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "account-a", "group-a", false)

        assertFalse(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-a"))
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-b"))
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-b", "group-a"))
    }

    /** Re-enabling collapse restores its default rather than retaining an unnecessary override. */
    @Test
    fun longMessageCollapseReenabledReturnsToDefault() {
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "account-a", "group-a", false)
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "account-a", "group-a", true)

        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-a"))
    }

    /** Whitespace and hex case do not create a second conversation preference key. */
    @Test
    fun longMessageCollapseNormalizesIds() {
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, " account-a ", " GROUP-A ", false)

        assertFalse(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-a"))
    }

    /** Invalid ownership cannot store an override or change another pair's default. */
    @Test
    fun longMessageCollapseIgnoresBlankAccountOrGroup() {
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "", "group-a", false)
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, "account-a", "   ", false)

        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", "group-a"))
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "", "group-a"))
        assertTrue(LongMessageCollapsePreferences.readCollapseLongMessages(preferences, "account-a", ""))
    }

    /** App-state color access uses the active account and never falls back to legacy unscoped writes. */
    @Test
    fun globalBubbleColorReadsAndWritesUseTheActiveAccountScope() {
        BubbleColorPreferences.writeGlobalColor(
            preferences,
            accountRef = "account-a",
            theme = BubbleTheme.Dark,
            side = BubbleSide.Mine,
            argb = 0xFF112233,
        )
        val appState =
            WhiteNoiseAppState(
                context = RuntimeEnvironment.getApplication().applicationContext,
                draftStore = DraftStore(EmptyDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = listOf(account("account-a", "self-a"), account("account-b", "self-b")),
                activeAccountRef = "account-a",
            )

        assertEquals(0xFF112233, appState.globalBubbleColorArgb(BubbleTheme.Dark, BubbleSide.Mine))

        appState.updateGlobalBubbleColor(BubbleTheme.Dark, BubbleSide.Mine, 0xFF445566)

        assertEquals(
            0xFF445566,
            BubbleColorPreferences.readGlobalColor(
                preferences,
                accountRef = "account-a",
                theme = BubbleTheme.Dark,
                side = BubbleSide.Mine,
            ),
        )
        assertNull(BubbleColorPreferences.readLegacyGlobalColor(preferences, BubbleTheme.Dark, BubbleSide.Mine))
    }

    /** Rendering can read a represented account while an edit belongs only to the active account. */
    @Test
    fun actionColorReadsRepresentedAccountsAndWritesTheActiveAccount() {
        ActionColorPreferences.writeColor(preferences, "account-a", BubbleTheme.Dark, 0xFF112233)
        ActionColorPreferences.writeColor(preferences, "account-b", BubbleTheme.Dark, 0xFF445566)
        val appState =
            WhiteNoiseAppState(
                context = RuntimeEnvironment.getApplication().applicationContext,
                draftStore = DraftStore(EmptyDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = listOf(account("account-a", "self-a"), account("account-b", "self-b")),
                activeAccountRef = "account-a",
            )

        assertEquals(0xFF112233, appState.actionColorArgb(BubbleTheme.Dark))
        assertEquals(0xFF445566, appState.actionColorArgb(BubbleTheme.Dark, accountRef = "account-b"))

        appState.updateActionColor(BubbleTheme.Dark, 0xFF778899)

        assertEquals(0xFF778899, ActionColorPreferences.readColor(preferences, "account-a", BubbleTheme.Dark))
        assertEquals(0xFF445566, ActionColorPreferences.readColor(preferences, "account-b", BubbleTheme.Dark))
    }

    /** Absent private names leave public profile presentation as the fallback. */
    @Test
    fun contactNicknameDefaultsAbsent() {
        assertEquals(null, ContactNicknamePreferences.readNickname(preferences, "account-a", "contact-a"))
    }

    /** One contact can have different local names in two viewing accounts. */
    @Test
    fun contactNicknamePersistsPerAccountAndContact() {
        ContactNicknamePreferences.writeNickname(preferences, "account-a", "CONTACT-A", "Alex Cousin")
        ContactNicknamePreferences.writeNickname(preferences, "account-b", "contact-a", "Alex Coworker")
        ContactNicknamePreferences.writeNickname(preferences, "account-a", "contact-b", "Other Alex")

        assertEquals("Alex Cousin", ContactNicknamePreferences.readNickname(preferences, "account-a", "contact-a"))
        assertEquals("Alex Coworker", ContactNicknamePreferences.readNickname(preferences, "account-b", "contact-a"))
        assertEquals("Other Alex", ContactNicknamePreferences.readNickname(preferences, "account-a", "contact-b"))
    }

    /** Blank local names remove the override and restore public-name fallback. */
    @Test
    fun contactNicknameBlankClearsOverride() {
        ContactNicknamePreferences.writeNickname(preferences, "account-a", "contact-a", "Alex Cousin")
        ContactNicknamePreferences.writeNickname(preferences, "account-a", "contact-a", "   ")

        assertEquals(null, ContactNicknamePreferences.readNickname(preferences, "account-a", "contact-a"))
    }

    /** Length-prefixed cleanup protects private names belonging to similarly named accounts. */
    @Test
    fun contactNicknameClearAllForAccountDoesNotClearSimilarPrefixes() {
        ContactNicknamePreferences.writeNickname(preferences, "a", "contact-a", "short")
        ContactNicknamePreferences.writeNickname(preferences, "a:long", "contact-a", "long")

        assertTrue(ContactNicknamePreferences.clearAllForAccount(preferences, "a"))

        assertEquals(null, ContactNicknamePreferences.readNickname(preferences, "a", "contact-a"))
        assertEquals("long", ContactNicknamePreferences.readNickname(preferences, "a:long", "contact-a"))
        assertFalse(ContactNicknamePreferences.clearAllForAccount(preferences, "missing"))
    }

    /** An unset note remains absent rather than creating an empty private record. */
    @Test
    fun contactNotesDefaultsAbsent() {
        assertEquals(null, ContactNotesPreferences.readNotes(preferences, "account-a", "contact-a"))
    }

    /** Private notes for the same contact remain isolated by viewing account. */
    @Test
    fun contactNotesPersistsPerAccountAndContact() {
        ContactNotesPreferences.writeNotes(preferences, "account-a", "CONTACT-A", "Met at conference")
        ContactNotesPreferences.writeNotes(preferences, "account-b", "contact-a", "Coworker on project X")
        ContactNotesPreferences.writeNotes(preferences, "account-a", "contact-b", "Other notes")

        assertEquals("Met at conference", ContactNotesPreferences.readNotes(preferences, "account-a", "contact-a"))
        assertEquals("Coworker on project X", ContactNotesPreferences.readNotes(preferences, "account-b", "contact-a"))
        assertEquals("Other notes", ContactNotesPreferences.readNotes(preferences, "account-a", "contact-b"))
    }

    /** Blank notes delete the override so private storage does not accumulate empty records. */
    @Test
    fun contactNotesBlankClearsOverride() {
        ContactNotesPreferences.writeNotes(preferences, "account-a", "contact-a", "Remember birthday")
        ContactNotesPreferences.writeNotes(preferences, "account-a", "contact-a", "   ")

        assertEquals(null, ContactNotesPreferences.readNotes(preferences, "account-a", "contact-a"))
    }

    /** Note normalization keeps the user's internal line breaks. */
    @Test
    fun contactNotesPreservesMultiline() {
        val notes = "Line one\nLine two\n\nLine four"
        ContactNotesPreferences.writeNotes(preferences, "account-a", "contact-a", notes)

        assertEquals(notes, ContactNotesPreferences.readNotes(preferences, "account-a", "contact-a"))
    }

    /** Deleting one account's notes cannot erase another account with a matching textual prefix. */
    @Test
    fun contactNotesClearAllForAccountDoesNotClearSimilarPrefixes() {
        ContactNotesPreferences.writeNotes(preferences, "a", "contact-a", "short")
        ContactNotesPreferences.writeNotes(preferences, "a:long", "contact-a", "long")

        assertTrue(ContactNotesPreferences.clearAllForAccount(preferences, "a"))

        assertEquals(null, ContactNotesPreferences.readNotes(preferences, "a", "contact-a"))
        assertEquals("long", ContactNotesPreferences.readNotes(preferences, "a:long", "contact-a"))
        assertFalse(ContactNotesPreferences.clearAllForAccount(preferences, "missing"))
    }

    /** Notes access follows the active signing account and excludes its own public identity. */
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

    /** Nickname access rejects missing accounts and self identities while normalizing contact hex case. */
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

    /** Public-profile and private-name changes each invalidate presentation without overwriting the other revision. */
    @Test
    fun profilePresentationRevisionTracksProfileAndNicknameCountersSeparately() {
        assertEquals(
            ProfilePresentationRevision(profiles = 1, contactNicknames = 0),
            ProfilePresentationRevision(1, 0),
        )
        assertFalse(ProfilePresentationRevision(1, 0) == ProfilePresentationRevision(0, 1))
    }

    /** Creates distinct account labels and public identities so preference-isolation assertions exercise both keys. */
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

private class EmptyDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
