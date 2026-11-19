package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRouteOriginTest {
    private val row = Rect(8f, 100f, 352f, 180f)

    @Test fun matchingRequestConsumesItsBoundsExactlyOnce() {
        val origin = ConversationRouteOrigin()
        origin.activate("group", row) {
            assertEquals(row, origin.take("group"))
            assertNull(origin.take("group"))
        }
        assertNull(origin.take("group"))
    }

    @Test fun differentDestinationCannotInheritAnActivatingRow() {
        val origin = ConversationRouteOrigin()
        origin.activate("group", row) { assertNull(origin.take("other")) }
        assertNull(origin.take("group"))
    }

    @Test fun rejectedOrThrowingActivationCannotLeakGeometryToALaterOpen() {
        val origin = ConversationRouteOrigin()
        origin.activate("group", row) { }
        assertNull(origin.take("group"))
        try {
            origin.activate("group", row) { throw IllegalStateException("rejected") }
        } catch (_: IllegalStateException) {
            assertNull(origin.take("group"))
        }
    }
}
