package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotKitException

/** A plain nsec sign-in the surface asked the engine for. */
internal data class LoginCall(
    val nsec: String,
    val relays: List<String>,
    val keyPackageRelays: List<String>,
)

/** A consent-gated setup recovery the surface asked the engine for. */
internal data class RecoveryCall(
    val nsec: String,
    val relays: List<String>,
    val keyPackageRelays: List<String>,
    val acknowledged: Boolean,
)

/**
 * Counts which login binding a sign-in surface reaches. Both calls fail by
 * default: a sign-in that succeeds goes on to activate the account, which is
 * engine work the JVM tests have no stand-in for.
 */
internal class RecordingIdentityLoginCalls(
    private val loginFails: () -> Throwable,
    private val recoveryFails: () -> Throwable = { MarmotKitException.Runtime("recovery failed") },
) : IdentityLoginCalls {
    val logins = mutableListOf<LoginCall>()
    val recoveries = mutableListOf<RecoveryCall>()

    override suspend fun login(
        nsec: String,
        relays: List<String>,
        keyPackageRelays: List<String>,
    ): AccountSummaryFfi {
        logins += LoginCall(nsec, relays, keyPackageRelays)
        throw loginFails()
    }

    override suspend fun loginRecoveringIncompleteSetup(
        nsec: String,
        relays: List<String>,
        keyPackageRelays: List<String>,
        acknowledgePossibleKeyPackageOrphan: Boolean,
    ): AccountSummaryFfi {
        recoveries += RecoveryCall(nsec, relays, keyPackageRelays, acknowledgePossibleKeyPackageOrphan)
        throw recoveryFails()
    }
}

/** Signed-out app state for the JVM sign-in tests: no platform services, engine logins recorded. */
internal fun signInTestAppState(
    context: Context,
    engine: IdentityLoginCalls,
): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore(DiscardedDrafts),
        accountIdHexResolver = { null },
        accounts = emptyList(),
        activeAccountRef = "",
        identityLoginCalls = engine,
    )

private object DiscardedDrafts : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
