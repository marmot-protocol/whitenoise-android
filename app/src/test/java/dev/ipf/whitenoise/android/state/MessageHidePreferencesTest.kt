package dev.ipf.whitenoise.android.state

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageHidePreferencesTest {
    private val preferences
        get() =
            RuntimeEnvironment
                .getApplication()
                .applicationContext
                .getSharedPreferences("whitenoise-hide-test", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun hiddenMessageIdsDefaultEmptyPerAccountGroup() {
        assertTrue(
            MessageHidePreferences.readHiddenMessageIds(preferences, "account-a", "group-a").isEmpty(),
        )
    }

    @Test
    fun hideMessagePersistsPerAccountGroup() {
        val updated =
            MessageHidePreferences.hideMessage(
                preferences,
                "account-a",
                "group-a",
                "MSG-1",
            )

        assertEquals(setOf("msg-1"), updated)
        assertEquals(
            setOf("msg-1"),
            MessageHidePreferences.readHiddenMessageIds(preferences, "account-a", "group-a"),
        )
        assertTrue(MessageHidePreferences.readHiddenMessageIds(preferences, "account-a", "group-b").isEmpty())
        assertTrue(MessageHidePreferences.readHiddenMessageIds(preferences, "account-b", "group-a").isEmpty())
    }

    @Test
    fun hideMessageNormalizesIds() {
        MessageHidePreferences.hideMessage(preferences, " account-a ", " GROUP-A ", " MSG-1 ")

        assertEquals(
            setOf("msg-1"),
            MessageHidePreferences.readHiddenMessageIds(preferences, "account-a", "group-a"),
        )
    }

    @Test
    fun hideMessageIgnoresBlankAccountOrGroup() {
        MessageHidePreferences.hideMessage(preferences, "", "group-a", "msg-1")
        MessageHidePreferences.hideMessage(preferences, "account-a", "   ", "msg-1")

        assertTrue(MessageHidePreferences.readHiddenMessageIds(preferences, "account-a", "group-a").isEmpty())
        assertTrue(MessageHidePreferences.readHiddenMessageIds(preferences, "", "group-a").isEmpty())
        assertTrue(MessageHidePreferences.readHiddenMessageIds(preferences, "account-a", "").isEmpty())
    }

    @Test
    fun clearAccountRemovesAllGroupKeysForAccount() {
        MessageHidePreferences.hideMessage(preferences, "account-a", "group-a", "msg-1")
        MessageHidePreferences.hideMessage(preferences, "account-a", "group-b", "msg-2")
        MessageHidePreferences.hideMessage(preferences, "account-b", "group-a", "msg-3")

        MessageHidePreferences.clearAccount(preferences, "account-a")

        assertTrue(MessageHidePreferences.readHiddenMessageIds(preferences, "account-a", "group-a").isEmpty())
        assertTrue(MessageHidePreferences.readHiddenMessageIds(preferences, "account-a", "group-b").isEmpty())
        assertEquals(
            setOf("msg-3"),
            MessageHidePreferences.readHiddenMessageIds(preferences, "account-b", "group-a"),
        )
    }

    @Test
    fun filterHiddenTimelineMessageIdsRemovesOnlyMatchingIds() {
        val ids = listOf("msg-1", "msg-2", "msg-3", "", "  ")

        assertEquals(
            listOf("msg-3", "", "  "),
            filterHiddenTimelineMessageIds(ids, setOf("msg-1", "msg-2")),
        )
    }

    @Test
    fun filterHiddenTimelineMessageIdsNoOpWhenEmpty() {
        val ids = listOf("msg-1", "msg-2")

        assertEquals(ids, filterHiddenTimelineMessageIds(ids, emptySet()))
    }

    @Test
    fun hideMessageWritesDefensiveCopy() {
        val ids = linkedSetOf("msg-1")
        val key = MessageHidePreferences.preferenceKey("account-a", "group-a")!!
        MessageHidePreferences.writeHiddenMessageIdsByKey(preferences, key, ids)
        ids.add("msg-2")

        assertEquals(
            setOf("msg-1"),
            MessageHidePreferences.readHiddenMessageIds(preferences, "account-a", "group-a"),
        )
    }
}
