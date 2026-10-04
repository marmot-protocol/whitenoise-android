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
    const val EXTRA_REVISION = "dev.ipf.whitenoise.android.notify.preview_revision"
    const val EXTRA_SESSION = "dev.ipf.whitenoise.android.notify.preview_session"
    const val EXTRA_ALLOWED = "dev.ipf.whitenoise.android.notify.preview_allowed"
    const val EXTRA_HIDDEN = "dev.ipf.whitenoise.android.notify.preview_hidden"
    const val EXTRA_CORRECTION = "dev.ipf.whitenoise.android.notify.preview_correction"
    private const val CLEANUP_TIMEOUT_MS = 25_000L
    private val writes = Mutex()
    private var application: Context? = null
    private var session = UUID.randomUUID().toString()
    private var revision = 0L
    private var blocked = false

    /** Test applications cannot inherit a failure fence; old process stamps cannot authorize history. */
    private fun bind(context: Context) {
        if (application !== context.applicationContext) {
            application = context.applicationContext
            session = UUID.randomUUID().toString()
            revision = 0L
            blocked = false
        }
    }

    /** Missing keeps current presentation; corrupt or failed preferences fail closed. */
    fun enabled(context: Context): Boolean =
        synchronized(UserEventNotificationGroup.mutationLock) {
            bind(context)
            !blocked && runCatching { preferences(context).getBoolean(KEY, true) }.getOrDefault(false)
        }

    fun capture(context: Context): NotificationPreviewToken =
        synchronized(UserEventNotificationGroup.mutationLock) {
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
            synchronized(UserEventNotificationGroup.mutationLock) {
                bind(context)
                revision++
                blocked = true
            }
            val saved = runCatching { preferences(context).edit().putBoolean(KEY, value).commit() }.getOrDefault(false)
            synchronized(UserEventNotificationGroup.mutationLock) {
                if (saved && value) revision++
                blocked = !saved
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
        // Existing callers can create their first card silently. Only the untouched default may
        // expose such a post without a live card; any persisted choice keeps corrections fail-closed.
        val initialDefaultPost =
            current == null && capture(context).revision == 0L &&
                runCatching { !preferences(context).contains(KEY) }.getOrDefault(false)
        val correctionAllowed =
            !notification.extras.getBoolean(EXTRA_CORRECTION) ||
                current?.extras?.getBoolean(EXTRA_HIDDEN) == false || initialDefaultPost
        return correctionAllowed && canRetainPreview(context, notification)
    }

    /** Unstamped OS cards from before this feature retain existing behavior until a privacy transition. */
    fun canRetainPreview(
        context: Context,
        notification: Notification,
    ): Boolean {
        val token = capture(context)
        if (!token.allowed || notification.extras.getBoolean(EXTRA_HIDDEN)) return false
        val untouchedLegacy = token.revision == 0L && !notification.extras.containsKey(EXTRA_SESSION)
        return untouchedLegacy || hasCurrentProvenance(context, notification)
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

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        "whitenoise",
        Context.MODE_PRIVATE,
    )
}
