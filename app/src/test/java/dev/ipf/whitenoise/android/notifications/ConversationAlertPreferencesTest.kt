package dev.ipf.whitenoise.android.notifications

import android.content.Context
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

    private fun store(): ConversationAlertPreferences {
        val context = RuntimeEnvironment.getApplication().applicationContext
        return ConversationAlertPreferences(context, context.getSharedPreferences("alert-test", Context.MODE_PRIVATE))
    }
}
