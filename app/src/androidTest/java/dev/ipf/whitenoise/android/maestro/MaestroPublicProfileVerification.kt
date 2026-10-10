package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Check the complete authoritative profile and unchanged other identities after editing the real form. */
internal suspend fun verifyMaestroPublicProfile(
    native: Marmot,
    state: WhiteNoiseAppState,
    baseline: Map<String, UserProfileMetadataFfi>,
    owner: String?,
    postcondition: String?,
): Boolean {
    if (postcondition?.startsWith("public-profile-") != true) return false
    val accountId = checkNotNull(owner)
    check(baseline.size == 3 && accountId in baseline)
    val original = baseline.getValue(accountId)
    val expected =
        when (postcondition) {
            "public-profile-text-saved" ->
                original.copy(
                    name = "Maestro edited Alice",
                    displayName = "Maestro edited Alice",
                    about = "Maestro saved biography",
                )
            "public-profile-about-cleared" -> original.copy(about = null)
            "public-profile-text-trimmed" ->
                original.copy(
                    name = "Maestro trimmed Alice",
                    displayName = "Maestro trimmed Alice",
                    about = "Maestro trimmed biography",
                )
            "public-profile-name-cleared" -> original.copy(name = null, displayName = null)
            "public-profile-unchanged" -> original
            else -> error("Unknown public-profile postcondition")
        }
    check(withContext(Dispatchers.Main.immediate) { state.activeAccount?.accountIdHex == accountId })
    return withTimeoutOrNull(15_000L) {
        var matches = false
        while (!matches) {
            matches =
                withContext(Dispatchers.IO) {
                    baseline.all { (id, profile) ->
                        native.userProfile(id) == if (id == accountId) expected else profile
                    }
                }
            if (!matches) delay(100L)
        }
        true
    } ?: false
}
