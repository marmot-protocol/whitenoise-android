package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

internal enum class ProfileImageTarget { Picture, Banner }

internal fun profileImageFailureOperation(
    target: ProfileImageTarget,
    prepared: Boolean,
): String =
    when (target) {
        ProfileImageTarget.Picture -> if (prepared) "PROFILE_IMAGE_UPLOAD" else "PROFILE_IMAGE_PREPARE"
        ProfileImageTarget.Banner -> if (prepared) "PROFILE_BANNER_UPLOAD" else "PROFILE_BANNER_PREPARE"
    }

internal data class ProfileImageDrafts(
    val picture: String = "",
    val banner: String = "",
    val baselinePicture: String = picture,
    val baselineBanner: String = banner,
) {
    /** Rebase only untouched fields; uploaded URLs/removals remain transient authored edits. */
    fun rebased(
        nativePicture: String?,
        nativeBanner: String?,
    ): ProfileImageDrafts =
        copy(
            picture = if (picture == baselinePicture) nativePicture ?: picture else picture,
            banner = if (banner == baselineBanner) nativeBanner ?: banner else banner,
            baselinePicture = nativePicture ?: baselinePicture,
            baselineBanner = nativeBanner ?: baselineBanner,
        )

    fun without(target: ProfileImageTarget): ProfileImageDrafts =
        when (target) {
            ProfileImageTarget.Picture -> copy(picture = "")
            ProfileImageTarget.Banner -> copy(banner = "")
        }

    fun withUploadedImage(
        target: ProfileImageTarget,
        uploadedUrl: String,
        capturedAccountRef: String,
        activeAccountRef: String?,
    ): ProfileImageDrafts {
        if (capturedAccountRef != activeAccountRef) return this
        return when (target) {
            ProfileImageTarget.Picture -> copy(picture = uploadedUrl)
            ProfileImageTarget.Banner -> copy(banner = uploadedUrl)
        }
    }
}

/** Saves transient image edits and their original baselines; native publication stays explicit. */
internal val ProfileImageDraftsSaver =
    listSaver<ProfileImageDrafts, String>(
        save = { listOf(it.picture, it.banner, it.baselinePicture, it.baselineBanner) },
        restore = { ProfileImageDrafts(it[0], it[1], it[2], it[3]) },
    )

/**
 * A remounted editor presents its saved authored edits rebased onto the current native image baseline.
 *
 * The rebase is derived on every read rather than written back during composition, so composing never mutates the
 * state it reads. Rebasing is idempotent, so a caller that writes a presented value back stores the same drafts.
 */
@Composable
internal fun rememberProfileImageDrafts(
    accountId: String?,
    nativePicture: String?,
    nativeBanner: String?,
): MutableState<ProfileImageDrafts> {
    val saved =
        rememberSaveable(accountId, stateSaver = ProfileImageDraftsSaver) {
            mutableStateOf(ProfileImageDrafts(nativePicture.orEmpty(), nativeBanner.orEmpty()))
        }
    return remember(saved, nativePicture, nativeBanner) {
        RebasedProfileImageDrafts(saved, nativePicture, nativeBanner)
    }
}

/** Reads [saved] rebased onto the native images, and writes straight through to it. */
private class RebasedProfileImageDrafts(
    private val saved: MutableState<ProfileImageDrafts>,
    private val nativePicture: String?,
    private val nativeBanner: String?,
) : MutableState<ProfileImageDrafts> {
    override var value: ProfileImageDrafts
        get() = saved.value.rebased(nativePicture, nativeBanner)
        set(drafts) {
            saved.value = drafts
        }

    /** The presented drafts, for destructuring. */
    override fun component1(): ProfileImageDrafts = value

    /** A setter that writes through to the saved drafts, for destructuring. */
    override fun component2(): (ProfileImageDrafts) -> Unit = { value = it }
}
