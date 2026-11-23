package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail

/**
 * Keyed items of the Settings home list, in display order; the viewport is restored by section key.
 * The group sections render an unheaded connected group of [SettingsHomeRow]s; every other item renders its own
 * content. [searchCategoryRes] labels a group's rows in Settings search only.
 */
internal enum class SettingsHomeSection(
    val isGroup: Boolean = false,
    @param:StringRes val searchCategoryRes: Int? = null,
) {
    /** Profile row, Account, then add or switch profile, in one group. */
    Profile,

    /** A newer version is waiting, so the offer sits where a reader lands. */
    AppUpdateAvailable,

    AppPreferences(isGroup = true, searchCategoryRes = R.string.app_preferences),

    /** Agents and Help & support close the list of destinations. */
    Assist(isGroup = true),
    SignOut,

    /** Nothing to install: the check stays reachable, quietly, beside the version it reports on. */
    AppUpdateControls,
    Version,
}

/**
 * Rows of the Settings home in display order, grouped under the [section] each one belongs to. Each row
 * carries its label, Material Symbols icon and test-tag suffix, the [detail] it opens, and the
 * [explanationRes] shown when the row is held.
 */
internal enum class SettingsHomeRow(
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int,
    val iconTag: String,
    val detail: SettingsDetail,
    val section: SettingsHomeSection,
    @param:StringRes val explanationRes: Int,
) {
    Account(
        R.string.account,
        R.drawable.ic_settings_account_circle,
        "account",
        SettingsDetail.Account,
        SettingsHomeSection.Profile,
        R.string.explanation_settings_account,
    ),
    Notifications(
        R.string.notifications,
        R.drawable.ic_settings_notifications,
        "notifications",
        SettingsDetail.Notifications,
        SettingsHomeSection.AppPreferences,
        R.string.explanation_settings_notifications,
    ),
    Chats(
        R.string.chats,
        R.drawable.ic_settings_chat_bubble_outline,
        "chats",
        SettingsDetail.Chats,
        SettingsHomeSection.AppPreferences,
        R.string.explanation_settings_chats,
    ),
    Appearance(
        R.string.appearance,
        R.drawable.ic_settings_contrast,
        "appearance",
        SettingsDetail.Appearance,
        SettingsHomeSection.AppPreferences,
        R.string.explanation_settings_appearance,
    ),
    VoiceLanguage(
        R.string.settings_voice_language,
        R.drawable.ic_translate,
        "voice_language",
        SettingsDetail.VoiceLanguage,
        SettingsHomeSection.AppPreferences,
        R.string.explanation_settings_voice_language,
    ),
    PrivacySecurity(
        R.string.settings_privacy_security,
        R.drawable.ic_settings_front_hand,
        "privacy_security",
        SettingsDetail.DevicePrivacy,
        SettingsHomeSection.AppPreferences,
        R.string.explanation_settings_privacy_security,
    ),
    DataUsage(
        R.string.settings_data_usage,
        R.drawable.ic_settings_hard_drive,
        "data_usage",
        SettingsDetail.Data,
        SettingsHomeSection.AppPreferences,
        R.string.explanation_settings_data_usage,
    ),
    AiAgents(
        R.string.ai_agents,
        R.drawable.ic_ai_agents,
        "ai_agents",
        SettingsDetail.AiAgents,
        SettingsHomeSection.Assist,
        R.string.explanation_settings_ai_agents,
    ),
    HelpSupport(
        R.string.settings_help_support,
        R.drawable.ic_help,
        "help_support",
        SettingsDetail.HelpSupport,
        SettingsHomeSection.Assist,
        R.string.explanation_settings_help_support,
    ),
}

/** One connected group of the Settings home: its section key and rows in display order. */
internal data class SettingsHomeGroup(
    val section: SettingsHomeSection,
    val rows: List<SettingsHomeRow>,
)

/**
 * Saveable position of the Settings home list, anchored by stable section key
 * so optional sections can change without restoring an unrelated viewport.
 */
internal data class SettingsHomeViewport(
    val section: SettingsHomeSection?,
    val fallbackIndex: Int,
    val scrollOffset: Int,
) {
    /** Resolves the saved key first and uses a clamped index only as fallback. */
    fun resolveIndex(sections: List<SettingsHomeSection>): Int {
        if (sections.isEmpty()) return 0
        val keyedIndex = section?.let(sections::indexOf)?.takeIf { it >= 0 }
        return keyedIndex ?: fallbackIndex.coerceIn(0, sections.lastIndex)
    }

    companion object {
        val Top = SettingsHomeViewport(SettingsHomeSection.Profile, fallbackIndex = 0, scrollOffset = 0)

        /** Saver used only for the lifecycle-scoped Settings visit. */
        val Saver: Saver<SettingsHomeViewport, Any> =
            listSaver(
                save = { listOf(it.section?.name.orEmpty(), it.fallbackIndex, it.scrollOffset) },
                restore = { saved ->
                    SettingsHomeViewport(
                        section =
                            saved[0]
                                .toString()
                                .takeIf(String::isNotEmpty)
                                ?.let { name -> runCatching { SettingsHomeSection.valueOf(name) }.getOrNull() },
                        fallbackIndex = saved[1] as Int,
                        scrollOffset = saved[2] as Int,
                    )
                },
            )
    }
}

/** Shell-level lifecycle events that either retain or retire a Settings visit. */
internal enum class SettingsHomeViewportEvent {
    OpenDiagnostics,
    OpenNewSettingsVisit,
    ExitSettings,
    OpenConversation,
    ChangeAccount,
}

/** Applies the Settings-visit ownership policy to a captured home viewport. */
internal fun reduceSettingsHomeViewport(
    current: SettingsHomeViewport,
    event: SettingsHomeViewportEvent,
): SettingsHomeViewport =
    when (event) {
        SettingsHomeViewportEvent.OpenDiagnostics -> current
        SettingsHomeViewportEvent.OpenNewSettingsVisit,
        SettingsHomeViewportEvent.ExitSettings,
        SettingsHomeViewportEvent.OpenConversation,
        SettingsHomeViewportEvent.ChangeAccount,
        -> SettingsHomeViewport.Top
    }

@Stable
internal data class SettingsHomeState(
    val sections: List<SettingsHomeSection>,
    val groups: List<SettingsHomeGroup>,
    val showProfileHeader: Boolean,
)

/** The group sections in display order, derived from the enum rather than from fixed indices. */
private val settingsHomeGroupSections: List<SettingsHomeSection>
    get() = SettingsHomeSection.entries.filter { it.isGroup }

/**
 * Sections and groups of the Settings home; the account-bound sections appear only with an active account.
 * Every group section always appears, with the rows that declare it as their [SettingsHomeRow.section].
 */
internal fun settingsHomeState(
    hasActiveAccount: Boolean,
    selfUpdateEnabled: Boolean,
    updateAvailable: Boolean = false,
): SettingsHomeState =
    SettingsHomeState(
        sections =
            buildList {
                if (hasActiveAccount) add(SettingsHomeSection.Profile)
                // Store-managed builds own updates; off-store redirects violate policy.
                //
                // An update worth installing is the only thing here a reader has to act on, so it leads.
                // With nothing to install the same row would be a standing callout for news that never
                // changes, so it drops to the foot of the screen beside the version it reports on. A
                // dismissed-but-available update still counts as available: dismissal belongs to the
                // banner, and Settings should keep offering what the banner stopped interrupting with.
                if (selfUpdateEnabled && updateAvailable) add(SettingsHomeSection.AppUpdateAvailable)
                addAll(settingsHomeGroupSections)
                if (hasActiveAccount) add(SettingsHomeSection.SignOut)
                if (selfUpdateEnabled && !updateAvailable) add(SettingsHomeSection.AppUpdateControls)
                add(SettingsHomeSection.Version)
            },
        groups =
            settingsHomeGroupSections.map { section ->
                SettingsHomeGroup(
                    section = section,
                    rows = SettingsHomeRow.entries.filter { it.section == section },
                )
            },
        showProfileHeader = hasActiveAccount,
    )

// Parent of a settings detail for back navigation; null means the Settings
// home. Kept pure so the back-stack shape (Key Packages → Developer tools → Help & support → home,
// Sounds & vibration → Notifications → home) is unit-testable without Compose.

/** Parent destination a nested settings detail returns to. */
internal fun settingsDetailParent(detail: SettingsDetail): SettingsDetail? =
    when (detail) {
        SettingsDetail.Profile,
        SettingsDetail.AccountKeys,
        SettingsDetail.Relays,
        -> SettingsDetail.Account
        SettingsDetail.ActionColor,
        SettingsDetail.ChatBubbleColors,
        -> SettingsDetail.Appearance
        SettingsDetail.ChatFolders,
        SettingsDetail.Gestures,
        SettingsDetail.Composer,
        SettingsDetail.ChatHeader,
        SettingsDetail.CustomEmoji,
        -> SettingsDetail.Chats
        SettingsDetail.EmojiSets -> SettingsDetail.CustomEmoji
        SettingsDetail.Language,
        SettingsDetail.VoiceNotes,
        SettingsDetail.TextToSpeech,
        SettingsDetail.Translation,
        SettingsDetail.Dictation,
        -> SettingsDetail.VoiceLanguage
        SettingsDetail.NotificationSounds,
        SettingsDetail.NotificationDelivery,
        -> SettingsDetail.Notifications
        SettingsDetail.DiagnosticsImprovements,
        SettingsDetail.BlockedUsers,
        -> SettingsDetail.DevicePrivacy
        SettingsDetail.Help,
        SettingsDetail.Support,
        SettingsDetail.Donate,
        SettingsDetail.Developer,
        -> SettingsDetail.HelpSupport
        SettingsDetail.About,
        SettingsDetail.BugReport,
        -> SettingsDetail.Help
        SettingsDetail.SupportRelays -> SettingsDetail.Support
        SettingsDetail.KeyPackages,
        SettingsDetail.QuarantinedGroups,
        -> SettingsDetail.Developer
        SettingsDetail.ShareConnect,
        SettingsDetail.Account,
        SettingsDetail.Appearance,
        SettingsDetail.Chats,
        SettingsDetail.VoiceLanguage,
        SettingsDetail.HelpSupport,
        SettingsDetail.Notifications,
        SettingsDetail.DevicePrivacy,
        SettingsDetail.Data,
        SettingsDetail.AiAgents,
        -> null
        SettingsDetail.ManageStorage -> SettingsDetail.Data
    }
