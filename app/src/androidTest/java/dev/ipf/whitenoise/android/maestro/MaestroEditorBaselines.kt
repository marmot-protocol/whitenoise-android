package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MaestroEditorBaselines(
    val profileOwner: String?,
    val publicProfiles: Map<String, UserProfileMetadataFfi>,
    val folderRules: MaestroFolderRulesBaseline?,
    val relayLists: MaestroRelayListsBaseline?,
)

/** Snapshot only the selected editor's real owner state before launching the Activity. */
internal suspend fun captureMaestroEditorBaselines(
    native: Marmot,
    state: WhiteNoiseAppState,
    postcondition: String,
): MaestroEditorBaselines {
    val accounts = withContext(Dispatchers.Main.immediate) { state.accounts.toList() }
    val owner = withContext(Dispatchers.Main.immediate) { state.activeAccount?.accountIdHex }
    val profiles =
        if (postcondition.startsWith("public-profile-")) {
            withContext(Dispatchers.IO) {
                accounts.associate { it.accountIdHex to checkNotNull(native.userProfile(it.accountIdHex)) }
            }
        } else {
            emptyMap()
        }
    val folders =
        if (postcondition.startsWith("smart-rule-") || postcondition.startsWith("folder-details-")) {
            captureMaestroFolderRules(state)
        } else {
            null
        }
    val relays =
        if (postcondition == "relay-lists-unchanged") {
            val relayOwner = withContext(Dispatchers.Main.immediate) { checkNotNull(state.activeAccountRef) }
            withContext(Dispatchers.IO) {
                MaestroRelayListsBaseline(
                    relayOwner,
                    accounts.associate { it.label to native.accountRelayLists(it.label) },
                )
            }
        } else {
            null
        }
    return MaestroEditorBaselines(owner, profiles, folders, relays)
}
