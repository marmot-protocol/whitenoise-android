package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ChatNotifyMode {
    ALL,
    MENTIONS_ONLY,
    NONE,
}

/** A time-bounded mute: silence until [expiryMillis], then restore [restoreMode]. */
data class MuteExpiry(
    val expiryMillis: Long,
    val restoreMode: ChatNotifyMode,
)

data class ChatNotificationState(
    val notificationModes: Map<String, ChatNotifyMode>,
) {
    val mutedConversations: Set<String> = ChatMutePreferences.mutedKeysOf(notificationModes)
}

/**
 * Applies elapsed timed mutes to [storedModes]: any key whose [expiries] entry
 * is at or past [now] restores its saved mode and drops the expiry. Pure so the
 * restore semantics are unit-testable without the clock or SharedPreferences.
 */
internal fun resolveExpiredMutes(
    storedModes: Map<String, ChatNotifyMode>,
    expiries: Map<String, MuteExpiry>,
    now: Long,
): Triple<Map<String, ChatNotifyMode>, Map<String, MuteExpiry>, Boolean> {
    val elapsed = expiries.filterValues { now >= it.expiryMillis }
    if (elapsed.isEmpty()) return Triple(storedModes, expiries, false)
    val modes =
        storedModes.toMutableMap().apply {
            elapsed.forEach { (key, expiry) ->
                if (expiry.restoreMode == ChatNotifyMode.ALL) remove(key) else put(key, expiry.restoreMode)
            }
        }
    return Triple(modes.toMap(), expiries - elapsed.keys, true)
}

/**
 * Per-account, per-conversation notification mode (#1179, #1252).
 * Android notification preference — not White Noise protocol data.
 */
@Suppress("TooManyFunctions") // Cohesive per-chat notify store + expiry scheduler.
class ChatMutePreferences(
    context: Context,
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    private val now: () -> Long = { System.currentTimeMillis() },
    scope: CoroutineScope? = null,
) {
    private val mutationLock = Any()
    private var muteExpiries: Map<String, MuteExpiry> = readMuteExpiries(preferences)
    private val _state = MutableStateFlow(ChatNotificationState(readNotificationModes(preferences)))
    val state: StateFlow<ChatNotificationState> = _state.asStateFlow()

    // When set, a coroutine sleeps until the nearest timed mute elapses and then
    // resolves + republishes, so the state flow *emits* on expiry — chat-list
    // icons and folder rules update without waiting for a getter call.
    private var expiryScope: CoroutineScope? = scope
    private var expiryJob: Job? = null

    init {
        synchronized(mutationLock) { scheduleNextExpiryLocked() }
    }

    /** Attach a scope after construction (field-init order) to drive scheduled
     *  expiry emission; resolves anything already elapsed first. */
    fun attachExpiryScheduler(scope: CoroutineScope) {
        synchronized(mutationLock) {
            expiryScope = scope
            resolveExpiredLockedInternal()
            scheduleNextExpiryLocked()
        }
    }

    fun mode(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotifyMode {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return ChatNotifyMode.ALL
        synchronized(mutationLock) { resolveExpiredLockedInternal() }
        return _state.value.notificationModes[key] ?: ChatNotifyMode.ALL
    }

    fun isMuted(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = mode(accountRef, groupIdHex) == ChatNotifyMode.NONE

    /**
     * The notify preference to show and restore when the chat is *not* muted:
     * the live mode when it isn't [ChatNotifyMode.NONE], otherwise a timed mute's
     * saved restore mode (a permanent mute keeps no restore, so defaults to ALL).
     * Lets the UI present Mute and the All/Only-mentions choice as separate rows.
     */
    fun restoreNotifyMode(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotifyMode {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return ChatNotifyMode.ALL
        return synchronized(mutationLock) {
            resolveExpiredLockedInternal()
            val current = _state.value.notificationModes[key] ?: ChatNotifyMode.ALL
            if (current != ChatNotifyMode.NONE) current else muteExpiries[key]?.restoreMode ?: ChatNotifyMode.ALL
        }
    }

    /** Remaining timed-mute expiry (epoch millis) for the chat, or null. */
    fun muteExpiryMillis(
        accountRef: String,
        groupIdHex: String,
    ): Long? {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return null
        return synchronized(mutationLock) {
            resolveExpiredLockedInternal()
            muteExpiries[key]?.expiryMillis
        }
    }

    fun setMode(
        accountRef: String,
        groupIdHex: String,
        mode: ChatNotifyMode,
    ) {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return
        synchronized(mutationLock) {
            val current = _state.value.notificationModes
            val updated =
                current.toMutableMap().apply {
                    if (mode == ChatNotifyMode.ALL) remove(key) else put(key, mode)
                }
            // An explicit mode choice cancels any pending timed-mute restore.
            val nextExpiries = muteExpiries - key
            if (updated == current && nextExpiries == muteExpiries) return
            publishLocked(updated.toMap(), nextExpiries)
        }
    }

    fun setMuted(
        accountRef: String,
        groupIdHex: String,
        muted: Boolean,
    ) {
        setMode(
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            mode = if (muted) ChatNotifyMode.NONE else ChatNotifyMode.ALL,
        )
    }

    /**
     * Mute the chat until [durationMillis] from now, then auto-restore whatever
     * mode is active today. A non-positive duration mutes permanently (no
     * expiry), matching [setMuted] with `true`.
     */
    fun muteFor(
        accountRef: String,
        groupIdHex: String,
        durationMillis: Long,
    ) {
        val key = compositeKeyOrNull(accountRef, groupIdHex) ?: return
        synchronized(mutationLock) {
            resolveExpiredLockedInternal()
            // Re-muting an already-timed-muted chat keeps its original restore
            // mode, so extending a mute never loses the pre-mute state. A timed
            // mute never restores to NONE — muting a permanently-muted chat for
            // an hour must unmute it after, not re-mute it — so coerce to ALL.
            val priorRestore =
                muteExpiries[key]?.restoreMode ?: (_state.value.notificationModes[key] ?: ChatNotifyMode.ALL)
            val restoreMode = if (priorRestore == ChatNotifyMode.NONE) ChatNotifyMode.ALL else priorRestore
            val modes =
                _state.value.notificationModes
                    .toMutableMap()
                    .apply { put(key, ChatNotifyMode.NONE) }
            val nextExpiries =
                if (durationMillis <= 0) {
                    muteExpiries - key
                } else {
                    muteExpiries + (key to MuteExpiry(now() + durationMillis, restoreMode))
                }
            publishLocked(modes.toMap(), nextExpiries)
        }
    }

    /**
     * Resolve any mutes that have elapsed by wall-clock time and re-arm the next
     * timer. The scheduler coroutine's [delay] runs on a clock that excludes
     * device deep sleep (Handler uptime), so a mute can elapse in real time while
     * that timer is still counting down; call this from a foreground/lifecycle
     * signal so the visible chat-list and folder state catch up immediately
     * instead of waiting for the delayed timer or the next notification getter.
     */
    fun resolveExpiredNow() {
        synchronized(mutationLock) {
            // publishLocked re-arms the timer whenever it publishes; when nothing
            // elapsed (an early wake, or the wall clock moved backward) re-arm
            // here so the pending timer is never dropped.
            if (!resolveExpiredLockedInternal()) scheduleNextExpiryLocked()
        }
    }

    // Caller must hold [mutationLock]. Restores any elapsed timed mutes and
    // republishes if anything changed. Returns whether it published.
    private fun resolveExpiredLockedInternal(): Boolean {
        val (modes, expiries, changed) =
            resolveExpiredMutes(_state.value.notificationModes, muteExpiries, now())
        if (changed) publishLocked(modes, expiries)
        return changed
    }

    // Caller must hold [mutationLock].
    private fun publishLocked(
        modes: Map<String, ChatNotifyMode>,
        expiries: Map<String, MuteExpiry>,
    ) {
        muteExpiries = expiries
        _state.value = ChatNotificationState(modes)
        preferences
            .edit()
            .putStringSet(KEY_MUTED_CONVERSATIONS, modes.filterValues { it == ChatNotifyMode.NONE }.keys)
            .putStringSet(
                KEY_MENTION_ONLY_CONVERSATIONS,
                modes.filterValues { it == ChatNotifyMode.MENTIONS_ONLY }.keys,
            ).putStringSet(KEY_MUTE_EXPIRIES, expiries.map(::encodeMuteExpiry).toSet())
            .apply()
        scheduleNextExpiryLocked()
    }

    // Caller must hold [mutationLock]. Sleeps until the nearest timed mute
    // elapses, then resolves + re-arms. The wake goes through resolveExpiredNow()
    // so that if the wall clock has not actually reached the expiry (the delay
    // fired early, or the clock moved backward) the timer re-arms instead of
    // exiting and dropping the pending mute.
    private fun scheduleNextExpiryLocked() {
        expiryJob?.cancel()
        expiryJob = null
        val scope = expiryScope ?: return
        val nearest = muteExpiries.values.minOfOrNull { it.expiryMillis } ?: return
        val delayMillis = (nearest - now()).coerceAtLeast(0)
        expiryJob =
            scope.launch {
                delay(delayMillis)
                resolveExpiredNow()
            }
    }

    internal companion object {
        private const val PREFERENCES_NAME = "whitenoise.chat_mute"
        private const val KEY_MUTED_CONVERSATIONS = "mutedConversations"
        private const val KEY_MENTION_ONLY_CONVERSATIONS = "mentionOnlyConversations"
        private const val KEY_MUTE_EXPIRIES = "muteExpiries"
        private const val EXPIRY_FIELD_SEPARATOR = "\u0000"
        private const val EXPIRY_FIELD_COUNT = 3

        // Key goes last: it can contain the separator (account labels may have
        // spaces), and split(limit = 3) leaves the final field whole. The mode
        // persists by stable name, not ordinal, so reordering the enum can't
        // silently reinterpret an installed user's pending timers.
        fun encodeMuteExpiry(entry: Map.Entry<String, MuteExpiry>): String =
            listOf(entry.value.expiryMillis, entry.value.restoreMode.name, entry.key)
                .joinToString(EXPIRY_FIELD_SEPARATOR)

        fun readMuteExpiries(preferences: SharedPreferences): Map<String, MuteExpiry> =
            preferences
                .getStringSet(KEY_MUTE_EXPIRIES, emptySet())
                .orEmpty()
                .mapNotNull(::decodeMuteExpiry)
                .toMap()

        private fun decodeMuteExpiry(encoded: String): Pair<String, MuteExpiry>? {
            val fields = encoded.split(EXPIRY_FIELD_SEPARATOR, limit = EXPIRY_FIELD_COUNT)
            if (fields.size != EXPIRY_FIELD_COUNT) return null
            val expiry = fields[0].toLongOrNull()
            val restore = decodeRestoreMode(fields[1])
            return if (expiry != null && restore != null) fields[2] to MuteExpiry(expiry, restore) else null
        }

        // Prefer the stable name; fall back to the legacy ordinal form so a blob
        // written by an earlier build of this feature still decodes.
        private fun decodeRestoreMode(field: String): ChatNotifyMode? =
            ChatNotifyMode.entries.firstOrNull { it.name == field }
                ?: field.toIntOrNull()?.let { ChatNotifyMode.entries.getOrNull(it) }

        fun compositeKey(
            accountRef: String,
            groupIdHex: String,
        ): String = "$accountRef|$groupIdHex"

        fun compositeKeyOrNull(
            accountRef: String?,
            groupIdHex: String?,
        ): String? {
            val account = accountRef?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val group = groupIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return compositeKey(account, group)
        }

        fun readMutedSet(preferences: SharedPreferences): Set<String> = preferences.getStringSet(KEY_MUTED_CONVERSATIONS, emptySet())?.toSet() ?: emptySet()

        fun mutedKeysOf(modes: Map<String, ChatNotifyMode>): Set<String> = modes.filterValues { it == ChatNotifyMode.NONE }.keys.toSet()

        fun readNotificationModes(preferences: SharedPreferences): Map<String, ChatNotifyMode> =
            buildMap {
                preferences
                    .getStringSet(KEY_MENTION_ONLY_CONVERSATIONS, emptySet())
                    .orEmpty()
                    .forEach { put(it, ChatNotifyMode.MENTIONS_ONLY) }
                // The existing mute set remains the migration source of truth.
                // If corrupt preferences contain a key in both sets, NONE wins.
                readMutedSet(preferences).forEach { put(it, ChatNotifyMode.NONE) }
            }
    }
}
