@file:Suppress("FunctionNaming") // Compose UI entry points intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.notifications.AndroidNotificationSettingsTarget
import dev.ipf.whitenoise.android.notifications.ConversationNotificationCategorySetting
import dev.ipf.whitenoise.android.notifications.ConversationNotificationChannels
import dev.ipf.whitenoise.android.notifications.ConversationNotificationRouting
import dev.ipf.whitenoise.android.notifications.ConversationNotificationScope
import dev.ipf.whitenoise.android.notifications.ConversationNotificationSettingsLaunchAttempt
import dev.ipf.whitenoise.android.notifications.ConversationNotificationSettingsLaunchGate
import dev.ipf.whitenoise.android.notifications.ConversationNotificationSettingsPreparation
import dev.ipf.whitenoise.android.notifications.ConversationNotificationSettingsPreparationRequest
import dev.ipf.whitenoise.android.notifications.ConversationNotificationSettingsPreparer
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.EffectiveConversationVibration
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import dev.ipf.whitenoise.android.notifications.NotificationConversationDescriptor
import dev.ipf.whitenoise.android.notifications.OverridableConversationNotificationCategory
import dev.ipf.whitenoise.android.notifications.PreparedConversationNotificationSettingsTarget
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.openConversationNotificationSettingsFallback
import dev.ipf.whitenoise.android.notifications.openNotificationChannelSettings
import dev.ipf.whitenoise.android.notifications.openPreparedConversationNotificationSettings
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import dev.ipf.whitenoise.android.ui.settings.SettingsSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MILLIS_PER_SECOND = 1_000L
internal const val MUTE_SWITCH_ROW_TAG = "conversation-mute-switch-row"

/** Category model paired only with its short-lived, Android-owned launch readiness. */
private data class PreparedNotificationCategorySetting(
    val setting: ConversationNotificationCategorySetting,
    val preparedTarget: PreparedConversationNotificationSettingsTarget?,
    val preparationOperationId: Long?,
)

/**
 * Shows one conversation's notification controls and begins lifecycle-scoped
 * Android shortcut/channel preparation before category actions become usable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod") // Compose screen owns one cohesive settings surface.
@Composable
internal fun ConversationNotificationSettingsScreen(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
    conversationTitle: String,
    conversationAvatarUrl: String?,
    isDm: Boolean,
    isMuted: Boolean,
    muteCommandPending: Boolean,
    muteExpiryMillis: Long?,
    notifyForMode: ChatNotifyMode,
    vibrationPattern: ConversationVibrationPattern,
    onBack: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onChooseNotifyFor: () -> Unit,
    onChooseVibrationPattern: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeGeneration by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumeGeneration += 1
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val effectiveVibration =
        remember(appState.activeAccountRef, groupIdHex, isDm, vibrationPattern, resumeGeneration) {
            val shortcutId = appState.activeAccountRef?.let { conversationShortcutId(it, groupIdHex) }
            if (shortcutId == null) {
                EffectiveConversationVibration(vibrationPattern, enabled = true, overriddenByAndroid = false)
            } else {
                ConversationNotificationChannels.effectiveVibration(
                    context = context,
                    conversationShortcutId = shortcutId,
                    isDm = isDm,
                    selectedPattern = vibrationPattern,
                )
            }
        }
    SettingsScaffold(title = stringResource(R.string.sounds_and_notifications), onBack = onBack) {
        SettingsList {
            item { SettingsSection(stringResource(R.string.notifications)) }
            item {
                SettingsGroup {
                    row("mute") { rowContext ->
                        SettingsSwitch(
                            context = rowContext,
                            title = stringResource(R.string.mute),
                            checked = isMuted,
                            onCheckedChange = onToggleMute,
                            modifier = Modifier.testTag(MUTE_SWITCH_ROW_TAG),
                            subtitle =
                                when {
                                    isMuted && muteExpiryMillis != null -> mutedUntilLabel(muteExpiryMillis)
                                    isMuted -> stringResource(R.string.notify_nothing_while_muted)
                                    else -> stringResource(R.string.notification_mute_detail)
                                },
                            enabled = !muteCommandPending,
                        )
                    }
                    row("notify_for") { rowContext ->
                        SettingsLink(
                            context = rowContext,
                            title = stringResource(R.string.notify_for),
                            onClick = onChooseNotifyFor,
                            value = notificationModeLabel(notifyForMode),
                        )
                    }
                    row("vibration") { rowContext ->
                        SettingsLink(
                            context = rowContext,
                            title = stringResource(R.string.vibration_pattern),
                            onClick = onChooseVibrationPattern,
                            value = effectiveVibrationLabel(effectiveVibration, vibrationPattern),
                        )
                    }
                }
            }
            item { SettingsExplainer(stringResource(R.string.notification_notify_restore)) }
            item { SettingsSection(stringResource(R.string.notification_categories)) }
            item {
                NotificationCategoriesSection(
                    appState = appState,
                    groupIdHex = groupIdHex,
                    conversationTitle = conversationTitle,
                    conversationAvatarUrl = conversationAvatarUrl,
                    isDm = isDm,
                    primaryVibrationPattern = vibrationPattern,
                )
            }
            item { SettingsExplainer(stringResource(R.string.notification_chat_categories_detail)) }
        }
    }
}

/** Resolves routing and Android-owned launch targets as one lifecycle-cancellable pass. */
@Composable
private fun NotificationCategoriesSection(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
    conversationTitle: String,
    conversationAvatarUrl: String?,
    isDm: Boolean,
    primaryVibrationPattern: ConversationVibrationPattern,
) {
    val routing = appState.conversationNotificationRouting
    val routingState by routing.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val accountRef = appState.activeAccountRef
    val shortcutId = remember(accountRef, groupIdHex) { accountRef?.let { conversationShortcutId(it, groupIdHex) } }
    val descriptor =
        remember(shortcutId, isDm, conversationTitle, primaryVibrationPattern) {
            shortcutId?.let {
                NotificationConversationDescriptor(
                    shortcutId = it,
                    isDm = isDm,
                    title = conversationTitle,
                    primaryVibrationPattern = primaryVibrationPattern,
                )
            }
        }
    val preparer = remember(context.applicationContext) { ConversationNotificationSettingsPreparer() }
    var settings by remember(shortcutId) { mutableStateOf<List<PreparedNotificationCategorySetting>>(emptyList()) }
    LaunchedEffect(descriptor, routingState, accountRef, groupIdHex, conversationAvatarUrl) {
        // Do not leave a stale target tappable while a scope/account change is
        // preparing its replacement Android channel.
        settings = emptyList()
        settings =
            if (descriptor == null || accountRef == null) {
                emptyList()
            } else {
                val resolved = withContext(Dispatchers.Default) { routing.settings(descriptor) }
                val requestedParents =
                    resolved
                        .filter { setting -> setting.settingsTarget is AndroidNotificationSettingsTarget.Conversation }
                        .map(ConversationNotificationCategorySetting::channel)
                val preparation =
                    preparer.prepare(
                        context = context.applicationContext,
                        request =
                            ConversationNotificationSettingsPreparationRequest(
                                accountRef = accountRef,
                                groupIdHex = groupIdHex,
                                isDm = descriptor.isDm,
                                conversationTitle = descriptor.title.orEmpty(),
                                conversationAvatarUrl = conversationAvatarUrl,
                                primaryVibrationPattern = descriptor.primaryVibrationPattern,
                                requestedParents = requestedParents,
                            ),
                    )
                resolved.map { setting -> setting.withPreparation(preparation) }
            }
    }
    if (descriptor == null || accountRef == null || settings.isEmpty()) {
        NotificationCategoriesLoadingRow()
        return
    }
    LoadedNotificationCategories(
        appState = appState,
        routing = routing,
        descriptor = descriptor,
        settings = settings,
    )
}

/** Accepts a prepared target only when it exactly matches the routing model. */
private fun ConversationNotificationCategorySetting.withPreparation(
    preparation: ConversationNotificationSettingsPreparation,
): PreparedNotificationCategorySetting {
    val expected = settingsTarget as? AndroidNotificationSettingsTarget.Conversation
    val prepared =
        (preparation as? ConversationNotificationSettingsPreparation.Ready)
            ?.targetsByParentChannelId
            ?.get(channel.id)
            ?.takeIf { target ->
                expected != null &&
                    target.channelId == expected.channelId &&
                    target.conversationShortcutId == expected.shortcutId
            }
    return PreparedNotificationCategorySetting(
        setting = this,
        preparedTarget = prepared,
        preparationOperationId = if (expected == null) null else preparation.operationId,
    )
}

/** Busy placeholder row while the categories load. */
@Composable
private fun NotificationCategoriesLoadingRow() {
    SettingsGroup {
        row("loading") { rowContext ->
            SettingsLink(
                context = rowContext,
                title = stringResource(R.string.notification_categories_loading),
                onClick = {},
                enabled = false,
                busy = true,
            )
        }
    }
}

/** Renders prepared category rows and coalesces taps until Android returns control. */
@Composable
private fun LoadedNotificationCategories(
    appState: WhiteNoiseAppState,
    routing: ConversationNotificationRouting,
    descriptor: NotificationConversationDescriptor,
    settings: List<PreparedNotificationCategorySetting>,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var pendingChannel by remember(descriptor.shortcutId) { mutableStateOf<NotificationChannelSpec?>(null) }
    val launchGate = remember(descriptor.shortcutId) { ConversationNotificationSettingsLaunchGate() }
    DisposableEffect(lifecycleOwner, launchGate) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) launchGate.onResumed()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ConversationNotificationCategoriesList(
        settings = settings.map(PreparedNotificationCategorySetting::setting),
        pendingChannel = pendingChannel,
        onOpen = { setting ->
            if (launchGate.tryBegin()) {
                val preparedSetting = settings.first { candidate -> candidate.setting == setting }
                val launch = openCategorySettings(context, preparedSetting, appState)
                if (!launch.opened) launchGate.onLaunchFailed()
            }
        },
        onScopeChange = { setting, useCustom ->
            val category =
                OverridableConversationNotificationCategory.from(setting.channel)
                    ?: return@ConversationNotificationCategoriesList
            pendingChannel = setting.channel
            coroutineScope.launch {
                val requestedScope =
                    if (useCustom) {
                        ConversationNotificationScope.CUSTOM_FOR_THIS_CHAT
                    } else {
                        ConversationNotificationScope.USE_GLOBAL_DEFAULT
                    }
                val result =
                    withContext(Dispatchers.Default) {
                        routing.setScope(descriptor, category, requestedScope)
                    }
                pendingChannel = null
                result.onFailure { appState.present(R.string.toast_notification_scope_update_failed) }
            }
        },
    )
}

/** Launches the exact prepared target and surfaces any broader Android fallback. */
private fun openCategorySettings(
    context: Context,
    preparedSetting: PreparedNotificationCategorySetting,
    appState: WhiteNoiseAppState,
): ConversationNotificationSettingsLaunchAttempt {
    val setting = preparedSetting.setting
    val launch =
        when (setting.settingsTarget) {
            is AndroidNotificationSettingsTarget.Global ->
                openNotificationChannelSettings(context, setting.channel)

            is AndroidNotificationSettingsTarget.Conversation -> {
                val preparedTarget = preparedSetting.preparedTarget
                if (preparedTarget != null) {
                    openPreparedConversationNotificationSettings(context, preparedTarget)
                } else {
                    appState.present(R.string.toast_notification_settings_unavailable)
                    openConversationNotificationSettingsFallback(
                        context = context,
                        operationId = checkNotNull(preparedSetting.preparationOperationId),
                    )
                }
            }
        }
    if (launch.usedFallback) appState.present(R.string.toast_notification_settings_unavailable)
    return launch
}

/** Subtitle for the vibration row: the effective pattern, or the selection when it differs. */
@Composable
private fun effectiveVibrationLabel(
    effective: EffectiveConversationVibration,
    selected: ConversationVibrationPattern,
): String =
    when {
        !effective.enabled -> stringResource(R.string.vibration_pattern_off_in_android_settings)
        effective.pattern == null -> stringResource(R.string.vibration_pattern_custom_in_android_settings)
        effective.overriddenByAndroid ->
            stringResource(
                R.string.vibration_pattern_android_override,
                vibrationPatternLabel(effective.pattern),
            )
        else -> vibrationPatternLabel(selected)
    }

/** "Muted until" label for the expiry clock time. */
@Composable
private fun mutedUntilLabel(expiryMillis: Long): String =
    stringResource(
        R.string.notify_muted_until,
        IdentityFormatter.clockTime((expiryMillis / MILLIS_PER_SECOND).toULong()),
    )

@Composable
internal fun notificationModeLabel(mode: ChatNotifyMode): String =
    stringResource(
        when (mode) {
            ChatNotifyMode.ALL -> R.string.notify_all_messages
            ChatNotifyMode.MENTIONS_ONLY -> R.string.notify_only_mentions
            ChatNotifyMode.NONE -> R.string.notify_nothing
        },
    )
