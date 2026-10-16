package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Job

/** Owns paired subscription resources, independently of chat projection and actions. */
internal class ChatListSubscriptionOwner {
    val lock = Any()
    var bindJob: Job? = null
    private var active: Attempt? = null
    val windows: ChatListWindowSet? get() = synchronized(lock) { active?.windows }
    val hasChats: Boolean get() = synchronized(lock) { active?.chats != null }

    fun install(attempt: Attempt) = synchronized(lock) { active = attempt }

    fun detach(attempt: Attempt) = synchronized(lock) {
        if (active === attempt) active = null
    }

    /** Transfers cleanup authority without closing handles while the controller lock is held. */
    fun take(): Pair<Attempt?, Job?> = synchronized(lock) {
        (active to bindJob).also { active = null; bindJob = null }
    }

    /** A captured attempt closes once even when teardown and bind-finally both retire it. */
    class Attempt {
        var windows: ChatListWindowSet? = null
        var chats: ChatsSubscriptionHandle? = null
        private var closed = false

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            runCatching { windows?.close() }
            runCatching { chats?.close() }
        }
    }
}
