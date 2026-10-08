package dev.ipf.whitenoise.android.maestro

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
    private val remoteLabel: String,
    val accountIdHex: String,
    val owner: String,
    private val publishedProfile: UserProfileMetadataFfi,
) {
    val publicProfiles = mutableMapOf<String, UserProfileMetadataFfi>()

    /** Accept the actual native Welcome before the host publishes the generated group message. */
    suspend fun acceptGroup(group: String) {
        while (runCatching { remote.acceptGroupInvite(remoteLabel, group) }.isFailure) delay(100L)
    }

    /** Retain actual public metadata for every local identity and the external peer before UI handoff. */
    suspend fun captureProfiles(
        native: Marmot,
        relays: List<String>,
    ) {
        native.refreshProfile(accountIdHex, relays)
        val ids = native.listAccounts().map { it.accountIdHex } + accountIdHex
        check(ids.size == 4 && ids.toSet().size == 4)
        withTimeout(15_000L) {
            while (native.userProfile(accountIdHex) != publishedProfile) delay(100L)
            for (id in ids) {
                while (native.userProfile(id) == null) delay(100L)
                publicProfiles[id] = checkNotNull(native.userProfile(id))
            }
        }
    }

    /** Close the second native engine before its nested generated storage is removed. */
    fun close() {
        remote.shutdownAndClose()
    }
}

/** Prepare only the contact scenario, using the same real native API and private loopback relay. */
internal fun prepareMaestroExternalContact(
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
    try {
        remote.start()
        val account = remote.createIdentity(relays, relays)
        val profile =
            UserProfileMetadataFfi(
                "Maestro Dave",
                "Maestro Dave",
                "Disposable external contact",
                null,
                null,
                null,
                null,
            )
        remote.publishUserProfile(
            account.label,
            profile,
            relays,
            relays,
        )
        return MaestroExternalContact(remote, account.label, account.accountIdHex, owner, profile)
    } catch (failure: Throwable) {
        remote.shutdownAndClose()
        throw failure
    }
}
