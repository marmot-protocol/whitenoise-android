package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual clicked bounds, not card order, determine each route edge in both directions. */
class SettingsPageMorphTest {
    private val host = Rect(0f, 24f, 360f, 800f)
    private val documents = Rect(16f, 340f, 344f, 392f)
    private val notifications = Rect(16f, 460f, 344f, 512f)

    @Test fun eachForwardRouteUsesItsOwnOriginAndMissingReverseFrameSettles() {
        val page = SettingsPageMorph()
        page.begin("details", true, true, host)
        page.pendingOrigin = documents
        assertTrue(page.begin("documents", true, true, host))
        assertEquals(documents, page.bounds(host, 0f))
        assertEquals(host, page.bounds(host, 1f))
        page.finish()
        assertFalse(page.begin("details", false, true, host))
        assertEquals(null, page.bounds(host, 0f))
        assertEquals(null, page.bounds(host, 1f))
        page.finish()
        page.pendingOrigin = notifications
        page.begin("notifications", true, true, host)
        page.finish()
        page.begin("details", false, true, host)
        assertEquals(null, page.bounds(host, 1f))
    }

    @Test fun missingOriginAndReducedMotionNeverInventAnOrigin() {
        val page = SettingsPageMorph()
        assertFalse(page.begin("documents", true, true, host))
        page.pendingOrigin = documents
        assertFalse(page.begin("settings", true, false, host))
        assertEquals(null, page.pendingOrigin)
        assertEquals(null, page.bounds(host, 0f))
    }

    @Test fun interruptedOpenCanReturnWithoutRetainingInteractiveContent() {
        val page = SettingsPageMorph()
        page.pendingOrigin = documents
        page.begin("documents", true, true, host)
        assertFalse(page.begin("details", false, true, host))
        assertEquals(0, page.keptPictures)
        assertEquals(null, page.bounds(host, 1f))
        page.clearPictures()
        page.finish()
        assertFalse(page.active)
    }
}
