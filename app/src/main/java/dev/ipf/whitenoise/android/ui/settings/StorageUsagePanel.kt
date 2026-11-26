@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.StorageUsage
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.PillShape
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import java.text.NumberFormat
import kotlin.math.abs

/** Sizes for the usage panel: a tall pill bar with hairline gaps, so neighbouring tints stay distinct. */
private object StorageUsageDefaults {
    val BarHeight = 20.dp

    /** The bar's tap band, taller than the bar so a thumb lands on it, and the minimum accessible target height. */
    val BarTouchHeight = 36.dp
    val SegmentGap = 2.dp
    val LegendDot = 10.dp
    val LegendRowMinHeight = 48.dp
    val LegendRowPadding = 4.dp

    /** A segment under this share of the bar is too thin to hit, so taps beside it pick the nearest centre. */
    const val MinimumHitShare = 0.08f

    /** Shares under one percent read as "<1%" rather than rounding to zero. */
    const val SliverShare = 0.01

    /** Segments and dots that are not highlighted fade to this alpha while one category is. */
    const val DimAlpha = 0.28f
    const val HighlightMillis = 150

    /** A non-empty category never shrinks below this share of the bar, so it stays visible. */
    const val MinimumShare = 0.02f
    const val PulseLowAlpha = 0.4f
    const val PulseMillis = 900

    /**
     * Accent tints stepping evenly down the legend, far enough apart to tell neighbours apart in every theme. Every
     * category is a tint of the accent, so AMOLED's white outline colour never makes one match Attachments.
     */
    const val ChatDataAlpha = 0.72f
    const val UnusedAlpha = 0.52f
    const val PicturesAlpha = 0.36f
    const val TemporaryAlpha = 0.2f

    /** From this font scale the size moves under its label, so labels never break mid-word. */
    const val StackedFontScale = 1.5f

    /** Material's unscaled body text size, the reference for [StackedFontScale]. */
    const val BaseBodySp = 16f
}

/** One measured category: its label, bytes and bar colour, and whether every profile on the device shares it. */
internal data class StorageCategory(
    @StringRes val labelRes: Int,
    val bytes: Long,
    val color: Color,
    val detail: String? = null,
    val shared: Boolean = false,
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
        StorageCategory(
            R.string.storage_unused_space,
            usage.unusedDatabaseBytes,
            tint(StorageUsageDefaults.UnusedAlpha),
        ).takeIf { usage.unusedDatabaseBytes > 0 },
        StorageCategory(R.string.storage_pictures, usage.pictureCacheBytes, tint(StorageUsageDefaults.PicturesAlpha)),
        StorageCategory(
            R.string.storage_temporary,
            usage.temporaryBytes,
            tint(StorageUsageDefaults.TemporaryAlpha),
            stringResource(R.string.storage_temporary_detail),
            shared = true,
        ),
    )
}

/**
 * This profile's total, a stacked pill bar of its categories and a legend naming every category with its size, so
 * nothing relies on colour. Tapping a bar segment or legend row highlights that category: the other segments fade,
 * its row takes a tonal fill, and the header shows its size and share of the total until it is tapped again.
 * Temporary files every profile shares stay in the legend but out of the total and the bar. Before the first figures
 * arrive the bar pulses under "Calculating…" without changing height.
 */
@Composable
internal fun StorageUsagePanel(
    usage: StorageUsage?,
    modifier: Modifier = Modifier,
) {
    val categories = usage?.let { storageCategories(it) }.orEmpty()
    var highlightedRes by rememberSaveable { mutableStateOf<Int?>(null) }
    val highlighted = categories.firstOrNull { it.labelRes == highlightedRes }
    // A category that a refresh leaves out drops its highlight for good, so it does not return unasked later.
    val listed = categories.map { it.labelRes }
    LaunchedEffect(listed) { if (usage != null && highlightedRes !in listed) highlightedRes = null }
    val toggle = { category: StorageCategory ->
        highlightedRes = category.labelRes.takeUnless { it == highlightedRes }
    }
    SettingsPanel(
        modifier = modifier.testTag("storage.usage.panel"),
        destination = SettingsDetail.ManageStorage,
        surface = "storage-usage",
    ) {
        Column(
            Modifier.padding(vertical = WhiteNoiseSpacing.FormField),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            StorageUsageHeader(usage, highlighted)
            Box(Modifier.padding(horizontal = WhiteNoiseSpacing.FormField)) {
                if (usage == null) {
                    StorageUsagePlaceholderBar()
                } else {
                    StorageUsageBar(categories.filterNot { it.shared }, highlighted, toggle)
                }
            }
            Column(
                Modifier.padding(horizontal = WhiteNoiseSpacing.Related).selectableGroup(),
            ) {
                categories.forEach { category ->
                    StorageLegendRow(
                        category = category,
                        share = usage?.let { storageShare(category, it) },
                        highlighted = category == highlighted,
                        dimmed = highlighted != null && category != highlighted,
                        onClick = { toggle(category) },
                    )
                }
            }
        }
    }
}

/**
 * The total and what it covers, or, while a category is highlighted, that category's size and its share of the
 * total. TalkBack reads the header as one sentence and announces it when the highlight changes.
 */
@Composable
private fun StorageUsageHeader(
    usage: StorageUsage?,
    highlighted: StorageCategory?,
) {
    val total = usage?.let { storageBytes(it.profileBytes) }
    val headline = highlighted?.let { storageBytes(it.bytes) } ?: total ?: stringResource(R.string.storage_calculating)
    val caption =
        if (highlighted == null || usage == null) {
            stringResource(R.string.storage_used_by_profile)
        } else {
            storageHighlightCaption(highlighted, usage)
        }
    val described =
        if (highlighted == null) {
            stringResource(R.string.storage_usage_description, total ?: headline, caption)
        } else {
            stringResource(R.string.storage_usage_category, headline, caption)
        }
    Column(
        Modifier
            .padding(horizontal = WhiteNoiseSpacing.FormField)
            .testTag("storage.usage")
            .clearAndSetSemantics {
                contentDescription = described
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Attachments · 79% of 2.4 GB", or, for files every profile shares, the category and where it is counted. */
@Composable
private fun storageHighlightCaption(
    category: StorageCategory,
    usage: StorageUsage,
): String {
    val label = stringResource(category.labelRes)
    val share = storageShare(category, usage)
    return if (category.shared || share == null) {
        stringResource(R.string.storage_highlight_shared, label)
    } else {
        stringResource(R.string.storage_highlight_share, label, share, storageBytes(usage.profileBytes))
    }
}

/** The category's share of this profile's total as a locale percentage, "under 1%" for a sliver, null when shared. */
@Composable
private fun storageShare(
    category: StorageCategory,
    usage: StorageUsage,
): String? {
    if (category.shared) return null
    val locale = LocalConfiguration.current.locales[0]
    val fraction = if (usage.profileBytes > 0) category.bytes.toDouble() / usage.profileBytes else 0.0
    val format = remember(locale) { NumberFormat.getPercentInstance(locale) }
    return if (fraction > 0 && fraction < StorageUsageDefaults.SliverShare) {
        stringResource(R.string.storage_share_under_one, format.format(StorageUsageDefaults.SliverShare))
    } else {
        format.format(fraction)
    }
}

/**
 * Segments share the bar by bytes, an all-zero account shows an empty track. A tap anywhere in the bar's touch band
 * highlights the segment under it, or the nearest one when a sliver is too thin to hit. The legend carries the same
 * choices for TalkBack, so the bar itself stays out of the accessibility tree.
 */
@Composable
private fun StorageUsageBar(
    categories: List<StorageCategory>,
    highlighted: StorageCategory?,
    onTap: (StorageCategory) -> Unit,
) {
    val total = categories.sumOf { it.bytes }.toFloat()
    val visible = categories.filter { it.bytes > 0 }
    val shares = visible.map { (it.bytes / total).coerceAtLeast(StorageUsageDefaults.MinimumShare) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentVisible by rememberUpdatedState(visible)
    val currentShares by rememberUpdatedState(shares)
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .fillMaxWidth()
            .height(StorageUsageDefaults.BarTouchHeight)
            .testTag("storage.usage.bar")
            .clearAndSetSemantics {}
            .pointerInput(rtl) {
                detectTapGestures { offset ->
                    val x = if (rtl) size.width - offset.x else offset.x
                    val gap = StorageUsageDefaults.SegmentGap.toPx()
                    val hit = storageSegmentAt(x, size.width.toFloat(), gap, currentShares)
                    hit?.let { currentOnTap(currentVisible[it]) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(StorageUsageDefaults.BarHeight)
                .clip(PillShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            horizontalArrangement = Arrangement.spacedBy(StorageUsageDefaults.SegmentGap),
        ) {
            visible.forEachIndexed { index, category ->
                val lit = highlighted == null || highlighted == category
                val alpha by animateFloatAsState(
                    targetValue = if (lit) 1f else StorageUsageDefaults.DimAlpha,
                    animationSpec = tween(StorageUsageDefaults.HighlightMillis),
                    label = "storage.segment.alpha",
                )
                Box(
                    Modifier
                        .weight(shares[index])
                        .height(StorageUsageDefaults.BarHeight)
                        .alpha(alpha)
                        .background(category.color),
                )
            }
        }
    }
}

/**
 * The segment under [x] pixels from the bar's start, laid out as the bar draws it: [weights] share [width] after the
 * [gap] between neighbours. A tap in a gap goes to the nearer side. A segment narrower than
 * [StorageUsageDefaults.MinimumHitShare] of the bar is hard to hit, so it also claims taps within half that share of
 * its centre, the nearest such sliver winning over a wider neighbour. Null when there are no segments.
 */
internal fun storageSegmentAt(
    x: Float,
    width: Float,
    gap: Float,
    weights: List<Float>,
): Int? {
    val sum = weights.sum()
    if (weights.isEmpty() || sum <= 0f || width <= 0f) return null
    val drawn = (width - gap * weights.lastIndex).coerceAtLeast(0f)
    var start = 0f
    val bounds =
        weights.map { weight ->
            val end = start + drawn * weight / sum
            (start to end).also { start = end + gap }
        }
    val at = x.coerceIn(0f, width)
    val reach = width * StorageUsageDefaults.MinimumHitShare / 2
    val sliver =
        bounds.indices
            .filter { bounds[it].second - bounds[it].first < width * StorageUsageDefaults.MinimumHitShare }
            .map { it to abs((bounds[it].first + bounds[it].second) / 2 - at) }
            .filter { (_, distance) -> distance <= reach }
            .minByOrNull { (_, distance) -> distance }
    return sliver?.first ?: bounds.indices.firstOrNull { at <= bounds[it].second + gap / 2 } ?: weights.lastIndex
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
        Modifier.fillMaxWidth().height(StorageUsageDefaults.BarTouchHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(StorageUsageDefaults.BarHeight)
                .alpha(pulse)
                .clip(PillShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}

/**
 * A legend row: colour dot, label with an optional detail, and the category's size at the end. It is a selectable
 * target for the same highlight as the bar, with a tonal fill while highlighted and a faded dot while another
 * category is. At large font scales the size joins the detail line instead, so the label keeps the full width.
 */
@Composable
private fun StorageLegendRow(
    category: StorageCategory,
    share: String?,
    highlighted: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    // Both the system font size and the app's own text size enlarge the label, so measure the rendered body size.
    val bodyScale = MaterialTheme.typography.bodyLarge.fontSize.value / StorageUsageDefaults.BaseBodySp
    val stacked = LocalDensity.current.fontScale * bodyScale >= StorageUsageDefaults.StackedFontScale
    val size = storageBytes(category.bytes)
    val detail = if (stacked) listOfNotNull(size, category.detail).joinToString(" · ") else category.detail
    val fill by animateColorAsState(
        targetValue = if (highlighted) storageHighlightFill() else Color.Transparent,
        animationSpec = tween(StorageUsageDefaults.HighlightMillis),
        label = "storage.legend.fill",
    )
    val dotAlpha by animateFloatAsState(
        targetValue = if (dimmed) StorageUsageDefaults.DimAlpha else 1f,
        animationSpec = tween(StorageUsageDefaults.HighlightMillis),
        label = "storage.legend.dot",
    )
    val label = stringResource(category.labelRes)
    // Each row reads as one localized sentence: label, size, then its share and any detail.
    val extra = listOfNotNull(share, category.detail).joinToString(", ").ifEmpty { null }
    val spoken =
        extra?.let { stringResource(R.string.storage_usage_category_detail, label, size, it) }
            ?: stringResource(R.string.storage_usage_category, label, size)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = StorageUsageDefaults.LegendRowMinHeight)
            .clip(MaterialTheme.shapes.medium)
            .background(fill)
            .testTag("storage.legend")
            .selectable(selected = highlighted, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related + 4.dp),
    ) {
        Box(
            Modifier
                .size(StorageUsageDefaults.LegendDot)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(category.color),
        )
        Column(Modifier.weight(1f).padding(vertical = StorageUsageDefaults.LegendRowPadding)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(it, style = storageDetailStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!stacked) {
            Text(
                size,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The highlighted legend row's fill, the picker groups' tonal highlight and its AMOLED wash. */
@Composable
private fun storageHighlightFill(): Color =
    if (isAmoledSurfaceTheme()) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = SettingsRowDefaults.AmoledSelectionAlpha)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

/** Detail text set in its own content's direction, so a size and count keep their order in a right-to-left layout. */
@Composable
private fun storageDetailStyle(): TextStyle {
    val base = MaterialTheme.typography.bodySmall
    return base.copy(textDirection = TextDirection.Content)
}
