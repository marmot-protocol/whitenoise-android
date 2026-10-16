package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChatListSubscriptionOwnerTest {
    @Test
    fun explicitTeardownAndFinallyCloseTheCapturedHandleOnce() {
        val owner = ChatListSubscriptionOwner()
        var closes = 0
        val attempt = ChatListSubscriptionOwner.Attempt()
        attempt.chats = object : ChatsSubscriptionHandle {
            override fun snapshot(): List<AppGroupRecordFfi> = emptyList()
            override suspend fun next(): AppGroupRecordFfi? = null
            override fun close() { closes += 1 }
        }
        owner.install(attempt)
        val job = Job()
        owner.bindJob = job
        val captured = owner.take()
        assertSame(job, captured.second)
        captured.first?.close()
        owner.detach(attempt)
        attempt.close()
        assertEquals(1, closes)
        assertNull(owner.windows)
        assertNull(owner.bindJob)
    }

    @Test
    fun oldFinalizerCannotDetachTheNewSession() {
        val owner = ChatListSubscriptionOwner()
        val old = ChatListSubscriptionOwner.Attempt()
        val newer = ChatListSubscriptionOwner.Attempt()
        owner.install(old)
        owner.install(newer)
        owner.detach(old)
        assertSame(newer, owner.take().first)
    }

    @Test
    fun aFailingCloseDoesNotRepeatOrMaskTheOtherHandleCleanup() {
        var closes = 0
        val attempt = ChatListSubscriptionOwner.Attempt()
        attempt.chats = object : ChatsSubscriptionHandle {
            override fun snapshot(): List<AppGroupRecordFfi> = emptyList()
            override suspend fun next(): AppGroupRecordFfi? = null
            override fun close() {
                closes += 1
                error("close failed")
            }
        }
        attempt.close()
        attempt.close()
        assertEquals(1, closes)
    }
}
