package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.notifications.NotificationPreviewPreferences
import dev.ipf.whitenoise.android.notifications.redactActiveNotificationPreviews
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Shared device settings state; account changes cannot replace a pending privacy transaction. */
internal class NotificationPreviewSettings private constructor(
    private val context: Context,
    private val scrub: suspend () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var enabled by mutableStateOf(NotificationPreviewPreferences.enabled(context))
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var requested = enabled

    /** A failed opt-out stays fail-closed in this process and keeps the same choice available for Retry. */
    suspend fun setEnabled(value: Boolean) {
        if (!busy) {
            requested = value
            update { NotificationPreviewPreferences.setEnabled(context, value, scrub) }
        }
    }

    suspend fun retry() = setEnabled(requested)

    /** Resume a saved opt-out after process death, before any account is configured. */
    suspend fun recover() {
        if (!busy && !NotificationPreviewPreferences.enabled(context)) {
            requested = false
            update { NotificationPreviewPreferences.recover(context, scrub) }
        }
    }

    private suspend fun update(operation: suspend () -> Boolean) {
        busy = true
        try {
            withContext(NonCancellable) {
                val result = withContext(ioDispatcher) { operation() }
                enabled = NotificationPreviewPreferences.enabled(context)
                failed = !result
            }
        } finally {
            busy = false
        }
    }

    companion object {
        private val lock = Any()
        private var application: Context? = null
        private var shared: NotificationPreviewSettings? = null

        fun forContext(
            context: Context,
            scrub: (suspend () -> Boolean)? = null,
        ): NotificationPreviewSettings =
            synchronized(lock) {
                val app = context.applicationContext
                if (application !== app) {
                    application = app
                    shared = NotificationPreviewSettings(app, scrub ?: { redactActiveNotificationPreviews(app) })
                }
                checkNotNull(shared)
            }
    }
}
