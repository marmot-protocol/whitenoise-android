package dev.ipf.whitenoise.android.ui

import android.os.Build
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
        assertTrue(
            recentMediaGrantAllowsRead(
                mapOf(images to true, video to false, partial to false),
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            ),
        )
    }

    @Test
    fun partialVisualGrantAloneUnlocksTheStrip() {
        assertTrue(
            recentMediaGrantAllowsRead(
                mapOf(images to false, video to false, partial to true),
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            ),
        )
    }

    @Test
    fun videoOnlyGrantUnlocksTheStrip() {
        assertTrue(recentMediaGrantAllowsRead(mapOf(video to true), Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun fullDenialKeepsTheStripClosed() {
        assertFalse(
            recentMediaGrantAllowsRead(
                mapOf(images to false, video to false, partial to false),
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            ),
        )
        assertFalse(recentMediaGrantAllowsRead(emptyMap(), Build.VERSION_CODES.R))
    }

    @Test
    fun requestsGranularAndPartialMediaPermissionsOnAndroid14() {
        val perms = recentMediaReadPermissions(Build.VERSION_CODES.UPSIDE_DOWN_CAKE).toList()
        assertTrue(perms.contains(images))
        assertTrue(perms.contains(video))
        assertTrue(perms.contains(partial))
        assertFalse(perms.contains("android.permission.READ_EXTERNAL_STORAGE"))
    }

    @Test
    fun requestsGranularMediaPermissionsWithoutPartialAccessOnAndroid13() {
        val perms = recentMediaReadPermissions(Build.VERSION_CODES.TIRAMISU).toList()
        assertTrue(perms.contains(images))
        assertTrue(perms.contains(video))
        assertFalse(perms.contains(partial))
        assertFalse(perms.contains("android.permission.READ_EXTERNAL_STORAGE"))
    }

    @Test
    fun requestsLegacySharedStorageReadOnAndroid11And12() {
        val legacy = "android.permission.READ_EXTERNAL_STORAGE"

        assertTrue(recentMediaReadPermissions(Build.VERSION_CODES.R).contentEquals(arrayOf(legacy)))
        assertTrue(recentMediaReadPermissions(Build.VERSION_CODES.S_V2).contentEquals(arrayOf(legacy)))
        assertTrue(recentMediaGrantAllowsRead(mapOf(legacy to true), Build.VERSION_CODES.R))
    }
}
