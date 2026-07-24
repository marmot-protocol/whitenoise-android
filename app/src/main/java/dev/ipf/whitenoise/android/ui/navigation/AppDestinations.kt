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
    ChatBubbleColors,
    FontSize,
    Data,
    Profile,
    Identity,
    Relays,
    KeyPackages,
    Notifications,
    SecurityPrivacy,
    Donate,
    TextToSpeech,
    ChatFolders,
}
