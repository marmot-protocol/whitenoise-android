@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.DeviceLinkEmojiFfi
import dev.ipf.marmotkit.DeviceLinkEndReasonFfi
import dev.ipf.marmotkit.NewDeviceLinkStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DeviceLinkCommand
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.settings.SettingsCallout
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val DEVICE_LINK_CODE_TAG = "device_link.code"
internal const val DEVICE_LINK_STEP_TAG = "device_link.step"
internal const val DEVICE_LINK_PRIMARY_TAG = "device_link.primary"
internal const val DEVICE_LINK_SECONDARY_TAG = "device_link.secondary"
internal const val DEVICE_LINK_FAILED_TAG = "device_link.failed"

/** What the user can do on a link step. Each maps to one native call or to leaving the step. */
internal enum class DeviceLinkAction(
    @param:StringRes val label: Int,
) {
    ConfirmMatch(R.string.link_device_they_match),
    Cancel(R.string.cancel),
    TryAgain(R.string.link_device_try_again),
    Link(R.string.link_request_link),
    NotNow(R.string.link_request_not_now),
    Done(R.string.done),
    Close(R.string.close),
}

/**
 * One step of linking as a screen shows it: what to say, the code to compare when MDK supplies one, and at most
 * one main action beside one way out. Every field follows MDK's state; nothing here advances the link.
 */
internal data class DeviceLinkStep(
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
    val detailArgs: List<String> = emptyList(),
    val code: List<DeviceLinkEmojiFfi> = emptyList(),
    val primary: DeviceLinkAction? = null,
    val secondary: DeviceLinkAction? = null,
)

/**
 * The step for MDK's new-device [state]. Waiting, comparing and joining are never shown as finished: only
 * [NewDeviceLinkStateFfi.Linked] says the device is linked, and an ended link names MDK's reason and offers an
 * explicit new attempt instead of resuming the old one.
 */
internal fun newDeviceLinkStep(state: NewDeviceLinkStateFfi): DeviceLinkStep =
    when (state) {
        NewDeviceLinkStateFfi.WaitingForAnswer ->
            DeviceLinkStep(
                title = R.string.link_device_waiting_title,
                detail = R.string.link_device_waiting_detail,
                secondary = DeviceLinkAction.Cancel,
            )
        is NewDeviceLinkStateFfi.Comparing ->
            DeviceLinkStep(
                title = R.string.link_device_compare_title,
                detail = R.string.link_device_compare_detail,
                code = state.code,
                primary = DeviceLinkAction.ConfirmMatch,
                secondary = DeviceLinkAction.Cancel,
            )
        NewDeviceLinkStateFfi.WaitingForOtherDevice ->
            DeviceLinkStep(
                title = R.string.link_device_waiting_other_title,
                detail = R.string.link_device_waiting_other_detail,
                secondary = DeviceLinkAction.Cancel,
            )
        NewDeviceLinkStateFfi.Joining ->
            DeviceLinkStep(
                title = R.string.link_device_joining_title,
                detail = R.string.link_device_joining_detail,
                secondary = DeviceLinkAction.Cancel,
            )
        NewDeviceLinkStateFfi.Linked ->
            DeviceLinkStep(
                title = R.string.link_device_linked_title,
                detail = R.string.link_device_linked_detail,
                primary = DeviceLinkAction.Done,
            )
        is NewDeviceLinkStateFfi.Ended ->
            DeviceLinkStep(
                title = R.string.link_device_this_title,
                detail = deviceLinkEndReason(state.reason),
                primary = DeviceLinkAction.TryAgain,
                secondary = DeviceLinkAction.Close,
            )
    }

/**
 * The step while MDK reports no link for this device. While a request is [starting] it reads as waiting; otherwise
 * nothing was sent, so it offers to try again and never a Cancel that has nothing to cancel.
 */
internal fun notStartedLinkStep(starting: Boolean): DeviceLinkStep =
    if (starting) {
        newDeviceLinkStep(NewDeviceLinkStateFfi.WaitingForAnswer).copy(secondary = DeviceLinkAction.Close)
    } else {
        DeviceLinkStep(
            title = R.string.link_device_this_title,
            detail = R.string.link_device_this_detail,
            primary = DeviceLinkAction.TryAgain,
            secondary = DeviceLinkAction.Close,
        )
    }

/** The sentence for why MDK ended a link. Each reason keeps its own wording so none reads as another. */
@StringRes
internal fun deviceLinkEndReason(reason: DeviceLinkEndReasonFfi): Int =
    when (reason) {
        DeviceLinkEndReasonFfi.CANCELLED -> R.string.link_device_ended_cancelled
        DeviceLinkEndReasonFfi.CANCELLED_ELSEWHERE -> R.string.link_device_ended_cancelled_elsewhere
        DeviceLinkEndReasonFfi.CODES_DIFFER -> R.string.link_device_ended_codes_differ
        DeviceLinkEndReasonFfi.VERIFICATION_FAILED -> R.string.link_device_ended_verification_failed
        DeviceLinkEndReasonFfi.TIMED_OUT -> R.string.link_device_ended_timed_out
        DeviceLinkEndReasonFfi.ANSWERED_ELSEWHERE -> R.string.link_device_ended_answered_elsewhere
        DeviceLinkEndReasonFfi.CANNOT_LINK -> R.string.link_device_ended_cannot_link
        DeviceLinkEndReasonFfi.REJECTED -> R.string.link_device_ended_rejected
        DeviceLinkEndReasonFfi.LINK_FAILED -> R.string.link_device_ended_link_failed
    }

/**
 * MDK's comparison code as large emoji in MDK's order, wrapping onto more lines instead of shrinking or cutting
 * off. Each emoji is announced with its position and MDK's description, so the order survives a screen reader;
 * the code itself is never logged or copied anywhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceLinkCode(
    code: List<DeviceLinkEmojiFfi>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().testTag(DEVICE_LINK_CODE_TAG),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        code.forEachIndexed { index, emoji ->
            val spoken = stringResource(R.string.link_device_emoji_position, index + 1, code.size, emoji.description)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(emoji.emoji, style = MaterialTheme.typography.displaySmall)
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The shared layout of a link step: a heading, one explanation announced when it changes, the comparison code when
 * there is one, any extra [content], then the step's actions. While a command runs the main action shows progress
 * and a second tap submits nothing. [failureMessage] explains a refusal on the step itself.
 */
@Composable
internal fun DeviceLinkStepLayout(
    screenTitle: String,
    step: DeviceLinkStep,
    running: DeviceLinkCommand?,
    onBack: () -> Unit,
    onAction: (DeviceLinkAction) -> Unit,
    primaryEnabled: Boolean = true,
    failureMessage: Int? = null,
    content: @Composable () -> Unit = {},
) {
    SettingsScaffold(title = screenTitle, onBack = onBack) {
        SettingsList(
            modifier = Modifier.testTag(DEVICE_LINK_STEP_TAG),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
        ) {
            val text = Modifier.padding(horizontal = WhiteNoiseSpacing.SettingsSectionInset)
            val block = Modifier.padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
            item(key = "title") {
                Text(
                    stringResource(step.title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = text.semantics { heading() },
                )
            }
            item(key = "detail") {
                Text(
                    stringResource(step.detail, *step.detailArgs.toTypedArray()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = text.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (step.code.isNotEmpty()) item(key = "code") { DeviceLinkCode(step.code, block) }
            item(key = "content") {
                Column(verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField)) { content() }
            }
            if (failureMessage != null) {
                val polite = LiveRegionMode.Polite
                item(key = "failed") {
                    SettingsCallout(
                        text = stringResource(failureMessage),
                        isError = true,
                        modifier = Modifier.testTag(DEVICE_LINK_FAILED_TAG).semantics { liveRegion = polite },
                    )
                }
            }
            item(key = "actions") {
                Column(block, verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
                    DeviceLinkStepActions(step, running, primaryEnabled, onAction)
                }
            }
        }
    }
}

/** The step's main action above its way out; both are disabled while a command runs. */
@Composable
private fun DeviceLinkStepActions(
    step: DeviceLinkStep,
    running: DeviceLinkCommand?,
    primaryEnabled: Boolean,
    onAction: (DeviceLinkAction) -> Unit,
) {
    step.primary?.let { action ->
        WhiteNoiseButton(
            onClick = { onAction(action) },
            enabled = running == null && primaryEnabled,
            loading = running != null && running != DeviceLinkCommand.Cancel && running != DeviceLinkCommand.Decline,
            loadingLabel = stringResource(action.label),
            modifier = Modifier.fillMaxWidth().testTag(DEVICE_LINK_PRIMARY_TAG),
        ) { Text(stringResource(action.label)) }
    }
    step.secondary?.let { action ->
        WhiteNoiseOutlinedButton(
            onClick = { onAction(action) },
            enabled = running == null,
            modifier = Modifier.fillMaxWidth().testTag(DEVICE_LINK_SECONDARY_TAG),
        ) { Text(stringResource(action.label)) }
    }
}
