package dev.ipf.whitenoise.android.notifications

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NotificationInboundStateTest {
    @Test
    fun recreatedActivityOwnerRetainsPendingTextAndRequestIdentityUntilHandled() {
        val store = ViewModelStore()
        val original = state(store)
        val target =
            NotificationTarget(
                "account-b",
                "group-b",
                "message-b",
                NotificationTargetKind.MESSAGE,
                NotificationReplyDraft("accepted-tap", "half typed"),
            )
        original.target = target
        original.requestId = 7

        val recreated = state(store)
        assertSame(original, recreated)
        assertEquals(target, recreated.target)
        assertEquals(7L, recreated.requestId)

        recreated.target = null
        assertNull(state(store).target)
        store.clear()
    }

    @Test
    fun separateTaskCannotInheritAnotherTasksPrivatePendingReply() {
        val firstStore = ViewModelStore()
        state(firstStore).target =
            NotificationTarget(
                "account-b",
                "group-b",
                null,
                NotificationTargetKind.MESSAGE,
                NotificationReplyDraft("accepted-tap", "private text"),
            )
        val secondStore = ViewModelStore()
        assertNull(state(secondStore).target)
        firstStore.clear()
        secondStore.clear()
    }

    private fun state(store: ViewModelStore): NotificationInboundState =
        ViewModelProvider(
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = store
            },
            ViewModelProvider.NewInstanceFactory(),
        )[NotificationInboundState::class.java]
}
