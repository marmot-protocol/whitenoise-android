package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.mutableStateMapOf
import dev.ipf.marmotkit.ChatNotificationSettingsFfi

/** The muted flag and timed-mute expiry one screen shows for a chat. */
internal data class ChatMuteDisplay(
    val muted: Boolean,
    val mutedUntilMs: Long?,
)

/**
 * Android's single owner of chat mute presentation state. MDK owns the durable
 * mute; this keeps only the latest MDK command result until a chat-list
 * projection supersedes it, and counts commands still in flight. Keys are
 * [ChatMutePreferences.compositeKey] values.
 */
internal class ChatMuteOwner {
    private val committedResults = mutableStateMapOf<String, ChatNotificationSettingsFfi>()
    private val pendingCommands = mutableStateMapOf<String, Int>()

    /** Keys with a command in flight. */
    internal val pendingKeys: Set<String> get() = pendingCommands.keys.toSet()

    /** Keys whose command result has not been superseded by a projection yet. */
    internal val committedKeys: Set<String> get() = committedResults.keys.toSet()

    fun clear() {
        committedResults.clear()
        pendingCommands.clear()
    }

    fun isPending(key: String): Boolean = (pendingCommands[key] ?: 0) > 0

    /** The command result, with an expired timed mute read as unmuted. */
    fun committedResult(
        key: String,
        nowMillis: Long,
    ): ChatNotificationSettingsFfi? = effectiveMuteOverride(committedResults[key], nowMillis)

    /** The command result's flag, otherwise the chat-list projection's. */
    fun muted(
        key: String,
        nowMillis: Long,
        listMuted: () -> Boolean,
    ): Boolean = committedResult(key, nowMillis)?.muted ?: listMuted()

    /** The command result's expiry, even when null, otherwise the chat-list projection's. */
    fun mutedUntilMs(
        key: String,
        nowMillis: Long,
        listMutedUntilMs: () -> Long?,
    ): Long? =
        if (committedResults.containsKey(key)) {
            committedResult(key, nowMillis)?.mutedUntilMs
        } else {
            listMutedUntilMs()
        }

    /**
     * Group details: the command result, then the screen's latest chat-list
     * row, then a direct MDK read made when that row is missing, then the
     * loaded chat list.
     */
    @Suppress("LongParameterList") // Each fallback is a distinct source in the display order.
    fun groupDetails(
        key: String,
        nowMillis: Long,
        rowMuted: Boolean?,
        rowMutedUntilMs: Long?,
        targeted: ChatNotificationSettingsFfi?,
        listMuted: () -> Boolean,
        listMutedUntilMs: () -> Long?,
    ): ChatMuteDisplay {
        val committed = committedResult(key, nowMillis)
        return ChatMuteDisplay(
            muted = committed?.muted ?: rowMuted ?: targeted?.muted ?: listMuted(),
            mutedUntilMs =
                if (committed != null) {
                    committed.mutedUntilMs
                } else {
                    rowMutedUntilMs ?: targeted?.mutedUntilMs ?: listMutedUntilMs()
                },
        )
    }

    /** Drops the command result once a projection supersedes it. */
    fun acceptProjection(
        key: String,
        projectedMuted: Boolean,
        projectedMutedUntilMs: Long?,
    ) {
        val committed = committedResults[key] ?: return
        if (shouldDropMuteOverride(committed, projectedMuted, projectedMutedUntilMs, isPending(key))) {
            committedResults.remove(key)
        }
    }

    /**
     * Counts [key] as pending immediately, so readers see the command before
     * it is scheduled. Run the returned command exactly once with [Command.execute].
     */
    fun admit(key: String): Command {
        pendingCommands[key] = (pendingCommands[key] ?: 0) + 1
        return Command(key)
    }

    inner class Command internal constructor(
        private val key: String,
    ) {
        private var released = false

        /** Runs [block] and releases the pending count afterwards, whatever the outcome. */
        suspend fun <T> execute(block: suspend Command.() -> T): T =
            try {
                block()
            } finally {
                release()
            }

        /** Stores MDK's result while the command is still counted as pending. */
        fun commit(settings: ChatNotificationSettingsFfi) {
            check(!released) { "Mute command already released" }
            committedResults[key] = settings
        }

        private fun release() {
            if (released) return
            released = true
            val remaining = (pendingCommands[key] ?: 0) - 1
            if (remaining <= 0) pendingCommands.remove(key) else pendingCommands[key] = remaining
        }
    }
}
