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
    Identity,
    Relays,
    KeyPackages,
    Notifications,
    Stickers,
    SecurityPrivacy,
    Donate,
}
