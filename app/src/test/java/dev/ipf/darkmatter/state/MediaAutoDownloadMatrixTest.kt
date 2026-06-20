package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAutoDownloadMatrixTest {
    @Test
    fun defaultMatchesSuggestedTableCellByCell() {
        val m = MediaAutoDownloadMatrix.DEFAULT

        // Wi-Fi: Images ON, Audio ON, Video ON, Documents OFF
        assertTrue(m.isEnabled(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi))
        assertTrue(m.isEnabled(MediaAutoDownloadType.Audio, MediaAutoDownloadNetwork.WiFi))
        assertTrue(m.isEnabled(MediaAutoDownloadType.Video, MediaAutoDownloadNetwork.WiFi))
        assertFalse(m.isEnabled(MediaAutoDownloadType.Document, MediaAutoDownloadNetwork.WiFi))

        // Mobile: Images ON, Audio ON, Video OFF, Documents OFF
        assertTrue(m.isEnabled(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.Mobile))
        assertTrue(m.isEnabled(MediaAutoDownloadType.Audio, MediaAutoDownloadNetwork.Mobile))
        assertFalse(m.isEnabled(MediaAutoDownloadType.Video, MediaAutoDownloadNetwork.Mobile))
        assertFalse(m.isEnabled(MediaAutoDownloadType.Document, MediaAutoDownloadNetwork.Mobile))

        // Roaming: all OFF
        assertFalse(m.isEnabled(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.Roaming))
        assertFalse(m.isEnabled(MediaAutoDownloadType.Audio, MediaAutoDownloadNetwork.Roaming))
        assertFalse(m.isEnabled(MediaAutoDownloadType.Video, MediaAutoDownloadNetwork.Roaming))
        assertFalse(m.isEnabled(MediaAutoDownloadType.Document, MediaAutoDownloadNetwork.Roaming))

        // Metered: Images ON, Audio OFF, Video OFF, Documents OFF
        assertTrue(m.isEnabled(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.Metered))
        assertFalse(m.isEnabled(MediaAutoDownloadType.Audio, MediaAutoDownloadNetwork.Metered))
        assertFalse(m.isEnabled(MediaAutoDownloadType.Video, MediaAutoDownloadNetwork.Metered))
        assertFalse(m.isEnabled(MediaAutoDownloadType.Document, MediaAutoDownloadNetwork.Metered))
    }

    @Test
    fun shouldAutoDownloadSingleNetwork() {
        val m = MediaAutoDownloadMatrix.DEFAULT
        // Video@WiFi is ON, so a Wi-Fi-only connection auto-downloads video.
        assertTrue(
            m.shouldAutoDownload(MediaAutoDownloadType.Video, setOf(MediaAutoDownloadNetwork.WiFi)),
        )
    }

    @Test
    fun emptyActiveNetworksNeverAutoDownloads() {
        val m = MediaAutoDownloadMatrix.DEFAULT
        assertFalse(m.shouldAutoDownload(MediaAutoDownloadType.Image, emptySet()))
        assertFalse(m.shouldAutoDownload(MediaAutoDownloadType.Video, emptySet()))
    }

    @Test
    fun mostRestrictiveRuleAppliesAcrossMatchingNetworks() {
        val m = MediaAutoDownloadMatrix.DEFAULT
        // {WiFi, Metered}: Video is ON@WiFi but OFF@Metered -> no.
        assertFalse(
            m.shouldAutoDownload(
                MediaAutoDownloadType.Video,
                setOf(MediaAutoDownloadNetwork.WiFi, MediaAutoDownloadNetwork.Metered),
            ),
        )
        // {WiFi, Metered}: Image is ON for both -> yes.
        assertTrue(
            m.shouldAutoDownload(
                MediaAutoDownloadType.Image,
                setOf(MediaAutoDownloadNetwork.WiFi, MediaAutoDownloadNetwork.Metered),
            ),
        )
    }

    @Test
    fun cellularRoamingEvaluatesEveryMatchingNetwork() {
        val m = MediaAutoDownloadMatrix.DEFAULT
        // {Mobile, Roaming}: Image is ON@Mobile but OFF@Roaming -> no.
        assertFalse(
            m.shouldAutoDownload(
                MediaAutoDownloadType.Image,
                setOf(MediaAutoDownloadNetwork.Mobile, MediaAutoDownloadNetwork.Roaming),
            ),
        )
    }

    @Test
    fun roamingOffWinsOverWifiOn() {
        // A type ON for Wi-Fi but OFF for Roaming, active {WiFi, Roaming} -> no.
        val m =
            MediaAutoDownloadMatrix(emptySet())
                .withToggle(MediaAutoDownloadType.Video, MediaAutoDownloadNetwork.WiFi, on = true)
        assertFalse(
            m.shouldAutoDownload(
                MediaAutoDownloadType.Video,
                setOf(MediaAutoDownloadNetwork.WiFi, MediaAutoDownloadNetwork.Roaming),
            ),
        )
    }

    @Test
    fun withToggleIsImmutableAndDoesNotMutateOriginal() {
        val original = MediaAutoDownloadMatrix(emptySet())
        val toggled = original.withToggle(MediaAutoDownloadType.Audio, MediaAutoDownloadNetwork.Mobile, on = true)

        assertFalse(original.isEnabled(MediaAutoDownloadType.Audio, MediaAutoDownloadNetwork.Mobile))
        assertTrue(toggled.isEnabled(MediaAutoDownloadType.Audio, MediaAutoDownloadNetwork.Mobile))
        assertNotEquals(original, toggled)
    }

    @Test
    fun withToggleOffRemovesCell() {
        val m =
            MediaAutoDownloadMatrix.DEFAULT
                .withToggle(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi, on = false)
        assertFalse(m.isEnabled(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi))
    }

    @Test
    fun serializeDeserializeRoundTrips() {
        val original = MediaAutoDownloadMatrix.DEFAULT
        val roundTripped = MediaAutoDownloadMatrix.fromPreference(original.toPreference())
        assertEquals(original, roundTripped)

        val custom =
            MediaAutoDownloadMatrix(emptySet())
                .withToggle(MediaAutoDownloadType.Document, MediaAutoDownloadNetwork.WiFi, on = true)
                .withToggle(MediaAutoDownloadType.Video, MediaAutoDownloadNetwork.Roaming, on = true)
        assertEquals(custom, MediaAutoDownloadMatrix.fromPreference(custom.toPreference()))
    }

    @Test
    fun deserializeGarbageIsEmptySafeAndDoesNotThrow() {
        assertEquals(MediaAutoDownloadMatrix(emptySet()), MediaAutoDownloadMatrix.fromPreference(null))
        assertEquals(MediaAutoDownloadMatrix(emptySet()), MediaAutoDownloadMatrix.fromPreference(""))
        assertEquals(MediaAutoDownloadMatrix(emptySet()), MediaAutoDownloadMatrix.fromPreference("not-a-cell,also::bad,foo:bar"))
        // A mix of valid and garbage tokens keeps only the valid cells.
        val mixed = MediaAutoDownloadMatrix.fromPreference("image:wifi,garbage,video:roaming")
        assertTrue(mixed.isEnabled(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi))
        assertTrue(mixed.isEnabled(MediaAutoDownloadType.Video, MediaAutoDownloadNetwork.Roaming))
        assertFalse(mixed.isEnabled(MediaAutoDownloadType.Audio, MediaAutoDownloadNetwork.WiFi))
    }
}
