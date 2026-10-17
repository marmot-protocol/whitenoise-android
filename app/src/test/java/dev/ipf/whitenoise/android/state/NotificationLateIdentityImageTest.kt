package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.notifications.ConversationCardOp
import dev.ipf.whitenoise.android.notifications.ConversationCardPostSynchronizer
import dev.ipf.whitenoise.android.notifications.ConversationCardTestHook
import dev.ipf.whitenoise.android.notifications.EXTRA_CONTENT_REDACTED
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import dev.ipf.whitenoise.android.notifications.NotificationPreviewPreferences
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.setAppLockScreenVisibleForTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val ACCOUNT_REF = "account-a"
private const val GROUP_ID = "group-a"

/**
 * #3311 through the production path: a typed update reaches AppState's notification listener, the first card posts
 * with the lettered circle because the sender's picture is cold, and once the post-write prewarm makes the picture
 * available the same card is rewritten once, silently. A dismissed or read card, an account switch, app lock and a
 * redacted hidden-preview card all leave it alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [FirstPostRecordingNotificationManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationLateIdentityImageTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val posts get() = FirstPostRecordingNotificationManager.posts

    /** Starts every scenario with notifications allowed, no fixture card and a cold avatar cache. */
    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        cancelFixtureCard()
        posts.clear()
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
    }

    /** Releases hooks, the card and avatar state so no scenario leaks into the next. */
    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
        cancelFixtureCard()
        runBlocking { NotificationPreviewPreferences.setEnabled(context, true) }
    }

    /**
     * A direct message whose picture loads after the first post gets that picture on the same live card, in its
     * identity slot and on its sender, as one write that does not alert again and keeps the text and timestamp. The
     * conversation shortcut is republished with the picture.
     */
    @Test
    fun latePictureSilentlyRewritesTheSameLiveCard() =
        runBlocking {
            withColdPicture { _, release ->
                val before = posts.size
                val first = posts.last()
                assertNotEquals("the first card shows the lettered circle", Color.MAGENTA, identityPixel(first))

                release()
                awaitPosts(before + 1)
                settle()

                assertEquals("one rewrite, no more", before + 1, posts.size)
                val rewritten = posts.last()
                assertTrue("no second alert", rewritten.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                assertEquals(Color.MAGENTA, identityPixel(rewritten))
                assertEquals(Color.MAGENTA, senderPixel(rewritten))
                assertEquals(first.`when`, rewritten.`when`)
                assertEquals(text(first), text(rewritten))
                assertEquals(Color.MAGENTA, identityPixel(checkNotNull(liveCard())))
                assertEquals(Color.MAGENTA, shortcutIconPixel())
            }
        }

    /** A card dismissed while its picture loads stays dismissed when the picture arrives. */
    @Test
    fun dismissedCardIsNotRewrittenByALatePicture() =
        runBlocking {
            withColdPicture { fixture, release ->
                val before = posts.size
                fixture.appState.dismissConversationNotifications(ACCOUNT_REF, GROUP_ID)

                release()
                awaitPictureThenSettle()

                assertEquals(before, posts.size)
                assertNull(liveCard())
            }
        }

    /** A card marked read while its picture loads is gone for good when the picture arrives. */
    @Test
    fun readCardIsNotRewrittenByALatePicture() =
        runBlocking {
            withColdPicture(messageIdHex = MESSAGE_ID_HEX) { fixture, release ->
                val before = posts.size
                assertTrue(fixture.appState.markNotificationMessageRead(ACCOUNT_REF, GROUP_ID, MESSAGE_ID_HEX))
                val key = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, GROUP_ID)
                LocalNotificationPresenter(context).dismissActionNotificationAndOlderSiblings(
                    notificationTag = key.tag,
                    notificationId = key.id,
                    actedMessageIdHex = MESSAGE_ID_HEX,
                    accountRef = ACCOUNT_REF,
                    groupIdHex = GROUP_ID,
                    sinceMs = System.currentTimeMillis(),
                )

                release()
                awaitPictureThenSettle()

                assertEquals(before, posts.size)
                assertNull(liveCard())
            }
        }

    /** An account switch that lands as the late picture reaches the card's write lock stops the rewrite. */
    @Test
    fun accountSwitchAtTheWriteStopsTheRewrite() =
        runBlocking {
            withColdPicture { fixture, release ->
                val reached =
                    atTheRewriteLock {
                        onMain { fixture.appState.clearCrossAccountCachesForTest() }
                    }
                val before = posts.size

                release()
                awaitLatch(reached)
                settle()

                assertEquals(before, posts.size)
                assertNotEquals(Color.MAGENTA, identityPixel(checkNotNull(liveCard())))
            }
        }

    /** App lock that rises as the late picture reaches the card's write lock stops the rewrite. */
    @Test
    fun appLockAtTheWriteStopsTheRewrite() =
        runBlocking {
            withColdPicture { fixture, release ->
                val reached = atTheRewriteLock { fixture.appState.setAppLockScreenVisibleForTest(true) }
                val before = posts.size

                release()
                awaitLatch(reached)
                settle()

                assertEquals(before, posts.size)
                assertNotEquals(Color.MAGENTA, identityPixel(checkNotNull(liveCard())))
            }
        }

    /** App lock that rises while the picture loads stops the rewrite, and unlocking later does not replay it. */
    @Test
    fun appLockWhileThePictureLoadsStopsTheRewrite() =
        runBlocking {
            withColdPicture { fixture, release ->
                val before = posts.size
                fixture.appState.setAppLockScreenVisibleForTest(true)

                release()
                awaitPictureThenSettle()
                fixture.appState.setAppLockScreenVisibleForTest(false)
                settle()

                assertEquals(before, posts.size)
                assertTrue("no write carried the picture", posts.none(::showsLatePicture))
            }
        }

    /**
     * A card posted while previews are hidden is a redacted, generic card, and stays one when its picture arrives,
     * including when previews are turned back on while the picture loads.
     */
    @Test
    fun hiddenPreviewCardIsNeverGivenAPicture() =
        runBlocking {
            assertTrue(NotificationPreviewPreferences.setEnabled(context, false))
            val release = CountDownLatch(1)
            val fixture = coldPictureFixture(release)
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
                assertTrue(NotificationPreviewPreferences.setEnabled(context, true))
                release.countDown()
                settle()

                val card = checkNotNull(liveCard())
                assertTrue(card.extras.getBoolean(NotificationPreviewPreferences.EXTRA_HIDDEN))
                assertTrue("the hidden card is redacted", card.extras.getBoolean(EXTRA_CONTENT_REDACTED))
                assertNull(card.getLargeIcon())
                assertTrue("no write carried the picture", posts.none(::showsLatePicture))
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /**
     * Posts the fixture DM with a cold sender picture whose download waits for [block]'s release, after any late
     * text correction has been written, so the scenario controls when the picture becomes available.
     */
    private suspend fun withColdPicture(
        messageIdHex: String = "message-a",
        block: suspend (NotificationBootstrapTestFixture, () -> Unit) -> Unit,
    ) {
        val release = CountDownLatch(1)
        val fetchStarted = CountDownLatch(1)
        val fixture = coldPictureFixture(release, fetchStarted, messageIdHex)
        try {
            fixture.bootstrap()
            fixture.awaitNotificationPosted()
            awaitLatch(fetchStarted)
            block(fixture) { release.countDown() }
        } finally {
            release.countDown()
            fixture.close()
        }
    }

    /** A direct-message fixture whose sender picture downloads only after [release], as a solid magenta PNG. */
    private fun coldPictureFixture(
        release: CountDownLatch,
        fetchStarted: CountDownLatch = CountDownLatch(1),
        messageIdHex: String = "message-a",
    ): NotificationBootstrapTestFixture {
        val png = magentaPng()
        return NotificationBootstrapTestFixture(
            context = context,
            isDm = true,
            senderPictureUrl = AVATAR_URL,
            messageIdHex = messageIdHex,
            profileImageDownload = { _, _ ->
                fetchStarted.countDown()
                release.await(FIXTURE_WAIT_SECONDS, TimeUnit.SECONDS)
                png
            },
        )
    }

    /** Runs [action] once, when the late-picture rewrite is about to take the card's write lock. */
    private fun atTheRewriteLock(action: () -> Unit): CountDownLatch {
        val reached = CountDownLatch(1)
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                /** Fires only for the enrichment lock, which only the late-picture rewrite takes here. */
                override fun onAwaitingLock(
                    op: ConversationCardOp,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (op == ConversationCardOp.SHOW_ENRICH && reached.count > 0L) {
                        action()
                        reached.countDown()
                    }
                }
            }
        return reached
    }

    /** Runs a main-confined production call from a background thread while the test pumps the main looper. */
    private fun onMain(action: () -> Unit) {
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            action()
            done.countDown()
        }
        check(done.await(FIXTURE_WAIT_SECONDS, TimeUnit.SECONDS)) { "main looper did not run the action" }
    }

    /** A small solid magenta PNG, so the picture that reaches the card is recognisable by its pixels. */
    private fun magentaPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            out.toByteArray()
        }
    }

    /** Waits until the platform has received [count] writes for the fixture card, pumping the main looper. */
    private suspend fun awaitPosts(count: Int) {
        withTimeout(FIXTURE_WAIT_MS) {
            while (posts.size < count) pump()
        }
    }

    /** Waits for the picture to be decoded, then lets any late rewrite settle. */
    private suspend fun awaitPictureThenSettle() {
        withTimeout(FIXTURE_WAIT_MS) {
            while (AvatarImageLoader.peekBitmap(AVATAR_URL) == null) pump()
        }
        settle()
    }

    /** Pumps the main looper for a window long enough for a pending rewrite to land. */
    private suspend fun settle() {
        val until = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < until) pump()
    }

    /** Waits for [latch] while pumping the main looper. */
    private suspend fun awaitLatch(latch: CountDownLatch) {
        withTimeout(FIXTURE_WAIT_MS) {
            while (latch.count > 0L) pump()
        }
    }

    /** Advances Robolectric's main looper by one step. */
    private suspend fun pump() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
        delay(1L)
    }

    /** The fixture card currently on the platform, if any. */
    private fun liveCard(): Notification? {
        val key = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, GROUP_ID)
        return context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.tag == key.tag && it.id == key.id }
            ?.notification
    }

    /** Removes only the fixture conversation's card. */
    private fun cancelFixtureCard() {
        val key = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, GROUP_ID)
        context.getSystemService(NotificationManager::class.java).cancel(key.tag, key.id)
    }

    /** The top-left pixel of the card's identity picture. */
    private fun identityPixel(card: Notification): Int =
        checkNotNull(card.getLargeIcon()?.loadDrawable(context)) { "no identity picture" }.toBitmap().getPixel(0, 0)

    /** True when a written card shows the late magenta picture in its identity slot. */
    private fun showsLatePicture(card: Notification): Boolean {
        val identity = card.getLargeIcon() ?: return false
        return checkNotNull(identity.loadDrawable(context)).toBitmap().getPixel(0, 0) == Color.MAGENTA
    }

    /** The top-left pixel of the newest message's sender picture. */
    private fun senderPixel(card: Notification): Int {
        val style = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card))
        val icon =
            checkNotNull(
                style.messages
                    .last()
                    .person
                    ?.icon,
            ) { "no sender picture" }
        return checkNotNull(icon.loadDrawable(context)).toBitmap().getPixel(0, 0)
    }

    /** The card's visible message text. */
    private fun text(card: Notification): List<String> =
        checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(card))
            .messages
            .map { it.text.toString() }

    /** The top-left pixel of the published conversation shortcut's icon, read from the platform record. */
    private fun shortcutIconPixel(): Int {
        val id = checkNotNull(conversationShortcutId(ACCOUNT_REF, GROUP_ID))
        val shortcut =
            context
                .getSystemService(ShortcutManager::class.java)
                .dynamicShortcuts
                .single { it.id == id }
        val icon = ShortcutInfo::class.java.getMethod("getIcon").invoke(shortcut) as Icon
        return checkNotNull(icon.loadDrawable(context)).toBitmap().getPixel(0, 0)
    }

    private companion object {
        const val AVATAR_URL = "https://profiles.example/alice.png"
        val MESSAGE_ID_HEX = "ab".repeat(32)
        const val PICTURE_PX = 16
        const val PNG_QUALITY = 100
        const val FIXTURE_WAIT_SECONDS = 10L
        const val FIXTURE_WAIT_MS = 30_000L
        const val SETTLE_MS = 500L
    }
}
