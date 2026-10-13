package dev.ipf.whitenoise.android.media.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoRegionGeometryTest {
    @Test
    fun encodedRegionRetainsItsOriginForEveryExifOrientationAndQuarterTurn() {
        for (orientation in 1..8) {
            for (turn in 0..3) {
                val geometry =
                    PhotoEditGeometry.create(
                        EditorPixelSize(12000, 9000),
                        orientation,
                        PhotoEditRecipe(crop = NormalizedRect(0.07f, 0.13f, 0.41f, 0.62f), quarterTurnsClockwise = turn),
                        4096,
                        12_000_000L,
                    )
                val region = geometry.encodedCropRegion()
                assertTrue(region.validFor(12000, 9000))
                val affine = geometry.regionBitmapToOutputAffine(region.width, region.height, region)
                for (point in listOf(NormalizedPoint(0f, 0f), NormalizedPoint(0.25f, 0.75f), NormalizedPoint(1f, 1f))) {
                    val encoded =
                        NormalizedPoint(
                            (region.left + region.width * point.x) / 12000f,
                            (region.top + region.height * point.y) / 9000f,
                        )
                    val expected = geometry.orientedToOutput(geometry.encodedToOriented(encoded))
                    val actual = affine.map(EditorPoint(region.width * point.x, region.height * point.y))
                    assertEquals(expected.x, actual.x, 0.01f)
                    assertEquals(expected.y, actual.y, 0.01f)
                }
            }
        }
    }

    @Test
    fun edgeCropRoundsOutwardAndNeverRequestsOutsideTheSource() {
        val geometry =
            PhotoEditGeometry.create(
                EditorPixelSize(8000, 6000),
                1,
                PhotoEditRecipe(crop = NormalizedRect(0f, 0f, 0.1001f, 0.2001f)),
                4096,
                12_000_000L,
            )
        val region = geometry.encodedCropRegion()
        assertEquals(0, region.left)
        assertEquals(0, region.top)
        assertTrue(region.right >= 801 && region.bottom >= 1201)
        assertTrue(region.validFor(8000, 6000))
    }
}
