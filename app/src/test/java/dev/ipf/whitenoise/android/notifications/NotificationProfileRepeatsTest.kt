package dev.ipf.whitenoise.android.notifications

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Held-back senders are remembered briefly, by hash only, and survive a process restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationProfileRepeatsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("notification-profile-repeats-test", Context.MODE_PRIVATE)

    @Test
    fun aHeldSenderIsRememberedForThreeMinutesAcrossARestart() {
        val now = System.currentTimeMillis()
        val update = profileTestUpdate(sender = "ABCD")
        val first = NotificationProfileRepeats(context, preferences)
        assertNull(first.heldAtMs(update, now))
        first.recordHeld(update, now)
        assertEquals(now, first.heldAtMs(profileTestUpdate(groupIdHex = "elsewhere", sender = "abcd"), now + 1_000L))
        assertNull(first.heldAtMs(update, now + NOTIFICATION_PROFILE_REPEAT_WINDOW_MS))
        assertNull(first.heldAtMs(profileTestUpdate(accountRef = "account-b", sender = "abcd"), now))

        val reopened = NotificationProfileRepeats(context, preferences)
        assertEquals(now, reopened.heldAtMs(update, now + 1_000L))
        assertTrue(preferences.all.keys.none { it.contains("abcd") || it.contains("account-a") })
    }

    @Test
    fun oldAndForgottenAccountsArePrunedAndTheRecordStaysBounded() {
        val now = System.currentTimeMillis()
        val repeats = NotificationProfileRepeats(context, preferences)
        repeats.recordHeld(profileTestUpdate(sender = "old"), now - NOTIFICATION_PROFILE_REPEAT_WINDOW_MS)
        repeat(80) { repeats.recordHeld(profileTestUpdate(sender = "s$it"), now + it) }
        assertNull(repeats.heldAtMs(profileTestUpdate(sender = "old"), now))
        assertNull(repeats.heldAtMs(profileTestUpdate(sender = "s0"), now + 80))
        assertEquals(now + 79, repeats.heldAtMs(profileTestUpdate(sender = "s79"), now + 80))
        assertEquals(64, preferences.all.size)

        repeats.retainAccounts(listOf("account-b"))
        assertTrue(preferences.all.isEmpty())
        assertNull(repeats.heldAtMs(profileTestUpdate(sender = "s79"), now + 80))
    }
}
