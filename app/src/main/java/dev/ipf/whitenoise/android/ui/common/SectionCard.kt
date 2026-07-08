package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder

internal val LocalSettingsRowsInsideSectionCard = staticCompositionLocalOf { false }

// Section panels read as white cards on a recessed page in light, and as a
// raised neutral card over the darker page in dark/AMOLED — the same "panel
// one step above the page" relationship, resolved per theme by luminance since
// "lighter == elevated" can't be expressed the same way in both.
@Composable
internal fun sectionPanelColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerLowest
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

@Composable
internal fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().amoledSurfaceBorder(RoundedCornerShape(12.dp)),
        colors = CardDefaults.elevatedCardColors(containerColor = sectionPanelColor()),
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.spaceLg), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            CompositionLocalProvider(LocalSettingsRowsInsideSectionCard provides true) {
                content()
            }
        }
    }
}

@Composable
internal fun SectionCardWithAction(
    title: String,
    action: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().amoledSurfaceBorder(RoundedCornerShape(12.dp)),
        colors = CardDefaults.elevatedCardColors(containerColor = sectionPanelColor()),
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.spaceLg), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                action()
            }
            CompositionLocalProvider(LocalSettingsRowsInsideSectionCard provides true) {
                content()
            }
        }
    }
}
