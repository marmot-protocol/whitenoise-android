package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.marmotkit.NotificationUpdateFfi
import java.util.Locale

/**
 * When each person's latest message was held back by a notification profile, so a second message within
 * [NOTIFICATION_PROFILE_REPEAT_WINDOW_MS] can notify. Keys are hashes of the account, profile and sender; only entries
 * inside the window are kept and at most [MAX_ENTRIES] people. The gate reads and records in memory and the
 * write goes to disk in the background; a cold process reloads the entries that are still recent.
 */
internal class NotificationProfileRepeats(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val lock = Any()

    private data class RepeatState(
        val at: Long,
        val held: String,
        val admitted: String,
    )

    private val entries = load()

    /** Returns whether a distinct follow-up may notify, and atomically consumes its held message. */
    fun admit(
        profileId: String,
        update: NotificationUpdateFfi,
        nowMs: Long,
    ): Boolean {
        val key = key(profileId, update) ?: return false
        val messageId = update.messageIdHex?.takeIf(String::isNotBlank) ?: return false
        val message = sha256Hex(messageId)
        return synchronized(lock) {
            val previous = entries[key]?.takeIf { notificationProfileRepeatOpen(it.at, nowMs) }
            if (previous?.admitted == message) return@synchronized true
            if (previous?.held == message) return@synchronized false
            val allowed = !previous?.held.isNullOrEmpty()
            entries[key] =
                if (allowed) {
                    RepeatState(nowMs, "", message)
                } else {
                    RepeatState(nowMs, message, previous?.admitted.orEmpty())
                }
            entries.entries.removeAll { !notificationProfileRepeatOpen(it.value.at, nowMs) }
            while (entries.size > MAX_ENTRIES) entries.remove(entries.minBy { it.value.at }.key)
            val editor = preferences.edit().clear()
            entries.forEach { (entry, state) -> editor.putString(entry, "${state.at}:${state.held}:${state.admitted}") }
            runCatching { editor.apply() }
            allowed
        }
    }

    /** Forgets every person of local accounts that no longer exist. */
    fun retainAccounts(accountRefs: Collection<String>) {
        val prefixes = accountRefs.map { "${sha256Hex(it)}." }
        synchronized(lock) {
            val removed = entries.keys.filter { key -> prefixes.none(key::startsWith) }
            if (removed.isEmpty()) return
            removed.forEach(entries::remove)
            val editor = preferences.edit()
            removed.forEach(editor::remove)
            runCatching { editor.apply() }
        }
    }

    private fun load(): MutableMap<String, RepeatState> {
        val now = System.currentTimeMillis()
        val stored = runCatching { preferences.all }.getOrNull().orEmpty()
        val recent = HashMap<String, RepeatState>()
        stored.forEach { (key, value) ->
            val fields = (value as? String)?.split(':') ?: return@forEach
            val at = fields.firstOrNull()?.toLongOrNull()
            if (fields.size == 3 && at != null && notificationProfileRepeatOpen(at, now)) {
                recent[key] = RepeatState(at, fields[1], fields[2])
            }
        }
        return recent
    }

    private fun key(
        profileId: String,
        update: NotificationUpdateFfi,
    ): String? {
        val sender =
            update.sender.accountIdHex
                .trim()
                .lowercase(Locale.ROOT)
        if (update.accountRef.isEmpty() || sender.isEmpty()) return null
        return "${sha256Hex(update.accountRef)}.${sha256Hex(profileId)}.${sha256Hex(sender)}"
    }

    companion object {
        const val PREFERENCES_NAME = "whitenoise.notification_profile_repeats"
        private const val MAX_ENTRIES = 64
    }
}
