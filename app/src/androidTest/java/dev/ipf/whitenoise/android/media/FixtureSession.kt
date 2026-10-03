package dev.ipf.whitenoise.android.media

import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.enforceAppOwnedAttachmentAcquisitionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** A generated sender and receiver sharing one generated group. */
internal class FixturePeers(
    val sender: AccountSummaryFfi,
    val receiver: AccountSummaryFfi,
    val group: String,
)

/** The runtime, generated accounts and per-account states for one process, always released on every path. */
internal class FixtureSession(
    val context: Context,
    val root: File,
    val marmot: Marmot,
    val relays: List<String>,
    val blobPort: Int,
) {
    val accounts = mutableListOf<String>()
    private val states = mutableMapOf<String, WhiteNoiseAppState>()

    /** Creates two disposable identities and a group, and waits for the receiver's genuine welcome. */
    suspend fun createPeers(): FixturePeers {
        val sender = marmot.createIdentity(relays, relays)
        accounts += sender.label
        val receiver = marmot.createIdentity(relays, relays)
        accounts += receiver.label
        marmot.enforceAppOwnedAttachmentAcquisitionPolicy(listOf(receiver.label, sender.label))
        val group = marmot.createGroup(sender.label, "Generated fixture", listOf(receiver.accountIdHex), null)
        ControlledAttachmentProbe.awaitReceivedGroup(marmot, receiver.label, group)
        return FixturePeers(sender, receiver, group)
    }

    /** Builds a fixture-only state for [account]; its drafts and caches never touch the installed app. */
    suspend fun state(account: String): WhiteNoiseAppState =
        states.getOrPut(account) {
            withContext(Dispatchers.Main.immediate) {
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore(DiscardedDrafts),
                    accountIdHexResolver = { null },
                    accounts = emptyList(),
                    activeAccountRef = account,
                    initialMarmotRuntime = AppMarmotRuntime(root.absolutePath, marmot),
                )
            }
        }

    /** Cancels fixture work, removes only generated accounts and, unless preserved, the generated root. */
    suspend fun close(preserve: Boolean) {
        states.values.forEach { it.mutationsScope.cancel() }
        val cleanup =
            if (preserve) {
                emptyList()
            } else {
                accounts.map { runCatching { withTimeout(CLEANUP_TIMEOUT_MILLIS) { marmot.removeAccount(it) } } }
            }
        try {
            marmot.shutdownAndClose()
        } finally {
            if (!preserve) root.deleteRecursively()
        }
        check(cleanup.all { it.isSuccess }) { "fixture account cleanup failed" }
    }

    private companion object {
        const val CLEANUP_TIMEOUT_MILLIS = 5_000L
    }

    /** The generated session has no persisted user draft. */
    private object DiscardedDrafts : DraftPersistence {
        /** Never reads installed draft storage. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards only generated-fixture draft writes. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
