package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.marmotkit.NotificationUpdateFfi
import java.util.Locale

/**
 * When each person's latest message was held back by a notification profile, so a second message within
 * [NOTIFICATION_PROFILE_REPEAT_WINDOW_MS] can notify. Keys are hashes of the account and sender; only times
 * inside the window are kept and at most [MAX_ENTRIES] people. The gate reads and records in memory and the
 * write goes to disk in the background; a cold process reloads the entries that are still recent.
 */
internal class NotificationProfileRepeats(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val lock = Any()
    private val heldAt: MutableMap<String, Long> = load()

    /** When the last held-back message from [update]'s sender arrived, if inside the window at [nowMs]. */
    fun heldAtMs(
        update: NotificationUpdateFfi,
        nowMs: Long,
    ): Long? {
        val key = key(update) ?: return null
        return synchronized(lock) { heldAt[key]?.takeIf { notificationProfileRepeatOpen(it, nowMs) } }
    }

    /** Remembers that a message from [update]'s sender was held back at [nowMs]. */
    fun recordHeld(
        update: NotificationUpdateFfi,
        nowMs: Long,
    ) {
        val key = key(update) ?: return
        synchronized(lock) {
            heldAt[key] = nowMs
            heldAt.entries.removeAll { !notificationProfileRepeatOpen(it.value, nowMs) }
            while (heldAt.size > MAX_ENTRIES) heldAt.remove(heldAt.minBy { it.value }.key)
            val editor = preferences.edit().clear()
            heldAt.forEach { (entry, at) -> editor.putLong(entry, at) }
            runCatching { editor.apply() }
        }
    }

    /** Forgets every person of local accounts that no longer exist. */
    fun retainAccounts(accountRefs: Collection<String>) {
        val prefixes = accountRefs.map { "${sha256Hex(it)}." }
        synchronized(lock) {
            val removed = heldAt.keys.filter { key -> prefixes.none(key::startsWith) }
            if (removed.isEmpty()) return
            removed.forEach(heldAt::remove)
            val editor = preferences.edit()
            removed.forEach(editor::remove)
            runCatching { editor.apply() }
        }
    }

    private fun load(): MutableMap<String, Long> {
        val now = System.currentTimeMillis()
        val stored = runCatching { preferences.all }.getOrNull().orEmpty()
        val recent = HashMap<String, Long>()
        stored.forEach { (key, value) ->
            if (value is Long && notificationProfileRepeatOpen(value, now)) recent[key] = value
        }
        return recent
    }

    private fun key(update: NotificationUpdateFfi): String? {
        val sender = update.sender.accountIdHex.trim().lowercase(Locale.ROOT)
        if (update.accountRef.isEmpty() || sender.isEmpty()) return null
        return "${sha256Hex(update.accountRef)}.${sha256Hex(sender)}"
    }

    companion object {
        const val PREFERENCES_NAME = "whitenoise.notification_profile_repeats"
        private const val MAX_ENTRIES = 64
    }
}
