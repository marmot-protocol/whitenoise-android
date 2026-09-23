package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry that decides which pixels of a chosen picture become an avatar or group image.
 *
 * The property that matters is squareness in *pixels* rather than in normalized units: a crop
 * stored as fractions of each edge is only square when those fractions are scaled by the source's
 * own dimensions, so a landscape photo cropped naively yields a stretched avatar. These pin that,
 * the bounds a pan may not cross, and the fact that a crop is taken before any rotation.
 */
class IdentityImageCropTest {
    /** A centred crop of a landscape source is the tallest square it can give. */
    @Test
    fun aCenteredCropOfALandscapeSourceIsTheFullHeightSquare() {
        val rect = IdentityImageCrop.Centered.rectFor(EditorPixelSize(1000, 500))

        assertEquals(0f, rect.top, TOLERANCE)
        assertEquals(1f, rect.bottom, TOLERANCE)
        assertEquals(0.25f, rect.left, TOLERANCE)
        assertEquals(0.75f, rect.right, TOLERANCE)
    }

    /** A centred crop of a portrait source is the full-width square. */
    @Test
    fun aCenteredCropOfAPortraitSourceIsTheFullWidthSquare() {
        val rect = IdentityImageCrop.Centered.rectFor(EditorPixelSize(400, 800))

        assertEquals(0f, rect.left, TOLERANCE)
        assertEquals(1f, rect.right, TOLERANCE)
        assertEquals(0.25f, rect.top, TOLERANCE)
        assertEquals(0.75f, rect.bottom, TOLERANCE)
    }

    /** The selected region is square in pixels, whatever the source's aspect ratio. */
    @Test
    fun theSelectedRegionIsSquareInPixelsForEveryAspectRatio() {
        listOf(
            EditorPixelSize(1000, 500),
            EditorPixelSize(500, 1000),
            EditorPixelSize(640, 640),
            EditorPixelSize(1920, 1080),
        ).forEach { size ->
            listOf(1f, 1.5f, 4f).forEach { zoom ->
                val rect = IdentityImageCrop(zoom = zoom).rectFor(size)
                val widthPx = rect.width * size.width
                val heightPx = rect.height * size.height

                assertEquals("$size at zoom $zoom must select a square", widthPx, heightPx, PIXEL_TOLERANCE)
            }
        }
    }

    /** Zooming in shrinks the selected square by exactly the zoom factor. */
    @Test
    fun zoomingInShrinksTheSquareByTheZoomFactor() {
        val size = EditorPixelSize(800, 600)

        val full = IdentityImageCrop.Centered.rectFor(size)
        val doubled = IdentityImageCrop(zoom = 2f).rectFor(size)

        assertEquals(full.height * size.height / 2f, doubled.height * size.height, PIXEL_TOLERANCE)
    }

    /** A pan toward an edge stops flush against it rather than sliding off the picture. */
    @Test
    fun aPanPastAnEdgeStopsFlushAgainstIt() {
        val size = EditorPixelSize(1000, 500)

        val farLeft = IdentityImageCrop(focusX = -5f, focusY = 0.5f).rectFor(size)
        val farRight = IdentityImageCrop(focusX = 5f, focusY = 0.5f).rectFor(size)

        assertEquals(0f, farLeft.left, TOLERANCE)
        assertEquals(0.5f, farLeft.right, TOLERANCE)
        assertEquals(0.5f, farRight.left, TOLERANCE)
        assertEquals(1f, farRight.right, TOLERANCE)
    }

    /** Zoom beyond the supported maximum is clamped rather than collapsing the crop. */
    @Test
    fun zoomBeyondTheMaximumIsClamped() {
        val size = EditorPixelSize(1000, 500)

        val atMax = IdentityImageCrop(zoom = IDENTITY_IMAGE_MAX_ZOOM).rectFor(size)
        val beyondMax = IdentityImageCrop(zoom = IDENTITY_IMAGE_MAX_ZOOM * 10f).rectFor(size)

        assertEquals(atMax.width, beyondMax.width, TOLERANCE)
        assertTrue("a clamped crop still has area", beyondMax.width > 0f && beyondMax.height > 0f)
    }

    /** Rotation leaves the crop alone, because a recipe crops before it turns. */
    @Test
    fun rotationDoesNotMoveTheCropBecauseCroppingHappensFirst() {
        val size = EditorPixelSize(1000, 500)
        val upright = IdentityImageCrop(focusX = 0.4f, zoom = 2f)

        val turned = upright.copy(quarterTurnsClockwise = 1).rectFor(size)

        assertEquals(upright.rectFor(size), turned)
    }

    /** The recipe carries both the derived square and the requested rotation. */
    @Test
    fun theRecipeCarriesTheDerivedSquareAndTheRotation() {
        val size = EditorPixelSize(1000, 500)
        val crop = IdentityImageCrop(focusX = 0.3f, zoom = 2f, quarterTurnsClockwise = 3)

        val recipe = crop.recipeFor(size)

        assertEquals(crop.rectFor(size), recipe.crop)
        assertEquals(3, recipe.quarterTurnsClockwise)
        assertTrue("an identity crop never draws on the picture", recipe.strokes.isEmpty())
    }

    /** A zoom below one, or a non-canonical rotation, is rejected rather than silently repaired. */
    @Test
    fun anUnusableCropIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { IdentityImageCrop(zoom = 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { IdentityImageCrop(quarterTurnsClockwise = 4) }
        assertThrows(IllegalArgumentException::class.java) { IdentityImageCrop(focusX = Float.NaN) }
    }

    /** Dragging the picture one whole mask width moves the crop by exactly one crop width. */
    @Test
    fun draggingOneMaskWidthMovesTheCropByOneCropWidth() {
        val size = EditorPixelSize(1000, 1000)
        val start = IdentityImageCrop(zoom = 4f)
        val startRect = start.rectFor(size)

        val panned = start.pannedBy(dragXPx = -VIEWPORT_PX, dragYPx = 0f, viewportPx = VIEWPORT_PX, oriented = size)

        assertEquals(startRect.right, panned.rectFor(size).left, TOLERANCE)
    }

    /** Dragging the picture right reveals what lay to its left. */
    @Test
    fun draggingRightRevealsWhatLayToTheLeft() {
        val size = EditorPixelSize(1000, 1000)
        val start = IdentityImageCrop(zoom = 2f)

        val panned = start.pannedBy(dragXPx = 40f, dragYPx = 0f, viewportPx = VIEWPORT_PX, oriented = size)

        assertTrue("dragging right must move the crop left", panned.focusX < start.focusX)
    }

    /** Panning can never push the crop off the picture, however far the drag goes. */
    @Test
    fun panningCannotPushTheCropOffThePicture() {
        val size = EditorPixelSize(1200, 400)
        var crop = IdentityImageCrop(zoom = 3f)

        repeat(50) {
            crop = crop.pannedBy(dragXPx = 5_000f, dragYPx = 5_000f, viewportPx = VIEWPORT_PX, oriented = size)
        }
        val rect = crop.rectFor(size)

        assertTrue("left edge stays on the picture", rect.left >= -TOLERANCE)
        assertTrue("top edge stays on the picture", rect.top >= -TOLERANCE)
        assertTrue("right edge stays on the picture", rect.right <= 1f + TOLERANCE)
        assertTrue("bottom edge stays on the picture", rect.bottom <= 1f + TOLERANCE)
    }

    /** A pinch is held between no zoom at all and the supported maximum. */
    @Test
    fun pinchingIsHeldWithinTheSupportedRange() {
        val zoomedOut = IdentityImageCrop(zoom = 2f).zoomedBy(0.01f)
        val zoomedIn = IdentityImageCrop(zoom = 2f).zoomedBy(1_000f)

        assertEquals(1f, zoomedOut.zoom, TOLERANCE)
        assertEquals(IDENTITY_IMAGE_MAX_ZOOM, zoomedIn.zoom, TOLERANCE)
    }

    /** A nonsensical gesture leaves the crop untouched rather than corrupting it. */
    @Test
    fun aNonsensicalGestureLeavesTheCropUntouched() {
        val size = EditorPixelSize(800, 600)
        val crop = IdentityImageCrop(focusX = 0.4f, zoom = 2f)

        assertEquals(crop, crop.pannedBy(Float.NaN, 0f, VIEWPORT_PX, size))
        assertEquals(crop, crop.pannedBy(10f, 10f, viewportPx = 0f, oriented = size))
        assertEquals(crop, crop.zoomedBy(0f))
        assertEquals(crop, crop.zoomedBy(Float.NaN))
    }

    /** Four quarter turns return to where they started. */
    @Test
    fun fourQuarterTurnsReturnToTheStart() {
        val crop = IdentityImageCrop(focusX = 0.3f, zoom = 2f)

        val turned =
            crop
                .rotatedClockwise()
                .rotatedClockwise()
                .rotatedClockwise()
                .rotatedClockwise()

        assertEquals(crop, turned)
    }

    /** After a quarter turn, a sideways drag still moves the picture sideways on screen. */
    @Test
    fun aSidewaysDragFollowsTheFingerAfterAQuarterTurn() {
        val size = EditorPixelSize(1000, 1000)
        val turned = IdentityImageCrop(zoom = 2f, quarterTurnsClockwise = 1)

        val panned = turned.pannedBy(dragXPx = 100f, dragYPx = 0f, viewportPx = VIEWPORT_PX, oriented = size)

        // The picture is drawn turned, so the screen's horizontal axis is the source's vertical one.
        assertEquals("the untouched axis must not drift", turned.focusX, panned.focusX, TOLERANCE)
        assertTrue("a sideways drag must move the crop along the turned axis", panned.focusY > turned.focusY)
    }

    /** Every quarter turn keeps a sideways drag moving the crop, never leaving it inert. */
    @Test
    fun everyQuarterTurnKeepsASidewaysDragEffective() {
        val size = EditorPixelSize(1000, 1000)

        (0 until 4).forEach { turns ->
            val start = IdentityImageCrop(zoom = 2f, quarterTurnsClockwise = turns)
            val panned = start.pannedBy(60f, 0f, VIEWPORT_PX, size)

            assertTrue(
                "a sideways drag must move something at $turns quarter turns",
                panned.focusX != start.focusX || panned.focusY != start.focusY,
            )
        }
    }

    /** Zooming out after an edge pan leaves no dead zone before the picture moves again. */
    @Test
    fun zoomingOutAfterAnEdgePanLeavesNoDeadZone() {
        val size = EditorPixelSize(1000, 1000)
        // Pan hard enough to sit at the zoom-4 bound (0.875), which the zoom-2 square cannot show.
        val atEdge = IdentityImageCrop(zoom = 4f).pannedBy(-VIEWPORT_PX * 3f, 0f, VIEWPORT_PX, size)
        val zoomedOut = atEdge.zoomedBy(0.5f)
        val shownBefore = zoomedOut.rectFor(size)

        val nudged = zoomedOut.pannedBy(dragXPx = 10f, dragYPx = 0f, viewportPx = VIEWPORT_PX, oriented = size)

        assertTrue(
            "a drag back toward the centre must move the crop at once, not after closing a hidden gap",
            nudged.rectFor(size).left < shownBefore.left,
        )
    }

    private companion object {
        const val TOLERANCE = 0.0001f
        const val PIXEL_TOLERANCE = 0.5f
        const val VIEWPORT_PX = 400f
    }
}
