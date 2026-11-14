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
        return SettingsContour(bounds, RoundRect(bounds, 20f, 20f), viewport, Color.White, 1f, key = key)
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
                    val alpha = if (contour.key == target.single().key) 1f else 1f - (t / 0.4f).coerceIn(0f, 1f)
                    assertEquals(Color.White.copy(alpha = alpha).alpha, contour.color.alpha, 0f)
                    assertEquals(1f, contour.modulationAlpha, 0f)
                    assertEquals(1f, contour.width, 0f)
                }
            }
        }
    }

    @Test fun surplusPaintFadesInFirstFortyPercentIncludingSeamsAndReverses() {
        val source = listOf(dividedGroup(100f, 2, 1), group(400f, key = 2))
        val target = listOf(group(140f, key = 2))
        for (t in listOf(0.01f, 0.2f, 0.4f, 0.8f)) {
            val frame = interpolateSettingsContours(source, target, t, source[1].bounds.center, viewport, 2)
            val satellite = frame.single { it.key == 1 }
            val alpha = 1f - (t / 0.4f).coerceIn(0f, 1f)
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
            // Retargeting must start from the partially transparent frame, never opaque native paint.
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
            var top = random.nextFloat() * 100f
            val source =
                List(2 + random.nextInt(5)) { index ->
                    val contour = group(top, 20f + random.nextFloat() * 180f, index + 1)
                    top = contour.bounds.bottom + 8f + random.nextFloat() * 80f
                    contour
                }
            val selected = random.nextInt(source.size)
            val target = listOf(group(random.nextFloat() * 350f, 30f + random.nextFloat() * 500f, source[selected].key))
            for (step in 1 until 240) {
                val t = step / 240f
                val frame = sample(source, target, t)
                assertNotNull(orderedSettingsContours(frame))
                frame.zipWithNext().forEachIndexed { index, (a, b) ->
                    val nativeGap = source[index + 1].bounds.top - source[index].bounds.bottom
                    assertTrue(b.bounds.top - a.bounds.bottom >= nativeGap - 0.001f)
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
                    assertTrue(contour.corners.topLeftCornerRadius.x >= 20f * (1f - t) - 0.001f)
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

    @Test fun nativeEndpointsIncludingRowsCornersViewportAndStylesAreTheOriginalObjects() {
        val source = home()
        val target = listOf(dividedGroup(120f, 2, 3).copy(viewport = Rect(0f, 90f, 360f, 650f)))
        for ((from, to) in listOf(source to target, target to source, source to source, source to emptyList())) {
            assertSame(from, sample(from, to, 0f))
            assertSame(to, sample(from, to, 1f))
        }
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
                sample(frozen, target, 0.00001f).zip(frozen).forEach { (actual, prior) ->
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
