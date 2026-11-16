@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.HeaderShortcut
import dev.ipf.whitenoise.android.state.HeaderShortcutPreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.headerShortcutPreferences

/** Settings → Chats → Chat header: which optional buttons sit in every chat header on this device. */
@Composable
internal fun ChatHeaderSettingsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val preferences = appState.headerShortcutPreferences
    ChatHeaderSettingsContent(preferences.shortcuts, preferences::toggle, onBack)
}

/** Check marks like the composer shortcut list; a full header disables the unchecked rows. */
@Composable
internal fun ChatHeaderSettingsContent(
    shortcuts: List<HeaderShortcut>,
    onToggle: (HeaderShortcut) -> Unit,
    onBack: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.header_shortcuts_title), onBack = onBack) {
        SettingsList {
            item(key = "search.ChatHeaderSettings.1") {
                SettingsGroup {
                    for (shortcut in HeaderShortcut.entries) {
                        val checked = shortcut in shortcuts
                        // A full header keeps its choices; another one waits until the person unchecks one.
                        val full = !checked && shortcuts.size >= HeaderShortcutPreferences.MAX
                        val shortcutRow = headerShortcutRow(shortcut)
                        row(shortcutRow.key) { context ->
                            SettingsCheckbox(
                                context,
                                stringResource(shortcutRow.titleRes),
                                checked,
                                { onToggle(shortcut) },
                                modifier = Modifier.testTag("chatHeader.${shortcutRow.key}"),
                                explanation = stringResource(shortcutRow.explanationRes),
                                subtitle = if (full) stringResource(R.string.header_shortcuts_limit) else null,
                                enabled = !full,
                                leading = { SettingsLeadingIcon(shortcutRow.iconRes) },
                            )
                        }
                    }
                }
            }
            item(key = "search.ChatHeaderSettings.2") {
                SettingsCallout(text = stringResource(R.string.header_shortcuts_hint))
            }
        }
    }
}

/** One settings row per header shortcut. */
internal class HeaderShortcutRow(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val explanationRes: Int,
    @DrawableRes val iconRes: Int,
)

/** Maps each shortcut to its settings row. */
internal fun headerShortcutRow(shortcut: HeaderShortcut): HeaderShortcutRow =
    when (shortcut) {
        HeaderShortcut.Search ->
            HeaderShortcutRow(
                "search",
                R.string.header_shortcut_search,
                R.string.explanation_header_shortcut_search,
                R.drawable.ic_search,
            )
        HeaderShortcut.MuteUnmute ->
            HeaderShortcutRow(
                "mute",
                R.string.gestures_mute_unmute,
                R.string.explanation_header_shortcut_mute,
                R.drawable.ic_notifications_off,
            )
        HeaderShortcut.ReadAloud ->
            HeaderShortcutRow(
                "read_aloud",
                R.string.tts_auto_read_title,
                R.string.explanation_header_shortcut_read_aloud,
                R.drawable.ic_record_voice_over,
            )
    }
