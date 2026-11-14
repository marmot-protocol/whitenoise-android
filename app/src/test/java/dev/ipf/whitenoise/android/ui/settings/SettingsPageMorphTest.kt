package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageMorphTest {
    private val host = Rect(0f, 0f, 360f, 780f)
    private val first = Rect(16f, 100f, 344f, 220f)
    private val second = Rect(16f, 240f, 344f, 420f)

    private fun surface(
        bounds: Rect,
        radius: Float = 20f,
    ) = SettingsRouteEndpoint(
        Any(),
        "source",
        bounds,
        RoundRect(Rect(0f, 0f, bounds.width, bounds.height), radius, radius),
        Color.Black,
        Color.White,
        true,
        true,
    )

    private fun reportGroup(
        page: SettingsPageMorph,
        bounds: Rect,
        radius: Float = 20f,
        seamFraction: Float = 0.5f,
        identity: Any = bounds.top,
    ) {
        val token = Any()
        page.reportDestination(token, surface(bounds, radius).copy(key = identity))
        val seamY = bounds.top + bounds.height * seamFraction
        page.reportRow(
            token,
            Any(),
            surface(Rect(bounds.left, bounds.top, bounds.right, seamY), radius).copy(last = false),
        )
        page.reportRow(
            token,
            Any(),
            surface(Rect(bounds.left, seamY, bounds.right, bounds.bottom), radius).copy(first = false),
        )
    }

    private fun departing(): SettingsPageMorph =
        SettingsPageMorph().also { page ->
            page.begin("parent", true, false, host)
            reportGroup(page, first)
            reportGroup(page, second)
            page.pendingOrigin = first
            assertTrue(page.begin("child", true, true, host))
        }

    @Test fun backUsesCloseCurveAndFreezesRenderedContourBeforeReversal() {
        val page = departing()
        val child = Rect(16f, 130f, 344f, 370f)
        reportGroup(page, child)
        assertTrue(page.admitDestination())
        val frozen = page.renderedContours(0.63f)
        assertTrue(page.begin("parent", false, true, host, currentTime = 0.63f))
        assertEquals(frozen, page.renderedContours(0f))
        reportGroup(page, first)
        assertTrue(page.admitDestination())
        val endpoint = page.renderedContours(1f).single()
        val origin = frozen.single { it.key == endpoint.key }
        assertEquals(
            lerp(origin.bounds, first, AppMotionCloseEasing.transform(0.5f)),
            page
                .renderedContours(0.5f)
                .single {
                    it.key ==
                        endpoint.key
                }.bounds,
        )
    }

    @Test fun ancestorFreezesThePresentedChildOutlineOnWholeRouteExit() {
        val child = departing()
        child.reportDestination(Any(), surface(Rect(16f, 130f, 344f, 370f), 8f))
        assertTrue(child.admitDestination())
        val parent = SettingsPageMorph()
        parent.begin("settings", true, false, host)
        parent.childContours = { child.presentedContours(host, 0.2f) }
        val presented = child.renderedContours(0.2f)
        parent.pendingOrigin = first
        assertTrue(parent.begin("chats", true, true, host))
        parent.childContours = null
        assertEquals(presented, parent.renderedContours(0f))
        parent.reportDestination(Any(), surface(second))
        assertTrue(parent.admitDestination())
        assertEquals(presented, parent.renderedContours(0f))
        assertEquals(second, parent.renderedContours(1f).single().bounds)
    }

    @Test fun selectedOutlineStaysOpaqueWhilePeersShrinkFadeAndRemainSeparate() {
        val page = departing()
        val target = Rect(16f, 120f, 344f, 300f)
        page.reportDestination(Any(), surface(target))
        assertTrue(page.admitDestination())
        val selectedKey = page.renderedContours(1f).single().key
        for (time in listOf(0.001f, 0.2f, 0.39f, 0.4f, 0.41f, 0.65f, 0.9f, 0.999f)) {
            val frame = page.renderedContours(time)
            val selected = frame.single { it.key == selectedKey }
            assertEquals(lerp(first, target, AppMotionOpenEasing.transform(time)), selected.bounds)
            assertEquals(2, frame.size)
            assertTrue(frame[0].bounds.bottom < frame[1].bounds.top)
            assertTrue(frame[1].bounds.width < second.width)
            frame.forEach { contour ->
                assertEquals(1f, contour.modulationAlpha, 0f)
                // Compose sRGB Color stores alpha in eight bits, as does the native paint.
                val alpha = if (contour.key == selectedKey) 1f else 1f - (time / 0.4f).coerceIn(0f, 1f)
                assertEquals(Color.White.copy(alpha = alpha).alpha, contour.color.alpha, 0f)
                assertEquals(1f, contour.width, 0f)
            }
        }
        assertEquals(target, page.renderedContours(1f).single().bounds)
    }

    @Test fun backAndForwardRetargetFreezeTheCurrentOutlineIncludingCornersAndSeams() {
        for (time in listOf(0.2f, 0.4f, 0.65f, 0.9f)) {
            for (reverse in listOf(false, true)) {
                val page = departing()
                reportGroup(page, Rect(16f, 120f, 344f, 300f), radius = 8f, seamFraction = 0.3f)
                page.admitDestination()
                val before = page.renderedContours(time)
                assertTrue(before.first().seams.isNotEmpty())
                assertTrue(
                    before
                        .first()
                        .corners.topLeftCornerRadius.x > 8f,
                )
                assertTrue(
                    before
                        .first()
                        .corners.topLeftCornerRadius.x < 20f,
                )
                if (!reverse) page.pendingOrigin = first
                assertTrue(page.begin(if (reverse) "parent" else "other", !reverse, true, host, time))
                assertEquals(before, page.renderedContours(0f))
                reportGroup(page, second, radius = 32f, seamFraction = 0.75f)
                page.admitDestination()
                assertEquals(before, page.renderedContours(0f))
                val end = page.renderedContours(1f).single()
                assertEquals(second, end.bounds)
                assertEquals(32f, end.corners.topLeftCornerRadius.x, 0f)
                assertEquals(
                    second.top + second.height * 0.75f - 0.5f,
                    end.seams
                        .single()
                        .start.y,
                    0f,
                )
                page.finish()
                assertFalse(page.active)
            }
        }
    }

    @Test fun nativeEntryRowKeysSurviveRecreatedGroupsBackAndAFrozenRetarget() {
        val page = SettingsPageMorph()
        page.begin("home", true, false, host)
        val groups = List(4) { index -> Rect(16f, 100f + index * 110f, 344f, 180f + index * 110f) }
        groups.forEachIndexed { index, bounds -> reportGroup(page, bounds, identity = "home-$index") }
        val native = requireNotNull(page.presentedContours(host, 1f))
        val selectedKey = native[2].key
        // This is the activating row inside group three, not the group rectangle itself.
        page.pendingOrigin = groups[2].copy(bottom = groups[2].center.y)
        assertTrue(page.begin("account", true, true, host))
        reportGroup(page, Rect(16f, 90f, 344f, 330f), identity = "account")
        assertTrue(page.admitDestination())
        assertEquals(selectedKey, page.renderedContours(1f).single().key)
        val frozen = page.renderedContours(0.37f)
        assertTrue(page.begin("home", false, true, host, 0.37f))
        groups.forEachIndexed { index, bounds -> reportGroup(page, bounds, identity = "home-$index") }
        assertTrue(page.admitDestination())
        assertEquals(frozen, page.renderedContours(0f))
        assertEquals(native.map { it.key }, page.renderedContours(1f).map { it.key })
        for (step in 1 until 240) assertTrue(orderedSettingsContours(page.renderedContours(step / 240f)) != null)
        page.finish()
        // A second trip through a different native row inherits its own original group key.
        page.pendingOrigin = groups[1].copy(top = groups[1].center.y)
        assertTrue(page.begin("notifications", true, true, host))
        reportGroup(page, Rect(16f, 100f, 344f, 380f), identity = "notifications")
        assertTrue(page.admitDestination())
        assertEquals(native[1].key, page.renderedContours(1f).single().key)
    }

    @Test fun retargetAndReducedMotionKeepOnlyMeasuredContourState() {
        val page = departing()
        assertTrue(page.admitDestination())
        val frame = page.renderedContours(0.4f)
        assertTrue(page.begin("parent", false, true, host, 0.4f))
        assertEquals(frame, page.renderedContours(0f))
        page.pendingOrigin = first
        assertFalse(page.begin("reduced", true, false, host))
        assertEquals(null, page.bounds(host, 0f))
    }

    @Test fun staleOriginAndChangedViewportCannotRetainPresentation() {
        val page = departing()
        assertEquals(null, page.bounds(Rect(0f, 0f, 780f, 360f), 0.2f))
        assertFalse(page.active)
        page.pendingOrigin = Rect(Float.NaN, 0f, 100f, 100f)
        assertFalse(page.begin("invalid", true, true, host))
        assertEquals(null, page.pendingOrigin)
    }
}
