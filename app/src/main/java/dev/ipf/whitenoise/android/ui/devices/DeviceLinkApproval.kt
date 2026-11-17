@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.DeviceLinkChoices
import dev.ipf.whitenoise.android.state.DeviceLinkCommand
import dev.ipf.whitenoise.android.state.deviceLabelFits
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val DEVICE_LINK_LABEL_TAG = "device_link.label"
internal const val DEVICE_LINK_ADD_TO_CHATS_TAG = "device_link.add_to_chats"
internal const val DEVICE_LINK_BRING_HISTORY_TAG = "device_link.bring_history"

/**
 * The step for a link request on a device already signed in. Link device and Not now are offered only while MDK
 * shows the code to compare. Every other state explains why linking is not offered or how it turned out, with a way
 * to close that answers nothing. [retryTime] renders the moment MDK will accept requests again.
 */
internal fun deviceLinkRequestStep(
    state: DeviceLinkPromptStateFfi,
    retryTime: (ULong) -> String,
): DeviceLinkStep {
    val title = R.string.link_request_title
    return when (state) {
        DeviceLinkPromptStateFfi.Preparing ->
            DeviceLinkStep(title, R.string.link_request_preparing, secondary = DeviceLinkAction.Close)
        is DeviceLinkPromptStateFfi.Comparing ->
            DeviceLinkStep(
                title = R.string.link_device_compare_title,
                detail = R.string.link_request_compare_detail,
                code = state.code,
                primary = DeviceLinkAction.Link,
                secondary = DeviceLinkAction.NotNow,
            )
        DeviceLinkPromptStateFfi.CompetingRequests ->
            DeviceLinkStep(title, R.string.link_request_competing, secondary = DeviceLinkAction.Close)
        is DeviceLinkPromptStateFfi.RateLimited ->
            DeviceLinkStep(
                title = title,
                detail = R.string.link_request_rate_limited,
                detailArgs = listOf(retryTime(state.retryAt)),
                secondary = DeviceLinkAction.Close,
            )
        is DeviceLinkPromptStateFfi.CannotLink ->
            DeviceLinkStep(title, R.string.link_request_cannot_link, secondary = DeviceLinkAction.Close)
        DeviceLinkPromptStateFfi.TooManyDevices ->
            DeviceLinkStep(title, R.string.link_request_too_many, secondary = DeviceLinkAction.Close)
        DeviceLinkPromptStateFfi.Linking ->
            DeviceLinkStep(title, R.string.link_request_linking, secondary = DeviceLinkAction.Close)
        DeviceLinkPromptStateFfi.Linked ->
            DeviceLinkStep(title, R.string.link_request_linked, primary = DeviceLinkAction.Done)
        is DeviceLinkPromptStateFfi.AddedElsewhere -> addedElsewhereStep(state)
        is DeviceLinkPromptStateFfi.Ended ->
            DeviceLinkStep(title, deviceLinkEndReason(state.reason), primary = DeviceLinkAction.Close)
    }
}

/** "<device> was added by <device>", or the unnamed wording when the roster lacks either name. */
private fun addedElsewhereStep(state: DeviceLinkPromptStateFfi.AddedElsewhere): DeviceLinkStep {
    val named = state.deviceLabel.isNotBlank() && state.addedByLabel.isNotBlank()
    return DeviceLinkStep(
        title = R.string.link_request_title,
        detail = if (named) R.string.link_request_added_elsewhere else R.string.link_request_added_elsewhere_unnamed,
        detailArgs = if (named) listOf(state.deviceLabel, state.addedByLabel) else emptyList(),
        primary = DeviceLinkAction.Done,
    )
}

/**
 * A link request as the approving device shows it. While MDK shows the code, the user names the new device and
 * chooses whether it joins the account's chats and receives their history (both on, as MDK offers them) before
 * Link device. The choices stay as entered if a submission fails. [onLink] receives them; [onDecline] is Not now;
 * [onClose] leaves without answering.
 */
@Composable
internal fun DeviceLinkRequestContent(
    state: DeviceLinkPromptStateFfi,
    running: DeviceLinkCommand?,
    onLink: (DeviceLinkChoices) -> Unit,
    onDecline: () -> Unit,
    onClose: () -> Unit,
) {
    val label = rememberTextFieldState()
    var addToAllChats by rememberSaveable { mutableStateOf(true) }
    var bringHistory by rememberSaveable { mutableStateOf(true) }
    val name = label.text.toString().trim()
    val nameFits = deviceLabelFits(name)
    val step = deviceLinkRequestStep(state) { IdentityFormatter.clockTime(it) }
    DeviceLinkStepLayout(
        screenTitle = stringResource(R.string.link_request_title),
        step = step,
        running = running,
        onBack = onClose,
        primaryEnabled = nameFits,
        onAction = { action ->
            when (action) {
                DeviceLinkAction.Link -> onLink(DeviceLinkChoices(name, addToAllChats, bringHistory))
                DeviceLinkAction.NotNow -> onDecline()
                else -> onClose()
            }
        },
    ) {
        if (state is DeviceLinkPromptStateFfi.Comparing) {
            DeviceLinkChoiceFields(
                label = label,
                tooLong = stringResource(R.string.link_request_label_too_long).takeUnless { nameFits },
                addToAllChats = addToAllChats,
                bringHistory = bringHistory,
                enabled = running == null,
                onAddToAllChats = { addToAllChats = it },
                onBringHistory = { bringHistory = it },
            )
        }
    }
}

/**
 * The approving user's choices: the new device's name, whether it joins the account's chats and whether it
 * receives their history. [tooLong] is the message for a name over MDK's limit, or null when the name fits.
 */
@Suppress("LongParameterList") // One flat set of field values and their two change callbacks.
@Composable
private fun DeviceLinkChoiceFields(
    label: TextFieldState,
    tooLong: String?,
    addToAllChats: Boolean,
    bringHistory: Boolean,
    enabled: Boolean,
    onAddToAllChats: (Boolean) -> Unit,
    onBringHistory: (Boolean) -> Unit,
) {
    WhiteNoiseTextField(
        state = label,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                .testTag(DEVICE_LINK_LABEL_TAG),
        label = { Text(stringResource(R.string.link_request_label)) },
        supportingText = tooLong?.let { message -> { Text(message) } },
        errorMessage = tooLong,
        lineLimits = TextFieldLineLimits.SingleLine,
    )
    SettingsGroup {
        row("add_to_chats") { context ->
            SettingsSwitch(
                context = context,
                title = stringResource(R.string.link_request_add_to_chats),
                checked = addToAllChats,
                onCheckedChange = onAddToAllChats,
                modifier = Modifier.testTag(DEVICE_LINK_ADD_TO_CHATS_TAG),
                explanation = stringResource(R.string.link_request_add_to_chats_detail),
                subtitle = stringResource(R.string.link_request_add_to_chats_detail),
                enabled = enabled,
                leading = { SettingsLeadingIcon(R.drawable.ic_settings_chat_bubble_outline) },
            )
        }
        row("bring_history") { context ->
            SettingsSwitch(
                context = context,
                title = stringResource(R.string.link_request_bring_history),
                checked = bringHistory,
                onCheckedChange = onBringHistory,
                modifier = Modifier.testTag(DEVICE_LINK_BRING_HISTORY_TAG),
                explanation = stringResource(R.string.link_request_bring_history_detail),
                subtitle = stringResource(R.string.link_request_bring_history_detail),
                enabled = enabled,
                leading = { SettingsLeadingIcon(R.drawable.ic_history) },
            )
        }
    }
}

/** Linking as the new device shows it, or the waiting step while MDK has not answered the start yet. */
@Composable
internal fun LinkThisDeviceContent(
    step: DeviceLinkStep,
    running: DeviceLinkCommand?,
    onBack: () -> Unit,
    onAction: (DeviceLinkAction) -> Unit,
) {
    DeviceLinkStepLayout(
        screenTitle = stringResource(R.string.link_device_this_title),
        step = step,
        running = running,
        onBack = onBack,
        onAction = onAction,
    )
}
