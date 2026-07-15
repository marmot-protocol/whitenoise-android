package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal enum class MainSection {
    Chats,
    Settings,
    Diagnostics,
}

internal enum class SettingsDetail {
    Appearance,
    FontSize,
    Data,
    Profile,
    AccountKeys,
    Relays,
    KeyPackages,
    Notifications,
    DevicePrivacy,
    Help,
    About,
    Developer,
    Donate,
}

private val defaultSettingsHomeDetails =
    listOf(
        SettingsDetail.Notifications,
        SettingsDetail.DevicePrivacy,
        SettingsDetail.Data,
        SettingsDetail.Appearance,
        SettingsDetail.Relays,
        SettingsDetail.AccountKeys,
        SettingsDetail.Help,
    )

private val developerSettingsHomeDetails = defaultSettingsHomeDetails + SettingsDetail.Developer

internal fun settingsHomeDetails(developerMode: Boolean): List<SettingsDetail> = if (developerMode) developerSettingsHomeDetails else defaultSettingsHomeDetails

internal fun shouldUnlockDeveloperMode(versionTapCount: Int): Boolean = versionTapCount >= 7
