package dev.ipf.whitenoise.android.benchmark

import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlin.math.abs

/** Observes only explicitly named disposable payloads, retaining indices rather than message content. */
class BackgroundDeliveryReceiptListener : NotificationListenerService() {
    /** Confirms that Android has acknowledged the listener before measurement starts. */
    override fun onListenerConnected() {
        activeListener = this
        scanExistingCards()
        BackgroundDeliveryReceipts.connected = true
    }

    /** Invalidates availability immediately when Android disconnects the listener. */
    override fun onListenerDisconnected() {
        if (activeListener === this) activeListener = null
        BackgroundDeliveryReceipts.connected = false
    }

    /** Rejects other profiles and packages before extras; no payload or notification identity is logged. */
    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (notification.user != Process.myUserHandle()) return
        if (!BackgroundDeliveryReceipts.accepts(notification.packageName)) return
        val extras = notification.notification.extras
        val messages =
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java),
            )
        val texts =
            messages.mapNotNull { it.text?.toString() } +
                listOfNotNull(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        BackgroundDeliveryReceipts.record(
            notification.packageName,
            texts,
            SystemClock.elapsedRealtime(),
            notification.postTime,
            System.currentTimeMillis(),
        )
    }

    /** Rescans every measurement generation, including access retained from an earlier run. */
    private fun scanExistingCards() {
        activeNotifications?.forEach(::onNotificationPosted)
    }

    companion object {
        @Volatile
        private var activeListener: BackgroundDeliveryReceiptListener? = null

        /** Refuses unavailable ownership and checks old cards immediately before opening a window. */
        internal fun scanFixtureCards() {
            val listener = checkNotNull(activeListener) { "The fixture listener is unavailable." }
            listener.scanExistingCards()
        }
    }
}

/** One measurement generation; repeated enrichment is distinct from receiving a new fixture message. */
internal object BackgroundDeliveryReceipts {
    @Volatile
    var connected = false

    private var target: String? = null
    private var expected = emptyList<String>()
    private val seen = mutableSetOf<Int>()
    private var deadlineMs: Long? = null
    private var startElapsedMs = 0L
    private var startWallMs = 0L
    private var clockChanged = false
    private var staleFixture = false

    /** Replaces all probe ownership before a new listener or measurement can emit callbacks. */
    @Synchronized
    fun arm(
        packageName: String,
        texts: List<String>,
    ) {
        target = packageName
        expected = texts.toList()
        seen.clear()
        deadlineMs = null
        startElapsedMs = 0L
        startWallMs = 0L
        clockChanged = false
        staleFixture = false
    }

    /** Gates parsing on an exact test-package match. */
    @Synchronized
    fun accepts(packageName: String): Boolean = target == packageName

    /** Rejects reused fixture bodies observed before measurement, rather than counting old cards. */
    @Synchronized
    fun requireFreshFixture() {
        check(!staleFixture) { "Use fresh disposable fixture texts; a matching card already exists." }
    }

    /** Begins a bounded observation without allowing setup callbacks into its receive count. */
    @Synchronized
    fun beginWindow(
        startMs: Long,
        endMs: Long,
        wallMs: Long,
    ) {
        require(endMs > startMs)
        requireFreshFixture()
        seen.clear()
        startElapsedMs = startMs
        startWallMs = wallMs
        clockChanged = false
        deadlineMs = endMs
    }

    /** Records each expected body once, only while the matching background window is still open. */
    @Synchronized
    fun record(
        packageName: String,
        texts: List<String>,
        nowMs: Long,
        postedWallMs: Long,
        wallNowMs: Long,
    ) {
        if (target != packageName) return
        val matches = expected.indices.filter { expected[it] in texts }
        val deadline = deadlineMs
        if (deadline == null) {
            staleFixture = staleFixture || matches.isNotEmpty()
        } else {
            clockChanged = clockChanged || !clockStable(nowMs, wallNowMs)
            if (nowMs in startElapsedMs..deadline && postedWallMs >= startWallMs && !clockChanged) {
                seen.addAll(matches)
            }
        }
    }

    /** Closes the generation before asserting complete in-window evidence. */
    @Synchronized
    fun finishWindow(
        nowMs: Long,
        wallNowMs: Long,
    ) {
        val complete =
            deadlineMs != null &&
                seen.size == expected.size &&
                connected &&
                !clockChanged &&
                clockStable(nowMs, wallNowMs)
        deadlineMs = null
        check(complete) { "The complete disposable burst was not observed while backgrounded." }
    }

    /** Rejects wall-clock steps that make Android posting timestamps ambiguous. */
    private fun clockStable(
        nowMs: Long,
        wallNowMs: Long,
    ): Boolean = abs((wallNowMs - startWallMs) - (nowMs - startElapsedMs)) <= 1_000L

    /** Releases all expected payloads and generation state after success, failure or cancellation. */
    @Synchronized
    fun disarm() {
        target = null
        expected = emptyList()
        seen.clear()
        deadlineMs = null
        startElapsedMs = 0L
        startWallMs = 0L
        clockChanged = false
        staleFixture = false
    }
}

/** Temporary, explicitly authorized listener access scoped to the disposable Android profile. */
internal class BackgroundDeliveryReceiptProbe(
    private val device: UiDevice,
    private val expectedTexts: List<String>,
) {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val componentName = ComponentName(context, BackgroundDeliveryReceiptListener::class.java)
    private val component = componentName.flattenToString()

    /** Restores the prior listener grant and clears all synthetic payloads on every exit path. */
    fun withListener(block: () -> Unit) {
        val arguments = InstrumentationRegistry.getArguments()
        val userId = arguments.getString("qualificationUserId")?.toIntOrNull()
        require(arguments.getString("allowReceiptListener") == "true" && userId != null && userId > 0) {
            "Authorize the receipt listener on a disposable profile using qualificationUserId."
        }
        require(Process.myUid() / ANDROID_PER_USER_UID_RANGE == userId) {
            "Instrumentation is not running in the authorized disposable profile."
        }
        BenchmarkConfig.requireQualificationUser(device.executeShellCommand("am get-current-user"))
        val original = hasListenerGrant()
        BackgroundDeliveryReceipts.arm(BenchmarkConfig.TARGET_PACKAGE, expectedTexts)
        try {
            if (!original) {
                BackgroundDeliveryReceipts.connected = false
                device.executeShellCommand("cmd notification allow_listener $component $userId")
            }
            val deadline = SystemClock.elapsedRealtime() + LISTENER_TIMEOUT_MS
            while (!BackgroundDeliveryReceipts.connected && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(LISTENER_POLL_MS)
            }
            check(BackgroundDeliveryReceipts.connected) { "Android did not acknowledge the fixture receipt listener." }
            block()
        } finally {
            BackgroundDeliveryReceipts.disarm()
            if (!original) device.executeShellCommand("cmd notification disallow_listener $component $userId")
            val restored = hasListenerGrant()
            check(restored == original) { "The fixture receipt-listener grant was not restored." }
        }
    }

    /** Compares parsed components so short and full Android setting spellings preserve one grant. */
    private fun hasListenerGrant(): Boolean =
        Settings.Secure
            .getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty()
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .contains(componentName)

    /** Refuses a fixture whose payloads were already seen during setup. */
    fun requireFreshFixture() = BackgroundDeliveryReceipts.requireFreshFixture()

    /** Uses a monotonic deadline so callbacks after a delayed wake cannot qualify the burst. */
    fun beginWindow(durationMs: Long) {
        BackgroundDeliveryReceiptListener.scanFixtureCards()
        val startMs = SystemClock.elapsedRealtime()
        BackgroundDeliveryReceipts.beginWindow(startMs, startMs + durationMs, System.currentTimeMillis())
    }

    /** Requires every unique fixture payload before any foreground catch-up can occur. */
    fun finishWindow() = BackgroundDeliveryReceipts.finishWindow(SystemClock.elapsedRealtime(), System.currentTimeMillis())

    private companion object {
        const val ANDROID_PER_USER_UID_RANGE = 100_000
        const val LISTENER_TIMEOUT_MS = 5_000L
        const val LISTENER_POLL_MS = 100L
    }
}
