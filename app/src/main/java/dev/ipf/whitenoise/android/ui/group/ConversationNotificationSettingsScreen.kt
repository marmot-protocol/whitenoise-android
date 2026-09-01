@file:Suppress("FunctionNaming") // Compose UI entry points intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
import dev.ipf.whitenoise.android.ui.chats.newchat.SectionHeader
import dev.ipf.whitenoise.android.ui.chats.newchat.SettingsActionRow
import dev.ipf.whitenoise.android.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MILLIS_PER_SECOND = 1_000L
private val MUTE_ROW_MIN_HEIGHT = 56.dp
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sounds_and_notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(vertical = Dimens.spaceSm),
        ) {
            SectionHeader(stringResource(R.string.notifications))
            MuteSwitchRow(
                muted = isMuted,
                mutedUntil = if (isMuted && muteExpiryMillis != null) mutedUntilLabel(muteExpiryMillis) else null,
                enabled = !muteCommandPending,
                onToggle = onToggleMute,
            )
            SettingsActionRow(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.notify_for),
                value = notificationModeLabel(notifyForMode),
                onClick = onChooseNotifyFor,
            )
            SettingsActionRow(
                icon = Icons.Default.Vibration,
                title = stringResource(R.string.vibration_pattern),
                value = effectiveVibrationLabel(effectiveVibration, vibrationPattern),
                onClick = onChooseVibrationPattern,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = Dimens.spaceSm),
            )
            SectionHeader(stringResource(R.string.notification_categories))
            Text(
                text = stringResource(R.string.conversation_notification_categories_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXs),
            )
            NotificationCategoriesSection(
                appState = appState,
                groupIdHex = groupIdHex,
                conversationTitle = conversationTitle,
                conversationAvatarUrl = conversationAvatarUrl,
                isDm = isDm,
                primaryVibrationPattern = vibrationPattern,
            )
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

@Composable
private fun NotificationCategoriesLoadingRow() {
    SettingsActionRow(
        icon = Icons.Default.Settings,
        title = stringResource(R.string.notification_categories_loading),
        inProgress = true,
    )
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

@Composable
private fun mutedUntilLabel(expiryMillis: Long): String =
    stringResource(
        R.string.notify_muted_until,
        IdentityFormatter.clockTime((expiryMillis / MILLIS_PER_SECOND).toULong()),
    )

@Suppress("FunctionNaming")
@Composable
internal fun MuteSwitchRow(
    muted: Boolean,
    mutedUntil: String?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MUTE_ROW_MIN_HEIGHT)
                .testTag(MUTE_SWITCH_ROW_TAG)
                .toggleable(value = muted, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        Icon(
            Icons.Default.NotificationsOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
            Text(stringResource(R.string.mute), style = MaterialTheme.typography.bodyLarge)
            if (mutedUntil != null) {
                Text(
                    mutedUntil,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = muted, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
internal fun notificationModeLabel(mode: ChatNotifyMode): String =
    stringResource(
        when (mode) {
            ChatNotifyMode.ALL -> R.string.notify_all_messages
            ChatNotifyMode.MENTIONS_ONLY -> R.string.notify_only_mentions
            ChatNotifyMode.NONE -> R.string.notify_nothing
        },
    )
