package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal enum class MainSection {
    Chats,
    Settings,
    Diagnostics,
}

internal enum class SettingsDetail {
    ShareConnect,
    Appearance,
    Composer,
    ChatHeader,
    ActionColor,
    ChatBubbleColors,
    Language,
    CustomEmoji,
    EmojiSets,
    Data,
    ManageStorage,
    Profile,
    AccountKeys,
    Relays,
    Support,
    SupportRelays,
    KeyPackages,
    QuarantinedGroups,
    Notifications,
    DevicePrivacy,
    DiagnosticsImprovements,
    BlockedUsers,
    AiAgents,
    Donate,
    VoiceNotes,
    TextToSpeech,
    Translation,
    Dictation,
    ChatFolders,
    Gestures,
    Help,
    BugReport,
    About,
    Developer,
    Account,
    Chats,
    VoiceLanguage,
    HelpSupport,
    LearnWhiteNoise,
    NotificationSounds,
    NotificationDelivery,
}

internal fun firstRunHelpDetail(support: Boolean): SettingsDetail {
    return if (support) SettingsDetail.Support else SettingsDetail.LearnWhiteNoise
}
