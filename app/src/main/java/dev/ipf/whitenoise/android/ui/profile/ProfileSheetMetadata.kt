package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.ProfileFieldValidation
import dev.ipf.whitenoise.android.core.ProfileSanitizer

internal data class ProfileSheetMetadata(
    val displayName: String?,
    val pictureUrl: String?,
    val bannerUrl: String?,
    val about: String?,
    val nip05: String?,
    val lightningAddress: String?,
)

private inline fun firstSanitizedProfileField(
    vararg candidates: String?,
    sanitize: (String?) -> String?,
): String? = candidates.firstNotNullOfOrNull(sanitize)

private fun sanitizedProfileNip05(raw: String?): String? =
    raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() && ProfileFieldValidation.isAcceptableNip05(it) }

private fun sanitizedProfileLightningAddress(raw: String?): String? =
    raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() && ProfileFieldValidation.isAcceptableLud16(it) }

/**
 * Keeps locally materialized fields authoritative without letting a sparse or
 * invalid cached record hide richer metadata carried by a discovery result.
 */
internal fun resolveProfileSheetMetadata(
    cached: UserProfileMetadataFfi?,
    discovered: UserProfileMetadataFfi?,
    cachedAvatarUrl: String?,
): ProfileSheetMetadata =
    ProfileSheetMetadata(
        displayName =
            firstSanitizedProfileField(
                cached?.displayName,
                discovered?.displayName,
                cached?.name,
                discovered?.name,
                sanitize = ProfileSanitizer::displayName,
            ),
        pictureUrl =
            firstSanitizedProfileField(
                cachedAvatarUrl,
                cached?.picture,
                discovered?.picture,
                sanitize = ProfileSanitizer::protocolImageUrl,
            ),
        bannerUrl =
            firstSanitizedProfileField(
                cached?.banner,
                discovered?.banner,
                sanitize = ProfileSanitizer::protocolImageUrl,
            ),
        about =
            firstSanitizedProfileField(
                cached?.about,
                discovered?.about,
                sanitize = ProfileSanitizer::about,
            ),
        nip05 =
            firstSanitizedProfileField(
                cached?.nip05,
                discovered?.nip05,
                sanitize = ::sanitizedProfileNip05,
            ),
        lightningAddress =
            firstSanitizedProfileField(
                cached?.lud16,
                discovered?.lud16,
                sanitize = ::sanitizedProfileLightningAddress,
            ),
    )
