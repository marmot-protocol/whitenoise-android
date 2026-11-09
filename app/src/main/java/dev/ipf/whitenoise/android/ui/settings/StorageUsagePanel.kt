@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.StorageUsage
import dev.ipf.whitenoise.android.ui.theme.PillShape
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Sizes for the usage panel: a tall pill bar with hairline gaps, so neighbouring tints stay distinct. */
private object StorageUsageDefaults {
    val BarHeight = 20.dp
    val SegmentGap = 2.dp
    val LegendDot = 10.dp
    val LegendRowMinHeight = 32.dp

    /** A non-empty category never shrinks below this share of the bar, so it stays visible. */
    const val MinimumShare = 0.02f
    const val PulseLowAlpha = 0.4f
    const val PulseMillis = 900

    /** Accent tints stepped far enough apart to tell the categories apart in light and dark themes. */
    const val ChatDataAlpha = 0.68f
    const val PicturesAlpha = 0.42f
    const val TemporaryAlpha = 0.2f

    /** From this font scale the size moves under its label, so labels never break mid-word. */
    const val StackedFontScale = 1.5f

    /** Material's unscaled body text size, the reference for [StackedFontScale]. */
    const val BaseBodySp = 16f
}

/** One measured category: its label, bytes and bar colour. */
internal data class StorageCategory(
    @StringRes val labelRes: Int,
    val bytes: Long,
    val color: Color,
    val detail: String? = null,
)

/** The categories the panel shows, largest meaning first, leaving out unused database space when there is none. */
@Composable
internal fun storageCategories(usage: StorageUsage): List<StorageCategory> {
    val scheme = MaterialTheme.colorScheme
    val tint = { alpha: Float -> scheme.primary.copy(alpha = alpha) }
    val count = usage.attachmentCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return listOfNotNull(
        StorageCategory(
            R.string.storage_attachments,
            usage.attachmentBytes,
            scheme.primary,
            pluralStringResource(R.plurals.storage_attachment_count, count, usage.attachmentCount),
        ),
        StorageCategory(R.string.storage_chat_data, usage.chatDataBytes, tint(StorageUsageDefaults.ChatDataAlpha)),
        StorageCategory(R.string.storage_unused_space, usage.unusedDatabaseBytes, scheme.outlineVariant)
            .takeIf { usage.unusedDatabaseBytes > 0 },
        StorageCategory(R.string.storage_pictures, usage.pictureCacheBytes, tint(StorageUsageDefaults.PicturesAlpha)),
        StorageCategory(
            R.string.storage_temporary,
            usage.temporaryBytes,
            tint(StorageUsageDefaults.TemporaryAlpha),
            stringResource(R.string.storage_temporary_detail),
        ),
    )
}

/**
 * The total, a stacked pill bar and a legend that names every category with its size, so nothing relies on colour.
 * TalkBack reads the whole panel as one sentence. Before the first figures arrive the bar pulses under
 * "Calculating…" without changing height.
 */
@Composable
internal fun StorageUsagePanel(
    usage: StorageUsage?,
    modifier: Modifier = Modifier,
) {
    val categories = usage?.let { storageCategories(it) }.orEmpty()
    val total = usage?.let { storageBytes(it.totalBytes) }
    val parts = categories.map { "${stringResource(it.labelRes)} ${storageBytes(it.bytes)}" }.joinToString()
    val described =
        stringResource(
            R.string.storage_usage_description,
            total ?: stringResource(R.string.storage_calculating),
            parts,
        )
    SettingsPanel(modifier.testTag("storage.usage").clearAndSetSemantics { contentDescription = described }) {
        Column(
            Modifier.padding(WhiteNoiseSpacing.FormField),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            Text(
                text = total ?: stringResource(R.string.storage_calculating),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.storage_used_by_profile),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (usage == null) StorageUsagePlaceholderBar() else StorageUsageBar(categories)
            categories.forEach { StorageLegendRow(it) }
        }
    }
}

/** Segments share the bar by bytes, an all-zero account shows an empty track. */
@Composable
private fun StorageUsageBar(categories: List<StorageCategory>) {
    val total = categories.sumOf { it.bytes }.toFloat()
    val visible = categories.filter { it.bytes > 0 }
    Row(
        Modifier
            .fillMaxWidth()
            .height(StorageUsageDefaults.BarHeight)
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        horizontalArrangement = Arrangement.spacedBy(StorageUsageDefaults.SegmentGap),
    ) {
        visible.forEach { category ->
            val share = (category.bytes / total).coerceAtLeast(StorageUsageDefaults.MinimumShare)
            Box(Modifier.weight(share).height(StorageUsageDefaults.BarHeight).background(category.color))
        }
    }
}

/** The empty track, pulsing while the first figures load. */
@Composable
private fun StorageUsagePlaceholderBar() {
    val pulse by
        rememberInfiniteTransition(label = "storage.calculating").animateFloat(
            initialValue = StorageUsageDefaults.PulseLowAlpha,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(StorageUsageDefaults.PulseMillis), RepeatMode.Reverse),
            label = "storage.calculating.alpha",
        )
    Box(
        Modifier
            .fillMaxWidth()
            .height(StorageUsageDefaults.BarHeight)
            .alpha(pulse)
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/**
 * A legend row: colour dot, label with an optional detail, and the category's size at the end. At large font scales
 * the size joins the detail line instead, so the label keeps the full width.
 */
@Composable
private fun StorageLegendRow(category: StorageCategory) {
    // Both the system font size and the app's own text size enlarge the label, so measure the rendered body size.
    val bodyScale = MaterialTheme.typography.bodyLarge.fontSize.value / StorageUsageDefaults.BaseBodySp
    if (LocalDensity.current.fontScale * bodyScale >= StorageUsageDefaults.StackedFontScale) {
        val size = storageBytes(category.bytes)
        return StorageLegendLine(category.copy(detail = listOfNotNull(size, category.detail).joinToString(" · ")))
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = StorageUsageDefaults.LegendRowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related + 4.dp),
    ) {
        Box(Modifier.size(StorageUsageDefaults.LegendDot).clip(CircleShape).background(category.color))
        Column(Modifier.weight(1f)) {
            Text(stringResource(category.labelRes), style = MaterialTheme.typography.bodyLarge)
            category.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            storageBytes(category.bytes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The stacked legend row: dot beside the label, with the size and detail on the line below. */
@Composable
private fun StorageLegendLine(category: StorageCategory) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = StorageUsageDefaults.LegendRowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related + 4.dp),
    ) {
        Box(Modifier.size(StorageUsageDefaults.LegendDot).clip(CircleShape).background(category.color))
        Column(Modifier.weight(1f)) {
            Text(stringResource(category.labelRes), style = MaterialTheme.typography.bodyLarge)
            category.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
