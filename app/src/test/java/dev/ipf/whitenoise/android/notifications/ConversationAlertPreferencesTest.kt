package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationAlertPreferencesTest {
    @Test
    fun existingAccountsKeepTheirLegacyChoiceUntilExplicitlyChanged() {
        val store = store()
        assertNull(store.choice("account", "group", NotificationChannelSpec.GROUP_MESSAGES))
        assertNull(store.choice("account", "group", NotificationChannelSpec.MENTIONS))
        assertTrue(store.state.value.isEmpty())
    }

    @Test
    fun switchesPersistIndependentlyAndStayScopedToTheirAccountAndChat() {
        val store = store()
        assertTrue(store.setEnabled("account", "group", NotificationChannelSpec.GROUP_MESSAGES, false))
        assertTrue(store.setEnabled("account", "group", NotificationChannelSpec.MENTIONS, true))
        assertFalse(store.choice("account", "group", NotificationChannelSpec.GROUP_MESSAGES)!!)
        assertTrue(store.choice("account", "group", NotificationChannelSpec.MENTIONS)!!)
        assertNull(store.choice("other", "group", NotificationChannelSpec.GROUP_MESSAGES))
        assertNull(store.choice("account", "other", NotificationChannelSpec.GROUP_MESSAGES))
        assertNull(store.choice("account", "group", NotificationChannelSpec.REACTIONS))
        assertEquals(false, store().choice("account", "group", NotificationChannelSpec.GROUP_MESSAGES))
    }

    @Test
    fun directMessagesAreIndependentAndUnsupportedCategoriesCannotBeHidden() {
        val store = store()
        assertTrue(store.setEnabled("account", "dm", NotificationChannelSpec.DIRECT_MESSAGES, false))
        assertNull(store.choice("account", "dm", NotificationChannelSpec.GROUP_MESSAGES))
        assertFalse(store.setEnabled("account", "dm", NotificationChannelSpec.GROUP_MEMBERSHIP, false))
        assertFalse(store.setEnabled("", "dm", NotificationChannelSpec.MENTIONS, false))
    }

    @Test
    fun accountRemovalErasesItsAlertChoicesButKeepsOtherAccounts() {
        val store = store()
        store.setEnabled("removed", "group", NotificationChannelSpec.MENTIONS, false)
        store.setEnabled("kept", "group", NotificationChannelSpec.MENTIONS, false)
        assertTrue(store.clearAccount("removed"))
        assertNull(store.choice("removed", "group", NotificationChannelSpec.MENTIONS))
        assertEquals(false, store.choice("kept", "group", NotificationChannelSpec.MENTIONS))
        val restarted = store()
        assertNull(restarted.choice("removed", "group", NotificationChannelSpec.MENTIONS))
        assertTrue(restarted.retainAccounts(listOf("kept")))
        assertEquals(false, restarted.choice("kept", "group", NotificationChannelSpec.MENTIONS))
        assertTrue(restarted.retainAccounts(emptyList()))
        assertTrue(store().state.value.isEmpty())
    }

    @Test
    fun failedSavingRestoresThePreviousInMemoryPreference() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val delegate = context.getSharedPreferences("alert-failure-test", Context.MODE_PRIVATE)
        val failing =
            object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putBoolean(
                            key: String?,
                            value: Boolean,
                        ): SharedPreferences.Editor {
                            editor.putBoolean(key, value)
                            return this
                        }

                        override fun commit(): Boolean {
                            editor.commit()
                            return false
                        }
                    }
                }
            }
        val store = ConversationAlertPreferences(context, failing)
        assertFalse(store.setEnabled("account", "group", NotificationChannelSpec.MENTIONS, false))
        assertNull(store.choice("account", "group", NotificationChannelSpec.MENTIONS))
        val restarted = ConversationAlertPreferences(context, delegate)
        assertNull(restarted.choice("account", "group", NotificationChannelSpec.MENTIONS))
    }

    private fun store(): ConversationAlertPreferences {
        val context = RuntimeEnvironment.getApplication().applicationContext
        return ConversationAlertPreferences(context, context.getSharedPreferences("alert-test", Context.MODE_PRIVATE))
    }
}
