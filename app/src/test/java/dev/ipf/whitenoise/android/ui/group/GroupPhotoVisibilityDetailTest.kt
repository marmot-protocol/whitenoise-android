package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.profile.personTestGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The editor describes the photo that is actually shown, never a privacy level the group does not have. */
class GroupPhotoVisibilityDetailTest {
    private val group = personTestGroup("04" + "00".repeat(31), "Group", emptyList(), emptyList()).group

    @Test
    fun noPhotoHasNoVisibilityDetail() {
        assertNull(groupPhotoVisibilityDetail(group.copy(avatarUrl = null, imageHashHex = null)))
        assertNull(groupPhotoVisibilityDetail(group.copy(avatarUrl = "  ", imageHashHex = null)))
    }

    @Test
    fun publicUrlIsDescribedAsPublic() {
        val publicGroup = group.copy(avatarUrl = "https://image.example/a.png", imageHashHex = null)
        val detail = groupPhotoVisibilityDetail(publicGroup)
        assertEquals(R.string.group_photo_public_detail, detail)
    }

    @Test
    fun publicUrlShownAheadOfAnEncryptedImageIsDescribedAsPublic() {
        val detail =
            groupPhotoVisibilityDetail(group.copy(avatarUrl = "https://image.example/a.png", imageHashHex = "hash"))
        assertEquals(R.string.group_photo_public_detail, detail)
    }

    @Test
    fun encryptedImageAloneIsDescribedAsEncrypted() {
        val detail = groupPhotoVisibilityDetail(group.copy(avatarUrl = null, imageHashHex = "hash"))
        assertEquals(R.string.group_photo_encrypted_detail, detail)
    }
}
