package dev.ipf.whitenoise.android.state

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.notifications.ConversationCardBarrier
import dev.ipf.whitenoise.android.notifications.ConversationCardOp
import dev.ipf.whitenoise.android.notifications.ConversationCardPostSynchronizer
import dev.ipf.whitenoise.android.notifications.ConversationCardTestHook
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/** API 30 platform-template baseline for the exact first card posted by the typed-update path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "en-rUS-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationFirstPostRemoteViewsScreenshotTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val originalTimeZone = TimeZone.getDefault()
    private lateinit var activityController: ActivityController<Activity>

    /** Clears process-global notification state before rendering the first card. */
    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        check(SystemClock.setCurrentTimeMillis(hostWallTimeMillis()))
        activityController = Robolectric.buildActivity(Activity::class.java).setup()
        ConversationCardPostSynchronizer.testHook = null
        notificationManager().cancelAll()
    }

    /** Releases the typed fixture and platform card after every capture attempt. */
    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        notificationManager().cancelAll()
        if (::activityController.isInitialized) activityController.pause().stop().destroy()
        TimeZone.setDefault(originalTimeZone)
    }

    /** Captures resolved sender and strong-Markdown text from the sole API 30 platform write. */
    @Test
    fun resolvedFirstPostRendersInThePlatformNotificationTemplate() =
        runBlocking {
            val writes = AtomicInteger(0)
            val events = CopyOnWriteArrayList<NotificationFirstPostTimingEvent>()
            ConversationCardPostSynchronizer.testHook = writeCounter(writes)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    previewText = "**Resolved first draw**",
                    isDm = true,
                    notificationUsersHaveDisplayNames = false,
                    localDisplayName = null,
                    delayFirstNotificationDispatchAfterRuntimeStart = true,
                    notificationFirstPostTimingObserver = events::add,
                )
            fixture.appState.applyAccountSwitchProfileSeed(
                AccountSwitchProfileSeed(
                    accountIdHex = fixture.update.sender.accountIdHex,
                    profile = null,
                    displayName = "Alice",
                    avatarUrl = null,
                ),
            )
            try {
                val notification = awaitResolvedFirstNotification(fixture, writes, events)
                val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                assertEquals("Resolved first draw", body)
                assertFalse(body.contains("**"))

                val card = renderPlatformTemplate(notification)
                val visibleText = card.descendantText()
                assertTrue(visibleText.any { "Alice" in it })
                assertTrue(visibleText.any { "Resolved first draw" in it })
                assertFalse(visibleText.any(YEAR_OFFSET::containsMatchIn))
                card.captureRoboImage(SNAPSHOT_PATH)
            } finally {
                fixture.close()
            }
        }

    /** Drives the typed path to one resolved platform write under a controlled rendering clock. */
    private suspend fun awaitResolvedFirstNotification(
        fixture: NotificationBootstrapTestFixture,
        writes: AtomicInteger,
        events: List<NotificationFirstPostTimingEvent>,
    ): Notification {
        fixture.bootstrap()
        // Keep one-time parser/FFI class loading outside this rendering setup.
        fixture.appState.parseMarkdownOrEmpty("**parser warm-up**")
        fixture.releaseNotificationDispatch()
        // Use a controlled Android clock for deterministic rendering;
        // this fixture does not measure wall-clock or device latency.
        fixture.awaitNotificationPosted(advanceMainClock = false)
        withTimeout(5_000L) {
            while (writes.get() < 1) delay(1L)
        }
        delay(100L)

        val content = events.single { it.stage == NotificationFirstPostTimingStage.ContentComplete }
        val resolutionTrace =
            "timing=$events, timelineReads=${fixture.notificationTimelineCalls.get()}, " +
                "markdownReads=${fixture.markdownParseCalls.get()}"
        assertEquals(resolutionTrace, "resolved_before_deadline", content.outcome)
        assertTrue(content.stageElapsedMillis in 0L..FIRST_POST_CONTENT_DEADLINE_MS)
        assertEquals(1, writes.get())

        return activeNotification().also(::assertAlignedHeaderClock)
    }

    /** Confirms the rendered header shares the Android wall clock aligned by this fixture. */
    private fun assertAlignedHeaderClock(notification: Notification) {
        val androidWallTimeMs = System.currentTimeMillis()
        val hostWallTimeMs = hostWallTimeMillis()
        val clockTrace =
            "notification.when=${notification.`when`}, androidWall=$androidWallTimeMs, " +
                "hostWall=$hostWallTimeMs"
        assertTrue(clockTrace, abs(notification.`when` - androidWallTimeMs) < ONE_MINUTE_MS)
        assertTrue(clockTrace, abs(androidWallTimeMs - hostWallTimeMs) < ONE_MINUTE_MS)
    }

    /** Counts only completed platform writes for the issue fixture's conversation card. */
    private fun writeCounter(writes: AtomicInteger): ConversationCardTestHook =
        object : ConversationCardTestHook {
            override fun onBarrier(
                op: ConversationCardOp,
                barrier: ConversationCardBarrier,
                notificationTag: String,
                notificationId: Int,
            ) {
                if (op == ConversationCardOp.SHOW_NOTIFY && barrier == ConversationCardBarrier.AFTER_WRITE) {
                    writes.incrementAndGet()
                }
            }
        }

    /** Recovers and lays out Android's real API 30 notification RemoteViews on a light shade surface. */
    private fun renderPlatformTemplate(notification: Notification): View {
        val activity = activityController.get()
        val parent = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
        activity.setContentView(parent)
        val remoteViews = Notification.Builder.recoverBuilder(activity, notification).createContentView()
        val card = remoteViews.apply(activity, parent)
        parent.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val density = context.resources.displayMetrics.density
        parent.measure(
            View.MeasureSpec.makeMeasureSpec((360f * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((240f * density).roundToInt(), View.MeasureSpec.AT_MOST),
        )
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
        return card
    }

    /** Collects rendered platform TextViews without relying on private framework resource ids. */
    private fun View.descendantText(): List<String> =
        buildList {
            if (this@descendantText.visibility != View.VISIBLE) return@buildList
            if (this@descendantText is TextView) add(this@descendantText.text.toString())
            if (this@descendantText is ViewGroup) {
                repeat(this@descendantText.childCount) { index ->
                    addAll(this@descendantText.getChildAt(index).descendantText())
                }
            }
        }

    /** Returns the sole active card for the deterministic issue fixture. */
    private fun activeNotification(): Notification =
        notificationManager()
            .activeNotifications
            .single { it.tag == "account-a|group-a" }
            .notification

    /** Retrieves the process-local platform manager used by Robolectric. */
    private fun notificationManager(): NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Reads an unshadowed host epoch so Android's paused wall clock can match the presenter timestamp. */
    private fun hostWallTimeMillis(): Long =
        ManagementFactory.getRuntimeMXBean().let { runtime ->
            runtime.startTime + runtime.uptime
        }

    private companion object {
        const val SNAPSHOT_PATH = "src/test/snapshots/notification_first_post_remote_views_api30.png"
        const val FIRST_POST_CONTENT_DEADLINE_MS = 100L
        const val ONE_MINUTE_MS = 60_000L
        val YEAR_OFFSET = Regex("\\b\\d+y\\b")
    }
}
