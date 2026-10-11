package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArrayList

/** The template a posted card renders with, read from the platform extras so it matches what SystemUI sees. */
enum class PostedStyle {
    /** No style template, which renders as a plain card. */
    NONE,

    /** The conversation template that carries sender, history and reply. */
    MESSAGING,

    /** The single expandable text block. */
    BIG_TEXT,

    /** Any other platform template. */
    OTHER,
}

/**
 * One platform write observed at the presenter's poster seam.
 *
 * [silent] is recomputed from the built card, not read from the presenter: AndroidX rewrites a non-summary
 * child that was built with `setSilent(true)` to GROUP_ALERT_SUMMARY, so that group behavior on a child is the
 * only trace the platform keeps of it. [notification] is a clone, immune to later mutation by the platform shadow.
 */
data class RecordedPost(
    val sequence: Int,
    val atMillis: Long,
    val tag: String,
    val id: Int,
    val channelId: String?,
    val silent: Boolean,
    val onlyAlertOnce: Boolean,
    val group: String?,
    val groupAlertBehavior: Int,
    val sortKey: String?,
    val style: PostedStyle,
    val carriesNoPerPostSoundOrVibration: Boolean,
    val notification: Notification,
)

/** A settable clock for the presenter's `nowMillis` seam, so time only moves when a test moves it. */
class ProbeClock(
    startMillis: Long = DEFAULT_START_MILLIS,
) {
    @Volatile
    var nowMillis: Long = startMillis
        private set

    /** Moves the clock forward by [millis]. */
    fun advanceBy(millis: Long) {
        require(millis >= 0) { "The clock never runs backwards" }
        nowMillis += millis
    }

    companion object {
        const val DEFAULT_START_MILLIS = 1_000_000L
    }
}

/**
 * Holds enrichment work started by the presenter's launcher seam until a test releases it.
 * The presenter launches optional avatar enrichment after the first post, so holding it makes the first-post
 * and same-key-update sequence deterministic and lets a test act between the two.
 */
class GatedEnrichmentLauncher {
    private val pending = ArrayDeque<suspend () -> Unit>()

    /** The launcher to pass as `enrichmentLauncher`. It queues the work and returns immediately. */
    val launcher: (suspend () -> Unit) -> Unit = { block -> synchronized(pending) { pending.addLast(block) } }

    /** How many blocks are waiting to run. */
    val pendingCount: Int
        get() = synchronized(pending) { pending.size }

    /** Runs the oldest waiting block to completion and returns whether there was one. */
    suspend fun releaseNext(): Boolean {
        val block = synchronized(pending) { pending.removeFirstOrNull() } ?: return false
        block()
        return true
    }

    /** Runs every waiting block in order, including any queued while draining, and returns how many ran. */
    suspend fun releaseAll(): Int {
        var released = 0
        while (releaseNext()) released += 1
        return released
    }
}

/**
 * Records every write the presenter makes and owns the clock and enrichment gate that make a sequence repeatable.
 *
 * Pass [notificationPoster], [nowMillis] and [enrichmentLauncher] to `LocalNotificationPresenter`. By default the
 * poster also performs the platform write, so later reads of the active cards see what the presenter wrote.
 */
class PostProbe(
    val clock: ProbeClock = ProbeClock(),
    val enrichment: GatedEnrichmentLauncher = GatedEnrichmentLauncher(),
    private val deliver: Boolean = true,
) {
    private val recorded = CopyOnWriteArrayList<RecordedPost>()

    /** Every observed write in order. */
    val posts: List<RecordedPost>
        get() = recorded.toList()

    /** How many of the next writes fail after being recorded, as a platform refusal would. Counts down as it fails. */
    @Volatile
    var failNextWrites: Int = 0

    /** The presenter clock seam. */
    val nowMillis: () -> Long = { clock.nowMillis }

    /** The presenter enrichment seam. */
    val enrichmentLauncher: (suspend () -> Unit) -> Unit = enrichment.launcher

    /** The presenter write seam. It records the card and, unless built with `deliver = false`, posts it too. */
    val notificationPoster: (NotificationManagerCompat, String, Int, Notification) -> Unit = ::post

    /** Every observed write for one platform key. */
    fun postsFor(
        tag: String,
        id: Int,
    ): List<RecordedPost> = posts.filter { it.tag == tag && it.id == id }

    /** Forgets the recorded writes while leaving the clock and any gated enrichment untouched. */
    fun clearPosts() = recorded.clear()

    /** Records one write without performing it, for callers that wrap the platform call themselves. */
    fun record(
        tag: String,
        id: Int,
        notification: Notification,
    ): RecordedPost {
        val card = notification.clone()
        return RecordedPost(
            sequence = recorded.size,
            atMillis = clock.nowMillis,
            tag = tag,
            id = id,
            channelId = card.channelId,
            silent = card.isBuiltSilentChild(),
            onlyAlertOnce = card.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            group = card.group,
            groupAlertBehavior = card.groupAlertBehavior,
            sortKey = card.sortKey,
            style = card.postedStyle(),
            carriesNoPerPostSoundOrVibration = card.carriesNoPerPostSoundOrVibration(),
            notification = card,
        ).also(recorded::add)
    }

    /** Records the write, then performs it unless this probe only observes. */
    @SuppressLint("MissingPermission")
    private fun post(
        manager: NotificationManagerCompat,
        tag: String,
        id: Int,
        notification: Notification,
    ) {
        record(tag, id, notification)
        if (failNextWrites > 0) {
            failNextWrites -= 1
            error("Simulated platform write failure")
        }
        if (deliver) manager.notify(tag, id, notification)
    }
}

/** A child card that suppresses its own alerting, which is how `setSilent(true)` survives into the built card. */
private fun Notification.isBuiltSilentChild(): Boolean =
    groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY && flags and Notification.FLAG_GROUP_SUMMARY == 0

/** Maps the platform template extra to a [PostedStyle]. */
private fun Notification.postedStyle(): PostedStyle {
    val template = extras?.getString(Notification.EXTRA_TEMPLATE) ?: return PostedStyle.NONE
    return when {
        template.endsWith("MessagingStyle") -> PostedStyle.MESSAGING
        template.endsWith("BigTextStyle") -> PostedStyle.BIG_TEXT
        else -> PostedStyle.OTHER
    }
}

/** Whether the card sets no sound, vibration or default alert of its own, leaving them to the channel. */
@Suppress("DEPRECATION") // Notification.sound and vibrate are the only observable per-post alert fields.
private fun Notification.carriesNoPerPostSoundOrVibration(): Boolean =
    sound == null && vibrate == null && defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE) == 0
