package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.core.ProfileFieldValidation
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

internal fun resolvedRecipientResolution(
    input: String,
    resolving: Boolean,
    resolvedHex: String?,
    appState: WhiteNoiseAppState,
): RecipientResolution {
    val profile = resolvedHex?.let { appState.userProfile(it) }
    val pictureUrl = resolvedHex?.let { appState.avatarUrl(it) } ?: ProfileSanitizer.protocolImageUrl(profile?.picture)
    val about = ProfileSanitizer.about(profile?.about)
    val nip05 = profile?.nip05?.trim()?.takeIf { ProfileFieldValidation.isAcceptableNip05(it) }
    val hasProfile =
        profile != null &&
            (
                !ProfileSanitizer.displayName(profile.displayName ?: profile.name).isNullOrBlank() ||
                    about != null ||
                    pictureUrl != null ||
                    nip05 != null
            )
    return RecipientResolution(
        recipientPreviewState(input.isNotEmpty(), resolving, resolvedHex, hasProfile),
        resolvedHex,
    )
}
