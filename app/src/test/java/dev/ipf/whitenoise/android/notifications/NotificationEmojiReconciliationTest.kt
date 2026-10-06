package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationEmojiReconciliationTest {
    @get:Rule val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        manager.cancelAll()
        pruneNotificationEmojiArtwork(context, emptyArray())
        NotificationGroupReconciler.shared(context).close()
        NotificationChannels.ensureChannels(context)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
    }

    @Test
    fun blockedSummaryDoesNotAdoptLegacyCardsOrSpendWriteSlots() =
        runTest {
            val artifact = requireNotNull(notificationEmojiArtwork(context, ":wn:"))
            val sender =
                Person
                    .Builder()
                    .setName("Alice")
                    .setKey("alice")
                    .build()
            val style = NotificationCompat.MessagingStyle(sender)
            notificationEmojiMessages(":wn:", 1L, sender, artifact.uri).forEach { style.addMessage(it) }
            val card =
                NotificationCompat
                    .Builder(context, NotificationChannelSpec.GROUP_MESSAGES.id)
                    .setSmallIcon(R.drawable.ic_stat_whitenoise)
                    .setStyle(style)
                    .build()
            manager.notify("account|group", 0, card)
            artifact.close()
            manager.createNotificationChannel(
                NotificationChannel(
                    NotificationChannelSpec.USER_EVENT_SUMMARY.id,
                    "summary",
                    NotificationManager.IMPORTANCE_NONE,
                ),
            )
            var slots = 0
            var writes = 0
            val coordinator =
                NotificationGroupReconciler(
                    context,
                    backgroundScope,
                    pacer =
                        NotificationPostPacer(nowMillis = {
                            slots++
                            testScheduler.currentTime
                        }),
                    post = { _, _, _, _ -> writes++ },
                )
            coordinator.request()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(0, slots)
            assertEquals(0, writes)
            assertEquals(1, manager.activeNotifications.size)
            assertEquals(1, artifacts().size)
            coordinator.close()
        }

    @Test
    fun settlingWindowIsRetriedBeforeOrphanArtworkIsDeleted() =
        runTest {
            requireNotNull(notificationEmojiArtwork(context, ":wn:")).close()
            NotificationGroupWriteVisibility.childWritten(context)
            val coordinator = NotificationGroupReconciler(context, backgroundScope)
            coordinator.request()
            advanceTimeBy(200)
            runCurrent()
            assertFalse(artifacts().isEmpty())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(artifacts().isEmpty())
            assertTrue(manager.activeNotifications.isEmpty())
            coordinator.close()
        }

    @Test
    fun unknownTrayReadCannotRevokeArtworkAndRecoveryDoesNotReplayCards() =
        runTest {
            requireNotNull(notificationEmojiArtwork(context, ":wn:")).close()
            var fail = true
            val coordinator =
                NotificationGroupReconciler(
                    context,
                    backgroundScope,
                    read = { if (fail) error("synthetic unavailable tray") else manager.activeNotifications },
                )
            coordinator.request()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(1, artifacts().size)
            fail = false
            coordinator.request()
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(artifacts().isEmpty())
            assertTrue(manager.activeNotifications.isEmpty())
            coordinator.close()
        }

    private fun artifacts(): List<File> = File(context.cacheDir, "notification_emoji").listFiles().orEmpty().toList()
}
