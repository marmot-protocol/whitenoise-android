package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.marmotkit.NotificationUpdateFfi
import java.util.Locale

/**
 * When each person's latest message was held back by a notification profile, so a second message within
 * [NOTIFICATION_PROFILE_REPEAT_WINDOW_MS] can notify. Keys are hashes of the account, profile and sender; only entries
 * inside the window are kept and at most [MAX_ENTRIES] messages. The gate reads and records in memory and the
 * write goes to disk in the background; a cold process reloads the entries that are still recent.
 */
internal class NotificationProfileRepeats(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val lock = Any()

    private enum class Decision { HELD, CONSUMED, ADMITTED }

    private data class RepeatState(
        val at: Long,
        val decision: Decision,
    )

    private val entries = load()
    private val reservations = mutableMapOf<String, String>()

    /** Returns whether a distinct follow-up may notify, reserving its held message until the platform write. */
    fun admit(
        profileId: String,
        update: NotificationUpdateFfi,
        nowMs: Long,
        reserve: Boolean = true,
    ): Boolean {
        val senderKey = senderKey(profileId, update)
        val messageId = update.messageIdHex
        if (senderKey == null || messageId.isNullOrBlank()) return false
        val key = "$senderKey.${sha256Hex(messageId)}"
        return synchronized(lock) {
            val previous = entries[key]?.takeIf { notificationProfileRepeatOpen(it.at, nowMs) }
            if (previous != null) return@synchronized previous.decision == Decision.ADMITTED
            if (!reserve) return@synchronized false
            if (key in reservations) return@synchronized true
            val held =
                entries.entries.firstOrNull { (entry, state) ->
                    entry.startsWith("$senderKey.") &&
                        entry !in reservations.values &&
                        state.decision == Decision.HELD &&
                        notificationProfileRepeatOpen(state.at, nowMs)
                }
            if (held != null) {
                reservations[key] = held.key
                return@synchronized true
            }
            entries[key] = RepeatState(nowMs, Decision.HELD)
            persist(nowMs)
            false
        }
    }

    /** Releases any reservation for the update, recording a repeat only after the platform write. */
    fun finish(
        update: NotificationUpdateFfi,
        posted: Boolean,
        nowMs: Long,
    ) {
        val messageId = update.messageIdHex?.takeIf { it.isNotBlank() } ?: return
        val accountPrefix = "${sha256Hex(update.accountRef)}."
        val messageSuffix = ".${sha256Hex(messageId)}"
        synchronized(lock) {
            val matching = reservations.keys.filter { it.startsWith(accountPrefix) && it.endsWith(messageSuffix) }
            matching.forEach { key ->
                val heldKey = reservations.remove(key)
                if (posted && heldKey != null) {
                    entries[heldKey]?.let { entries[heldKey] = it.copy(decision = Decision.CONSUMED) }
                    entries[key] = RepeatState(nowMs, Decision.ADMITTED)
                }
            }
            if (posted && matching.isNotEmpty()) persist(nowMs)
        }
    }

    private fun persist(nowMs: Long) {
        entries.entries.removeAll { !notificationProfileRepeatOpen(it.value.at, nowMs) }
        while (entries.size > MAX_ENTRIES) entries.remove(entries.minBy { it.value.at }.key)
        val editor = preferences.edit().clear()
        entries.forEach { (entry, state) -> editor.putString(entry, "${state.at}:${state.decision.name}") }
        runCatching { editor.apply() }
    }

    /** Forgets every person of local accounts that no longer exist. */
    fun retainAccounts(accountRefs: Collection<String>) {
        val prefixes = accountRefs.map { "${sha256Hex(it)}." }
        synchronized(lock) {
            reservations.keys.removeAll { key -> prefixes.none(key::startsWith) }
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
            val fields = (value as? String)?.split(':', limit = 2) ?: return@forEach
            val at = fields.firstOrNull()?.toLongOrNull()
            val decision = Decision.entries.firstOrNull { it.name == fields.getOrNull(1) }
            if (at != null && decision != null && notificationProfileRepeatOpen(at, now)) {
                recent[key] = RepeatState(at, decision)
            }
        }
        return recent
    }

    private fun senderKey(
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

internal const val NOTIFICATION_PROFILE_REPEAT_WINDOW_MS = 3L * 60L * 1_000L

/** True when [heldAtMs], the last held-back message from the same person, is recent enough at [nowMs]. */
internal fun notificationProfileRepeatOpen(
    heldAtMs: Long?,
    nowMs: Long,
): Boolean = heldAtMs != null && nowMs >= heldAtMs && nowMs - heldAtMs < NOTIFICATION_PROFILE_REPEAT_WINDOW_MS
