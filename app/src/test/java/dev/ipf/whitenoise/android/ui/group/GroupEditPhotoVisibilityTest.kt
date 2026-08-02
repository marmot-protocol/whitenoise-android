package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEditPhotoVisibilityTest {
    @Test
    fun aGroupPublishingASafeUrlAvatarStartsOnThePublicTrack() {
        assertTrue(groupPhotoIsPublic("https://blossom.example/abc.jpg"))
    }

    @Test
    fun aGroupWithoutAUrlAvatarStartsOnTheEncryptedTrack() {
        assertFalse(groupPhotoIsPublic(null))
        assertFalse(groupPhotoIsPublic(""))
        assertFalse(groupPhotoIsPublic("   "))
    }

    @Test
    fun anUnusableStoredUrlDoesNotReadAsPublic() {
        assertFalse(groupPhotoIsPublic("http://blossom.example/abc.jpg"))
        assertFalse(groupPhotoIsPublic("https://127.0.0.1/abc.jpg"))
        assertFalse(groupPhotoIsPublic("not a url"))
    }

    @Test
    fun aSafeUploadUrlIsPublishedAsGiven() {
        assertEquals(
            "https://blossom.example/abc.jpg",
            safeAvatarUploadUrl("  https://blossom.example/abc.jpg  "),
        )
    }

    @Test
    fun anUnsafeUploadUrlIsRejectedRatherThanPublished() {
        listOf(
            "http://blossom.example/abc.jpg",
            "https://user:pass@blossom.example/abc.jpg",
            "https://localhost/abc.jpg",
            "",
        ).forEach { uploaded ->
            assertThrows(IllegalStateException::class.java) { safeAvatarUploadUrl(uploaded) }
        }
    }
}
