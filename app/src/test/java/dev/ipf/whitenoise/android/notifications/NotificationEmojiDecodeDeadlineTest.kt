package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationEmojiDecodeDeadlineTest {
    @Test
    fun blockedImageIpcCannotDelayFirstTextPostUntilTheCodecDeadline() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val manager = context.getSystemService(NotificationManager::class.java)
            val interrupted = CountDownLatch(1)
            IsolatedMediaDecoder.installTestTransport(blockedTransport(interrupted))
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            manager.cancelAll()
            try {
                val presenter =
                    LocalNotificationPresenter(
                        context,
                        groupReconciliation = {},
                        enrichmentLauncher = {},
                        emojiArtworkPreparer = { _, _ ->
                            decodeNotificationEmojiInterruptibly(byteArrayOf(1))
                            null
                        },
                        emojiArtworkTimeoutMs = 250L,
                    )
                presenter.ensureChannels()
                val update = alertBudgetUpdate("a".repeat(64), 1L).copy(previewText = "Hello :custom:")
                withTimeout(3000L) {
                    assertTrue(presenter.show(update, shortNpub = { "npub1fixture" }))
                }
                assertTrue(interrupted.await(3, TimeUnit.SECONDS))
                assertEquals(1, manager.activeNotifications.size)
                val style =
                    requireNotNull(
                        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                            manager.activeNotifications.single().notification,
                        ),
                    )
                assertTrue(
                    style.messages
                        .last()
                        .text
                        .toString()
                        .contains("Hello"),
                )
            } finally {
                manager.cancelAll()
                IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
            }
        }

    private fun blockedTransport(interrupted: CountDownLatch): MediaDecodeTransport =
        object : MediaDecodeTransport by TestMediaDecodeTransport {
            override fun decode(
                bytes: ByteArray,
                edge: Int,
                oriented: Boolean,
                operation: Int,
            ): Bitmap? {
                try {
                    CountDownLatch(1).await(10, TimeUnit.SECONDS)
                } catch (error: InterruptedException) {
                    interrupted.countDown()
                    throw error
                }
                return null
            }
        }
}
