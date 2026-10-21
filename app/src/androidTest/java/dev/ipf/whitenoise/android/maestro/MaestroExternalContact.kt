package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File

/** A genuine nonlocal contact; installed identities are intentionally ineligible for private nicknames. */
internal class MaestroExternalContact(
    private val remote: Marmot,
    private val relays: List<String>,
    val owner: String,
) {
    private var account: AccountSummaryFfi? = null
    private val publishedProfile =
        UserProfileMetadataFfi(
            "Maestro Dave",
            "Maestro Dave",
            "Disposable external contact",
            null,
            null,
            null,
            null,
        )
    val accountIdHex: String
        get() = checkNotNull(account).accountIdHex
    val publicProfiles = mutableMapOf<String, UserProfileMetadataFfi>()

    /** Startup remains suspending, with resource ownership already retained by the host's finally block. */
    suspend fun prepare() {
        remote.start()
        val identity = remote.createIdentity(relays, relays)
        account = identity
        // Identity setup publishes kind:0 too; give this rename a strictly newer Nostr second.
        val initialProfileSecond = System.currentTimeMillis() / 1000L
        while (System.currentTimeMillis() / 1000L <= initialProfileSecond) delay(25L)
        remote.publishUserProfile(identity.label, publishedProfile, relays, relays)
    }

    /** Accept the actual native Welcome before the host publishes the generated group message. */
    suspend fun acceptGroup(group: String) {
        while (runCatching { remote.acceptGroupInvite(checkNotNull(account).label, group) }.isFailure) delay(100L)
    }

    /** Retain actual public metadata for every local identity and the external peer before UI handoff. */
    suspend fun captureProfiles(
        native: Marmot,
        relays: List<String>,
    ) {
        val ids = native.listAccounts().map { it.accountIdHex } + accountIdHex
        check(ids.size == 4 && ids.toSet().size == 4)
        withTimeout(15_000L) {
            native.refreshProfile(accountIdHex, relays)
            check(native.userProfile(accountIdHex) == publishedProfile) {
                "External profile publication was not fetched"
            }
            for (id in ids) {
                publicProfiles[id] = checkNotNull(native.userProfile(id)) { "Generated public profile missing" }
            }
        }
    }

    /** Close the second native engine before its nested generated storage is removed. */
    suspend fun close() {
        remote.shutdownAndClose()
    }
}

/** Allocate the contact engine before suspending preparation, so failed setup still reaches host cleanup. */
internal fun createMaestroExternalContact(
    root: File,
    relays: List<String>,
    owner: String,
    fixture: String,
): MaestroExternalContact? {
    if (fixture != "contacts") return null
    val remote =
        Marmot.newWithConfiguration(
            File(root, "external-contact").absolutePath,
            relays,
            MarmotOptions(relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS),
        )
    return MaestroExternalContact(remote, relays, owner)
}
