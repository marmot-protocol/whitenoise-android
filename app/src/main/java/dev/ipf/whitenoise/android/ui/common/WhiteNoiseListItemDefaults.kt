package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

/** Keep native list boundaries stable while Material supplies interaction and selection feedback. */
@OptIn(ExperimentalMaterial3Api::class)
internal object WhiteNoiseListItemDefaults {
    /** AMOLED rows share one-pixel seams; tonal groups keep Material's segmented gap. */
    val segmentedGap
        @Composable get() = if (isAmoledSurfaceTheme()) 0.dp else ListItemDefaults.SegmentedGap

    /** Unsegmented rows retain Material's resting shape across all interaction states. */
    @Composable
    fun shapes(): ListItemShapes {
        val defaults = ListItemDefaults.shapes()
        return remember(defaults) { defaults.withStableShape() }
    }

    /** Use Material's positional corners, closing only the outer edges of connected AMOLED groups. */
    @Composable
    fun segmentedShapes(
        index: Int,
        count: Int,
    ): ListItemShapes {
        val defaults = ListItemDefaults.segmentedShapes(index, count)
        val amoled = isAmoledSurfaceTheme()
        return remember(defaults, index, count, amoled) {
            if (!amoled) {
                defaults.withStableShape()
            } else {
                val corners =
                    requireNotNull(defaults.shape as? CornerBasedShape) {
                        "Material list corners must be corner based"
                    }
                val shape =
                    corners.copy(
                        topStart = if (index == 0) corners.topStart else CornerSize(0.dp),
                        topEnd = if (index == 0) corners.topEnd else CornerSize(0.dp),
                        bottomStart = if (index == count - 1) corners.bottomStart else CornerSize(0.dp),
                        bottomEnd = if (index == count - 1) corners.bottomEnd else CornerSize(0.dp),
                    )
                defaults
                    .copy(shape = ConnectedRowShape(shape, first = index == 0, last = index == count - 1))
                    .withStableShape()
            }
        }
    }
}

/** Prevent interaction morphs from separating otherwise connected rows. */
private fun ListItemShapes.withStableShape(): ListItemShapes =
    copy(
        selectedShape = shape,
        pressedShape = shape,
        focusedShape = shape,
        hoveredShape = shape,
        draggedShape = shape,
    )
