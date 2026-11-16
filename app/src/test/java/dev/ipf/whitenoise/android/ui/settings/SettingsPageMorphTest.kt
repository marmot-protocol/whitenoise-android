package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import dev.ipf.whitenoise.android.ui.common.AppMotionCloseEasing
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

    @Test fun unchangedNativeFramesReuseGeometryButChangedReportsReplaceIt() {
        val page = SettingsPageMorph()
        page.begin("parent", true, false, host)
        val group = Any()
        val row = Any()
        val viewport = Any()
        val endpoint = surface(first).copy(viewportToken = viewport)
        val rowEndpoint = surface(first.copy(bottom = 160f)).copy(last = false)
        page.reportDestination(group, endpoint)
        page.reportRow(group, row, rowEndpoint)
        page.reportViewport(viewport, host)
        val original = requireNotNull(page.presentedContours(host, 1f))
        repeat(60) { assertSame(original, page.presentedContours(host, 1f)) }
        page.reportDestination(group, endpoint)
        page.reportRow(group, row, rowEndpoint)
        page.reportViewport(viewport, host)
        assertSame(original, page.presentedContours(host, 1f))

        page.reportRow(group, row, rowEndpoint.copy(bounds = first.copy(bottom = 180f)))
        val moved = requireNotNull(page.presentedContours(host, 1f))
        assertNotSame(original, moved)
        assertEquals(
            179.5f,
            moved
                .single()
                .seams
                .single()
                .start
                .y,
            0f,
        )
        assertEquals(
            159.5f,
            original
                .single()
                .seams
                .single()
                .start
                .y,
            0f,
        )
        assertEquals(original.single().key, moved.single().key)

        val clipped = host.copy(top = 150f)
        page.reportViewport(viewport, clipped)
        assertEquals(clipped, page.presentedContours(host, 1f)?.single()?.viewport)
        page.removeViewport(viewport)
        assertEquals(host, page.presentedContours(host, 1f)?.single()?.viewport)
        page.removeRow(group, row)
        assertTrue(requireNotNull(page.presentedContours(host, 1f)).single().seams.isEmpty())
        page.reportDestination(group, endpoint.copy(bounds = second))
        assertEquals(second, page.presentedContours(host, 1f)?.single()?.bounds)
        page.removeDestination(group)
        assertTrue(requireNotNull(page.presentedContours(host, 1f)).isEmpty())
    }

    @Test fun nativeFrameReuseRespectsHostInvalidGeometryAndNewRoutes() {
        val page = SettingsPageMorph()
        page.begin("parent", true, false, host)
        val group = Any()
        val endpoint = surface(first)
        page.reportDestination(group, endpoint)
        val original = requireNotNull(page.presentedContours(host, 1f))
        val resized = host.copy(bottom = 900f)
        assertEquals(resized, page.presentedContours(resized, 1f)?.single()?.viewport)
        page.reportDestination(group, endpoint.copy(bounds = first.copy(top = Float.NaN)))
        assertEquals(null, page.presentedContours(host, 1f))
        assertEquals(null, page.presentedContours(host, 1f))
        page.reportDestination(group, endpoint)
        assertEquals(original, page.presentedContours(host, 1f))
        page.cancel()
        page.begin("another", true, false, host)
        assertTrue(requireNotNull(page.presentedContours(host, 1f)).isEmpty())
        page.reportDestination(group, endpoint)
        assertEquals(first, page.presentedContours(host, 1f)?.single()?.bounds)
    }

    @Test fun childFramesRemainLiveWithoutParentLayoutReports() {
        val page = SettingsPageMorph()
        page.begin("parent", true, false, host)
        reportGroup(page, first)
        val native = requireNotNull(page.presentedContours(host, 1f))
        var child = native
        page.childContours = { child }
        assertSame(child, page.presentedContours(host, 1f))
        child = native.map { it.copy(bounds = second) }
        assertSame(child, page.presentedContours(host, 1f))
        assertEquals(second, page.presentedContours(host, 1f)?.single()?.bounds)
        page.childContours = { null }
        assertSame(native, page.presentedContours(host, 1f))
    }

    @Test fun partiallyVisibleActivationKeepsFullSourcePerimeterAndReturnsToIt() {
        val page = SettingsPageMorph()
        page.begin("parent", true, false, host)
        val full = Rect(16f, -90f, 344f, 160f)
        reportGroup(page, full)
        page.pendingEndpoint = surface(full).copy(destination = "child", visibleBounds = full.intersect(host))
        assertTrue(page.begin("child", true, true, host))
        assertEquals(full, page.renderedContours(0f).single().bounds)
        reportGroup(page, second)
        assertTrue(page.admitDestination())
        page.finish()
        assertTrue(page.begin("parent", false, true, host))
        reportGroup(page, full)
        assertTrue(page.admitDestination())
        assertEquals(full, page.renderedContours(1f).single().bounds)
    }

    @Test fun fullyClippedActivationCannotStartTransition() {
        val page = SettingsPageMorph()
        page.begin("parent", true, false, host)
        reportGroup(page, first)
        page.pendingEndpoint =
            surface(Rect(16f, -120f, 344f, -20f)).copy(destination = "child", visibleBounds = Rect.Zero)
        assertFalse(page.begin("child", true, true, host))
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
        val origin = frozen.first()
        assertEquals(
            lerp(origin.bounds, first, AppMotionCloseEasing.transform(0.5f)),
            page
                .renderedContours(0.5f)
                .single {
                    it.key ==
                        origin.key
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

    private val anchor = Rect(16f, 8f, 344f, 56f)

    private fun anchorOutline(bounds: Rect = anchor) =
        SettingsContour(bounds, RoundRect(Rect(0f, 0f, bounds.width, bounds.height), 12f, 12f), bounds, Color.Red, 2f)

    private fun openedFromAnchor(outline: () -> SettingsContour? = { anchorOutline() }): SettingsPageMorph =
        SettingsPageMorph().also { page ->
            page.anchorContour = outline
            page.begin("conversation", true, false, host)
            page.pendingOrigin = anchor
            assertTrue(page.begin("details", true, true, host))
            reportGroup(page, first)
            reportGroup(page, second)
            assertTrue(page.admitDestination())
        }

    @Test fun anchorOutlineOpensOnceIntoTheNearestGroupWhileOthersArriveSeparately() {
        val page = openedFromAnchor()
        val start = page.renderedContours(0f)
        assertEquals(anchor, start.single().bounds)
        assertEquals(Color.Red, start.single().color)
        assertEquals(2f, start.single().width, 0f)
        val selectedKey = page.renderedContours(1f).first { it.bounds == first }.key
        assertTrue(start.single().key != selectedKey)
        for (time in listOf(0.05f, 0.3f, 0.6f, 0.9f)) {
            val frame = page.renderedContours(time)
            val selected = frame.single { it.key == selectedKey }
            assertEquals(lerp(anchor, first, AppMotionOpenEasing.transform(time)), selected.bounds)
            // Only one outline leaves the anchor; the other group never duplicates it.
            assertTrue(frame.none { it.key != selectedKey && it.bounds == selected.bounds })
        }
        assertEquals(listOf(first, second), page.renderedContours(1f).map { it.bounds })
    }

    @Test fun backFoldsIntoTheAnchorOutline() {
        val page = openedFromAnchor()
        page.finish()
        assertTrue(page.begin("conversation", false, true, host))
        assertTrue(page.admitDestination())
        val end = page.renderedContours(1f)
        assertEquals(anchor, end.single().bounds)
        assertEquals(Color.Red, end.single().color)
        assertEquals(
            12f,
            end
                .single()
                .corners.topLeftCornerRadius.x,
            0f,
        )
    }

    @Test fun reopeningFromTheAnchorPairsWithTheSameGroup() {
        val page = openedFromAnchor()
        page.finish()
        page.begin("conversation", false, false, host)
        page.pendingOrigin = anchor
        assertTrue(page.begin("details", true, true, host))
        reportGroup(page, first)
        reportGroup(page, second)
        assertTrue(page.admitDestination())
        assertEquals(first, page.renderedContours(1f).first().bounds)
    }

    @Test fun offscreenAnchorOutlineLeavesTheOpeningWithoutDuplicates() {
        val page = openedFromAnchor { anchorOutline(Rect(16f, -200f, 344f, -150f)) }
        for (time in listOf(0f, 0.3f)) assertTrue(page.renderedContours(time).none { it.bounds.top < 0f })
    }

    @Test fun selectedOutlineStaysOpaqueWhilePeersExitRigidlyAndRemainSeparate() {
        val page = departing()
        val target = Rect(16f, 120f, 344f, 300f)
        page.reportDestination(Any(), surface(target))
        assertTrue(page.admitDestination())
        val selectedKey = page.renderedContours(0f).first().key
        for (time in listOf(0.001f, 0.2f, 0.39f, 0.4f, 0.41f, 0.65f, 0.9f, 0.999f)) {
            val frame = page.renderedContours(time)
            val selected = frame.single { it.key == selectedKey }
            assertEquals(lerp(first, target, AppMotionOpenEasing.transform(time)), selected.bounds)
            assertEquals(2, frame.size)
            assertTrue(frame[0].bounds.bottom < frame[1].bounds.top)
            assertEquals(second.width, frame[1].bounds.width, 0.001f)
            frame.forEach { contour ->
                assertEquals(1f, contour.modulationAlpha, 0f)
                // Compose sRGB Color stores alpha in eight bits, as does the native paint.
                val alpha = 1f
                assertEquals(Color.White.copy(alpha = alpha).alpha, contour.color.alpha, 0f)
                assertEquals(1f, contour.width, 0f)
            }
        }
        assertEquals(target, page.renderedContours(1f).single().bounds)
    }

    @Test fun backAndForwardRetargetFreezeTheCurrentOutlineIncludingCornersAndSeams() {
        for (time in listOf(0.2f, 0.4f, 0.6f, 0.65f, 0.84f, 0.9f)) {
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
                if (AppMotionOpenEasing.transform(time) >= 0.5f) {
                    // No active body is captured: only the live body settles, never its outline.
                    assertEquals(1f, page.incomingPresence(0f), 0f)
                    assertEquals(0, page.keptPictures)
                }
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
        assertTrue(selectedKey != page.renderedContours(1f).single().key)
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
        assertTrue(native[1].key != page.renderedContours(1f).single().key)
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

    @Test fun semanticDestinationWinsEvenWhenLaterMeasurementIsNearerToAnotherGroup() {
        val page = departing()
        val manual = Rect(16f, 110f, 344f, 190f)
        val connectors = Rect(16f, 430f, 344f, 600f)
        val manualToken = Any()
        val connectorsToken = Any()
        page.reportDestination(manualToken, surface(manual).copy(destination = "manual"))
        page.admitDestination()
        page.reportDestination(connectorsToken, surface(connectors).copy(destination = "child"))
        page.admitDestination()
        val sourceKey = page.renderedContours(0f).first().key
        val time = 0.1f
        assertEquals(
            lerp(first, connectors, AppMotionOpenEasing.transform(time)),
            page.renderedContours(time).single { it.key == sourceKey }.bounds,
        )
    }

    @Test fun forwardInterruptionResolvesNativeGroupToItsFrozenAnchor() {
        val page = SettingsPageMorph()
        page.begin("parent", true, false, host)
        val bottom = Rect(16f, 500f, 344f, 580f)
        reportGroup(page, first)
        reportGroup(page, bottom)
        page.pendingOrigin = bottom
        assertTrue(page.begin("child", true, true, host))
        val destination = Rect(16f, 100f, 344f, 180f)
        page.reportDestination(Any(), surface(destination).copy(destination = "child"))
        for (top in listOf(250f, 400f, 650f)) reportGroup(page, Rect(16f, top, 344f, top + 80f))
        assertTrue(page.admitDestination())
        val anchorKey = page.renderedContours(0f).single { it.bounds == bottom }.key
        val frozen = page.renderedContours(0.2f)
        val anchor = frozen.single { it.key == anchorKey }
        page.pendingOrigin = destination
        assertTrue(page.begin("grandchild", true, true, host, 0.2f))
        val target = Rect(16f, 140f, 344f, 300f)
        reportGroup(page, target)
        assertTrue(page.admitDestination())
        val fraction = 0.1f
        assertEquals(
            lerp(anchor.bounds, target, AppMotionOpenEasing.transform(fraction)),
            page.renderedContours(fraction).single { it.key == anchorKey }.bounds,
        )
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
