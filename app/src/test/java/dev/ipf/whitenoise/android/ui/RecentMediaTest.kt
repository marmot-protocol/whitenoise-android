package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.media.recentMediaGrantAllowsRead
import dev.ipf.whitenoise.android.ui.conversation.media.recentMediaReadPermissions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the recent-media strip's permission gate: full images, full video, OR
 * Android's partial "Select photos" grant each unlock the strip, and a full
 * denial keeps it closed (the permission-free Gallery tile still works).
 */
class RecentMediaTest {
    private val images = "android.permission.READ_MEDIA_IMAGES"
    private val video = "android.permission.READ_MEDIA_VIDEO"
    private val partial = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    @Test
    fun fullImageGrantUnlocksTheStrip() {
        assertTrue(recentMediaGrantAllowsRead(mapOf(images to true, video to false, partial to false)))
    }

    @Test
    fun partialVisualGrantAloneUnlocksTheStrip() {
        assertTrue(recentMediaGrantAllowsRead(mapOf(images to false, video to false, partial to true)))
    }

    @Test
    fun videoOnlyGrantUnlocksTheStrip() {
        assertTrue(recentMediaGrantAllowsRead(mapOf(video to true)))
    }

    @Test
    fun fullDenialKeepsTheStripClosed() {
        assertFalse(recentMediaGrantAllowsRead(mapOf(images to false, video to false, partial to false)))
        assertFalse(recentMediaGrantAllowsRead(emptyMap()))
    }

    @Test
    fun requestsGranularMediaPermissionsNotLegacyStorage() {
        val perms = recentMediaReadPermissions().toList()
        assertTrue(perms.contains(images))
        assertTrue(perms.contains(video))
        assertTrue(perms.contains(partial))
        assertFalse(perms.contains("android.permission.READ_EXTERNAL_STORAGE"))
    }
}
