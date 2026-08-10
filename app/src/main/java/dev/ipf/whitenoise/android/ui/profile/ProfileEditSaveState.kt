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
