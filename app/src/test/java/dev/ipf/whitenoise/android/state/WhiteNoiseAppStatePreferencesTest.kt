package dev.ipf.whitenoise.android.state

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        ContactNicknamePreferences.clearAllForAccount(preferences, "a")

        assertEquals(null, ContactNicknamePreferences.readNickname(preferences, "a", "contact-a"))
        assertEquals("long", ContactNicknamePreferences.readNickname(preferences, "a:long", "contact-a"))
    }
}
