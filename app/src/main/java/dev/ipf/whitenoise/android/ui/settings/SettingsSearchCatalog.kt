package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail

/** UI-owned, resource-backed metadata. Dynamic rows and private values are deliberately absent. */
@Suppress("LongMethod") // Declarative static UI metadata, kept in catalog order.
internal fun settingsSearchCatalog(): List<SettingsSearchEntry> =
    buildList {
        SettingsHomeRow.entries.forEach { row ->
            val destination = row.detail
            add(
                SettingsSearchEntry(
                    id = "home.${row.name}",
                    titleRes = row.titleRes,
                    categoryRes = requireNotNull(row.section.groupTitleRes),
                    destination = destination,
                    itemKey = if (destination == null) row.section.name else null,
                    rowKey = if (destination == null) row.name else null,
                    accountRequired = row.section == SettingsHomeSection.Account,
                    aliasesRes = if (destination == SettingsDetail.TextToSpeech) R.string.settings_search_alias_read_aloud else null,
                ),
            )
        }
        add(
            SettingsSearchEntry(
                "Appearance.quick_account_switching",
                R.string.quick_account_switching,
                R.string.appearance,
                SettingsDetail.Appearance,
                itemKey = "search.Appearance.1",
                rowKey = "quick_account_switching",
                summaryRes = R.string.quick_account_switching_detail,
            ),
        )
        add(
            SettingsSearchEntry(
                "Appearance.action_color",
                R.string.action_color,
                R.string.appearance,
                SettingsDetail.Appearance,
                itemKey = "search.Appearance.5",
                rowKey = "action_color",
                nonAmoledRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "Appearance.chat_bubble_colors",
                R.string.chat_bubble_colors,
                R.string.appearance,
                SettingsDetail.Appearance,
                itemKey = "search.Appearance.5",
                rowKey = "chat_bubble_colors",
            ),
        )
        add(
            SettingsSearchEntry(
                "Appearance.font_family",
                R.string.app_font,
                R.string.appearance,
                SettingsDetail.Appearance,
                itemKey = "search.Appearance.6",
                rowKey = "font_family",
            ),
        )
        add(
            SettingsSearchEntry(
                "Appearance.font_size",
                R.string.font_size,
                R.string.appearance,
                SettingsDetail.Appearance,
                itemKey = "search.Appearance.6",
                rowKey = "font_size",
                aliasesRes = R.string.settings_search_alias_font_size,
            ),
        )
        add(
            SettingsSearchEntry(
                "Appearance.enter_behavior",
                R.string.enter_key_behavior_title,
                R.string.appearance,
                SettingsDetail.Appearance,
                itemKey = "search.Appearance.6",
                rowKey = "enter_behavior",
            ),
        )
        add(
            SettingsSearchEntry(
                "Appearance.custom_emoji",
                R.string.custom_emoji,
                R.string.appearance,
                SettingsDetail.Appearance,
                itemKey = "search.Appearance.7",
                rowKey = "custom_emoji",
            ),
        )
        add(
            SettingsSearchEntry(
                "Appearance.language",
                R.string.language,
                R.string.appearance,
                SettingsDetail.Appearance,
                itemKey = "search.Appearance.8",
                rowKey = "language",
            ),
        )
        add(
            SettingsSearchEntry(
                "Data.reset",
                R.string.download_reset,
                R.string.settings_data_usage,
                SettingsDetail.Data,
                itemKey = "search.DataUsage.2",
                rowKey = "reset",
                summaryRes = R.string.download_reset_help,
            ),
        )
        add(
            SettingsSearchEntry(
                "Data.quality",
                R.string.download_quality,
                R.string.settings_data_usage,
                SettingsDetail.Data,
                itemKey = "search.DataUsage.7",
                rowKey = "quality",
            ),
        )
        add(
            SettingsSearchEntry(
                "Data.public_jpeg_previews",
                R.string.public_jpeg_previews_title,
                R.string.settings_data_usage,
                SettingsDetail.Data,
                itemKey = "search.DataUsage.9",
                rowKey = "public_jpeg_previews",
                summaryRes = R.string.public_jpeg_previews_privacy,
            ),
        )
        add(
            SettingsSearchEntry(
                "DevicePrivacy.screen_security",
                R.string.allow_chat_screenshots,
                R.string.settings_privacy_security,
                SettingsDetail.DevicePrivacy,
                itemKey = "search.DevicePrivacy.2",
                rowKey = "screen_security",
                summaryRes = R.string.allow_chat_screenshots_subtitle,
            ),
        )
        add(
            SettingsSearchEntry(
                "DevicePrivacy.incognito_keyboard",
                R.string.force_incognito_keyboard,
                R.string.settings_privacy_security,
                SettingsDetail.DevicePrivacy,
                itemKey = "search.DevicePrivacy.2",
                rowKey = "incognito_keyboard",
                summaryRes = R.string.incognito_keyboard_detail,
            ),
        )
        add(
            SettingsSearchEntry(
                "DevicePrivacy.device_authentication",
                R.string.require_device_authentication,
                R.string.settings_privacy_security,
                SettingsDetail.DevicePrivacy,
                itemKey = "search.DevicePrivacy.2",
                rowKey = "device_authentication",
            ),
        )
        add(
            SettingsSearchEntry(
                "DevicePrivacy.blocked_users",
                R.string.blocked_users,
                R.string.settings_privacy_security,
                SettingsDetail.DevicePrivacy,
                itemKey = "search.DevicePrivacy.4",
                rowKey = "blocked_users",
            ),
        )
        add(
            SettingsSearchEntry(
                "DevicePrivacy.default_disappearing_messages",
                R.string.default_disappearing_messages,
                R.string.settings_privacy_security,
                SettingsDetail.DevicePrivacy,
                itemKey = "search.DevicePrivacy.6",
                rowKey = "default_disappearing_messages",
                summaryRes = R.string.default_disappearing_messages_detail,
            ),
        )
        add(
            SettingsSearchEntry(
                "DevicePrivacy.diagnostics",
                R.string.diagnostics_improvements,
                R.string.settings_privacy_security,
                SettingsDetail.DevicePrivacy,
                itemKey = "search.DevicePrivacy.8",
                rowKey = "diagnostics",
            ),
        )
        add(
            SettingsSearchEntry(
                "TextToSpeech.engine",
                R.string.tts_settings_engine_title,
                R.string.settings_read_aloud,
                SettingsDetail.TextToSpeech,
                itemKey = "search.TextToSpeech.1",
                rowKey = "engine",
            ),
        )
        add(
            SettingsSearchEntry(
                "TextToSpeech.voice",
                R.string.tts_voice_title,
                R.string.settings_read_aloud,
                SettingsDetail.TextToSpeech,
                itemKey = "search.TextToSpeech.1",
                rowKey = "voice",
            ),
        )
        add(
            SettingsSearchEntry(
                "TextToSpeech.rate",
                R.string.tts_settings_rate_title,
                R.string.settings_read_aloud,
                SettingsDetail.TextToSpeech,
                itemKey = "search.TextToSpeech.1",
                rowKey = "rate",
            ),
        )
        add(
            SettingsSearchEntry(
                "TextToSpeech.auto_read",
                R.string.tts_auto_read_default_global_title,
                R.string.settings_read_aloud,
                SettingsDetail.TextToSpeech,
                itemKey = "search.TextToSpeech.3",
                rowKey = "auto_read",
                summaryRes = R.string.tts_auto_read_default_global_subtitle,
            ),
        )
        add(
            SettingsSearchEntry(
                "TextToSpeech.media_mix",
                R.string.tts_media_mix_title,
                R.string.settings_read_aloud,
                SettingsDetail.TextToSpeech,
                itemKey = "search.TextToSpeech.3",
                rowKey = "media_mix",
                summaryRes = R.string.tts_media_mix_subtitle,
            ),
        )
        add(
            SettingsSearchEntry(
                "TextToSpeech.mix_volume",
                R.string.tts_media_mix_volume_title,
                R.string.settings_read_aloud,
                SettingsDetail.TextToSpeech,
                itemKey = "search.TextToSpeech.3",
                rowKey = "mix_volume",
            ),
        )
        add(
            SettingsSearchEntry(
                "TextToSpeech.refresh",
                R.string.refresh,
                R.string.settings_read_aloud,
                SettingsDetail.TextToSpeech,
                itemKey = "search.TextToSpeech.4",
                rowKey = "refresh",
            ),
        )
        add(
            SettingsSearchEntry(
                "TextToSpeech.android_settings",
                R.string.speech_android_settings,
                R.string.settings_read_aloud,
                SettingsDetail.TextToSpeech,
                itemKey = "search.TextToSpeech.4",
                rowKey = "android_settings",
            ),
        )
        add(
            SettingsSearchEntry(
                "Dictation.provider",
                R.string.dictation_provider_title,
                R.string.dictation_settings_title,
                SettingsDetail.Dictation,
                itemKey = "search.DictationSettings.4",
                rowKey = "provider",
            ),
        )
        add(
            SettingsSearchEntry(
                "Dictation.pause_other_audio",
                R.string.dictation_pause_other_audio_title,
                R.string.dictation_settings_title,
                SettingsDetail.Dictation,
                itemKey = "search.DictationSettings.4",
                rowKey = "pause_other_audio",
                summaryRes = R.string.dictation_pause_other_audio_subtitle,
            ),
        )
        add(
            SettingsSearchEntry(
                "Dictation.finish",
                R.string.dictation_finish_title,
                R.string.dictation_settings_title,
                SettingsDetail.Dictation,
                itemKey = "search.DictationSettings.4",
                rowKey = "finish",
            ),
        )
        add(
            SettingsSearchEntry(
                "Dictation.android_settings",
                R.string.speech_android_settings,
                R.string.dictation_settings_title,
                SettingsDetail.Dictation,
                itemKey = "search.DictationSettings.6",
                rowKey = "android_settings",
            ),
        )
        add(
            SettingsSearchEntry(
                "DiagnosticsImprovements.usage",
                R.string.usage_diagnostics_title,
                R.string.diagnostics_improvements,
                SettingsDetail.DiagnosticsImprovements,
                itemKey = "search.DiagnosticsImprovements.1",
                rowKey = "usage",
                summaryRes = R.string.usage_diagnostics_subtitle,
            ),
        )
        add(
            SettingsSearchEntry(
                "DiagnosticsImprovements.audit_logs",
                R.string.audit_upload_title,
                R.string.diagnostics_improvements,
                SettingsDetail.DiagnosticsImprovements,
                itemKey = "search.DiagnosticsImprovements.1",
                rowKey = "audit_logs",
                summaryRes = R.string.audit_upload_subtitle,
            ),
        )
        add(
            SettingsSearchEntry(
                "DiagnosticsImprovements.export",
                R.string.export_audit_logs,
                R.string.diagnostics_improvements,
                SettingsDetail.DiagnosticsImprovements,
                itemKey = "search.DiagnosticsImprovements.4",
                rowKey = "export",
                summaryRes = R.string.export_audit_logs_subtitle,
            ),
        )
        add(
            SettingsSearchEntry(
                "DiagnosticsImprovements.delete",
                R.string.delete_audit_logs,
                R.string.diagnostics_improvements,
                SettingsDetail.DiagnosticsImprovements,
                itemKey = "search.DiagnosticsImprovements.4",
                rowKey = "delete",
                summaryRes = R.string.diagnostics_storage_device,
            ),
        )

        add(
            SettingsSearchEntry(
                "Developer.developer_mode",
                R.string.developer_mode,
                R.string.settings_developer_tools,
                SettingsDetail.Developer,
                itemKey = "search.Developer.2",
                rowKey = "developer_mode",
            ),
        )
        add(
            SettingsSearchEntry(
                "Developer.key_packages",
                R.string.key_packages,
                R.string.settings_developer_tools,
                SettingsDetail.Developer,
                itemKey = "search.Developer.7",
                rowKey = "key_packages",
                summaryRes = R.string.key_packages_settings_subtitle,
            ),
        )
        add(
            SettingsSearchEntry(
                "Developer.quarantined_groups",
                R.string.quarantined_groups,
                R.string.settings_developer_tools,
                SettingsDetail.Developer,
                itemKey = "search.Developer.8",
                rowKey = "quarantined_groups",
                developerRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "Developer.streaming_debug",
                R.string.streaming_debug,
                R.string.settings_developer_tools,
                SettingsDetail.Developer,
                itemKey = "search.Developer.10",
                rowKey = "streaming_debug",
                developerRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "Developer.diagnostics",
                R.string.diagnostics,
                R.string.settings_developer_tools,
                SettingsDetail.Developer,
                itemKey = "search.Developer.10",
                rowKey = "diagnostics",
                summaryRes = R.string.diagnostics_settings_subtitle,
                developerRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "AiAgents.public_key",
                R.string.copy_public_key,
                R.string.ai_agents,
                SettingsDetail.AiAgents,
                itemKey = "search.AiAgents.7",
                rowKey = "public_key",
                accountRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "AiAgents.docs",
                R.string.ai_agents_connector_docs_title,
                R.string.ai_agents,
                SettingsDetail.AiAgents,
                itemKey = "search.AiAgents.7",
                rowKey = "docs",
                summaryRes = R.string.ai_agents_connector_docs_subtitle,
                accountRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "ChatFolders.restore",
                R.string.folder_restore_defaults,
                R.string.chat_folders_title,
                SettingsDetail.ChatFolders,
                itemKey = "search.ChatFolders.2",
                rowKey = "restore",
                accountRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "CustomEmoji.choose_photo",
                R.string.choose_photos,
                R.string.custom_emoji,
                SettingsDetail.CustomEmoji,
                itemKey = "search.CustomEmoji.2",
                rowKey = "choose_photo",
            ),
        )
        add(
            SettingsSearchEntry(
                "CustomEmoji.choose_file",
                R.string.choose_files,
                R.string.custom_emoji,
                SettingsDetail.CustomEmoji,
                itemKey = "search.CustomEmoji.2",
                rowKey = "choose_file",
            ),
        )
        add(
            SettingsSearchEntry(
                "AccountKeys.copy_private_key",
                R.string.copy_private_key,
                R.string.settings_profile_keys,
                SettingsDetail.AccountKeys,
                itemKey = "search.AccountKeys.8",
                rowKey = "copy_private_key",
                accountRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "AccountKeys.export_encrypted",
                R.string.export_encrypted_private_key,
                R.string.settings_profile_keys,
                SettingsDetail.AccountKeys,
                itemKey = "search.AccountKeys.11",
                rowKey = "export_encrypted",
                accountRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "AccountKeys.export_raw",
                R.string.export_nsec,
                R.string.settings_profile_keys,
                SettingsDetail.AccountKeys,
                itemKey = "search.AccountKeys.11",
                rowKey = "export_raw",
                accountRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "AccountKeys.sign_out",
                R.string.sign_out,
                R.string.settings_profile_keys,
                SettingsDetail.AccountKeys,
                itemKey = "search.AccountKeys.13",
                rowKey = "sign_out",
                accountRequired = true,
            ),
        )
        add(
            SettingsSearchEntry(
                "AccountKeys.wipe",
                R.string.sign_out_and_wipe,
                R.string.settings_profile_keys,
                SettingsDetail.AccountKeys,
                itemKey = "search.AccountKeys.14",
                rowKey = "wipe",
                accountRequired = true,
            ),
        )
        addAll(settingsSearchAdditionalControls())
    }
