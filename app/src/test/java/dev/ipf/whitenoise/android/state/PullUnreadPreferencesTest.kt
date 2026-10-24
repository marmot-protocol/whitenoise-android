package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PullUnreadPreferencesTest {
    @Test fun optOutSurvivesRecreationWithoutChangingHorizontalBindings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = context.getSharedPreferences("pull-unread-test", Context.MODE_PRIVATE)
        storage.edit().clear().commit()
        val preferences = SwipePreferences(storage)
        assertTrue(preferences.state.pullUnread)
        preferences.set(SwipeBinding.ChatLeft, SwipeAction.PinUnpin)
        preferences.setPullUnread(false)
        val recreated = SwipePreferences(storage)
        assertFalse(recreated.state.pullUnread)
        assertEquals(SwipeAction.PinUnpin, recreated.state.chatLeft)
        recreated.reset()
        assertTrue(SwipePreferences(storage).state.pullUnread)
    }
}
