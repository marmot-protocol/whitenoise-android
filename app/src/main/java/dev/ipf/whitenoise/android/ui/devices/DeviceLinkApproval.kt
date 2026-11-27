@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
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
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal const val DEVICE_LINK_LABEL_TAG = "device_link.label"
internal const val DEVICE_LINK_ADD_TO_CHATS_TAG = "device_link.add_to_chats"
internal const val DEVICE_LINK_BRING_HISTORY_TAG = "device_link.bring_history"
internal const val DEVICE_LINK_OPTIONS_TAG = "device_link.options"

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

/** Transient consent for one request, owned above the dialog so covering it cannot reset the sharing choices. */
@Stable
internal class DeviceLinkApprovalDraft(
    name: String = "",
    expanded: Boolean = false,
    add: Boolean = true,
    history: Boolean = true,
) {
    val label = TextFieldState(name)
    var optionsOpen by mutableStateOf(expanded)
    var addToAllChats by mutableStateOf(add)
    var bringHistory by mutableStateOf(history)

    companion object {
        val Saver =
            listSaver<DeviceLinkApprovalDraft, Any>(
                save = { listOf(it.label.text.toString(), it.optionsOpen, it.addToAllChats, it.bringHistory) },
                restore = { values ->
                    DeviceLinkApprovalDraft(
                        name = values[0] as String,
                        expanded = values[1] as Boolean,
                        add = values[2] as Boolean,
                        history = values[3] as Boolean,
                    )
                },
            )
    }
}

/** Retain the transient choices across activity recreation for this composition. */
@Composable
internal fun rememberApprovalDraft() =
    rememberSaveable(
        saver = DeviceLinkApprovalDraft.Saver,
        init = { DeviceLinkApprovalDraft() },
    )

/**
 * A link request as the approving device shows it. While MDK shows the code, the new device's name is on the step
 * itself, so the user sees what it will be called before linking, and an empty field shows the default it gets.
 * Link device stays one tap: the device joins the account's chats with their history (both on, as MDK offers them),
 * and Options opens those two choices for anyone who wants to change them. The choices stay as entered if a
 * submission fails. [onLink] receives them, [onDecline] is Not now and [onClose] leaves without answering.
 *
 * Link can be tapped only [DEVICE_LINK_ARM_DELAY_MS] after the code first appears. A [repeated] request offers no
 * Link at all: it warns instead, with Don't link ([onDecline]) and Review devices ([onReviewDevices]).
 */
@Composable
@Suppress("LongParameterList") // The request's state, its two answers, leaving, and the warning's devices route.
internal fun DeviceLinkRequestContent(
    state: DeviceLinkPromptStateFfi,
    running: DeviceLinkCommand?,
    onLink: (DeviceLinkChoices) -> Unit,
    onDecline: () -> Unit,
    onClose: () -> Unit,
    failureMessage: Int? = null,
    draft: DeviceLinkApprovalDraft = rememberApprovalDraft(),
    repeated: Boolean = false,
    onReviewDevices: () -> Unit = onClose,
) {
    val label = draft.label
    // MDK requires a name. The approving device supplies the default, shown in the empty field.
    val placeholder = stringResource(R.string.link_request_default_label)
    val name =
        label.text
            .toString()
            .trim()
            .ifEmpty { placeholder }
    val nameFits = deviceLabelFits(name)
    val warning = repeatedLinkRequestStep(state).takeIf { repeated }
    val step = warning ?: deviceLinkRequestStep(state) { IdentityFormatter.clockTime(it) }
    var codeVisible by remember(step.code) { mutableStateOf(false) }
    val armed = rememberLinkArmed(step.code, codeVisible)
    DeviceLinkStepLayout(
        screenTitle = stringResource(R.string.link_request_title),
        step = step,
        running = running,
        onBack = onClose,
        primaryEnabled = step.primary != DeviceLinkAction.Link || (nameFits && armed),
        failureMessage = failureMessage,
        onCodeVisible = { codeVisible = true },
        onAction = { action ->
            when (action) {
                DeviceLinkAction.Link -> {
                    if (nameFits && armed) {
                        onLink(DeviceLinkChoices(name, draft.addToAllChats, draft.bringHistory))
                    }
                }
                DeviceLinkAction.NotNow, DeviceLinkAction.DontLink -> onDecline()
                DeviceLinkAction.ReviewDevices -> onReviewDevices()
                else -> onClose()
            }
        },
    ) {
        if (warning == null && state is DeviceLinkPromptStateFfi.Comparing) {
            DeviceLinkNameField(
                label = label,
                placeholder = placeholder,
                tooLong = stringResource(R.string.link_request_label_too_long).takeUnless { nameFits },
                enabled = running == null,
            )
            DeviceLinkOptionsRow(onToggle = { draft.optionsOpen = !draft.optionsOpen })
            if (draft.optionsOpen) {
                DeviceLinkSharingChoices(
                    addToAllChats = draft.addToAllChats,
                    bringHistory = draft.bringHistory,
                    enabled = running == null,
                    onAddToAllChats = { draft.addToAllChats = it },
                    onBringHistory = { draft.bringHistory = it },
                )
            }
        }
    }
}

/** The row that opens and closes the two sharing choices, saying what they cover while they stay closed. */
@Composable
private fun DeviceLinkOptionsRow(onToggle: () -> Unit) {
    SettingsGroup {
        row("options") { context ->
            SettingsAction(
                context = context,
                title = stringResource(R.string.link_request_options),
                subtitle = stringResource(R.string.link_request_options_detail),
                onClick = onToggle,
                modifier = Modifier.testTag(DEVICE_LINK_OPTIONS_TAG),
                explanation = stringResource(R.string.explanation_link_request_options),
                leading = { SettingsLeadingIcon(R.drawable.ic_expand_more) },
            )
        }
    }
}

/**
 * The new device's name. Empty, it shows [placeholder], the name the device gets when the user enters none.
 * [tooLong] is the message for a name over MDK's limit, or null when the name fits.
 */
@Composable
private fun DeviceLinkNameField(
    label: TextFieldState,
    placeholder: String,
    tooLong: String?,
    enabled: Boolean,
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
        placeholder = { Text(placeholder) },
        // A name of only spaces is sent as the default, so the field empties and shows that default.
        inputTransformation = { if (length > 0 && asCharSequence().isBlank()) replace(0, length, "") },
        supportingText = tooLong?.let { message -> { Text(message) } },
        errorMessage = tooLong,
        lineLimits = TextFieldLineLimits.SingleLine,
    )
}

/** The approving user's two choices: whether the new device joins the account's chats and gets their history. */
@Composable
private fun DeviceLinkSharingChoices(
    addToAllChats: Boolean,
    bringHistory: Boolean,
    enabled: Boolean,
    onAddToAllChats: (Boolean) -> Unit,
    onBringHistory: (Boolean) -> Unit,
) {
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
    failureMessage: Int? = null,
) {
    DeviceLinkStepLayout(
        screenTitle = stringResource(R.string.link_device_this_title),
        step = step,
        running = running,
        onBack = onBack,
        onAction = onAction,
        failureMessage = failureMessage,
    )
}
