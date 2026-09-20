package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.marmotkit.AccountKeyPackageInventoryEntryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MarmotClient
import kotlinx.coroutines.CancellationException

/** Reads typed local provenance first and performs network refresh only on explicit demand. */
@Suppress("TooGenericExceptionCaught") // Preserve loaded UI state for every native/transport failure shape.
internal suspend fun WhiteNoiseAppState.fetchKeyPackageInventory(
    refreshFromNetwork: Boolean = false,
): List<AccountKeyPackageInventoryEntryFfi> {
    val account = activeAccountRef ?: return emptyList()
    return try {
        marmotIo {
            if (refreshFromNetwork) {
                refreshAccountKeyPackages(account, MarmotClient.bootstrapRelays)
            } else {
                localAccountKeyPackages(account)
            }
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        presentFailure(R.string.toast_couldnt_load_key_packages, "KEY_PACKAGE_LOAD", failure)
        throw failure
    }
}

/** Compatibility projection for callers that do not render typed provenance. */
@Suppress("MaxLineLength") // Kept as an expression body by ktlint's formatter.
internal suspend fun WhiteNoiseAppState.fetchKeyPackages(refreshFromNetwork: Boolean = false): List<AccountKeyPackageFfi> =
    fetchKeyPackageInventory(refreshFromNetwork).map { it.record }
