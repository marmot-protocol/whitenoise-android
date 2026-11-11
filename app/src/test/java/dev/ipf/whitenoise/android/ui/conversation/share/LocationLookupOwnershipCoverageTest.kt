package dev.ipf.whitenoise.android.ui.conversation.share

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The location picker moves the map only through [LocationLookupOwner]: a manual pan retires the
 * outstanding lookup, and an explicit locate stops earlier motion before it starts (#3719).
 */
class LocationLookupOwnershipCoverageTest {
    private val picker = appModuleSource("$SHARE/LocationPickerScreen.kt")

    /** A manual pan retires the lookup before it records the user's selection. */
    @Test
    fun manualPanRetiresTheOutstandingLookup() {
        val onScroll = picker.functionBody("onScroll")
        assertTrue("a manual pan must retire the lookup", "lookups.retire()" in onScroll)
        assertTrue(
            "the lookup is retired before the manual selection is recorded",
            onScroll.indexOf("lookups.retire()") < onScroll.indexOf("selectedCenter ="),
        )
    }

    /** An explicit locate ends any fling or animation, then publishes only through the owner. */
    @Test
    fun explicitLocateStopsEarlierMotionAndPublishesThroughTheOwner() {
        val locate = picker.functionBody("centerOnCurrent")
        val start = locate.indexOf("lookups.start(")
        assertTrue("locate goes through the owner", start >= 0)
        assertTrue("a fling cannot retire the new request", locate.indexOf("stopPanning()") in 0 until start)
        assertTrue(
            "an animation cannot retire the new request",
            locate.indexOf("stopAnimation(false)") in 0 until start,
        )
        assertFalse("no lookup is launched outside the owner", "scope.launch" in picker)
    }

    private companion object {
        const val SHARE = "src/main/java/dev/ipf/whitenoise/android/ui/conversation/share"
    }
}
