package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.NotificationProfileChat
import dev.ipf.whitenoise.android.notifications.profileTestUpdate
import dev.ipf.whitenoise.android.notifications.testProfile
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** An active notification profile is the last gate of the production post decision and never loosens it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationProfilePostGateTest {
    @Test
    fun anActiveProfileHoldsBackAllButAllowedChatsAndMentions() {
        val context = RuntimeEnvironment.getApplication()
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "account-a",
            )
        try {
            val ordinary = profileTestUpdate(groupIdHex = "other")
            val allowed = profileTestUpdate(groupIdHex = "group-a")
            assertTrue(state.shouldPostNotification(ordinary, false))

            val profiles = state.notificationProfiles
            val allowedChats = setOf(NotificationProfileChat("account-a", "group-a"))
            assertTrue(profiles.save(testProfile("sleep", allowedChats = allowedChats)))
            assertTrue(state.shouldPostNotification(ordinary, false))
            assertTrue(profiles.turnOn("sleep", null))

            assertFalse(state.shouldPostNotification(ordinary, false))
            assertFalse(state.shouldPostNotification(ordinary.copy(isDm = true), false))
            assertTrue(state.shouldPostNotification(allowed, false))
            assertTrue(state.shouldPostNotification(ordinary.copy(isMention = true), false))
            assertFalse(state.shouldPostNotification(allowed, engineMuted = true))
            assertFalse(state.shouldPostNotification(allowed.copy(accountRef = "account-b"), false))

            assertTrue(profiles.turnOff())
            assertTrue(state.shouldPostNotification(ordinary, false))
        } finally {
            state.mutationsScope.cancel()
        }
    }
}
