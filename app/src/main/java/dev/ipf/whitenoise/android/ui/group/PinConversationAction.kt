@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.AndroidPinnedShortcutPlatform
import dev.ipf.whitenoise.android.notifications.ConversationPinResult
import dev.ipf.whitenoise.android.notifications.PinnedConversationCapability
import dev.ipf.whitenoise.android.notifications.PinnedConversationShortcuts
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.appStateDebug
import dev.ipf.whitenoise.android.state.isSignedInSigningAccount
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.automirrored.filled.AddToHomeScreen
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns only the mounted details action's busy state; launcher approval is never persisted as an app flag. */
@Composable
internal fun PinConversationAction(
    context: SettingsRowContext,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    title: String,
) {
    val androidContext = LocalContext.current
    val platform = remember(androidContext) { AndroidPinnedShortcutPlatform(androidContext) }
    val shortcuts = remember(androidContext) { PinnedConversationShortcuts(androidContext) }
    var supported by remember(platform) { mutableStateOf(runCatching { platform.supported() }.getOrDefault(false)) }
    var busy by remember(controller) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestTitle by rememberUpdatedState(title)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        supported = runCatching { platform.supported() }.getOrDefault(false)
    }
    PinConversationActionRow(context, supported, busy) {
        if (!busy) {
            busy = true
            val account = controller.boundAccountRef
            val group = controller.group.groupIdHex
            val runtime = appState.runtimeGeneration
            val requestGeneration = PinnedConversationTokens.captureRequest()

            /**
             * A pin request belongs only to the still-mounted account, conversation and runtime
             * captured by its tap.
             */
            fun stillCurrent(): Boolean = pinActionStillCurrent(appState, controller, account, group, runtime)
            scope.launch {
                try {
                    val request =
                        currentPinCapability(
                            androidContext,
                            appState,
                            account,
                            group,
                            requestGeneration,
                            ::stillCurrent,
                        )
                    val result =
                        if (request == null || !stillCurrent()) {
                            ConversationPinResult.UNAVAILABLE
                        } else {
                            val (capability, item) = request
                            val presentation =
                                appState.pinnedConversationPresentation(checkNotNull(account), item, latestTitle)
                            withContext(Dispatchers.IO) {
                                shortcuts.request(capability, presentation.title, null, presentation = presentation) {
                                    isActive && stillCurrent()
                                }
                            }
                        }
                    if (stillCurrent()) appState.present(pinResultMessage(result))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (stillCurrent()) appState.present(R.string.pinned_shortcut_failed)
                } finally {
                    busy = false
                }
            }
        }
    }
}

/** A stale details screen cannot issue credentials for a missing group; revalidate its local native row first. */
private suspend fun currentPinCapability(
    context: Context,
    appState: WhiteNoiseAppState,
    account: String?,
    group: String,
    requestGeneration: Long,
    stillCurrent: () -> Boolean,
): Pair<PinnedConversationCapability, ChatListItem>? {
    val currentRow =
        account?.takeIf { stillCurrent() }?.let {
            runCatchingCancellable { appState.preloadNotificationChatListItem(it, group) }.getOrNull()
        }
    val available =
        currentRow?.group?.let {
            val member = it.selfMembership == SelfMembershipFfi.MEMBER
            member && !it.pendingConfirmation
        } ?: false
    if (!available) {
        // Debug-only shape of the refusal; it names no account, group or message.
        appStateDebug {
            "pin request unavailable: account=${account != null} current=${stillCurrent()} row=${currentRow != null} " +
                "membership=${currentRow?.group?.selfMembership} pending=${currentRow?.group?.pendingConfirmation}"
        }
    }
    return if (available && stillCurrent()) {
        withContext(Dispatchers.IO) {
            PinnedConversationTokens
                .create(context)
                .issue(checkNotNull(account), group, requestGeneration)
                ?.let { it to checkNotNull(currentRow) }
        }
    } else {
        null
    }
}

/** Uses the existing settings-row touch target, busy behavior and accessible disabled explanation. */
@Composable
internal fun PinConversationActionRow(
    context: SettingsRowContext,
    supported: Boolean,
    busy: Boolean,
    onPin: () -> Unit,
) {
    SettingsLink(
        context = context,
        title = stringResource(R.string.add_conversation_to_home_screen),
        subtitle = if (supported) null else stringResource(R.string.pinned_shortcut_unsupported),
        onClick = onPin,
        enabled = supported,
        busy = busy,
        modifier = Modifier.testTag("chat_info.pin_shortcut"),
        leading = { SettingsLeadingIcon(Icons.AutoMirrored.Filled.AddToHomeScreen) },
    )
}

/** A successful request still needs launcher approval; the copy never claims the shortcut was installed. */
private fun pinResultMessage(result: ConversationPinResult): Int =
    when (result) {
        ConversationPinResult.REQUESTED -> R.string.pinned_shortcut_finish
        ConversationPinResult.ALREADY_PINNED -> R.string.pinned_shortcut_already_added
        ConversationPinResult.UNSUPPORTED -> R.string.pinned_shortcut_unsupported
        ConversationPinResult.UNAVAILABLE -> R.string.pinned_shortcut_unavailable
        ConversationPinResult.FAILED -> R.string.pinned_shortcut_failed
    }

/** A suspended credential write cannot transfer this mounted action to a changed account or group. */
private fun pinActionStillCurrent(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    account: String?,
    group: String,
    runtime: Int,
): Boolean =
    account != null &&
        appState.activeAccountRef == account &&
        controller.boundAccountRef == account &&
        controller.group.groupIdHex == group &&
        appState.runtimeGeneration == runtime &&
        !appState.appLockScreenVisible &&
        !appState.appUnlockEvaluationPending &&
        !controller.group.pendingConfirmation &&
        controller.group.selfMembership == SelfMembershipFfi.MEMBER &&
        appState.accounts.any { it.label == account && it.isSignedInSigningAccount() }
