package dev.ipf.whitenoise.android.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileImageDraftsTest {
    @Test
    fun restoredUploadedPictureRemainsAnEditAgainstTheNativeRefreshBaseline() {
        val native = ProfileEditDraft(picture = "https://example.com/previous.jpg")
        val restored = native.copy(picture = "https://example.com/uploaded.jpg")
        val refreshed = native.copy(about = "Refreshed native biography")

        val merged = restored.mergeUntouchedFields(loadStartedWith = native, refreshed = refreshed)

        assertEquals(restored.picture, merged.picture)
        assertEquals(refreshed.about, merged.about)
    }

    @Test
    fun bannerUploadUpdatesOnlyTheBannerDraft() {
        val drafts =
            ProfileImageDrafts(
                picture = "https://example.com/picture.jpg",
                banner = "https://example.com/old-banner.jpg",
            )

        val updated =
            drafts.withUploadedImage(
                target = ProfileImageTarget.Banner,
                uploadedUrl = "https://example.com/new-banner.jpg",
                capturedAccountRef = "alice",
                activeAccountRef = "alice",
            )

        assertEquals("https://example.com/picture.jpg", updated.picture)
        assertEquals("https://example.com/new-banner.jpg", updated.banner)
    }

    @Test
    fun completedUploadCannotApplyAfterAccountSwitch() {
        val drafts =
            ProfileImageDrafts(
                picture = "https://example.com/picture.jpg",
                banner = "https://example.com/old-banner.jpg",
            )

        val updated =
            drafts.withUploadedImage(
                target = ProfileImageTarget.Banner,
                uploadedUrl = "https://example.com/new-banner.jpg",
                capturedAccountRef = "alice",
                activeAccountRef = "bob",
            )

        assertEquals(drafts, updated)
    }

    @Test
    fun removingBannerPreservesPictureDraft() {
        val drafts =
            ProfileImageDrafts(
                picture = "https://example.com/picture.jpg",
                banner = "https://example.com/banner.jpg",
            )

        assertEquals(
            drafts.copy(banner = ""),
            drafts.without(ProfileImageTarget.Banner),
        )
    }
}
