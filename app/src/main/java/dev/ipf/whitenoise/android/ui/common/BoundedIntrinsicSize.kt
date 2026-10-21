package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/**
 * Sizes to a child's intrinsic width without packing its unbounded result into [Constraints].
 * Long peer-provided text can have a natural width larger than Compose can represent, even when
 * its parent is screen-sized. Keep ordinary wrap-content sizing and clamp before packing.
 */
internal fun Modifier.boundedIntrinsicWidth(
    size: IntrinsicSize,
): Modifier = this.then(BoundedIntrinsicSizeElement(size, width = true))

/** The height counterpart, including tall blockquotes measured inside a scrolling container. */
internal fun Modifier.boundedIntrinsicHeight(
    size: IntrinsicSize,
): Modifier = this.then(BoundedIntrinsicSizeElement(size, width = false))

/** Preserves the incoming height budget while fitting a desired width into Compose's packed range. */
internal fun boundedIntrinsicWidthConstraints(
    incoming: Constraints,
    intrinsicWidth: Int,
): Constraints {
    val width = incoming.constrainWidth(intrinsicWidth.coerceAtMost(Constraints.Infinity - 1))
    return Constraints.fitPrioritizingHeight(
        minWidth = width,
        maxWidth = width,
        minHeight = incoming.minHeight,
        maxHeight = incoming.maxHeight,
    )
}

/** Preserves the incoming width budget while fitting a desired height into Compose's packed range. */
internal fun boundedIntrinsicHeightConstraints(
    incoming: Constraints,
    intrinsicHeight: Int,
): Constraints {
    val height = incoming.constrainHeight(intrinsicHeight.coerceAtMost(Constraints.Infinity - 1))
    return Constraints.fitPrioritizingWidth(
        minWidth = incoming.minWidth,
        maxWidth = incoming.maxWidth,
        minHeight = height,
        maxHeight = height,
    )
}

private data class BoundedIntrinsicSizeElement(
    val size: IntrinsicSize,
    val width: Boolean,
) : ModifierNodeElement<BoundedIntrinsicSizeNode>() {
    /** Gives each attached layout its own mutable measurement policy. */
    override fun create(): BoundedIntrinsicSizeNode = BoundedIntrinsicSizeNode(size, width)

    /** Retains layout identity when a caller changes axis or switches between min and max sizing. */
    override fun update(node: BoundedIntrinsicSizeNode) {
        node.size = size
        node.isWidth = width
    }

    /** Distinguishes the guarded axis and policy when inspecting a failing layout. */
    override fun InspectorInfo.inspectableProperties() {
        name = if (width) "boundedIntrinsicWidth" else "boundedIntrinsicHeight"
        properties["size"] = size
    }
}

/** Keeps intrinsic queries available to ancestor rows/columns instead of falling back to measurement. */
private class BoundedIntrinsicSizeNode(
    var size: IntrinsicSize,
    var isWidth: Boolean,
) : Modifier.Node(),
    LayoutModifierNode {
    /** Fits scalar intrinsic results before packing and avoids querying an already fixed axis. */
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val childConstraints =
            when {
                isWidth && constraints.hasFixedWidth -> constraints
                !isWidth && constraints.hasFixedHeight -> constraints
                isWidth ->
                    boundedIntrinsicWidthConstraints(constraints, intrinsicWidth(measurable, constraints.maxHeight))
                else ->
                    boundedIntrinsicHeightConstraints(constraints, intrinsicHeight(measurable, constraints.maxWidth))
            }
        val placeable = measurable.measure(childConstraints)
        return layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

    /** A width policy reports its chosen intrinsic width even to a parent's minimum query. */
    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int,
    ): Int = if (isWidth) intrinsicWidth(measurable, height) else measurable.minIntrinsicWidth(height)

    /** A height policy preserves the child's width query; a width policy reports its chosen size. */
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int,
    ): Int = if (isWidth) intrinsicWidth(measurable, height) else measurable.maxIntrinsicWidth(height)

    /** A height policy reports its chosen intrinsic height even to a parent's minimum query. */
    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int = if (!isWidth) intrinsicHeight(measurable, width) else measurable.minIntrinsicHeight(width)

    /** A width policy preserves the child's height query; a height policy reports its chosen size. */
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int = if (!isWidth) intrinsicHeight(measurable, width) else measurable.maxIntrinsicHeight(width)

    /** Queries the selected natural width without constructing intermediate constraints. */
    private fun intrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int,
    ): Int =
        when (size) {
            IntrinsicSize.Min -> measurable.minIntrinsicWidth(height)
            IntrinsicSize.Max -> measurable.maxIntrinsicWidth(height)
        }

    /** Queries the selected natural height without constructing intermediate constraints. */
    private fun intrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int =
        when (size) {
            IntrinsicSize.Min -> measurable.minIntrinsicHeight(width)
            IntrinsicSize.Max -> measurable.maxIntrinsicHeight(width)
        }
}
