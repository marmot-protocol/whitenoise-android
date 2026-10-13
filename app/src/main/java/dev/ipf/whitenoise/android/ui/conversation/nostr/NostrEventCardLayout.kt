@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** A ceiling, never reserved space; follow the current window and keyboard target. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun nostrEventPreviewMaximumHeight(): Dp {
    val density = LocalDensity.current
    val measured = LocalWindowInfo.current.containerSize.height
    val window =
        measured.takeIf { it > 0 } ?: with(density) {
            LocalConfiguration.current.screenHeightDp.dp
                .roundToPx()
        }
    val available =
        window - WindowInsets.statusBars.getTop(density) -
            maxOf(WindowInsets.navigationBars.getBottom(density), WindowInsets.imeAnimationTarget.getBottom(density))
    return with(density) { (available.coerceAtLeast(1) * EVENT_PREVIEW_HEIGHT_FRACTION).toDp() }
}

/** Measures one content composition; only overflowing cards place an expansion action. */
@Composable
internal fun NostrEventCardLayout(
    maximumHeight: Dp,
    onExpand: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val expansionHeight = 48.dp * density.fontScale.coerceAtLeast(1f)
    Layout(
        content = {
            NostrEventCardViewport(maximumHeight, expansionHeight, content)
            TextButton(
                onClick = onExpand,
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                modifier = Modifier.fillMaxWidth().height(expansionHeight).testTag(NOSTR_CARD_EXPAND_TAG),
            ) {
                Text(stringResource(R.string.nostr_event_expand))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { children, constraints ->
        val ceiling = minOf(maximumHeight.roundToPx().coerceAtLeast(1), constraints.maxHeight)
        val loose = constraints.copy(minHeight = 0, maxHeight = ceiling)
        val footer = children[1].measure(loose)
        val body = children[0].measure(loose)
        // Immutable child measurement metadata survives cached measurements, and
        // Layout supports the intrinsic-width query used by message reply frames.
        val overflow = body[NaturalCardHeight] > ceiling
        val height = body.height + if (overflow) footer.height else 0
        layout(maxOf(body.width, footer.width), constraints.constrainHeight(height)) {
            body.placeRelative(0, 0)
            if (overflow) footer.placeRelative(0, body.height)
        }
    }
}

@Composable
private fun NostrEventCardViewport(
    maximumHeight: Dp,
    expansionHeight: Dp,
    content: @Composable () -> Unit,
) {
    Layout(
        content = { Box(Modifier.fillMaxWidth()) { content() } },
        modifier = Modifier.clipToBounds(),
    ) { children, constraints ->
        val ceiling = minOf(maximumHeight.roundToPx().coerceAtLeast(1), constraints.maxHeight)
        val footerHeight = minOf(expansionHeight.roundToPx(), ceiling)
        val natural = children.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val overflow = natural.height > ceiling
        val height = if (overflow) (ceiling - footerHeight).coerceAtLeast(0) else natural.height
        layout(natural.width, height, alignmentLines = mapOf(NaturalCardHeight to natural.height)) {
            natural.placeRelative(0, 0)
        }
    }
}

private val NaturalCardHeight = HorizontalAlignmentLine(::maxOf)

internal const val NOSTR_CARD_EXPAND_TAG = "nostr-card-expand"

private const val EVENT_PREVIEW_HEIGHT_FRACTION = 0.6f
