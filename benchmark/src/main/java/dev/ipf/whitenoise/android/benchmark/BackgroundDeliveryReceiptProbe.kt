package dev.ipf.whitenoise.android.benchmark

import android.app.Notification
import android.content.ComponentName
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** Observes only explicitly named disposable payloads, retaining indices rather than message content. */
class BackgroundDeliveryReceiptListener : NotificationListenerService() {
    /** Confirms that Android has acknowledged the listener before measurement starts. */
    override fun onListenerConnected() {
        activeNotifications?.forEach(::onNotificationPosted)
        BackgroundDeliveryReceipts.connected = true
    }

    /** Invalidates availability immediately when Android disconnects the listener. */
    override fun onListenerDisconnected() {
        BackgroundDeliveryReceipts.connected = false
    }

    /** Rejects other packages before accessing extras; no payload or notification identity is logged. */
    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (!BackgroundDeliveryReceipts.accepts(notification.packageName)) return
        val extras = notification.notification.extras
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            extras.getParcelableArray(Notification.EXTRA_MESSAGES),
        )
        val texts = messages.mapNotNull { it.text?.toString() } +
            listOfNotNull(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        BackgroundDeliveryReceipts.record(notification.packageName, texts, SystemClock.elapsedRealtime())
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
    private var staleFixture = false

    /** Replaces all probe ownership before a new listener or measurement can emit callbacks. */
    @Synchronized
    fun arm(packageName: String, texts: List<String>) {
        target = packageName
        expected = texts.toList()
        seen.clear()
        deadlineMs = null
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
    fun beginWindow(endMs: Long) {
        requireFreshFixture()
        seen.clear()
        deadlineMs = endMs
    }

    /** Records each expected body once, only while the matching background window is still open. */
    @Synchronized
    fun record(packageName: String, texts: List<String>, nowMs: Long) {
        if (target != packageName) return
        val matches = expected.indices.filter { expected[it] in texts }
        val deadline = deadlineMs
        if (deadline == null) {
            staleFixture = staleFixture || matches.isNotEmpty()
        } else if (nowMs <= deadline) {
            seen.addAll(matches)
        }
    }

    /** Closes the generation before asserting complete in-window evidence. */
    @Synchronized
    fun finishWindow() {
        val complete = deadlineMs != null && seen.size == expected.size && connected
        deadlineMs = null
        check(complete) { "The complete disposable burst was not observed while backgrounded." }
    }

    /** Releases all expected payloads and generation state after success, failure or cancellation. */
    @Synchronized
    fun disarm() {
        target = null
        expected = emptyList()
        seen.clear()
        deadlineMs = null
        staleFixture = false
    }
}

/** Temporary, explicitly authorized listener access scoped to the disposable Android profile. */
internal class BackgroundDeliveryReceiptProbe(
    private val device: UiDevice,
    private val expectedTexts: List<String>,
) {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val component = ComponentName(context, BackgroundDeliveryReceiptListener::class.java).flattenToString()

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
        val original = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            .orEmpty().split(':').contains(component)
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
            val restored = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                .orEmpty().split(':').contains(component)
            check(restored == original) { "The fixture receipt-listener grant was not restored." }
        }
    }

    /** Refuses a fixture whose payloads were already seen during setup. */
    fun requireFreshFixture() = BackgroundDeliveryReceipts.requireFreshFixture()

    /** Uses a monotonic deadline so callbacks after a delayed wake cannot qualify the burst. */
    fun beginWindow(durationMs: Long) = BackgroundDeliveryReceipts.beginWindow(SystemClock.elapsedRealtime() + durationMs)

    /** Requires every unique fixture payload before any foreground catch-up can occur. */
    fun finishWindow() = BackgroundDeliveryReceipts.finishWindow()

    private companion object {
        const val ANDROID_PER_USER_UID_RANGE = 100_000
        const val LISTENER_TIMEOUT_MS = 5_000L
        const val LISTENER_POLL_MS = 100L
    }
}
