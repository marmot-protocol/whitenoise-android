package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
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
    fun failedCommitDoesNotReportMessageAsHidden() {
        val failingPreferences = CommitFailingPreferences(preferences)

        val updated = MessageHidePreferences.hideMessage(failingPreferences, "account-a", "group-a", "msg-1")

        assertNull(updated)
        assertTrue(MessageHidePreferences.readHiddenMessageIds(preferences, "account-a", "group-a").isEmpty())
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
    fun preferenceKeyDoesNotTreatAccountDelimiterAsGroupBoundary() {
        val accountPrefix = MessageHidePreferences.accountKeyPrefix("account")!!
        val delimitedAccountKey = MessageHidePreferences.preferenceKey("account:with-delimiter", "group-a")!!

        assertFalse(delimitedAccountKey.startsWith(accountPrefix))
        assertTrue(
            delimitedAccountKey.startsWith(
                MessageHidePreferences.accountKeyPrefix("account:with-delimiter")!!,
            ),
        )
    }

    @Test
    fun filterHiddenTimelineMessageIdsRemovesOnlyMatchingIds() {
        val ids = listOf("msg-1", "MSG-2", "msg-3", "", "  ")

        assertEquals(
            listOf("msg-3", "", "  "),
            filterHiddenTimelineMessageIds(ids, setOf("msg-1", "msg-2")),
        )
        assertFalse(isTimelineMessageVisible("MSG-1", setOf("msg-1")))
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

    private class CommitFailingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = CommitFailingEditor(delegate.edit())
    }

    private class CommitFailingEditor(
        private val delegate: SharedPreferences.Editor,
    ) : SharedPreferences.Editor by delegate {
        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            delegate.putStringSet(key, values)
            return this
        }

        override fun commit(): Boolean {
            // Android updates SharedPreferences' in-memory view before the disk
            // write completes. Model a failed disk result after that mutation so
            // local cleanup cannot disappear only until process restart.
            delegate.commit()
            return false
        }
    }
}
