package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.UserProfileMetadataFfi

internal fun profileEditMetadata(
    displayName: String,
    about: String,
    picture: String,
    banner: String,
    nip05: String,
    lud16: String,
): UserProfileMetadataFfi =
    UserProfileMetadataFfi(
        name = displayName.trim().ifBlank { null },
        displayName = displayName.trim().ifBlank { null },
        about = about.trim().ifBlank { null },
        picture = picture.trim().ifBlank { null },
        banner = banner.trim().ifBlank { null },
        nip05 = nip05.trim().ifBlank { null },
        lud16 = lud16.trim().ifBlank { null },
    )

internal data class ProfileEditDraft(
    val displayName: String = "",
    val about: String = "",
    val picture: String = "",
    val banner: String = "",
    val nip05: String = "",
    val lud16: String = "",
) {
    fun metadata(): UserProfileMetadataFfi = profileEditMetadata(displayName, about, picture, banner, nip05, lud16)

    fun mergeUntouchedFields(
        loadStartedWith: ProfileEditDraft,
        refreshed: ProfileEditDraft,
    ): ProfileEditDraft =
        ProfileEditDraft(
            displayName = displayName.refreshIfUntouched(loadStartedWith.displayName, refreshed.displayName),
            about = about.refreshIfUntouched(loadStartedWith.about, refreshed.about),
            picture = picture.refreshIfUntouched(loadStartedWith.picture, refreshed.picture),
            banner = banner.refreshIfUntouched(loadStartedWith.banner, refreshed.banner),
            nip05 = nip05.refreshIfUntouched(loadStartedWith.nip05, refreshed.nip05),
            lud16 = lud16.refreshIfUntouched(loadStartedWith.lud16, refreshed.lud16),
        )
}

internal fun profileEditDraft(profile: UserProfileMetadataFfi?): ProfileEditDraft =
    ProfileEditDraft(
        displayName = profile?.displayName ?: profile?.name.orEmpty(),
        about = profile?.about.orEmpty(),
        picture = profile?.picture.orEmpty(),
        banner = profile?.banner.orEmpty(),
        nip05 = profile?.nip05.orEmpty(),
        lud16 = profile?.lud16.orEmpty(),
    )

private fun String.refreshIfUntouched(
    loadStartedWith: String,
    refreshed: String,
): String = if (this == loadStartedWith) refreshed else this

@Stable
internal class ProfileEditSaveState {
    private var baselineAccountId by mutableStateOf<String?>(null)
    private var baseline by mutableStateOf<UserProfileMetadataFfi?>(null)

    var profileLoaded by mutableStateOf(false)
        private set

    fun beginLoad(accountId: String?) {
        baselineAccountId = accountId
        baseline = null
        profileLoaded = false
    }

    fun completeLoad(
        accountId: String,
        metadata: UserProfileMetadataFfi,
    ): Boolean {
        if (baselineAccountId != accountId) return false
        baseline = metadata.copy()
        profileLoaded = true
        return true
    }

    fun completeSave(
        accountId: String,
        submittedMetadata: UserProfileMetadataFfi,
        succeeded: Boolean,
    ): Boolean {
        if (!succeeded || !profileLoaded || baselineAccountId != accountId) return false
        baseline = submittedMetadata.copy()
        return true
    }

    fun isLoadedFor(accountId: String?): Boolean = accountId != null && profileLoaded && baselineAccountId == accountId

    fun canSave(
        accountId: String?,
        currentMetadata: UserProfileMetadataFfi,
    ): Boolean = isLoadedFor(accountId) && baseline != currentMetadata
}
