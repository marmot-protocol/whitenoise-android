package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalNotificationEnrichmentRaceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Grants notification access and starts each race from an empty tray. */
    @Before
    fun setUp() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        LocalNotificationPresenter(context).ensureChannels()
    }

    /** Releases the shared synchronizer hook and removes fixture notifications. */
    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        manager.cancelAll()
    }

    /** Conversation opening cancels an avatar enrichment that writes after the dismissal cutoff. */
    @Suppress("LongMethod") // The complete latch ordering is the regression contract for this race.
    @Test
    fun conversationDismissOwnsAvatarEnrichmentThatLandsAfterItsCutoff() {
        val conversation = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT, GROUP)
        val enrichmentReadyToWrite = CountDownLatch(1)
        val allowEnrichmentWrite = CountDownLatch(1)
        val enrichmentFinished = CountDownLatch(1)
        val dismissalAwaitingLock = CountDownLatch(1)
        val dismissFinished = CountDownLatch(1)
        val dismissFailure = AtomicReference<Throwable>()
        val postCount = AtomicInteger()
        val presenter =
            LocalNotificationPresenter(
                context = context,
                notificationPoster = { _, tag, id, notification ->
                    if (postCount.incrementAndGet() == 2) {
                        enrichmentReadyToWrite.countDown()
                        check(allowEnrichmentWrite.await(5, TimeUnit.SECONDS))
                    }
                    manager.notify(tag, id, notification)
                },
                avatarBitmapResolver = { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) },
                enrichmentLauncher = { block ->
                    Thread {
                        try {
                            runBlocking { block() }
                        } finally {
                            enrichmentFinished.countDown()
                        }
                    }.start()
                },
                dismissalRetryDelay = {},
            )
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onAwaitingLock(
                    op: ConversationCardOp,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.DISMISS_CANCEL &&
                        notificationTag == conversation.tag &&
                        notificationId == conversation.id
                    ) {
                        dismissalAwaitingLock.countDown()
                    }
                }
            }

        assertTrue(
            runBlocking {
                presenter.show(
                    messageUpdate(),
                    senderAvatarUrl = "https://example.com/alice.png",
                    shortNpub = { "npub1test" },
                )
            },
        )
        assertTrue(enrichmentReadyToWrite.await(5, TimeUnit.SECONDS))

        Thread {
            try {
                assertTrue(runBlocking { presenter.dismissConversationMessages(ACCOUNT, GROUP) })
            } catch (throwable: Throwable) {
                dismissFailure.set(throwable)
            } finally {
                dismissFinished.countDown()
            }
        }.start()
        assertTrue(dismissalAwaitingLock.await(5, TimeUnit.SECONDS))
        allowEnrichmentWrite.countDown()
        assertTrue(enrichmentFinished.await(5, TimeUnit.SECONDS))
        assertTrue(dismissFinished.await(5, TimeUnit.SECONDS))
        dismissFailure.get()?.let { throw it }

        assertEquals(2, postCount.get())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    /** Builds one message whose avatar URL forces the detached enrichment lane. */
    private fun messageUpdate() =
        NotificationUpdateFfi(
            notificationKey = "key",
            conversationKey = "conversation",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = ACCOUNT,
            accountIdHex = ACCOUNT,
            groupIdHex = GROUP,
            groupName = "General",
            isDm = false,
            isMention = false,
            messageIdHex = "msg-a",
            sender = user(SENDER, "Alice"),
            receiver = user("self", "Me"),
            previewText = "hello",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 1_000L,
            isFromSelf = false,
        )

    /** Creates the minimal notification identity used by the race fixture. */
    private fun user(
        accountIdHex: String,
        displayName: String,
    ) = NotificationUserFfi(
        accountIdHex = accountIdHex,
        displayName = displayName,
        pictureUrl = null,
    )

    private companion object {
        const val ACCOUNT = "account-a"
        const val GROUP = "group-a"
        const val SENDER = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
