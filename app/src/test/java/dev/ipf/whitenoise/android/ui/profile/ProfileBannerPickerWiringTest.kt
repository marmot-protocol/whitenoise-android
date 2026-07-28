package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileBannerPickerWiringTest {
    @Test
    fun profileBannerUsesDedicatedPickerUploadAndStaleResultState() {
        val body = profileEditSource().readText().functionBody("ProfileEditScreen")

        assertTrue("header must render the tappable wide banner control", "ProfileBannerControl(" in body)
        assertTrue(
            "banner picker must use the wide preview",
            "previewPresentation = ImagePreviewPresentation.Banner" in body,
        )
        assertTrue("banner upload must have independent progress state", "bannerUploading" in body)
        assertTrue("save must wait for banner upload", "!bannerUploading" in body)
        assertTrue(
            "banner gallery and URL/search paths must target banner state",
            Regex("target = ProfileImageTarget\\.Banner").findAll(body).count() >= 2,
        )
        assertTrue(
            "account changes must cancel banner upload",
            "bannerUploadJob?.cancel()" in body,
        )
        assertTrue(
            "banner completion must be stale-guarded by account",
            "activeAccountRef = appState.activeAccountRef" in body,
        )
        assertFalse(
            "the developer-facing standalone banner URL field must be removed",
            "value = imageDrafts.banner" in body,
        )
    }

    @Test
    fun galleryUploadOwnsAVisiblePickerProgressIndicator() {
        val source = groupImageSearchSource().readText()

        assertTrue(
            "gallery selection must own the pending action before upload starts",
            "pendingAction = GroupImageAction.PickPhoto" in source,
        )
        assertTrue(
            "gallery button must render progress for its pending upload",
            "pendingAction == GroupImageAction.PickPhoto" in source,
        )
    }

    private fun profileEditSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/profile/ProfileEditScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/profile/ProfileEditScreen.kt"),
        ).firstOrNull(File::exists) ?: error("Missing ProfileEditScreen.kt")

    private fun groupImageSearchSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/group/GroupImageSearch.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/group/GroupImageSearch.kt"),
        ).firstOrNull(File::exists) ?: error("Missing GroupImageSearch.kt")
}
