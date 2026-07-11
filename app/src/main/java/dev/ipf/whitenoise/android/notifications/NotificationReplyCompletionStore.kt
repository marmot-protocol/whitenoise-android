package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences

internal class NotificationReplyCompletionStore(
    private val preferences: SharedPreferences,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun isCompleted(key: String): Boolean = completedAt(key) != null

    fun hasStarted(key: String): Boolean = startedAt(key) != null

    fun markStarted(key: String) {
        val now = nowMillis()
        preferences
            .edit()
            .putLong(startedStorageKey(key), now)
            .commit()
        pruneExpired(now)
    }

    fun markCompleted(key: String) {
        val now = nowMillis()
        preferences
            .edit()
            .putLong(completedStorageKey(key), now)
            .remove(startedStorageKey(key))
            .commit()
        pruneExpired(now)
    }

    private fun completedAt(key: String): Long? =
        preferences
            .getLong(completedStorageKey(key), MISSING_TIMESTAMP)
            .takeUnless { it == MISSING_TIMESTAMP }

    private fun startedAt(key: String): Long? =
        preferences
            .getLong(startedStorageKey(key), MISSING_TIMESTAMP)
            .takeUnless { it == MISSING_TIMESTAMP }

    private fun pruneExpired(now: Long) {
        val expired =
            preferences
                .all
                .mapNotNull { (key, value) ->
                    key.takeIf {
                        (it.startsWith(COMPLETED_KEY_PREFIX) || it.startsWith(STARTED_KEY_PREFIX)) &&
                            value is Long &&
                            now - value > RETENTION_MILLIS
                    }
                }
        if (expired.isEmpty()) return
        val editor = preferences.edit()
        expired.forEach(editor::remove)
        editor.commit()
    }

    companion object {
        private const val PREFERENCES_NAME = "whitenoise.notification_replies"
        private const val COMPLETED_KEY_PREFIX = "completed_"
        private const val STARTED_KEY_PREFIX = "started_"
        private const val MISSING_TIMESTAMP = Long.MIN_VALUE
        private const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L

        fun create(context: Context): NotificationReplyCompletionStore =
            NotificationReplyCompletionStore(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )

        internal fun completedStorageKey(key: String): String = COMPLETED_KEY_PREFIX + key

        internal fun startedStorageKey(key: String): String = STARTED_KEY_PREFIX + key
    }
}
