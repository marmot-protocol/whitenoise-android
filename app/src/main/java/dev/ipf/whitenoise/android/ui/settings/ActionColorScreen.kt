package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.outlineButtonColors
import dev.ipf.whitenoise.android.ui.theme.whiteNoiseBaseColorScheme
import dev.ipf.whitenoise.android.ui.theme.withActionColor

/**
 * Action colour editor: a trial accent previewed on this screen only, committed to the active account's colour
 * for the current theme on Save. AMOLED keeps its fixed white accent and shows the notice instead.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ActionColorScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val theme = BubbleTheme.resolve(appState.themeMode, isSystemInDarkTheme())
    val accountRef = appState.activeAccountRef?.takeIf(String::isNotBlank)
    when {
        theme == BubbleTheme.Amoled -> OutlineColorNotice(stringResource(R.string.action_color), onBack)
        accountRef == null ->
            OutlineColorNotice(stringResource(R.string.action_color), onBack, R.string.action_color_no_active_account)
        else -> {
            val initial = appState.actionColorArgb(theme)
            key(accountRef, theme, initial) {
                ActionColorEditor(
                    theme = theme,
                    initial = initial,
                    onBack = onBack,
                    onSave = { appState.updateActionColor(theme, it) },
                )
            }
        }
    }
}

@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun ActionColorEditor(
    theme: BubbleTheme,
    initial: Long?,
    onBack: () -> Unit,
    onSave: (Long?) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(initial) }
    var valid by rememberSaveable { mutableStateOf(true) }
    var reset by rememberSaveable { mutableStateOf(false) }
    var resetRevision by rememberSaveable { mutableIntStateOf(0) }
    val base = whiteNoiseBaseColorScheme(darkTheme = theme == BubbleTheme.Dark)
    // Only this screen sees the trial accent; Save commits it to the owning theme.
    MaterialTheme(colorScheme = base.withActionColor(draft)) {
        SettingsScaffold(
            title = stringResource(R.string.action_color),
            onBack = onBack,
            bottomBar = {
                SettingsBottomAction {
                    WhiteNoiseButton(
                        onClick = {
                            onSave(draft)
                            onBack()
                        },
                        enabled = valid && draft != initial,
                        modifier = Modifier.fillMaxWidth().testTag("action_color.save"),
                    ) { Text(stringResource(R.string.save)) }
                }
            },
        ) {
            SettingsList {
                stickyHeader {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        SettingsGroup {
                            row("preview") { context ->
                                SettingsGroupPanel(context) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.FormField),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            stringResource(R.string.bubble_color_preview),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Button(
                                            onClick = {},
                                            border = amoledOutlineBorder(),
                                            colors = outlineButtonColors(),
                                            modifier = Modifier.testTag("action_color.preview"),
                                        ) { Text(stringResource(R.string.color_preview_action)) }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    SettingsGroup {
                        row("controls") { context ->
                            SettingsGroupPanel(context) {
                                Column(
                                    Modifier.padding(WhiteNoiseSpacing.FormField).testTag("action_color.controls"),
                                    verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
                                ) {
                                    key(resetRevision) {
                                        FullSpectrumColorPicker(
                                            selectedArgb = if (reset) null else initial,
                                            fallbackArgb = base.primary.toOpaqueArgb(),
                                            onColorSelected = { draft = it },
                                            onValidityChanged = { valid = it },
                                        )
                                    }
                                    WhiteNoiseOutlinedButton(
                                        onClick = {
                                            draft = null
                                            valid = true
                                            reset = true
                                            resetRevision++
                                        },
                                        enabled = draft != null || !valid,
                                        modifier = Modifier.fillMaxWidth().testTag("action_color.reset"),
                                    ) { Text(stringResource(R.string.reset_to_default)) }
                                }
                            }
                        }
                    }
                }
                item { SettingsExplainer(stringResource(R.string.action_color_detail, stringResource(theme.labelRes))) }
            }
        }
    }
}

/** The colour editors' fallback frame: the title, Back, and one explainer, no controls. */
@Suppress("FunctionNaming")
@Composable
internal fun OutlineColorNotice(
    title: String,
    onBack: () -> Unit,
    @StringRes text: Int = R.string.appearance_outline_colors_fixed,
) {
    SettingsScaffold(title = title, onBack = onBack) {
        SettingsList { item { SettingsExplainer(stringResource(text)) } }
    }
}

/** The theme's name as the colour explainers quote it. */
internal val BubbleTheme.labelRes: Int
    get() =
        when (this) {
            BubbleTheme.Light -> R.string.theme_light
            BubbleTheme.Dark -> R.string.theme_dark
            BubbleTheme.Amoled -> R.string.theme_amoled
        }

/** Opaque ARGB of a resolved theme colour. */
internal fun Color.toOpaqueArgb(): Long = toArgb().toLong() and OPAQUE_COLOR_MASK

private const val OPAQUE_COLOR_MASK = 0xFFFFFFFFL
