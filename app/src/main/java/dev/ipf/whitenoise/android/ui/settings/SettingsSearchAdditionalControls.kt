package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.SwipeBinding
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail

/** Contributions from enum-driven controls and small nested destinations. */
@Suppress("LongMethod") // Declarative contributions, including existing enum-owned controls.
internal fun settingsSearchAdditionalControls(): List<SettingsSearchEntry> =
    buildList {
        addAll(settingsSearchStableActions())

        @Suppress("LongParameterList") // Named catalog fields mirror the typed entry.
        fun control(
            id: String,
            title: Int,
            category: Int,
            destination: SettingsDetail?,
            item: String? = null,
            row: String? = null,
            summary: Int? = null,
            account: Boolean = false,
            push: Boolean = false,
            aliases: Int? = null,
        ) {
            add(
                SettingsSearchEntry(
                    id,
                    title,
                    category,
                    destination,
                    item,
                    row,
                    summary,
                    aliases,
                    accountRequired = account,
                    pushRequired = push,
                ),
            )
        }
        control(
            "Appearance.theme",
            R.string.appearance_theme,
            R.string.appearance,
            SettingsDetail.Appearance,
            "search.Appearance.2",
            "theme",
        )
        control(
            "Dictation.result",
            R.string.dictation_result_title,
            R.string.dictation_settings_title,
            SettingsDetail.Dictation,
            "search.DictationSettings.4",
            "result",
        )
        control(
            "Privacy.security_settings",
            R.string.open_android_security_settings,
            R.string.settings_privacy_security,
            SettingsDetail.DevicePrivacy,
            "search.DevicePrivacy.2",
            "security_settings",
        )
        control(
            "Developer.review_demo",
            R.string.review_demo_section,
            R.string.settings_developer_tools,
            SettingsDetail.Developer,
            "search.Developer.5",
            "review_demo",
        )
        control(
            "Privacy.auto_lock",
            R.string.auto_lock,
            R.string.settings_privacy_security,
            SettingsDetail.DevicePrivacy,
            "search.DevicePrivacy.2",
            "auto_lock",
        )
        control(
            "Notifications.push",
            R.string.notification_delivery_push,
            R.string.notification_delivery_battery,
            SettingsDetail.NotificationDelivery,
            "search.NotificationDelivery.2",
            "fcm",
            R.string.notification_delivery_push_detail,
            push = true,
        )
        control(
            "Notifications.local",
            R.string.local_notifications,
            R.string.notification_delivery_battery,
            SettingsDetail.NotificationDelivery,
            "search.NotificationDelivery.2",
            "local",
            R.string.notification_delivery_local_detail,
        )
        control(
            "Notifications.previews",
            R.string.notification_show_previews,
            R.string.notifications,
            SettingsDetail.Notifications,
            "search.Notifications.1",
            "previews",
            R.string.notification_show_previews_detail,
        )
        control(
            "Notifications.permission",
            R.string.notification_permission_status,
            R.string.notification_delivery_battery,
            SettingsDetail.NotificationDelivery,
            "search.NotificationDelivery.6",
            "permission",
        )
        control(
            "Notifications.battery",
            R.string.notification_battery_policy,
            R.string.notification_delivery_battery,
            SettingsDetail.NotificationDelivery,
            "search.NotificationDelivery.6",
            "battery",
        )
        control(
            "Notifications.sound",
            R.string.notification_tones,
            R.string.notification_sounds_vibration,
            SettingsDetail.NotificationSounds,
            "search.NotificationSounds.5",
            aliases = R.string.settings_search_alias_sound,
        )
        control(
            "Notifications.spoken_audio",
            R.string.notification_quiet_during_spoken_audio,
            R.string.notification_sounds_vibration,
            SettingsDetail.NotificationSounds,
            "search.NotificationSounds.7",
            "spoken-audio",
            R.string.notification_quiet_during_spoken_audio_detail,
        )
        control(
            "Notifications.categories",
            R.string.notification_categories,
            R.string.notification_sounds_vibration,
            SettingsDetail.NotificationSounds,
            "search.NotificationSounds.2",
            summary = R.string.notification_categories_detail,
        )
        MediaAutoDownloadType.entries.forEach { type ->
            control(
                "Data.download.${type.name}",
                type.labelRes,
                R.string.download_automatic,
                SettingsDetail.Data,
                "search.DataUsage.2",
                type.preferenceKey,
                R.string.download_rules_help,
            )
        }
        control(
            "Data.queue",
            R.string.download_queue,
            R.string.settings_data_usage,
            SettingsDetail.Data,
            "search.DataUsage.5",
            "queue",
        )
        SwipeBinding.entries.forEach { binding ->
            val messages = binding == SwipeBinding.MessageLeft || binding == SwipeBinding.MessageRight
            val left = binding == SwipeBinding.MessageLeft || binding == SwipeBinding.ChatLeft
            control(
                "Gestures.${binding.name}",
                if (left) R.string.gestures_left else R.string.gestures_right,
                if (messages) R.string.gestures_messages else R.string.chats,
                SettingsDetail.Gestures,
                if (messages) "search.GestureSettings.2" else "search.GestureSettings.4",
                binding.name,
            )
        }
        control(
            "Gestures.pull_unread",
            R.string.pull_unread_setting,
            R.string.gestures_title,
            SettingsDetail.Gestures,
            "search.GestureSettings.5",
            "pull_unread",
        )
        control(
            "Gestures.reset",
            R.string.reset_to_default,
            R.string.gestures_title,
            SettingsDetail.Gestures,
            "search.GestureSettings.7",
            "reset",
        )
        control(
            "Translation.automatic",
            R.string.translation_automatic,
            R.string.translation_title,
            SettingsDetail.Translation,
            "search.TranslationSettings.1",
            "automatic",
            R.string.translation_privacy_warning,
        )
        control(
            "Translation.target",
            R.string.translation_target_language,
            R.string.translation_title,
            SettingsDetail.Translation,
            "search.TranslationSettings.1",
            "target",
        )
        control("Help.about", R.string.about, R.string.help, SettingsDetail.About)
        control("Help.bug_report", R.string.report_a_bug, R.string.help, SettingsDetail.BugReport)
        control(
            "Account.public_key",
            R.string.public_key,
            R.string.settings_profile_keys,
            SettingsDetail.AccountKeys,
            "search.AccountKeys.4",
            "public_key",
            account = true,
        )
        control(
            "Account.private_key",
            R.string.private_key,
            R.string.settings_profile_keys,
            SettingsDetail.AccountKeys,
            "search.AccountKeys.8",
            "private_key",
            account = true,
        )
        control(
            "About.licenses",
            R.string.open_source_licenses,
            R.string.about_and_licenses,
            SettingsDetail.About,
            "search.HelpAboutScreens.11",
            "licenses",
            R.string.open_source_licenses_subtitle,
        )
        control(
            "About.privacy",
            R.string.privacy_policy,
            R.string.about_and_licenses,
            SettingsDetail.About,
            "search.HelpAboutScreens.11",
            "privacy",
            R.string.privacy_policy_subtitle,
        )
        control(
            "Account.share_connect",
            R.string.share_and_connect,
            R.string.account,
            SettingsDetail.ShareConnect,
            account = true,
        )
        control(
            "Support.relays",
            R.string.support_open_relays,
            R.string.chat_with_support,
            SettingsDetail.SupportRelays,
            account = true,
        )
        control(
            "ChatFolders.create",
            R.string.folder_new_title,
            R.string.chat_folders_title,
            SettingsDetail.ChatFolders,
            row = "create",
            account = true,
        )
        control(
            "Relays.posting",
            R.string.nip_65,
            R.string.relays,
            SettingsDetail.Relays,
            "search.Relays.3",
            "posting",
            account = true,
        )
        control(
            "Relays.receiving",
            R.string.inbox,
            R.string.relays,
            SettingsDetail.Relays,
            "search.Relays.3",
            "receiving",
            account = true,
        )
        control(
            "Relays.add",
            R.string.add_relay,
            R.string.relays,
            SettingsDetail.Relays,
            "search.Relays.6",
            "add",
            account = true,
        )
        control(
            "Relays.restore",
            R.string.restore_default_relays,
            R.string.relays,
            SettingsDetail.Relays,
            "search.Relays.8",
            account = true,
        )
        control("Home.sign_out", R.string.sign_out, R.string.account, null, "SignOut", account = true)
        control(
            "Home.add_profile",
            R.string.settings_add_profile,
            R.string.account,
            null,
            "Profile",
            "add_profile",
            account = true,
        )
        control(
            "Home.switch_profile",
            R.string.settings_switch_profile,
            R.string.account,
            null,
            "Profile",
            "switch_profile",
            account = true,
        )
        control("Home.update_available", R.string.app_updates, R.string.settings, null, "AppUpdateAvailable")
        control("Home.update_controls", R.string.app_updates, R.string.settings, null, "AppUpdateControls")
    }

/** Existing stable actions, including toolbar/pinned controls outside SettingsGroup rows. */
private fun settingsSearchStableActions(): List<SettingsSearchEntry> =
    listOf(
        SettingsSearchEntry(
            id = "ShareConnect.scan_qr_code",
            titleRes = R.string.scan_qr_code,
            categoryRes = R.string.share_and_connect,
            destination = SettingsDetail.ShareConnect,
            rowKey = "scan_qr_code",
            accountRequired = true,
        ),
        SettingsSearchEntry(
            id = "ShareConnect.share",
            titleRes = R.string.share_profile,
            categoryRes = R.string.share_and_connect,
            destination = SettingsDetail.ShareConnect,
            rowKey = "share_profile",
            accountRequired = true,
            additionalAliasLabelRes =
                listOf(
                    R.string.share_profile_url,
                    R.string.share_profile_picture,
                    R.string.share_connect_save_to_gallery,
                ),
        ),
        SettingsSearchEntry(
            id = "ShareConnect.copy_public_key",
            titleRes = R.string.copy_public_key,
            categoryRes = R.string.share_and_connect,
            destination = SettingsDetail.ShareConnect,
            rowKey = "copy_public_key",
            accountRequired = true,
        ),
    ) + settingsSearchOtherStableActions()

private fun settingsSearchOtherStableActions(): List<SettingsSearchEntry> =
    listOf(
        SettingsSearchEntry(
            id = "Help.open_bug_report",
            titleRes = R.string.report_bug_open_github,
            categoryRes = R.string.help,
            destination = SettingsDetail.BugReport,
            rowKey = "open_github",
        ),
        SettingsSearchEntry(
            id = "KeyPackages.refresh",
            titleRes = R.string.refresh,
            categoryRes = R.string.key_packages,
            destination = SettingsDetail.KeyPackages,
            rowKey = "refresh",
            accountRequired = true,
        ),
        SettingsSearchEntry(
            id = "KeyPackages.republish",
            titleRes = R.string.republish,
            categoryRes = R.string.key_packages,
            destination = SettingsDetail.KeyPackages,
            itemKey = "search.KeyPackages.publishing",
            rowKey = "republish",
            accountRequired = true,
        ),
        SettingsSearchEntry(
            id = "KeyPackages.publish_new",
            titleRes = R.string.developer_publish_new,
            categoryRes = R.string.key_packages,
            destination = SettingsDetail.KeyPackages,
            itemKey = "search.KeyPackages.publishing",
            rowKey = "publish_new",
            accountRequired = true,
        ),
        SettingsSearchEntry(
            id = "Support.start_chat",
            titleRes = R.string.start_chat,
            categoryRes = R.string.support,
            destination = SettingsDetail.Support,
            itemKey = "search.SupportScreen.5",
            accountRequired = true,
        ),
    )
