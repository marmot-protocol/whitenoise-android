package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences

internal data class NotificationReplyRecoveryBoundary(
    val timelineAt: ULong,
    val messageIdHex: String,
)

internal class NotificationReplyCompletionStore(
    private val preferences: SharedPreferences,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun isCompleted(key: String): Boolean = completedAt(key) != null

    fun hasStarted(key: String): Boolean = startedAt(key) != null

    fun startedRecoveryBoundary(key: String): NotificationReplyRecoveryBoundary? {
        val timelineAt =
            preferences
                .getLong(recoveryTimelineAtStorageKey(key), MISSING_TIMESTAMP)
                .takeUnless { it == MISSING_TIMESTAMP || it < 0L }
                ?.toULong()
                ?: return null
        val messageId = preferences.getString(recoveryMessageIdStorageKey(key), null) ?: return null
        return NotificationReplyRecoveryBoundary(timelineAt = timelineAt, messageIdHex = messageId)
    }

    fun markStarted(
        key: String,
        recoveryBoundary: NotificationReplyRecoveryBoundary,
    ): Boolean {
        if (recoveryBoundary.timelineAt > Long.MAX_VALUE.toULong()) return false
        val now = nowMillis()
        val persisted =
            preferences
                .edit()
                .putLong(startedStorageKey(key), now)
                .putLong(recoveryTimelineAtStorageKey(key), recoveryBoundary.timelineAt.toLong())
                .putString(recoveryMessageIdStorageKey(key), recoveryBoundary.messageIdHex)
                .commit()
        if (persisted) pruneExpired(now)
        return persisted
    }

    fun markCompleted(key: String) {
        val now = nowMillis()
        preferences
            .edit()
            .putLong(completedStorageKey(key), now)
            .remove(startedStorageKey(key))
            .remove(recoveryTimelineAtStorageKey(key))
            .remove(recoveryMessageIdStorageKey(key))
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
        expired.forEach { storageKey ->
            editor.remove(storageKey)
            if (storageKey.startsWith(STARTED_KEY_PREFIX)) {
                val key = storageKey.removePrefix(STARTED_KEY_PREFIX)
                editor.remove(recoveryTimelineAtStorageKey(key))
                editor.remove(recoveryMessageIdStorageKey(key))
            }
        }
        editor.commit()
    }

    companion object {
        private const val PREFERENCES_NAME = "whitenoise.notification_replies"
        private const val COMPLETED_KEY_PREFIX = "completed_"
        private const val STARTED_KEY_PREFIX = "started_"
        private const val RECOVERY_TIMELINE_AT_KEY_PREFIX = "recovery_timeline_at_"
        private const val RECOVERY_MESSAGE_ID_KEY_PREFIX = "recovery_message_id_"
        private const val MISSING_TIMESTAMP = Long.MIN_VALUE
        private const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L

        fun create(context: Context): NotificationReplyCompletionStore =
            NotificationReplyCompletionStore(
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            )

        internal fun completedStorageKey(key: String): String = COMPLETED_KEY_PREFIX + key

        internal fun startedStorageKey(key: String): String = STARTED_KEY_PREFIX + key

        internal fun recoveryTimelineAtStorageKey(key: String): String = RECOVERY_TIMELINE_AT_KEY_PREFIX + key

        internal fun recoveryMessageIdStorageKey(key: String): String = RECOVERY_MESSAGE_ID_KEY_PREFIX + key
    }
}
