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
    ActionColor,
    ChatBubbleColors,
    Data,
    Profile,
    AccountKeys,
    Relays,
    KeyPackages,
    Notifications,
    DevicePrivacy,
    Donate,
    TextToSpeech,
    ChatFolders,
    Help,
    About,
    Developer,
}
