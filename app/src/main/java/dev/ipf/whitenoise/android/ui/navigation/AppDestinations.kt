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
    HistoryBackups,
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

    // Pages that used to swap in place inside their parent (#4095). Each is a destination of its own, and the
    // ones that show one item carry it as the route's argument, see ShellRoute.Settings.arg.

    /** The bundled open-source notices, opened from About. */
    Licenses,

    /** One notice's text; its argument is the notice id. */
    LicenseNotice,

    /** The account's linked devices, opened from Account or a sign-in notice. */
    LinkedDevices,

    /** Notification profiles, opened from Notifications. */
    NotificationProfiles,

    /** One relay's lists and actions; its argument is the relay URL. */
    RelayDetails,

    /** Stored attachments of one chat or the largest overall; its argument is the list id. */
    StoredAttachments,

    /** One emoji set's preview; its argument is the set's coordinate. */
    EmojiSetPreview,
}

internal fun firstRunHelpDetail(team: Boolean) = if (team) SettingsDetail.Support else SettingsDetail.LearnWhiteNoise
