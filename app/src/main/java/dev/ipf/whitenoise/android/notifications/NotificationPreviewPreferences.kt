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

internal data class NotificationPreviewToken(
    val session: String,
    val revision: Long,
    val allowed: Boolean,
)

/** Device presentation only. One transaction covers the choice and cleanup across every presenter. */
internal object NotificationPreviewPreferences {
    const val KEY = "show_notification_previews"
    private const val EPOCH_KEY = "notification_preview_epoch"
    const val EXTRA_REVISION = "dev.ipf.whitenoise.android.notify.preview_revision"
    const val EXTRA_SESSION = "dev.ipf.whitenoise.android.notify.preview_session"
    const val EXTRA_ALLOWED = "dev.ipf.whitenoise.android.notify.preview_allowed"
    const val EXTRA_HIDDEN = "dev.ipf.whitenoise.android.notify.preview_hidden"
    const val EXTRA_CORRECTION = "dev.ipf.whitenoise.android.notify.preview_correction"
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

    /** Missing keeps current presentation; corrupt or failed preferences fail closed. */
    fun enabled(context: Context): Boolean =
        synchronized(stateLock) {
            bind(context)
            !blocked && runCatching { preferences(context).getBoolean(KEY, true) }.getOrDefault(false)
        }

    fun capture(context: Context): NotificationPreviewToken =
        synchronized(stateLock) {
            bind(context)
            NotificationPreviewToken(session, revision, enabled(context))
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
            },
        )
    }

    /** Off-main disk commit, then bounded cleanup, without holding either notification mutation lock. */
    suspend fun setEnabled(
        context: Context,
        value: Boolean,
        scrub: suspend () -> Boolean = { redactActiveNotificationPreviews(context) },
    ): Boolean =
        writes.withLock {
            val savedEpoch =
                synchronized(UserEventNotificationGroup.mutationLock) {
                    synchronized(stateLock) {
                        bind(context)
                        revision++
                        blocked = true
                        revision + if (value) 1L else 0L
                    }
                }
            val saved =
                runCatching {
                    preferences(context)
                        .edit()
                        .apply {
                            putBoolean(KEY, value)
                            putLong(EPOCH_KEY, savedEpoch)
                        }.commit()
                }.getOrDefault(false)
            synchronized(UserEventNotificationGroup.mutationLock) {
                synchronized(stateLock) {
                    if (saved && value) revision++
                    blocked = !saved
                }
            }
            val cleaned = if (!value || !saved) boundedCleanup(scrub) else true
            saved && cleaned
        }

    /** Recovers process death after a successful opt-out commit but before the old cards were scrubbed. */
    suspend fun recover(
        context: Context,
        scrub: suspend () -> Boolean = { redactActiveNotificationPreviews(context) },
    ): Boolean =
        writes.withLock {
            if (enabled(context)) true else boundedCleanup(scrub)
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
            !enabled(context) ||
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
        if (
            !token.allowed ||
            notification.extras.getBoolean(EXTRA_HIDDEN) ||
            notification.extras.getBoolean(EXTRA_CONTENT_REDACTED)
        ) {
            return false
        }
        val extras = notification.extras
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
