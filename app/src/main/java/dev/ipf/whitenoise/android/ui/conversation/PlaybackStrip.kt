package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Radii

/** Every strip control keeps the Material 48 dp touch target. */
private val StripControlSize = 48.dp
private val StripEdgeInset = 4.dp
private val StripInfoMinWidth = 56.dp
private val StripStackedTextSize = 16.dp
private val StripProgressHeight = 3.dp
private const val STRIP_TRACK_ALPHA = 0.16f

/** Start of the info column, so a status line under the row lines up with the title. */
internal val PlaybackStripInfoStart = StripEdgeInset + StripControlSize + StripEdgeInset

/**
 * Shared one-row "now playing" strip for voice notes and read-aloud: the primary play/pause,
 * the info column that names what plays, the trailing transport actions, and a full-width progress line that
 * doubles as the strip's bottom edge. It paints the plain [androidx.compose.material3.ColorScheme.surface], so it
 * continues the top bar. When supplied, the body action returns to the source.
 *
 * Controls keep their natural width. At narrow widths or large text sizes they are placed below the source.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun PlaybackStrip(
    progress: (() -> Float)?,
    modifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    onBodyClick: (() -> Unit)? = null,
    announceProgress: Boolean = true,
    status: (@Composable () -> Unit)? = null,
    leading: @Composable () -> Unit,
    info: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        val click =
            if (onBodyClick == null) {
                Modifier
            } else {
                Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.tts_bar_return_to_source),
                    onClick = onBodyClick,
                )
            }
        Column(bodyModifier.then(click)) {
            PlaybackStripRow(leading, info, actions)
            if (status != null) {
                Box(Modifier.fillMaxWidth().padding(start = PlaybackStripInfoStart, end = 16.dp, bottom = 6.dp)) {
                    status()
                }
            }
            PlaybackStripProgressEdge(progress, announceProgress)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun PlaybackStripRow(
    leading: @Composable () -> Unit,
    info: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = constraints.maxWidth
        val textSize =
            with(LocalDensity.current) {
                MaterialTheme.typography.labelMedium.fontSize
                    .toDp()
            }
        val stackForText = textSize >= StripStackedTextSize
        Layout(
            contents = listOf(leading, info, actions),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = StripEdgeInset),
        ) { (leadingMeasurables, infoMeasurables, actionMeasurables), _ ->
            val loose = Constraints()
            val leadingPlaceables = leadingMeasurables.map { it.measure(loose) }
            val actionPlaceables = actionMeasurables.map { it.measure(loose) }
            val leadingWidth = leadingPlaceables.sumOf { it.width }
            val actionsWidth = actionPlaceables.sumOf { it.width }
            val innerWidth = availableWidth - StripEdgeInset.roundToPx() * 2
            val minimumInfoWidth = StripInfoMinWidth.roundToPx()
            val stackActions = stackForText || leadingWidth + minimumInfoWidth + actionsWidth > innerWidth
            val inlineActionsWidth = if (stackActions) 0 else actionsWidth
            val infoWidth = (innerWidth - leadingWidth - inlineActionsWidth).coerceAtLeast(minimumInfoWidth)
            val infoConstraints = Constraints.fixedWidth(infoWidth)
            val infoPlaceables = infoMeasurables.map { it.measure(infoConstraints) }
            val firstRow = leadingPlaceables + infoPlaceables + if (stackActions) emptyList() else actionPlaceables
            val firstRowHeight = maxOf(StripControlSize.roundToPx(), firstRow.maxOfOrNull { it.height } ?: 0)
            val secondRowHeight = if (stackActions) actionPlaceables.maxOfOrNull { it.height } ?: 0 else 0
            val width = maxOf(leadingWidth + infoWidth + inlineActionsWidth, actionsWidth)
            layout(width, firstRowHeight + secondRowHeight) {
                var x = 0
                firstRow.forEach { placeable ->
                    placeable.placeRelative(x, (firstRowHeight - placeable.height) / 2)
                    x += placeable.width
                }
                if (stackActions) {
                    x = width - actionsWidth
                    actionPlaceables.forEach { placeable ->
                        placeable.placeRelative(x, firstRowHeight + (secondRowHeight - placeable.height) / 2)
                        x += placeable.width
                    }
                }
            }
        }
    }
}

/** Full-width bottom edge: a faint track with a solid fill, monochrome in every theme including AMOLED. */
@Composable
@Suppress("FunctionNaming")
private fun PlaybackStripProgressEdge(
    progress: (() -> Float)?,
    announce: Boolean,
) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = STRIP_TRACK_ALPHA)
    val edge = Modifier.fillMaxWidth().height(StripProgressHeight)
    LinearProgressIndicator(
        progress = { progress?.invoke()?.coerceIn(0f, 1f) ?: 0f },
        modifier = if (announce && progress != null) edge else edge.clearAndSetSemantics {},
        color = MaterialTheme.colorScheme.onSurface,
        trackColor = trackColor,
        strokeCap = StrokeCap.Butt,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** Title over a quieter supporting line; either may be absent. */
@Composable
@Suppress("FunctionNaming")
internal fun PlaybackStripInfo(
    title: String?,
    modifier: Modifier = Modifier,
    supporting: @Composable () -> Unit = {},
) {
    Column(
        modifier.heightIn(min = StripControlSize).padding(horizontal = StripEdgeInset),
        verticalArrangement = Arrangement.Center,
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        supporting()
    }
}

/** Primary play/pause: the composer's filled disc, squared off while audio plays (Expressive shape morph). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress("FunctionNaming")
internal fun PlaybackStripPlayButton(
    playing: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val corner by animateDpAsState(
        targetValue = if (playing) Radii.md else 20.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "playbackStripPlayShape",
    )
    FilledIconButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(corner)) {
        Icon(
            painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
            contentDescription = contentDescription,
        )
    }
}

/** Non-interactive occupant of the play slot (preparing, failed, finished) so the row never shifts. */
@Composable
@Suppress("FunctionNaming")
internal fun PlaybackStripLeadingSlot(content: @Composable () -> Unit = {}) {
    Box(Modifier.size(StripControlSize), contentAlignment = Alignment.Center) { content() }
}

/** Compact outlined speed label: visibly a small chip, still a 48 dp target. */
@Composable
@Suppress("FunctionNaming")
internal fun PlaybackSpeedChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val content = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Radii.sm),
        color = Color.Transparent,
        contentColor = if (enabled) content else content.copy(alpha = DISABLED_ALPHA),
        border =
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
            ),
        modifier = modifier,
    ) {
        Box(
            Modifier.heightIn(min = 28.dp).padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
                maxLines = 1,
            )
        }
    }
}

/** Ends playback and dismisses the strip; the glyph says "close", the accessible name keeps the action. */
@Composable
@Suppress("FunctionNaming")
internal fun PlaybackStripCloseButton(
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_close), contentDescription = contentDescription)
    }
}

private const val DISABLED_ALPHA = 0.38f
