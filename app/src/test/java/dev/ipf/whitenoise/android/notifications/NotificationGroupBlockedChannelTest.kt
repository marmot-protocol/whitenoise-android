package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** User blocking controls must not compete with child writes for the shared rate budget. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationGroupBlockedChannelTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        manager.cancelAll()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationGroupReconciler.shared(context).close()
        NotificationChannels.ensureChannels(context)
    }

    @Test
    fun blockedSummaryChannelSpendsNoSlotsAndRecoversAfterItIsUnblocked() {
        runTest { verifyBlockedSummary(blockGroup = false) }
    }

    @Test
    fun blockedSummaryChannelGroupSpendsNoSlotsAndRecoversAfterItIsUnblocked() {
        runTest { verifyBlockedSummary(blockGroup = true) }
    }

    private suspend fun TestScope.verifyBlockedSummary(blockGroup: Boolean) {
        val fixture = GroupFixture(context, backgroundScope)
        fixture.coordinator.close()
        fixture.send("account-a", "group-a", "one")
        setBlocked(blockGroup, true)
        var sleeps = 0
        var posts = 0
        val pacer = NotificationPostPacer(burstCapacity = 1, nowMillis = { 0L }, sleep = { sleeps++ })
        val coordinator =
            NotificationGroupReconciler(
                context,
                backgroundScope,
                pacer,
                post = { compat, tag, id, card ->
                    posts++
                    compat.notify(tag, id, card)
                },
            )
        try {
            repeat(3) {
                coordinator.request()
                settle()
            }
            assertEquals(0, posts)
            assertEquals(0, sleeps)
            assertEquals(0L, pacer.awaitSlot())
            assertEquals(0, sleeps)
            assertNull(fixture.summary())
            assertTrue(manager.activeNotifications.any { it.tag == "account-a|group-a" })
            setBlocked(blockGroup, false)
            coordinator.request()
            settle()
            assertEquals(1, posts)
            assertNotNull(fixture.summary())
        } finally {
            coordinator.close()
        }
    }

    private fun setBlocked(
        blockGroup: Boolean,
        blocked: Boolean,
    ) {
        val channel = requireNotNull(manager.getNotificationChannel(NotificationChannelSpec.USER_EVENT_SUMMARY.id))
        if (blockGroup) {
            val group = requireNotNull(manager.getNotificationChannelGroup(requireNotNull(channel.group)))
            // Android Settings owns this hidden setter; simulate that user action in the framework fixture.
            ReflectionHelpers.callInstanceMethod<Void>(
                group,
                "setBlocked",
                ClassParameter.from(requireNotNull(Boolean::class.javaPrimitiveType), blocked),
            )
            manager.createNotificationChannelGroup(group)
            assertEquals(blocked, manager.getNotificationChannelGroup(group.id).isBlocked)
        } else {
            val level = if (blocked) NotificationManager.IMPORTANCE_NONE else NotificationManager.IMPORTANCE_DEFAULT
            channel.importance = level
            manager.createNotificationChannel(channel)
            assertEquals(level, manager.getNotificationChannel(channel.id).importance)
        }
    }

    private fun TestScope.settle() {
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2_000))
        advanceTimeBy(2_000)
        runCurrent()
    }
}
