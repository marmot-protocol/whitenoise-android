package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.core.GROUP_ID_SEARCH_MIN_LENGTH
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.looksLikeGroupIdNeedle
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

internal data class ShareAccountAliases(
    val displayName: String,
    val human: List<String>,
    val identity: List<String>,
)

@Composable
internal fun rememberShareAccountAliases(
    appState: WhiteNoiseAppState,
    ownerAccountRef: String?,
    accountIds: List<String>,
): Map<String, ShareAccountAliases> =
    buildMap {
        accountIds.distinct().forEach { accountIdHex ->
            key(accountIdHex) {
                val revision = appState.profileAccountRevisionForCompose(accountIdHex)
                put(
                    accountIdHex,
                    remember(appState, ownerAccountRef, accountIdHex, revision) {
                        val profile = appState.userProfileCached(accountIdHex)
                        val human =
                            listOfNotNull(
                                appState.contactDisplayNameCachedOrNull(ownerAccountRef, accountIdHex),
                                profile?.displayName,
                                profile?.name,
                                profile?.nip05,
                            ).mapNotNull(ProfileSanitizer::displayName)
                                .distinct()
                        ShareAccountAliases(
                            displayName = human.firstOrNull() ?: appState.shortNpub(accountIdHex),
                            human = human,
                            identity =
                                listOfNotNull(
                                    accountIdHex,
                                    runCatching { appState.npub(accountIdHex) }.getOrNull(),
                                ).distinct(),
                        )
                    },
                )
            }
        }
    }

internal fun looksLikeShareIdentityNeedle(foldedNeedle: String): Boolean =
    looksLikeGroupIdNeedle(foldedNeedle) ||
        (foldedNeedle.length >= GROUP_ID_SEARCH_MIN_LENGTH && foldedNeedle.startsWith("npub1"))
