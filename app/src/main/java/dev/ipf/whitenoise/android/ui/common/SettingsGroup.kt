package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

// Corner radius of a segment by its position in the group: the outer edges of
// the group are large, the seams between neighbours are tight — the Android-16 /
// Google grouped-list look. A single-item group is fully rounded.
internal fun segmentShape(
    index: Int,
    count: Int,
    large: Dp = 20.dp,
    small: Dp = 4.dp,
): RoundedCornerShape {
    val top = if (index == 0) large else small
    val bottom = if (index == count - 1) large else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

// A toggle row sized for a segmented group item: the segment Surface owns the
// shape, the row owns its 16dp inset (matching ListItem-based rows).
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GroupSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) {
            LoadingIndicator(modifier = Modifier.size(24.dp))
        } else {
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

class SettingsGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()

    /** Add one row to the group; its container shape is computed from its position. */
    fun item(content: @Composable () -> Unit) {
        items.add(content)
    }
}

/**
 * A segmented settings group: an optional accent label/icon above a stack of
 * rows, each rendered on its own shaped [Surface] with tight seams and large
 * outer corners. Replaces the single-card-with-dividers layout so a group of
 * rows reads as one shaped unit.
 */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    content: SettingsGroupScope.() -> Unit,
) {
    val built = SettingsGroupScope().apply(content)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Row(
                // Match the rows' ListItem leading inset so the label column
                // lines up with the row icons below it.
                modifier = Modifier.padding(start = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // Rows never paint their own per-row border inside a group — the group
        // owns the shape and the AMOLED edge.
        CompositionLocalProvider(LocalSettingsRowsInsideSectionCard provides true) {
            if (isAmoledSurfaceTheme()) {
                // AMOLED: black-on-black segments only read through their outline,
                // and per-segment outlines double up at every seam. Draw the group
                // as one outlined container with a single hairline per seam instead.
                val outerShape = RoundedCornerShape(20.dp)
                Surface(
                    modifier = Modifier.fillMaxWidth().amoledSurfaceBorder(outerShape),
                    shape = outerShape,
                    color = sectionPanelColor(),
                ) {
                    Column {
                        built.items.forEachIndexed { index, itemContent ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            itemContent()
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val count = built.items.size
                    built.items.forEachIndexed { index, itemContent ->
                        val shape = segmentShape(index, count)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = shape,
                            color = sectionPanelColor(),
                        ) {
                            itemContent()
                        }
                    }
                }
            }
        }
    }
}
