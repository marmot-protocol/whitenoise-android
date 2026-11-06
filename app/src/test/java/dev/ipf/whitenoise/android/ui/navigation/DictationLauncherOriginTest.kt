package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictationLauncherOriginTest {
    /** Outgoing disposal and invalid measurements cannot erase the current route's replacement. */
    @Test
    fun replacementRetainsOnlyItsOwnFiniteMeasuredOrigin() {
        val origin = DictationLauncherOrigin()
        val outgoing = Any()
        val incoming = Any()
        origin.report(outgoing, Rect(8f, 600f, 56f, 648f))
        val current = Rect(80f, 620f, 128f, 668f)
        origin.report(incoming, current)
        origin.remove(outgoing)
        origin.report(outgoing, Rect.Zero)
        assertEquals(current, origin.boundsInWindow)
        origin.remove(incoming)
        assertNull(origin.boundsInWindow)
    }

    /** Hidden, unmeasured or nonfinite launchers supply no first-appearance trajectory. */
    @Test
    fun missingAndInvalidGeometryNeverSuppliesAnOrigin() {
        val origin = DictationLauncherOrigin()
        val token = Any()
        assertNull(origin.boundsInWindow)
        origin.report(token, Rect.Zero)
        assertNull(origin.boundsInWindow)
        origin.report(token, Rect(0f, 0f, Float.POSITIVE_INFINITY, 48f))
        assertNull(origin.boundsInWindow)
    }

    /** Recreating a motion object for lock/unlock never replays an active session's entrance. */
    @Test
    fun existingOrInitiallyExcludedSessionCannotReenterFromCachedGeometry() {
        val origin = DictationLauncherOrigin()
        val launcher = Rect(80f, 620f, 128f, 668f)
        origin.report(Any(), launcher)
        assertEquals(launcher, origin.takeEntrance(1L, eligible = true))
        assertNull(origin.takeEntrance(1L, eligible = false))
        assertNull(origin.takeEntrance(1L, eligible = true))
        assertNull(origin.takeEntrance(2L, eligible = false))
        assertNull(origin.takeEntrance(2L, eligible = true))
        assertEquals(launcher, origin.takeEntrance(3L, eligible = true))
    }

    /** Late geometry cannot animate a session whose first measured entry was missing. */
    @Test
    fun missingFirstGeometryIsConsumedWithoutWaitingForAnUnrelatedMount() {
        val origin = DictationLauncherOrigin()
        assertNull(origin.takeEntrance(1L, eligible = true))
        val launcher = Rect(80f, 620f, 128f, 668f)
        origin.report(Any(), launcher)
        assertNull(origin.takeEntrance(1L, eligible = true))
        assertEquals(launcher, origin.takeEntrance(2L, eligible = true))
    }

    /** A Plus menu row closes before dictation starts, so its latched origin serves only the next entrance. */
    @Test
    fun transientMenuOriginWinsOnceAndANewLauncherSupersedesIt() {
        val origin = DictationLauncherOrigin()
        val toolbar = Rect(80f, 620f, 128f, 668f)
        val menuRow = Rect(16f, 400f, 216f, 448f)
        val token = Any()
        origin.report(token, toolbar)
        origin.launchFrom(menuRow)
        origin.report(token, toolbar)
        assertEquals(menuRow, origin.takeEntrance(1L, eligible = true))
        assertEquals(toolbar, origin.takeEntrance(2L, eligible = true))
        origin.launchFrom(menuRow)
        origin.report(Any(), toolbar)
        assertEquals(toolbar, origin.takeEntrance(3L, eligible = true))
        origin.launchFrom(Rect.Zero)
        assertEquals(toolbar, origin.takeEntrance(4L, eligible = true))
    }
}
