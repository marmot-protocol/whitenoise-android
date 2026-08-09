package dev.ipf.whitenoise.android.media.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhotoEditTransformTest {
    @Test
    fun allExifOrientationsMapEncodedTopLeftCorrectly() {
        val expected =
            mapOf(
                1 to NormalizedPoint(0f, 0f),
                2 to NormalizedPoint(1f, 0f),
                3 to NormalizedPoint(1f, 1f),
                4 to NormalizedPoint(0f, 1f),
                5 to NormalizedPoint(0f, 0f),
                6 to NormalizedPoint(1f, 0f),
                7 to NormalizedPoint(1f, 1f),
                8 to NormalizedPoint(0f, 1f),
            )

        expected.forEach { (orientation, point) ->
            assertPointEquals(point, geometry(exif = orientation).encodedToOriented(NormalizedPoint(0f, 0f)))
        }
    }

    @Test
    fun everyExifOrientationRoundTripsArbitraryPoint() {
        val source = NormalizedPoint(0.23f, 0.71f)

        (1..8).forEach { orientation ->
            val geometry = geometry(exif = orientation)
            assertPointEquals(source, geometry.orientedToEncoded(geometry.encodedToOriented(source)))
        }
    }

    @Test
    fun rotatedExifSwapsOrientedDimensions() {
        val geometry = geometry(exif = PhotoEditGeometry.EXIF_ROTATE_90)

        assertEquals(EditorPixelSize(3000, 4000), geometry.orientedSize)
    }

    @Test
    fun cropThenQuarterTurnMapsCorners() {
        val geometry =
            geometry(
                recipe =
                    PhotoEditRecipe(
                        crop = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f),
                        quarterTurnsClockwise = 1,
                    ),
            )

        assertPointEquals(
            EditorPoint(geometry.outputSize.width.toFloat(), 0f),
            geometry.orientedToOutput(NormalizedPoint(0.25f, 0.25f)),
        )
        assertPointEquals(
            EditorPoint(0f, geometry.outputSize.height.toFloat()),
            geometry.orientedToOutput(NormalizedPoint(0.75f, 0.75f)),
        )
    }

    @Test
    fun outputAndOrientedCoordinatesRoundTripAcrossQuarterTurns() {
        val point = NormalizedPoint(0.43f, 0.61f)

        repeat(4) { turns ->
            val geometry =
                geometry(
                    recipe =
                        PhotoEditRecipe(
                            crop = NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f),
                            quarterTurnsClockwise = turns,
                        ),
                )
            assertPointEquals(point, geometry.outputToOriented(geometry.orientedToOutput(point)))
        }
    }

    @Test
    fun standardAndHighUseIdenticalNormalizedGeometry() {
        val recipe =
            PhotoEditRecipe(
                crop = NormalizedRect(0.1f, 0.2f, 0.8f, 0.9f),
                quarterTurnsClockwise = 3,
            )
        val standard = geometry(recipe = recipe, maxEdge = 2048, maxPixels = 12_000_000)
        val high = geometry(recipe = recipe, maxEdge = 4096, maxPixels = 12_000_000)
        val sourcePoint = NormalizedPoint(0.4f, 0.5f)
        val standardPoint = standard.orientedToOutput(sourcePoint)
        val highPoint = high.orientedToOutput(sourcePoint)

        assertEquals(
            standardPoint.x / standard.outputSize.width,
            highPoint.x / high.outputSize.width,
            EPSILON,
        )
        assertEquals(
            standardPoint.y / standard.outputSize.height,
            highPoint.y / high.outputSize.height,
            EPSILON,
        )
    }

    @Test
    fun outputPlanNeverUpscalesAndHonorsPixelLimit() {
        val small =
            PhotoEditGeometry.create(
                encodedSize = EditorPixelSize(800, 600),
                exifOrientation = 1,
                recipe = PhotoEditRecipe.Original,
                maxEdgePx = 4096,
                maxPixels = 12_000_000,
            )
        val pixelLimited =
            PhotoEditGeometry.create(
                encodedSize = EditorPixelSize(8000, 6000),
                exifOrientation = 1,
                recipe = PhotoEditRecipe.Original,
                maxEdgePx = 4096,
                maxPixels = 8_000_000,
            )

        assertEquals(EditorPixelSize(800, 600), small.outputSize)
        assertTrue(pixelLimited.outputSize.pixels <= 8_000_000)
        assertTrue(maxOf(pixelLimited.outputSize.width, pixelLimited.outputSize.height) <= 4096)
    }

    @Test
    fun viewGestureMappingUsesInverseFitPanAndZoom() {
        val geometry = geometry(maxEdge = 1000)
        val viewport =
            EditorViewTransform.fit(
                outputSize = geometry.outputSize,
                viewWidth = 1200f,
                viewHeight = 900f,
                zoom = 2f,
                panX = 17f,
                panY = -23f,
            )
        val oriented = NormalizedPoint(0.35f, 0.65f)
        val view = viewport.outputToView(geometry.orientedToOutput(oriented))

        assertPointEquals(oriented, geometry.viewToOriented(view, viewport))
    }

    private fun geometry(
        exif: Int = 1,
        recipe: PhotoEditRecipe = PhotoEditRecipe.Original,
        maxEdge: Int = 2048,
        maxPixels: Long = 12_000_000,
    ): PhotoEditGeometry =
        PhotoEditGeometry.create(
            encodedSize = EditorPixelSize(4000, 3000),
            exifOrientation = exif,
            recipe = recipe,
            maxEdgePx = maxEdge,
            maxPixels = maxPixels,
        )

    private fun assertPointEquals(
        expected: NormalizedPoint,
        actual: NormalizedPoint,
    ) {
        assertTrue(abs(expected.x - actual.x) < EPSILON)
        assertTrue(abs(expected.y - actual.y) < EPSILON)
    }

    private fun assertPointEquals(
        expected: EditorPoint,
        actual: EditorPoint,
    ) {
        assertTrue(abs(expected.x - actual.x) < EPSILON)
        assertTrue(abs(expected.y - actual.y) < EPSILON)
    }

    private companion object {
        const val EPSILON = 0.0001f
    }
}
