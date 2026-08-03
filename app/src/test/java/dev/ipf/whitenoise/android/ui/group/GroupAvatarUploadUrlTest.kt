package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GroupAvatarUploadUrlTest {
    @Test
    fun aSafeUploadUrlIsPublishedAsGiven() {
        assertEquals(
            "https://blossom.example/abc.jpg",
            safeAvatarUploadUrl("  https://blossom.example/abc.jpg  "),
        )
    }

    @Test
    fun anUnsafeUploadUrlIsRejectedRatherThanPublished() {
        // The upload answer becomes a permanently public URL, so anything the
        // sanitizer rejects must fail the publish instead of falling back.
        listOf(
            "http://blossom.example/abc.jpg",
            "https://user:pass@blossom.example/abc.jpg",
            "https://localhost/abc.jpg",
            "https://127.0.0.1/abc.jpg",
            "not a url",
            "",
        ).forEach { uploaded ->
            assertThrows(IllegalStateException::class.java) { safeAvatarUploadUrl(uploaded) }
        }
    }
}
