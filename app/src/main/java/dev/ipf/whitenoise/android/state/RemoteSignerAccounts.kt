package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.Nip46SessionFfiInterface
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory handles to the MDK-owned NIP-46 sessions of this runtime's remote-signer accounts, keyed by account id.
 * MDK persists and restores the sessions; this holds only the handles it returned and their last display status,
 * so nothing here survives the runtime that owns them. Callers write only for the app's current runtime; status is
 * published through a thread-safe flow that Compose collects.
 */
internal class RemoteSignerAccounts {
    private val sessions = HashMap<String, Nip46SessionFfiInterface>()
    private var owner: Any? = null
    private val published = MutableStateFlow<Map<String, RemoteSignerAccountStatus>>(emptyMap())

    /** Display status per remote-signer account id. */
    val statuses: StateFlow<Map<String, RemoteSignerAccountStatus>> = published.asStateFlow()

    /** The status watcher; at most one runs per app state. */
    var watcher: Job? = null

    /** The last status read for [accountIdHex], or null when it is not a remote-signer account. */
    fun status(accountIdHex: String?): RemoteSignerAccountStatus? = accountIdHex?.let { published.value[it] }

    @Synchronized
    fun session(accountIdHex: String?): Nip46SessionFfiInterface? = accountIdHex?.let { sessions[it] }

    /** [accountIdHex]'s handle only while [runtime] still owns this store; a replaced runtime gets nothing. */
    @Synchronized
    fun session(
        runtime: Any,
        accountIdHex: String,
    ): Nip46SessionFfiInterface? = sessions[accountIdHex].takeIf { owner === runtime }

    @Synchronized
    fun hasSessions(): Boolean = sessions.isNotEmpty()

    /** Records [session] for [accountIdHex] on current [runtime]; a new runtime first drops the previous handles. */
    @Synchronized
    fun adopt(
        runtime: Any,
        accountIdHex: String,
        session: Nip46SessionFfiInterface,
    ) {
        claim(runtime)
        sessions[accountIdHex] = session
        publish(accountIdHex, session.status().toAccountStatus())
    }

    /** Shows [health] for an account whose session could not be resumed or was detached; no handle is kept. */
    @Synchronized
    fun markWithoutSession(
        runtime: Any,
        accountIdHex: String,
        health: RemoteSignerHealth,
    ) {
        claim(runtime)
        sessions.remove(accountIdHex)
        publish(accountIdHex, RemoteSignerAccountStatus(health))
    }

    /** Drops every handle once a different, live [runtime] replaced theirs; a momentarily absent runtime keeps them. */
    @Synchronized
    fun retainRuntime(runtime: Any?) {
        if (runtime != null && owner != null && owner !== runtime) clear()
    }

    /** Re-reads every session's local status; MDK serves it without network access. */
    fun refresh() {
        val snapshot = synchronized(this) { sessions.toMap() }
        snapshot.forEach { (account, session) ->
            val next = session.status().toAccountStatus()
            synchronized(this) { if (sessions[account] === session) publish(account, next) }
        }
    }

    private fun claim(runtime: Any) {
        if (owner !== runtime) clear()
        owner = runtime
    }

    private fun publish(
        accountIdHex: String,
        status: RemoteSignerAccountStatus,
    ) {
        published.update { current ->
            if (current[accountIdHex] == status) current else current + (accountIdHex to status)
        }
    }

    private fun clear() {
        sessions.clear()
        published.value = emptyMap()
        owner = null
    }
}
