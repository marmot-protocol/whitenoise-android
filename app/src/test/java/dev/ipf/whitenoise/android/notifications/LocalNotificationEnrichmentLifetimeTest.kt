package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import dev.ipf.whitenoise.android.notifications.UserEventNotificationGroup.EXTRA_GENERATION
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the presenter's actual dispatch connection with delayed image work and real card generations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalNotificationEnrichmentLifetimeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    /** Starts from an empty synthetic tray with posting allowed. */
    @Before
    fun setUp() {
        manager.cancelAll()
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Leaves no fixture card or global barrier behind for the following suite. */
    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        manager.cancelAll()
    }

    /** The initial post returns with one detached reference, which the actual enrichment write then retires. */
    @Test
    fun presenterRetainsOnlyTheDetachedReferenceAfterItsInitialPost() =
        runBlocking {
            var pending: (suspend () -> Unit)? = null
            var resolutions = 0
            val posts = mutableListOf<Notification>()
            val presenter =
                presenter(
                    launcher = { pending = it },
                    resolveAvatar = { url ->
                        if (url == null) null else picture().also { resolutions++ }
                    },
                    posts = posts,
                )
            presenter.ensureChannels()
            assertTrue(
                presenter.show(
                    alertBudgetUpdate("first", 1_000),
                    conversationAvatarUrl = "https://example.com/general.png",
                    shortNpub = { "npub1fixture" },
                ),
            )
            assertEquals(1, posts.size)
            assertEquals(0, resolutions)
            val generationId = checkNotNull(posts.single().extras.getString(EXTRA_GENERATION))
            val probe = NotificationCardGenerations.register(generationId)
            try {
                assertEquals("probe plus the presenter's detached owner", 2, probe.references)
                checkNotNull(pending).invoke()
                pending = null
                assertEquals(2, posts.size)
                assertEquals(1, resolutions)
                assertTrue(posts.last().isOnlyAlertOnce())
                assertEquals(generationId, posts.last().extras.getString(EXTRA_GENERATION))
                assertEquals("only the test probe remains", 1, probe.references)
            } finally {
                try {
                    pending?.invoke()
                } finally {
                    NotificationCardGenerations.release(probe)
                }
            }
        }

    /** A caller's invalidation during a real suspended avatar read prevents an old write across accounts. */
    @Test
    fun eligibilityLostDuringAvatarReadLeavesBothAccountsCardsUnchanged() =
        runBlocking {
            var pending: (suspend () -> Unit)? = null
            val avatarStarted = CompletableDeferred<Unit>()
            val avatarReady = CompletableDeferred<Bitmap>()
            val isPostAllowed = AtomicBoolean(true)
            val posts = mutableListOf<Notification>()
            val presenter =
                presenter(
                    launcher = { pending = it },
                    resolveAvatar = { url ->
                        if (url == null) {
                            null
                        } else {
                            avatarStarted.complete(Unit)
                            avatarReady.await()
                        }
                    },
                    posts = posts,
                )
            presenter.ensureChannels()
            assertTrue(
                presenter.show(
                    alertBudgetUpdate("first", 1_000),
                    conversationAvatarUrl = "https://example.com/general.png",
                    isPostStillAllowed = isPostAllowed::get,
                    shortNpub = { "npub1fixture" },
                ),
            )
            val enrichment = async(start = CoroutineStart.UNDISPATCHED) { checkNotNull(pending).invoke() }
            try {
                withTimeout(5_000) { avatarStarted.await() }
                isPostAllowed.set(false)
                assertTrue(
                    presenter.show(
                        alertBudgetUpdate("other", 2_000, accountRef = "account-b"),
                        shortNpub = { "npub1fixture" },
                    ),
                )
                avatarReady.complete(picture())
                withTimeout(5_000) { enrichment.await() }
                assertEquals(2, posts.size)
                assertEquals(2, manager.activeNotifications.size)
                assertTrue(presenter.dismissConversationMessages("account-a", "group-a"))
                val remaining = manager.activeNotifications.single().notification
                assertEquals("other", conversationCardMessageIdHex(remaining))
                assertFalse(remaining.extras.getString(EXTRA_GENERATION).isNullOrBlank())
            } finally {
                avatarReady.complete(picture())
                enrichment.cancelAndJoin()
            }
        }

    /** Dismissing during a held avatar read cannot restore its card or shortcut, or cancel another account. */
    @Test
    fun dismissalDuringAvatarReadKeepsOtherAccountAndPreventsResurrection() =
        runBlocking {
            var pending: (suspend () -> Unit)? = null
            val avatarStarted = CompletableDeferred<Unit>()
            val avatarReady = CompletableDeferred<Bitmap>()
            val posts = mutableListOf<Notification>()
            var shortcutWrites = 0
            val presenter =
                presenter(
                    launcher = { pending = it },
                    resolveAvatar = { url ->
                        if (url == null) {
                            null
                        } else {
                            avatarStarted.complete(Unit)
                            avatarReady.await()
                        }
                    },
                    posts = posts,
                    onShortcutPublished = { shortcutWrites++ },
                )
            presenter.ensureChannels()
            assertTrue(
                presenter.show(
                    alertBudgetUpdate("first", 1_000),
                    conversationAvatarUrl = "https://example.com/general.png",
                    shortNpub = { "npub1fixture" },
                ),
            )
            val enrichment = async(start = CoroutineStart.UNDISPATCHED) { checkNotNull(pending).invoke() }
            try {
                withTimeout(5_000) { avatarStarted.await() }
                assertTrue(
                    presenter.show(
                        alertBudgetUpdate("other", 2_000, accountRef = "account-b"),
                        shortNpub = { "npub1fixture" },
                    ),
                )
                assertTrue(presenter.dismissConversationMessages("account-a", "group-a"))
                assertEquals("other", conversationCardMessageIdHex(manager.activeNotifications.single().notification))
                assertTrue(shortcutWrites > 0)
                val shortcutsBeforeAvatarCompletes = shortcutWrites

                avatarReady.complete(picture())
                withTimeout(5_000) { enrichment.await() }
                assertEquals(2, posts.size)
                assertEquals(shortcutsBeforeAvatarCompletes, shortcutWrites)
                val remaining = manager.activeNotifications.single().notification
                assertEquals("other", conversationCardMessageIdHex(remaining))
            } finally {
                avatarReady.complete(picture())
                enrichment.cancelAndJoin()
            }
        }

    /** Keeps real formatter/write ownership while controlling launch and picture effects and observing shortcuts. */
    private fun presenter(
        launcher: (suspend () -> Unit) -> Unit,
        resolveAvatar: suspend (String?) -> Bitmap?,
        posts: MutableList<Notification>,
        onShortcutPublished: () -> Unit = {},
    ): LocalNotificationPresenter =
        LocalNotificationPresenter(
            context = context,
            shortcutPublisher = { onShortcutPublished() },
            notificationPoster = { notificationManager, tag, id, notification ->
                notificationManager.notify(tag, id, notification)
                posts += notification
            },
            cachedAvatarBitmap = { null },
            avatarBitmapResolver = resolveAvatar,
            enrichmentLauncher = launcher,
            postPacer = NotificationPostPacer(),
            groupReconciliation = {},
        )

    /** Supplies a distinct fixture bitmap only when a controlled image read completes. */
    private fun picture(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
}
