package dev.ipf.whitenoise.android.ui.navigation

/**
 * The shell entries for [section] above the chat list, built from the Settings pages as the stack holds them, so Back
 * retraces the pages that were actually opened (#4095). Diagnostics sits above the Settings page it was opened from.
 */
internal fun settingsSectionEntries(
    section: MainSection,
    settingsStack: List<ShellRoute.Settings>,
): List<ShellRoute> =
    when (section) {
        MainSection.Chats -> emptyList()
        MainSection.Settings -> settingsStack
        MainSection.Diagnostics -> settingsStack + ShellRoute.Diagnostics
    }

/**
 * The event that moves Settings to [next], the full stack the Settings screen asks for: its top page and argument,
 * with the pages between home and that top as the real opener chain.
 */
internal fun settingsStackChange(next: List<ShellRoute.Settings>): ShellNavEvent.ChangeSettingsDetail {
    val top = next.last()
    return ShellNavEvent.ChangeSettingsDetail(top.detail, top.arg, next.drop(1).dropLast(1))
}
