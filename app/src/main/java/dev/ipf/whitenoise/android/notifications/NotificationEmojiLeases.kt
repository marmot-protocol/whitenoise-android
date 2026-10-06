package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.core.app.NotificationCompat

private val historyArtworkName = Regex("[0-9a-f-]{36}\\.png")

/** Transient export ownership, shared by new artwork and posts carrying older image rows. */
internal object NotificationEmojiLeases {
    val lock = Any()
    private val references = mutableMapOf<String, Int>()

    fun contains(name: String): Boolean = synchronized(lock) { name in references }

    fun retain(names: Set<String>): AutoCloseable {
        synchronized(lock) {
            names.forEach { name -> references[name] = (references[name] ?: 0) + 1 }
        }
        var closed = false
        return AutoCloseable {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    names.forEach { name ->
                        val count = (references[name] ?: 1) - 1
                        if (count == 0) references.remove(name) else references[name] = count
                    }
                }
            }
        }
    }
}

/** Acquire alongside the confirmed history read under the same gate used by export pruning. */
internal fun retainNotificationEmojiHistoryArtwork(
    context: Context,
    messages: List<NotificationCompat.MessagingStyle.Message>,
): AutoCloseable {
    val names =
        messages
            .mapNotNull { message ->
                val uri = message.dataUri
                val segments = uri?.pathSegments.orEmpty()
                val ownedProvider =
                    uri != null && uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider"
                val ownedPath = segments.size == 2 && segments.first() == "notification_emoji"
                if (ownedProvider && ownedPath) {
                    segments.last().takeIf(historyArtworkName::matches)
                } else {
                    null
                }
            }.toSet()
    return NotificationEmojiLeases.retain(names)
}
