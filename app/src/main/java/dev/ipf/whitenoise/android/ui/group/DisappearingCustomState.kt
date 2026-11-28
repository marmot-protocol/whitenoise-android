@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.group

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** The custom duration being edited: a value in one of [disappearingCustomUnits], read back as [secs]. */
@Stable
internal class DisappearingCustomState(
    initialSecs: Long,
) {
    private val start = disappearingCustomPickerStateForSeconds(initialSecs)
    var unitIndex by mutableIntStateOf(start.unitIndex)
    var value by mutableIntStateOf(start.value)
    val secs: Long get() = disappearingCustomSeconds(value, unitIndex)
}

/** A custom-duration editor seeded from [secs], reset whenever that changes. */
@Composable
internal fun rememberDisappearingCustomState(secs: Long) = remember(secs) { DisappearingCustomState(secs) }

/**
 * The custom-duration controls shared by the chat sheet's second page and the Settings default dialog: a big
 * readout, a slider with fine steps and plus/minus buttons, and the unit choices.
 */
@Composable
@Suppress("LongMethod")
internal fun DisappearingCustomControls(state: DisappearingCustomState) {
    val units = disappearingCustomUnits
    val unitIndex = state.unitIndex
    val value = state.value
    val maxValue = units[unitIndex].max
    val columns = disappearingChipColumns()
    val secs = state.secs
    Column(verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
        AnimatedContent(
            targetState = secs,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically { h -> if (up) h / 2 else -h / 2 } + fadeIn())
                    .togetherWith(slideOutVertically { h -> if (up) -h / 2 else h / 2 } + fadeOut())
            },
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            label = "disappearing-custom-readout",
        ) { shownSecs ->
            Text(
                disappearingMessagesLabel(shownSecs),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = WhiteNoiseSpacing.Related),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { state.value = (value - 1).coerceIn(1, maxValue) },
                enabled = value > 1,
                modifier = Modifier.testTag(DISAPPEARING_CUSTOM_DECREASE_TAG),
            ) {
                Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.disappearing_custom_decrease))
            }
            Slider(
                value = value.coerceAtMost(maxValue).toFloat(),
                onValueChange = { state.value = it.toInt().coerceIn(1, maxValue) },
                valueRange = 1f..maxValue.toFloat(),
                steps = (maxValue - 2).coerceAtLeast(0),
                modifier = Modifier.weight(1f).testTag(DISAPPEARING_CUSTOM_SLIDER_TAG),
            )
            IconButton(
                onClick = { state.value = (value + 1).coerceAtMost(maxValue) },
                enabled = value < maxValue,
                modifier = Modifier.testTag(DISAPPEARING_CUSTOM_INCREASE_TAG),
            ) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.disappearing_custom_increase))
            }
        }
        Column(
            Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            units.indices.chunked(columns).forEach { row ->
                ChipRow {
                    row.forEach { index ->
                        DisappearingChip(
                            label = stringResource(units[index].labelRes),
                            selected = unitIndex == index,
                            onClick = {
                                state.unitIndex = index
                                state.value = clampDisappearingCustomValue(value, index)
                            },
                            modifier = Modifier.weight(1f).testTag(disappearingUnitTag(index)),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
