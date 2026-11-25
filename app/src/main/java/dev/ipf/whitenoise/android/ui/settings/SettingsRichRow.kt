package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The settings row for content the fixed-text rows cannot carry: a profile or contact identity, a monospace
 * reference, or a coloured status. It is a Material list item on a transparent fill, so the enclosing
 * [SettingsGroupPanel] owns the shape, fill and AMOLED outline, and it keeps the link, switch and value rows' insets
 * and type: a `bodyLarge` headline, or `titleMedium` for a [hero] identity row, over supporting text in the
 * variant colour. The slots take the same names as [ListItem]'s, and the caller owns any click or semantics.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun SettingsRichRow(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val headlineStyle = if (hero) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
    ListItem(
        headlineContent = { ProvideTextStyle(headlineStyle, headlineContent) },
        modifier = modifier,
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
