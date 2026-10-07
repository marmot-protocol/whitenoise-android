@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.SwipeAction
import dev.ipf.whitenoise.android.state.SwipeBinding
import dev.ipf.whitenoise.android.state.SwipePreferenceState
import dev.ipf.whitenoise.android.state.SwipePreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SpeechChoice
import dev.ipf.whitenoise.android.ui.common.SpeechChoiceDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme

/** Settings owns only four Android input preferences; command eligibility stays with each current row. */
@Composable
internal fun GestureSettingsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    GestureSettingsContent(
        appState.swipePreferences.state,
        appState.swipePreferences::set,
        appState.swipePreferences::reset,
        onBack,
    )
}

/** The stateless settings body exposes the currently effective default and physical-direction choices. */
@Composable
@Suppress("LongMethod") // Two bounded settings groups and their choice dialog.
internal fun GestureSettingsContent(
    state: SwipePreferenceState,
    onChange: (SwipeBinding, SwipeAction) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var picker by rememberSaveable { mutableStateOf<SwipeBinding?>(null) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    SettingsScaffold(title = stringResource(R.string.gestures_title), onBack = onBack) {
        SettingsList {
            item { SettingsSection(stringResource(R.string.gestures_messages)) }
            item {
                SettingsGroup {
                    for (binding in listOf(SwipeBinding.MessageLeft, SwipeBinding.MessageRight)) {
                        row(binding.name) { rowContext ->
                            SettingsAction(
                                context = rowContext,
                                title = bindingTitle(binding),
                                subtitle = bindingSummary(state, binding, rtl),
                                modifier = Modifier.testTag("gestures." + binding.name),
                                onClick = { picker = binding },
                            )
                        }
                    }
                }
            }
            item { SettingsSection(stringResource(R.string.chats)) }
            item {
                SettingsGroup {
                    for (binding in listOf(SwipeBinding.ChatLeft, SwipeBinding.ChatRight)) {
                        row(binding.name) { rowContext ->
                            SettingsAction(
                                context = rowContext,
                                title = bindingTitle(binding),
                                subtitle = bindingSummary(state, binding, rtl),
                                modifier = Modifier.testTag("gestures." + binding.name),
                                onClick = { picker = binding },
                            )
                        }
                    }
                }
            }
            item { SettingsCallout(text = stringResource(R.string.gestures_physical_hint)) }
            item {
                SettingsGroup {
                    row("reset") { rowContext ->
                        SettingsAction(
                            context = rowContext,
                            title = stringResource(R.string.reset_to_default),
                            modifier = Modifier.testTag("gestures.reset"),
                            onClick = onReset,
                        )
                    }
                }
            }
        }
    }
    picker?.let { binding ->
        SpeechChoiceDialog(
            title = bindingTitle(binding),
            onDismiss = { picker = null },
            choices =
                SwipePreferences.choices(binding).map { action ->
                    SpeechChoice(
                        title = actionTitle(action),
                        selected = state.choice(binding) == action,
                        onClick = {
                            onChange(binding, action)
                            picker = null
                        },
                    )
                },
        )
    }
}

/** Explains the effective mirrored default while explicit bindings retain their physical direction. */
@Composable
private fun bindingSummary(
    state: SwipePreferenceState,
    binding: SwipeBinding,
    rtl: Boolean,
): String {
    val action = state.resolved(binding, rtl)
    return if (state.choice(binding) == SwipeAction.Default) {
        stringResource(R.string.gestures_default_summary, actionTitle(action))
    } else {
        actionTitle(action)
    }
}

/** The same accessible action name appears in settings and the swipe cue. */
@Composable
internal fun actionTitle(action: SwipeAction): String =
    stringResource(
        when (action) {
            SwipeAction.Default -> R.string.gestures_default
            SwipeAction.Off -> R.string.gestures_off
            SwipeAction.Reply -> R.string.reply
            SwipeAction.Forward -> R.string.forward
            SwipeAction.React -> R.string.gestures_react
            SwipeAction.ReadUnread -> R.string.gestures_read_unread
            SwipeAction.MuteUnmute -> R.string.gestures_mute_unmute
            SwipeAction.PinUnpin -> R.string.gestures_pin_unpin
        },
    )

/** Names the movement consistently in both sections, independent of text direction. */
@Composable
private fun bindingTitle(binding: SwipeBinding): String =
    stringResource(
        when (binding) {
            SwipeBinding.MessageLeft, SwipeBinding.ChatLeft -> R.string.gestures_left
            SwipeBinding.MessageRight, SwipeBinding.ChatRight -> R.string.gestures_right
        },
    )

/** Shows the requested left-reply/right-forward setup alongside opt-in chat actions. */
@Preview
@Composable
private fun GestureSettingsPreview() {
    WhiteNoiseTheme {
        GestureSettingsContent(
            SwipePreferenceState(
                SwipeAction.Reply,
                SwipeAction.Forward,
                SwipeAction.ReadUnread,
                SwipeAction.MuteUnmute,
            ),
            { _, _ -> },
            {},
            {},
        )
    }
}
