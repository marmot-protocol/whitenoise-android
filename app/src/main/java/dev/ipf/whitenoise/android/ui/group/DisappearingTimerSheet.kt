@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.group

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetInset
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledSelectedChipBorder
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val DISAPPEARING_PICKER_TAG = "disappearing.picker"
internal const val DISAPPEARING_CUSTOM_ACTION_TAG = "disappearing.custom"
internal const val DISAPPEARING_CONFIRM_TAG = "disappearing.confirm"
internal const val DISAPPEARING_CONFIRM_ACTION_TAG = "disappearing.confirm.action"
internal const val DISAPPEARING_CUSTOM_PAGE_TAG = "disappearing.custom.page"
internal const val DISAPPEARING_CUSTOM_SET_TAG = "disappearing.custom.set"
internal const val DISAPPEARING_CUSTOM_SLIDER_TAG = "disappearing.custom.slider"
internal const val DISAPPEARING_CUSTOM_DECREASE_TAG = "disappearing.custom.decrease"
internal const val DISAPPEARING_CUSTOM_INCREASE_TAG = "disappearing.custom.increase"

internal fun disappearingPresetTag(secs: Long): String = "disappearing.preset.$secs"

internal fun disappearingUnitTag(index: Int): String = "disappearing.unit.$index"

/** Durations shown in the grid, shortest first; Off and Custom sit in their own row below. */
internal val disappearingGridSecs: List<Long> = disappearingPresetSecs.filter { it > 0L }.sorted()

private const val DEFAULT_CHIP_COLUMNS = 3
private const val COMPACT_FONT_SCALE = 1.3f
private val compactWindowWidth = 340.dp
private val timerChoiceMinHeight = 48.dp

private const val PAGE_SLIDE_WIDTH_DIVISOR = 4

private enum class DisappearingSheetPage { Presets, Custom }

/**
 * The disappearing-messages picker: one sheet, every duration visible at once, and a tap applies it.
 * A pick that [requiresConfirmation] (it removes existing history) expands an inline warning in the same
 * sheet instead of opening a second dialog. Custom times are chosen on a second page of the sheet.
 * [onPick] runs after the sheet has animated away; [onDismiss] always follows, so callers only close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // Keep dismissal and selection state together.
internal fun DisappearingMessagesPickerSheet(
    currentSecs: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    explainer: String? = null,
    requiresConfirmation: (Long) -> Boolean = { false },
    confirmIdentity: (@Composable () -> Unit)? = null,
    confirmEnabled: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val latestOnPick by rememberUpdatedState(onPick)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    var selected by remember(currentSecs) { mutableLongStateOf(currentSecs) }
    // The warning keeps its last duration while it collapses, so only visibility flips on cancel.
    var confirmSecs by remember(currentSecs) { mutableLongStateOf(currentSecs) }
    var confirmOpen by remember(currentSecs) { mutableStateOf(false) }
    var page by remember { mutableStateOf(DisappearingSheetPage.Presets) }
    var closing by remember { mutableStateOf(false) }
    var closingPick by remember { mutableStateOf<Long?>(null) }
    var finished by remember { mutableStateOf(false) }

    // Reports once, whether the exit animation ends or a drag dismisses the sheet while it is closing.
    fun finish(picked: Long?) {
        if (finished) return
        finished = true
        picked?.let { latestOnPick(it) }
        latestOnDismiss()
    }

    fun close(picked: Long?) {
        if (closing) return
        closing = true
        closingPick = picked
        scope.launch {
            try {
                sheetState.hide()
            } catch (interrupted: CancellationException) {
                // A gesture can interrupt hide(); leaving composition cancels this job and must not apply.
                if (!currentCoroutineContext().isActive) throw interrupted
            }
            finish(picked)
        }
    }

    fun choose(secs: Long) {
        if (closing) return
        page = DisappearingSheetPage.Presets
        when {
            secs == currentSecs -> {
                confirmOpen = false
                close(null)
            }
            requiresConfirmation(secs) -> {
                confirmSecs = secs
                confirmOpen = true
            }
            else -> {
                selected = secs
                confirmOpen = false
                close(secs)
            }
        }
    }

    WhiteNoiseModalBottomSheet(
        onDismissRequest = { finish(if (closing) closingPick else null) },
        modifier = Modifier.testTag(DISAPPEARING_PICKER_TAG),
        sheetState = sheetState,
    ) {
        BackHandler(enabled = page == DisappearingSheetPage.Custom && !closing) {
            page = DisappearingSheetPage.Presets
        }
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState == DisappearingSheetPage.Custom
                (
                    slideInHorizontally { width -> (if (forward) width else -width) / PAGE_SLIDE_WIDTH_DIVISOR } +
                        fadeIn()
                ).togetherWith(
                    slideOutHorizontally { width -> (if (forward) -width else width) / PAGE_SLIDE_WIDTH_DIVISOR } +
                        fadeOut(),
                ).using(SizeTransform(clip = false))
            },
            label = "disappearing-sheet-page",
        ) { shown ->
            Column(Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState())) {
                when (shown) {
                    DisappearingSheetPage.Presets ->
                        DisappearingPresetsPage(
                            selected = selected,
                            explainer = explainer ?: stringResource(R.string.disappearing_explainer),
                            confirmSecs = confirmSecs,
                            confirmOpen = confirmOpen,
                            confirmIdentity = confirmIdentity,
                            confirmEnabled = confirmEnabled && !closing,
                            onChoose = ::choose,
                            onCustom = { if (!closing) page = DisappearingSheetPage.Custom },
                            onConfirm = { secs ->
                                selected = secs
                                close(secs)
                            },
                            onCancelConfirm = { confirmOpen = false },
                        )
                    DisappearingSheetPage.Custom ->
                        DisappearingCustomPage(
                            initialSecs = selected.takeIf { it > 0L } ?: 60L,
                            onBack = { page = DisappearingSheetPage.Presets },
                            onSet = ::choose,
                        )
                }
            }
        }
    }
}

/** First page of the disappearing-messages sheet: the preset durations and the custom entry. */
@Composable
@Suppress("LongParameterList")
private fun DisappearingPresetsPage(
    selected: Long,
    explainer: String,
    confirmSecs: Long,
    confirmOpen: Boolean,
    confirmIdentity: (@Composable () -> Unit)?,
    confirmEnabled: Boolean,
    onChoose: (Long) -> Unit,
    onCustom: () -> Unit,
    onConfirm: (Long) -> Unit,
    onCancelConfirm: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = WhiteNoiseSheetInset)) {
        WhiteNoiseSheetHeader(stringResource(R.string.disappearing_messages))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSheetInset),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            Text(
                explainer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(
                visible = confirmOpen,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                DisappearingPruneConfirm(
                    secs = confirmSecs,
                    identity = confirmIdentity,
                    enabled = confirmEnabled && confirmOpen,
                    onConfirm = { onConfirm(confirmSecs) },
                    onCancel = onCancelConfirm,
                )
            }
            Spacer(Modifier.size(WhiteNoiseSpacing.Related))
            DisappearingPresetGrid(selected, onChoose, onCustom)
        }
    }
}

@Composable
private fun DisappearingPresetGrid(
    selected: Long,
    onChoose: (Long) -> Unit,
    onCustom: () -> Unit,
) {
    val isCustom = selected !in disappearingPresetSecs
    Column(
        Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        disappearingGridSecs.chunked(disappearingChipColumns()).forEach { row ->
            ChipRow {
                row.forEach { secs ->
                    DisappearingChip(
                        label = disappearingMessagesLabel(secs),
                        selected = selected == secs,
                        onClick = { onChoose(secs) },
                        modifier = Modifier.weight(1f).testTag(disappearingPresetTag(secs)),
                    )
                }
            }
        }
        ChipRow {
            DisappearingChip(
                label = stringResource(R.string.disappearing_off),
                selected = selected == 0L,
                onClick = { onChoose(0L) },
                modifier = Modifier.weight(1f).testTag(disappearingPresetTag(0L)),
            )
            DisappearingChip(
                label =
                    if (isCustom) {
                        disappearingMessagesLabel(selected)
                    } else {
                        stringResource(R.string.disappearing_custom)
                    },
                selected = isCustom,
                onClick = onCustom,
                modifier = Modifier.weight(2f).testTag(DISAPPEARING_CUSTOM_ACTION_TAG),
                iconRes = R.drawable.ic_timer,
            )
        }
    }
}

@Composable
internal fun disappearingChipColumns(): Int =
    if (
        LocalDensity.current.fontScale >= COMPACT_FONT_SCALE ||
        LocalWindowInfo.current.containerDpSize.width < compactWindowWidth
    ) {
        2
    } else {
        DEFAULT_CHIP_COLUMNS
    }

@Composable
private fun ChipRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A compact timer choice with a gray selection outline on AMOLED and an accent fill elsewhere. */
@Composable
private fun DisappearingChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val amoled = isAmoledSurfaceTheme()
    val filled = selected && !amoled
    val container by animateColorAsState(
        if (filled) scheme.primary else Color.Transparent,
        label = "disappearing-chip-container",
    )
    val content by animateColorAsState(
        if (filled) scheme.onPrimary else scheme.onSurface,
        label = "disappearing-chip-content",
    )
    val border by animateColorAsState(
        if (selected) scheme.primary else scheme.outlineVariant,
        label = "disappearing-chip-border",
    )
    // The selectable Surface clips its press highlight to the chip's rounded corners.
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .heightIn(min = timerChoiceMinHeight)
                .semantics { role = Role.RadioButton },
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        border = if (amoled) amoledSelectedChipBorder(selected) else BorderStroke(1.dp, border),
    ) {
        Row(
            Modifier.padding(horizontal = Dimens.spaceXs, vertical = Dimens.spaceMd),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = filled,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                Row {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(Dimens.spaceXs))
                }
            }
            if (iconRes != null && !filled) {
                Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Dimens.spaceXs))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The inline warning for a pick that removes existing history; the sheet stays put until it is answered. */
@Composable
private fun DisappearingPruneConfirm(
    secs: Long,
    identity: (@Composable () -> Unit)?,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(requester, secs) { requester.bringIntoView() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = WhiteNoiseSpacing.Related)
            .bringIntoViewRequester(requester)
            .testTag(DISAPPEARING_CONFIRM_TAG),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            Icon(
                painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.padding(top = Dimens.spaceXxs).size(16.dp),
            )
            Text(
                stringResource(R.string.disappearing_confirm_message, disappearingMessagesLabel(secs)),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
        }
        identity?.invoke()
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(
                onClick = onConfirm,
                enabled = enabled,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                modifier = Modifier.testTag(DISAPPEARING_CONFIRM_ACTION_TAG),
            ) { Text(stringResource(R.string.disappearing_confirm_button)) }
        }
    }
}

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

/** A custom-duration editor seeded from [initialSecs], reset whenever that changes. */
@Composable
internal fun rememberDisappearingCustomState(initialSecs: Long): DisappearingCustomState = remember(initialSecs) { DisappearingCustomState(initialSecs) }

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

/** Second page of the chat sheet: the custom controls under a header with Back, and Set. */
@Composable
private fun DisappearingCustomPage(
    initialSecs: Long,
    onBack: () -> Unit,
    onSet: (Long) -> Unit,
) {
    val state = rememberDisappearingCustomState(initialSecs)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = WhiteNoiseSpacing.Section)
            .testTag(DISAPPEARING_CUSTOM_PAGE_TAG),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        WhiteNoiseSheetHeader(stringResource(R.string.disappearing_custom), onBack = onBack)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.Section),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            DisappearingCustomControls(state)
            Spacer(Modifier.size(WhiteNoiseSpacing.Related))
            Button(
                onClick = { onSet(state.secs) },
                shape = MaterialTheme.shapes.medium,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = timerChoiceMinHeight)
                        .testTag(DISAPPEARING_CUSTOM_SET_TAG),
            ) { Text(stringResource(R.string.disappearing_set)) }
        }
    }
}
