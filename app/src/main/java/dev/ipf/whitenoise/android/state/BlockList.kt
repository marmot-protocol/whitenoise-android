package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.BlockListSnapshotFfi
import dev.ipf.marmotkit.BlockListSubscriptionInterface
import dev.ipf.marmotkit.BlockedUserFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Runtime-wide live mirrors of MDK summaries that outlive individual screens. */
internal class RuntimeMirrors(
    val attention: AccountAttentionMirror = AccountAttentionMirror(),
    val blocks: BlockListMirror = BlockListMirror(),
)

/** Outcome of a block or unblock request, as the UI must present it. */
internal enum class BlockOutcome {
    /** MDK confirmed the new block state. */
    Confirmed,

    /** MDK stored the intent but could not confirm publication; retrying the same request reconciles it. */
    Uncertain,

    /** The account's block list cannot be read or written right now. */
    Unavailable,

    /** Any other failure; nothing changed. */
    Failed,
}

/**
 * Mirrors one account's live block list. Blocking keeps history and existing DMs, hides blocked authors
 * through MDK's presentation APIs and refuses user-authored sends in those DMs; this mirror only answers
 * "is this account blocked" for the active account and lists blocked users for settings.
 */
internal class BlockListMirror {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** Account whose list is mirrored, or null when no account is bound. */
    var accountRef: String? = null
        private set

    /** Blocked users of the bound account, in MDK's order; empty until the first replacement arrives. */
    var users: List<BlockedUserFfi> by mutableStateOf(emptyList())
        private set

    /** Revision of the newest installed replacement. */
    var revision: ULong? by mutableStateOf(null)
        private set

    /** False after MDK reported the list unavailable; the UI renders that state instead of "nobody blocked". */
    var available: Boolean by mutableStateOf(true)
        private set

    /** Lower-cased account ids for membership checks. */
    private var blockedIds: Set<String> = emptySet()

    /** Whether [userAccountIdHex] is blocked by the bound account. */
    fun isBlocked(userAccountIdHex: String): Boolean = userAccountIdHex.lowercase(Locale.ROOT) in blockedIds

    /** Replaces any running mirror with one bound to [accountRef]; a null account only stops the current one. */
    fun bind(
        accountRef: String?,
        open: (accountRef: String) -> BlockListSubscriptionInterface,
    ) {
        if (accountRef == this.accountRef && job?.isActive == true) return
        stop()
        this.accountRef = accountRef ?: return
        job = scope.launch { receive(accountRef, open) }
    }

    /**
     * Stops the mirror and forgets the bound account's list. Returns the receive job it cancelled, if any,
     * so a caller that must not outlive it (a test tearing down its main dispatcher) can wait for it.
     */
    fun stop(): Job? {
        val stopped = job
        stopped?.cancel()
        job = null
        accountRef = null
        install(emptyList(), revision = null, available = true)
        return stopped
    }

    /** Applies one replacement; MDK's revision is monotonic per account. */
    internal fun install(snapshot: BlockListSnapshotFfi) {
        val current = revision
        if (current != null && snapshot.revision <= current) return
        install(snapshot.users, snapshot.revision, available = true)
    }

    /** Marks the list unavailable while keeping the last known users for display. */
    internal fun markUnavailable() {
        available = false
    }

    private fun install(
        users: List<BlockedUserFfi>,
        revision: ULong?,
        available: Boolean,
    ) {
        this.users = users
        this.revision = revision
        this.available = available
        blockedIds = users.mapTo(hashSetOf()) { it.publicKey.lowercase(Locale.ROOT) }
    }

    // A failing list must never take the screen down; it is shown as unavailable instead.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun receive(
        accountRef: String,
        open: (String) -> BlockListSubscriptionInterface,
    ) {
        val subscription =
            try {
                withContext(Dispatchers.IO) { open(accountRef) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                appStateDebug(failure) { "block list subscription unavailable" }
                markUnavailable()
                return
            }
        try {
            withContext(Dispatchers.IO) { subscription.snapshot() }?.let(::install)
            while (currentCoroutineContext().isActive) {
                val next = withContext(Dispatchers.IO) { subscription.next() } ?: break
                install(next)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            appStateDebug(failure) { "block list mirror ended" }
            markUnavailable()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { (subscription as? AutoCloseable)?.close() } }
        }
    }
}

/** Binds the runtime block-list mirror to [accountRef] using the live MarmotKit subscription. */
internal fun WhiteNoiseAppState.bindBlockListMirror(accountRef: String?) {
    runtimeMirrors.blocks.bind(accountRef) { account -> marmot().subscribeBlockedUsers(account) }
}

/**
 * Blocks or unblocks [userAccountIdHex] for [accountRef] and reports how MDK settled it. An uncertain
 * publication is not success: the UI keeps the previous state and offers the same request again. Each
 * typed outcome is returned to the UI; the engine's detail text carries no user-facing value.
 */
@Suppress("SwallowedException")
internal suspend fun WhiteNoiseAppState.setUserBlocked(
    accountRef: String,
    userAccountIdHex: String,
    blocked: Boolean,
): BlockOutcome =
    try {
        marmotIo { if (blocked) blockUser(accountRef, userAccountIdHex) else unblockUser(accountRef, userAccountIdHex) }
        BlockOutcome.Confirmed
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (uncertain: MarmotKitException.BlockPublicationUncertain) {
        BlockOutcome.Uncertain
    } catch (unavailable: MarmotKitException.BlockListUnavailable) {
        BlockOutcome.Unavailable
    } catch (failure: MarmotKitException) {
        appStateDebug(failure) { "block state change failed" }
        BlockOutcome.Failed
    }

/**
 * Whether the active account blocks [userAccountIdHex], from the live mirror when bound, else from MDK.
 * Null means nobody could answer: the mirror holds no revision yet and the authoritative read failed or the
 * block list is unavailable. Callers must keep the block action disabled instead of reading null as "not
 * blocked", which would offer Block for someone already blocked.
 */
internal suspend fun WhiteNoiseAppState.isUserBlocked(
    accountRef: String,
    userAccountIdHex: String,
): Boolean? =
    resolveBlockedState(runtimeMirrors.blocks, accountRef, userAccountIdHex) {
        marmotIo { isUserBlocked(accountRef, userAccountIdHex) }
    }

/** The mirror's answer when it is bound and has a revision, otherwise [read]'s, or null when it fails. */
internal suspend fun resolveBlockedState(
    mirror: BlockListMirror,
    accountRef: String,
    userAccountIdHex: String,
    read: suspend () -> Boolean,
): Boolean? {
    if (mirror.accountRef == accountRef && mirror.revision != null) return mirror.isBlocked(userAccountIdHex)
    return runCatchingCancellable { read() }.getOrNull()
}
