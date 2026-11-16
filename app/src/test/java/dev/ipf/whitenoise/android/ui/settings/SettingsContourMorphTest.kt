package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SettingsContourMorphTest {
    private val viewport = Rect(0f, 80f, 360f, 700f)

    private fun group(
        top: Float,
        height: Float = 80f,
        key: Int = 1,
    ): SettingsContour {
        val bounds = Rect(16f, top, 344f, top + height)
        return SettingsContour(
            bounds,
            RoundRect(bounds, minOf(20f, height / 2f), minOf(20f, height / 2f)),
            viewport,
            Color.White,
            1f,
            key = key,
        )
    }

    private fun home(): List<SettingsContour> = List(4) { group(100f + it * 110f, key = it + 1) }

    private fun dividedGroup(
        top: Float,
        count: Int,
        key: Int = 1,
    ): SettingsContour =
        group(top, 240f, key).let { contour ->
            contour.copy(
                seams =
                    List(count) { index ->
                        val y = top + (index + 1) * 240f / (count + 1)
                        SettingsContourSeam(Offset(16.5f, y), Offset(343.5f, y), Color.White)
                    },
            )
        }

    private fun sample(
        source: List<SettingsContour>,
        target: List<SettingsContour>,
        t: Float,
    ): List<SettingsContour> = interpolateSettingsContours(source, target, t, Offset(180f, 160f), viewport)

    private fun assertBounds(
        expected: Rect,
        actual: Rect,
        tolerance: Float = 0.001f,
    ) {
        assertEquals(expected.left, actual.left, tolerance)
        assertEquals(expected.top, actual.top, tolerance)
        assertEquals(expected.right, actual.right, tolerance)
        assertEquals(expected.bottom, actual.bottom, tolerance)
    }

    @Test fun oneToManyRemeasureKeepsSemanticAnchorWhenNeighbourIsNearer() {
        val source = listOf(group(100f, key = 1))
        val target = listOf(group(100f, key = 20), group(500f, key = 10).copy(destination = "child"))
        val point = source.single().bounds.center
        val pairs = matchSettingsContours(source, target, 1, "child", point, viewport)
        val anchor = pairs.single { it.target.key == 10 }
        val frozen = interpolateSettingsContours(source, target, 0.7f, point, viewport, 1, correspondence = pairs)
        val frozenAnchor = frozen.single { it.key == anchor.key }
        val remeasured = target.map { it.copy(bounds = it.bounds.translate(Offset(0f, 30f))) }
        val rebased = matchSettingsContours(frozen, remeasured, 1, "child", point, viewport)
        assertEquals(frozenAnchor.bounds, rebased.single { it.target.key == 10 }.source.bounds)
        assertEquals(1, rebased.single { it.target.key == 10 }.key)
    }

    @Test fun firstAndThirdGroupsEachMoveDirectlyToTheirKeyedDestinationThroughoutTheClock() {
        val source = home()
        for (selected in listOf(0, 2)) {
            val target = listOf(group(120f, 240f, source[selected].key))
            for (step in 1 until 240) {
                val t = step / 240f
                val frame = sample(source, target, t)
                assertEquals(source.map { it.key }, frame.map { it.key })
                assertBounds(
                    lerp(source[selected].bounds, target.single().bounds, t),
                    frame
                        .single {
                            it.key ==
                                target.single().key
                        }.bounds,
                )
                assertNotNull(orderedSettingsContours(frame))
                frame.forEach { contour ->
                    // Match native sRGB paint quantization without widening the assertion tolerance.
                    val alpha = 1f
                    assertEquals(Color.White.copy(alpha = alpha).alpha, contour.color.alpha, 0f)
                    assertEquals(1f, contour.modulationAlpha, 0f)
                    assertEquals(1f, contour.width, 0f)
                }
            }
        }
    }

    @Test fun surplusKeepsNativePaintIncludingSeamsAndReverses() {
        val source = listOf(dividedGroup(100f, 2, 1), group(400f, key = 2))
        val target = listOf(group(140f, key = 2))
        for (t in listOf(0.01f, 0.2f, 0.4f, 0.8f)) {
            val frame = interpolateSettingsContours(source, target, t, source[1].bounds.center, viewport, 2)
            val satellite = frame.single { it.key == 1 }
            val alpha = 1f
            assertEquals(source[0].color.copy(alpha = alpha).alpha, satellite.color.alpha, 0f)
            satellite.seams.forEachIndexed { index, seam ->
                assertEquals(
                    source[0]
                        .seams[index]
                        .color
                        .copy(alpha = alpha)
                        .alpha,
                    seam.color.alpha,
                    0f,
                )
            }
            assertEquals(1f, frame.single { it.key == 2 }.color.alpha, 0f)
            val reversed = interpolateSettingsContours(target, source, 1f - t, source[1].bounds.center, viewport, 2)
            assertEquals(satellite.color.alpha, reversed.single { it.key == 1 }.color.alpha, 0.001f)
            // Retargeting preserves native paint and starts at exactly the frozen frame.
            assertSame(frame, interpolateSettingsContours(frame, source, 0f, source[1].bounds.center, viewport, 2))
            val resumed = interpolateSettingsContours(frame, source, 0.00001f, source[1].bounds.center, viewport, 2)
            assertEquals(satellite.color.alpha, resumed.single { it.key == 1 }.color.alpha, 0.001f)
        }
    }

    @Test fun trueEmptyPaintFadesAndInvertsIncludingFrozenPartialSeams() {
        val native =
            dividedGroup(100f, 2).let { contour ->
                contour.copy(
                    color = contour.color.copy(alpha = 0.6f),
                    seams = contour.seams.map { it.copy(color = it.color.copy(alpha = 0.4f)) },
                )
            }
        val source = listOf(native)
        val empty = emptyList<SettingsContour>()
        val point = Offset(180f, 160f)
        assertSame(source, sample(source, empty, 0f))
        assertSame(empty, sample(source, empty, 1f))
        for (time in listOf(0.01f, 0.2f, 0.4f, 0.8f)) {
            // Geometry and paint have separate clocks; fade must follow linear time.
            val frame = interpolateSettingsContours(source, empty, 0.7f, point, viewport, paintFraction = time)
            val out = frame.single()
            val presence = 1f - (time / 0.4f).coerceIn(0f, 1f)
            assertEquals(native.color.copy(alpha = native.color.alpha * presence).alpha, out.color.alpha, 0f)
            out.seams.zip(native.seams).forEach { (actual, prior) ->
                assertEquals(prior.color.copy(alpha = prior.color.alpha * presence).alpha, actual.color.alpha, 0f)
            }
            val reverse =
                interpolateSettingsContours(
                    empty,
                    source,
                    0.3f,
                    point,
                    viewport,
                    paintFraction = 1f - time,
                ).single()
            assertBounds(out.bounds, reverse.bounds)
            assertEquals(out.color.alpha, reverse.color.alpha, 0.001f)
            out.seams.zip(reverse.seams).forEach { (a, b) ->
                assertEquals(a.start.x, b.start.x, 0.001f)
                assertEquals(a.start.y, b.start.y, 0.001f)
                assertEquals(a.end.x, b.end.x, 0.001f)
                assertEquals(a.end.y, b.end.y, 0.001f)
                assertEquals(a.color.alpha, b.color.alpha, 0.001f)
            }
            // A frozen partly faded frame remains partly faded when it starts a new empty leg.
            assertSame(frame, interpolateSettingsContours(frame, empty, 0f, point, viewport))
            val resumed = interpolateSettingsContours(frame, empty, 0.2f, point, viewport).single()
            assertEquals(out.color.copy(alpha = out.color.alpha * 0.5f).alpha, resumed.color.alpha, 0f)
            resumed.seams.zip(out.seams).forEach { (actual, prior) ->
                assertEquals(prior.color.copy(alpha = prior.color.alpha * 0.5f).alpha, actual.color.alpha, 0f)
            }
            val returning = interpolateSettingsContours(frame, source, 0.00001f, point, viewport).single()
            assertEquals(out.color.alpha, returning.color.alpha, 0.001f)
            returning.seams.zip(out.seams).forEach { (actual, prior) ->
                assertEquals(prior.color.alpha, actual.color.alpha, 0.001f)
            }
        }
    }

    @Test fun satellitesSeparateWithoutDockingOrFunnellingAcrossOrderedRandomLayouts() {
        val random = Random(3793)
        repeat(24) {
            var top = 100f + random.nextFloat() * 10f
            val source =
                List(2 + random.nextInt(5)) { index ->
                    val contour = group(top, 20f + random.nextFloat() * 45f, index + 1)
                    top = contour.bounds.bottom + 8f + random.nextFloat() * 8f
                    contour
                }
            val selected = random.nextInt(source.size)
            val target =
                listOf(group(100f + random.nextFloat() * 250f, 40f + random.nextFloat() * 250f, source[selected].key))
            for (step in 1 until 240) {
                val t = step / 240f
                val frame = sample(source, target, t)
                assertNotNull(orderedSettingsContours(frame))
                frame.zipWithNext().forEachIndexed { index, (a, b) ->
                    assertTrue(b.bounds.top - a.bounds.bottom >= -0.001f)
                    if (index + 1 < selected || index > selected) {
                        assertEquals(
                            source[index + 1].bounds.center.y - source[index].bounds.center.y,
                            b.bounds.center.y - a.bounds.center.y,
                            0.001f,
                        )
                    }
                }
                frame.forEach { contour ->
                    assertEquals(1f, contour.width, 0f)
                    assertTrue(contour.corners.topLeftCornerRadius.x <= contour.bounds.height / 2f)
                }
            }
            val nearEnd = sample(source, target, 0.99999f)
            assertEquals(source.size, nearEnd.map { it.bounds.center }.distinct().size)
        }
    }

    @Test fun forwardAndReverseHaveTheSameGeometryAndNativeStyle() {
        val source = home()
        val target = listOf(dividedGroup(120f, 2, key = 3))
        for (step in 1 until 240) {
            val t = step / 240f
            val forward = sample(source, target, t)
            val reverse = sample(target, source, 1f - t)
            forward.zip(reverse).forEach { (a, b) ->
                assertEquals(a.key, b.key)
                assertBounds(a.bounds, b.bounds)
                assertEquals(a.corners.topLeftCornerRadius.x, b.corners.topLeftCornerRadius.x, 0.001f)
                assertEquals(a.width, b.width, 0f)
                assertEquals(a.color, b.color)
                a.seams.zip(b.seams).forEach { (sa, sb) ->
                    assertEquals(sa.start.x, sb.start.x, 0.001f)
                    assertEquals(sa.start.y, sb.start.y, 0.001f)
                    assertEquals(sa.end.x, sb.end.x, 0.001f)
                    assertEquals(sa.end.y, sb.end.y, 0.001f)
                }
            }
        }
    }

    @Test fun declaredDestinationBeatsNearestAndPairingIsNoncrossingAndOneToOne() {
        val source = List(5) { group(100f + it * 100f, 60f, it + 1) }
        val target = listOf(group(110f, 60f, 10), group(500f, 60f, 11).copy(destination = "agents"))
        val pairs = matchSettingsContours(source, target, 3, "agents", source[2].bounds.center, viewport)
        val matched = pairs.filter { it.source in source && it.target in target }
        assertEquals(target[1], matched.single { it.source.key == 3 }.target)
        assertEquals(matched.size, matched.map { it.target.key }.distinct().size)
        assertEquals(matched.map { it.target.bounds.top }.sorted(), matched.map { it.target.bounds.top })
        assertTrue(matched.none { it.source.key == 2 })
    }

    @Test fun interiorSurplusContractsAtFullWidthIntoExclusiveGapWithoutFadingOrCrossing() {
        val source = listOf(group(100f, 60f, 1), group(210f, 60f, 2), group(320f, 60f, 3))
        val target = listOf(group(100f, 60f, 1), group(320f, 60f, 3))
        val pairs = matchSettingsContours(source, target, 1, null, source[0].bounds.center, viewport)
        val surplus = pairs.single { it.source.key == 2 }
        assertEquals(0f, surplus.target.bounds.height, 0f)
        assertTrue(surplus.target.bounds.top > target[0].bounds.bottom)
        assertTrue(surplus.target.bounds.bottom < target[1].bounds.top)
        for (step in 1 until 100) {
            val t = step / 100f
            val frame =
                interpolateSettingsContours(
                    source,
                    target,
                    t,
                    source[0].bounds.center,
                    viewport,
                    1,
                    correspondence = pairs,
                )
            val middle = frame.single { it.key == 2 }
            assertEquals(source[1].bounds.width, middle.bounds.width, 0.001f)
            assertEquals(1f, middle.color.alpha, 0f)
            assertTrue(middle.bounds.height > 0f)
            assertTrue(middle.corners.topLeftCornerRadius.y <= middle.bounds.height / 2f)
            assertNotNull(orderedSettingsContours(frame))
            val reversed =
                interpolateSettingsContours(
                    target,
                    source,
                    1f - t,
                    source[0].bounds.center,
                    viewport,
                    1,
                    correspondence = pairs.map { SettingsContourPair(it.target, it.source, it.key) },
                )
            frame.zip(reversed).forEach { (a, b) -> assertBounds(a.bounds, b.bounds) }
        }
    }

    @Test fun offscreenContoursCannotStealNearestVisiblePartnerAndFullScrolledBoundsSurvive() {
        val source = listOf(group(-100f, 50f, 1), group(40f, 100f, 2), group(250f, 70f, 3))
        val target = listOf(group(110f, 100f, 4))
        val pairs = matchSettingsContours(source, target, 2, null, source[1].bounds.center, viewport)
        assertTrue(pairs.none { it.source.key == 1 })
        assertEquals(
            40f,
            pairs
                .single { it.source.key == 2 }
                .source.bounds.top,
            0f,
        )
    }

    @Test fun onlyOffscreenTargetGroupsDoNotTurnVisibleSourceIntoAnEmptySeedOrPop() {
        val source = listOf(group(100f, 80f, 1), group(250f, 80f, 2))
        val target = listOf(group(800f, 80f, 3))
        val pairs = matchSettingsContours(source, target, 1, null, source[0].bounds.center, viewport)
        assertEquals(source.size, pairs.size)
        pairs.forEach { pair ->
            assertEquals(pair.source.bounds.size, pair.target.bounds.size)
            assertTrue(pair.target.bounds.top > viewport.bottom)
        }
        val frame =
            interpolateSettingsContours(
                source,
                target,
                0.1f,
                source[0].bounds.center,
                viewport,
                1,
                correspondence = pairs,
            )
        assertEquals(2, frame.size)
        frame.forEach { assertEquals(1f, it.color.alpha, 0f) }
    }

    @Test fun unmatchedSeamRetractsToLogicalStartInRtl() {
        val source = dividedGroup(100f, 1).copy(rtl = true)
        val target = source.copy(seams = emptyList())
        val frame = sample(listOf(source), listOf(target), 0.999f).single()
        assertTrue(
            frame.seams
                .single()
                .start.x > 340f,
        )
    }

    @Test fun nativeEndpointsIncludingRowsCornersViewportAndStylesAreTheOriginalObjects() {
        val source = home()
        val target = listOf(dividedGroup(120f, 2, 3).copy(viewport = Rect(0f, 90f, 360f, 650f)))
        for ((from, to) in listOf(source to target, target to source, source to source, source to emptyList())) {
            assertSame(from, sample(from, to, 0f))
            assertSame(to, sample(from, to, 1f))
        }
    }

    @Test fun returningFrozenGroupsKeepTheirAvailableNativeKeysBeforeChoosingNearestSurplus() {
        val source = home()
        val frozen = sample(source, listOf(dividedGroup(90f, 2, 3)), 0.75f)
        val visible = frozen.filter { !it.bounds.intersect(viewport).isEmpty }
        val pairs = matchSettingsContours(frozen, source, 3, null, frozen.single { it.key == 3 }.bounds.center, viewport)
        visible.forEach { contour ->
            val pair = pairs.single { it.source == contour }
            assertEquals(contour.key, pair.target.key)
        }
        val newlyArriving = pairs.filter { it.target.key == 1 }
        assertEquals(1, newlyArriving.size)
        assertTrue(
            newlyArriving
                .single()
                .source.bounds.bottom <= viewport.top,
        )
    }

    @Test fun frozenFramesRemainOrderedOnReturnAndOnRetargetToAnotherGroup() {
        val source = home()
        val account = listOf(dividedGroup(90f, 2, 3))
        for (freezeTime in listOf(0.01f, 0.37f, 0.75f, 0.9999f)) {
            val frozen = sample(source, account, freezeTime)
            for (target in listOf(source, listOf(group(540f, 120f, key = 2)))) {
                assertSame(frozen, sample(frozen, target, 0f))
                assertSame(target, sample(frozen, target, 1f))
                for (step in 1 until 240) assertNotNull(orderedSettingsContours(sample(frozen, target, step / 240f)))
                val visibleFrozen = frozen.filter { !it.bounds.intersect(viewport).isEmpty }
                val visibleNext = sample(frozen, target, 0.00001f).filter { !it.bounds.intersect(viewport).isEmpty }
                assertEquals(visibleFrozen.size, visibleNext.size)
                visibleNext.zip(visibleFrozen).forEach { (actual, prior) ->
                    assertBounds(prior.bounds, actual.bounds, 0.02f)
                    assertEquals(prior.width, actual.width, 0f)
                }
            }
        }
    }

    @Test fun equalCountAndOrdinaryManyGroupRoutesKeepOrderedOneToOneCorrespondence() {
        val source = home()
        val equal = source.map { it.copy(bounds = it.bounds.translate(Offset(0f, 45f))) }
        val several = listOf(group(100f, key = 3), group(220f, key = 4), group(340f, key = 5))
        for (target in listOf(equal, several)) {
            for (step in 1 until 240) {
                val t = step / 240f
                val forward = interpolateSettingsContours(source, target, t, source[2].bounds.center, viewport, 3)
                val reverse = interpolateSettingsContours(target, source, 1f - t, source[2].bounds.center, viewport, 3)
                assertNotNull(orderedSettingsContours(forward))
                assertEquals(forward.size, reverse.size)
                forward.zip(reverse).forEach { (a, b) -> assertBounds(a.bounds, b.bounds) }
            }
        }
    }

    @Test fun missingSeamsRetractToSeparatePerimeterPointsWhileTheGroupMoves() {
        val source = dividedGroup(100f, 3)
        val target = group(150f, 200f)
        for (step in 1 until 240) {
            val t = step / 240f
            val frame = sample(listOf(source), listOf(target), t).single()
            assertBounds(lerp(source.bounds, target.bounds, t), frame.bounds)
            assertEquals(3, frame.seams.size)
            frame.seams.zip(source.seams).forEach { (actual, native) ->
                assertEquals((native.end.x - native.start.x) * (1f - t), actual.end.x - actual.start.x, 0.001f)
                assertEquals(native.color, actual.color)
                assertEquals(native.width, actual.width, 0f)
            }
            assertTrue(frame.seams.zipWithNext().all { (a, b) -> a.start.y < b.start.y })
        }
    }

    @Test fun matchedSeamsKeepTheirPartialFrozenExtent() {
        val native = dividedGroup(100f, 1)
        val partial = native.copy(seams = native.seams.map { it.copy(end = Offset(140f, it.end.y)) })
        val frame = sample(listOf(partial), listOf(native), 0.01f).single()
        assertTrue(
            frame.seams
                .single()
                .end.x < 145f,
        )
    }

    @Test fun borderlessFallbackRetainsItsExistingFullSpanSeamGeometry() {
        val native =
            group(100f).copy(
                seams = listOf(SettingsContourSeam(Offset(16.5f, 140f), Offset(343.5f, 140f), Color.White)),
            )
        val frame = interpolateSettingsContours(listOf(native), emptyList(), 0.5f, Offset(40f, 50f), viewport).single()
        assertEquals(Rect(28f, 75f, 192f, 115f), frame.bounds)
        assertEquals(Offset(28.5f, 95.25f), frame.seams.single().start)
        assertEquals(Offset(191.5f, 95.25f), frame.seams.single().end)
        assertEquals(Color.White.copy(alpha = 0f), frame.color)
        assertEquals(Color.White.copy(alpha = 0f), frame.seams.single().color)
        assertTrue(sample(listOf(native), emptyList(), 1f).isEmpty())
        assertTrue(sample(emptyList(), listOf(native), 0f).isEmpty())
        val growing = sample(emptyList(), listOf(native), 0.001f).single()
        assertFalse(growing.bounds.isEmpty)
        assertTrue(growing.bounds.width < 1f)
    }

    @Test fun overlappingOrNonfiniteNativeLayoutsCannotEnterTheVerticalSolver() {
        assertNull(orderedSettingsContours(listOf(group(100f), group(140f))))
        assertNull(orderedSettingsContours(listOf(group(Float.NaN))))
    }
}
