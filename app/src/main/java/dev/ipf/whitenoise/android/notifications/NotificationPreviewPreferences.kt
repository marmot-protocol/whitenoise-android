package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** How much a notification may reveal: sender and message, sender only, or neither. */
internal enum class NotificationPreviewMode(
    val storedValue: String,
) {
    FULL("full"),
    NAME_ONLY("name_only"),
    NONE("none"),
    ;

    val showsNames: Boolean get() = this != NONE
    val showsMessageText: Boolean get() = this == FULL

    companion object {
        /** An unknown stored value fails closed. */
        fun fromStored(value: String): NotificationPreviewMode = entries.firstOrNull { it.storedValue == value } ?: NONE
    }
}

/** [allowed] gates names, pictures and shortcuts; [messageText] additionally gates message content. */
internal data class NotificationPreviewToken(
    val session: String,
    val revision: Long,
    val allowed: Boolean,
    val messageText: Boolean = allowed,
)

/** Device presentation only. One transaction covers the choice and cleanup across every presenter. */
internal object NotificationPreviewPreferences {
    const val KEY = "show_notification_previews"
    const val MODE_KEY = "notification_preview_mode"

    /** The epoch [MODE_KEY] was written with; older builds advance only the epoch, which voids the saved mode. */
    private const val MODE_EPOCH_KEY = "notification_preview_mode_epoch"
    private const val EPOCH_KEY = "notification_preview_epoch"
    const val EXTRA_REVISION = "dev.ipf.whitenoise.android.notify.preview_revision"
    const val EXTRA_SESSION = "dev.ipf.whitenoise.android.notify.preview_session"
    const val EXTRA_ALLOWED = "dev.ipf.whitenoise.android.notify.preview_allowed"
    const val EXTRA_HIDDEN = "dev.ipf.whitenoise.android.notify.preview_hidden"
    const val EXTRA_CORRECTION = "dev.ipf.whitenoise.android.notify.preview_correction"
    const val EXTRA_MESSAGE_TEXT = "dev.ipf.whitenoise.android.notify.preview_message_text"
    private const val CLEANUP_TIMEOUT_MS = 25_000L
    private val writes = Mutex()
    private val stateLock = Any()
    private var application: Context? = null
    private var session = UUID.randomUUID().toString()
    private var revision = 0L
    private var blocked = false

    /** Test applications cannot inherit a failure fence; only current persisted epochs can authorize live history. */
    private fun bind(context: Context) {
        if (application !== context.applicationContext) {
            application = context.applicationContext
            session = UUID.randomUUID().toString()
            val savedEpoch = runCatching { preferences(context).getLong(EPOCH_KEY, 0L) }.getOrNull()
            revision = savedEpoch?.takeIf { it >= 0L } ?: 0L
            blocked = savedEpoch == null || savedEpoch < 0L
        }
    }

    /**
     * Missing keeps current presentation; corrupt or failed preferences fail closed. The older on/off key stays
     * authoritative for full previews, and a restricted mode counts only if no older build changed the choice since,
     * so a choice made after a downgrade survives the next upgrade.
     */
    fun mode(context: Context): NotificationPreviewMode =
        synchronized(stateLock) {
            bind(context)
            if (blocked) {
                NotificationPreviewMode.NONE
            } else {
                runCatching {
                    val preferences = preferences(context)
                    if (preferences.getBoolean(KEY, true)) {
                        NotificationPreviewMode.FULL
                    } else {
                        preferences
                            .getString(MODE_KEY, null)
                            ?.takeIf { preferences.getLong(MODE_EPOCH_KEY, -1L) == preferences.getLong(EPOCH_KEY, 0L) }
                            ?.let(NotificationPreviewMode::fromStored)
                            ?.takeIf { it != NotificationPreviewMode.FULL }
                            ?: NotificationPreviewMode.NONE
                    }
                }.getOrDefault(NotificationPreviewMode.NONE)
            }
        }

    fun capture(context: Context): NotificationPreviewToken =
        synchronized(stateLock) {
            bind(context)
            val mode = mode(context)
            NotificationPreviewToken(session, revision, mode.showsNames, mode.showsMessageText)
        }

    fun stamp(
        builder: NotificationCompat.Builder,
        token: NotificationPreviewToken,
        correction: Boolean,
    ) {
        builder.addExtras(
            Bundle().apply {
                putString(EXTRA_SESSION, token.session)
                putLong(EXTRA_REVISION, token.revision)
                putBoolean(EXTRA_ALLOWED, token.allowed)
                putBoolean(EXTRA_CORRECTION, correction)
                putBoolean(EXTRA_MESSAGE_TEXT, token.messageText)
            },
        )
    }

    /**
     * Off-main disk commit, then bounded cleanup, without holding either notification mutation lock. Every change
     * starts a new revision, so cards prepared under the previous mode can no longer authorize their content.
     */
    suspend fun setMode(
        context: Context,
        mode: NotificationPreviewMode,
        scrub: suspend () -> Boolean = { redactActiveNotificationPreviews(context) },
    ): Boolean =
        writes.withLock {
            val savedEpoch =
                synchronized(UserEventNotificationGroup.mutationLock) {
                    synchronized(stateLock) {
                        bind(context)
                        revision++
                        blocked = true
                        revision + if (mode.showsNames) 1L else 0L
                    }
                }
            val saved =
                runCatching {
                    preferences(context)
                        .edit()
                        .apply {
                            putString(MODE_KEY, mode.storedValue)
                            // Older builds read only this key; anything short of full previews stays hidden there.
                            putBoolean(KEY, mode == NotificationPreviewMode.FULL)
                            putLong(EPOCH_KEY, savedEpoch)
                            putLong(MODE_EPOCH_KEY, savedEpoch)
                        }.commit()
                }.getOrDefault(false)
            synchronized(UserEventNotificationGroup.mutationLock) {
                synchronized(stateLock) {
                    if (saved && mode.showsNames) revision++
                    blocked = !saved
                }
            }
            val cleaned = if (!mode.showsMessageText || !saved) boundedCleanup(scrub) else true
            saved && cleaned
        }

    /** Recovers process death after a successful restrictive commit but before the old cards were scrubbed. */
    suspend fun recover(
        context: Context,
        scrub: suspend () -> Boolean = { redactActiveNotificationPreviews(context) },
    ): Boolean =
        writes.withLock {
            if (mode(context).showsMessageText) true else boundedCleanup(scrub)
        }

    private suspend fun boundedCleanup(scrub: suspend () -> Boolean): Boolean =
        try {
            withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { scrub() } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            false
        }

    /** Called at the final group commit gate; missing/old stamps cannot authorize a content rewrite. */
    fun canExpose(
        context: Context,
        notification: Notification,
        current: Notification? = null,
    ): Boolean {
        if (
            !mode(context).showsNames ||
            notification.extras.getBoolean(EXTRA_HIDDEN) ||
            notification.extras.getBoolean(EXTRA_CONTENT_REDACTED)
        ) {
            return false
        }
        val prepared = hasCurrentProvenance(context, notification)
        val visible = current != null && canRetainPreview(context, current)
        val sameGeneration =
            current != null &&
                current.extras.getString(UserEventNotificationGroup.EXTRA_GENERATION) ==
                notification.extras.getString(UserEventNotificationGroup.EXTRA_GENERATION)
        val copiedVisible = visible && sameGeneration && canRetainPreview(context, notification)
        val correctionAllowed =
            !notification.extras.getBoolean(EXTRA_CORRECTION) || current == null || visible
        return correctionAllowed && (prepared || copiedVisible)
    }

    /** Live OS cards may survive a process restart, but never a later privacy transition. */
    fun canRetainPreview(
        context: Context,
        notification: Notification,
    ): Boolean {
        val token = capture(context)
        val extras = notification.extras
        // Revisions restart with each process, so only the card's own stamp shows it was prepared without text.
        val textAllowed = token.messageText || !extras.getBoolean(EXTRA_MESSAGE_TEXT, true)
        val redacted = extras.getBoolean(EXTRA_HIDDEN) || extras.getBoolean(EXTRA_CONTENT_REDACTED)
        if (!token.allowed || !textAllowed || redacted) {
            return false
        }
        val legacy = token.revision == 0L && !extras.containsKey(EXTRA_SESSION)
        val allowedEpoch = extras.getBoolean(EXTRA_ALLOWED) && extras.getLong(EXTRA_REVISION, -1L) == token.revision
        return legacy || allowedEpoch
    }

    fun hasCurrentProvenance(
        context: Context,
        notification: Notification,
    ): Boolean {
        val token = capture(context)
        return notification.extras.getBoolean(EXTRA_ALLOWED) &&
            notification.extras.getString(EXTRA_SESSION) == token.session &&
            notification.extras.getLong(EXTRA_REVISION, -1L) == token.revision
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            "whitenoise",
            Context.MODE_PRIVATE,
        )
}
