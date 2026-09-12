package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemShapes
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native row shape contracts shared by Settings groups and unsegmented chat rows. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhiteNoiseListItemDefaultsTest {
    @get:Rule val composeRule = createComposeRule()

    /** Selection, press, focus, hover and drag cannot morph resting row boundaries. */
    @Test fun allInteractionStatesKeepRestingShape() {
        var rows = emptyList<ListItemShapes>()
        composeRule.setContent {
            WhiteNoiseTheme {
                rows = listOf(
                    WhiteNoiseListItemDefaults.shapes(),
                    WhiteNoiseListItemDefaults.segmentedShapes(0, 1),
                ) +
                    (0..2).map { WhiteNoiseListItemDefaults.segmentedShapes(it, 3) }
            }
        }
        composeRule.runOnIdle { rows.forEach(::assertStableShape) }
    }

    /** Connected groups have no interior rounding and a singleton closes every outside corner. */
    @Test fun amoledGroupsKeepSquareSeamsAndRoundSingletonCorners() {
        var rows = emptyList<ListItemShapes>()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                rows = (0..2).map { WhiteNoiseListItemDefaults.segmentedShapes(it, 3) } +
                    WhiteNoiseListItemDefaults.segmentedShapes(0, 1)
            }
        }
        composeRule.runOnIdle {
            rows.forEach(::assertStableShape)
            val shapes = rows.map { it.shape as ConnectedRowShape }
            assertEquals(listOf(true, false, false, true), shapes.map { it.first })
            assertEquals(listOf(false, false, true, true), shapes.map { it.last })
            val outlines =
                shapes.map {
                    when (val outline = it.createOutline(Size(360f, 88f), LayoutDirection.Rtl, Density(1f))) {
                        is Outline.Rounded -> outline.roundRect
                        is Outline.Rectangle -> RoundRect(outline.rect)
                        is Outline.Generic -> error("Material row must have rectangular or rounded corners")
                    }
                }
            assertEquals(0f, outlines[0].bottomLeftCornerRadius.x, 0f)
            assertEquals(0f, outlines[0].bottomRightCornerRadius.x, 0f)
            assertEquals(0f, outlines[1].topLeftCornerRadius.x, 0f)
            assertEquals(0f, outlines[1].bottomRightCornerRadius.x, 0f)
            assertEquals(0f, outlines[2].topLeftCornerRadius.x, 0f)
            assertEquals(0f, outlines[2].topRightCornerRadius.x, 0f)
            val singleton = outlines[3]
            assertTrue(singleton.topLeftCornerRadius.x > 0f)
            assertTrue(singleton.topRightCornerRadius.x > 0f)
            assertTrue(singleton.bottomLeftCornerRadius.x > 0f)
            assertTrue(singleton.bottomRightCornerRadius.x > 0f)
        }
    }

    /** All Material interaction fields must resolve to the same outer boundary. */
    private fun assertStableShape(shapes: ListItemShapes) {
        listOf(
            shapes.selectedShape,
            shapes.pressedShape,
            shapes.focusedShape,
            shapes.hoveredShape,
            shapes.draggedShape,
        ).forEach { assertEquals(shapes.shape, it) }
    }
}
