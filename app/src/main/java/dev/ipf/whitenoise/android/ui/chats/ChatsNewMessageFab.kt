package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseContentMaxWidth
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.settings.RelaysScreen
import dev.ipf.whitenoise.android.ui.settings.SupportRelayState
import dev.ipf.whitenoise.android.ui.settings.supportRelayState
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlinx.coroutines.CancellationException

/** Only actual known-missing receiving configuration changes New Message into a recovery route. */
internal fun chatsFabNeedsRelays(lists: AccountRelayListsFfi?): Boolean {
    val relayState = supportRelayState(lists)
    return relayState == SupportRelayState.Missing
}

/** Plain prototype FAB keeps actual native configuration and the existing New Message/Relays owners. */
@Suppress("FunctionNaming", "TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun ChatsNewMessageFab(
    appState: WhiteNoiseAppState,
    onNewMessage: () -> Unit,
    loadRelays: suspend (String) -> AccountRelayListsFfi? = appState::loadAccountRelayLists,
) {
    val account = appState.activeAccountRef
    val runtime = appState.runtimeGeneration
    var active by remember(appState, account, runtime) { mutableStateOf(true) }
    DisposableEffect(appState, account, runtime) { onDispose { active = false } }

    fun current(): Boolean {
        val sameOwner = account != null && appState.activeAccountRef == account && appState.runtimeGeneration == runtime
        val available =
            !appState.signOutInProgress && !appState.wipeInProgress && appState.retainedAccountReactivationRef == null
        return active && sameOwner && available
    }
    var missing by remember(appState, account, runtime) { mutableStateOf(false) }
    var relayOpening by remember(appState, account, runtime) { mutableStateOf<Any?>(null) }
    var refresh by remember(appState, account, runtime) { mutableIntStateOf(0) }
    LaunchedEffect(appState, account, runtime, refresh) {
        if (!current() || account == null) return@LaunchedEffect
        val result =
            try {
                loadRelays(account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        if (current()) missing = chatsFabNeedsRelays(result)
    }
    ChatsNewMessageFabContent(appState, missing, onClick = {
        if (current()) {
            if (missing) relayOpening = Any() else onNewMessage()
        }
    })
    val relayToken = relayOpening
    if (relayToken != null && current()) {
        key(relayToken) {
            var feedback by remember { mutableStateOf<ToastMessage?>(null) }
            val close = {
                if (relayOpening === relayToken) {
                    relayOpening = null
                    refresh++
                }
            }
            Dialog(
                onDismissRequest = close,
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                RelaysScreen(appState, onBack = close, onFeedback = { result ->
                    if (relayOpening === relayToken && current()) feedback = result
                })
                feedback?.let { result ->
                    ChatRelayFeedbackDialog(result, onDismiss = { feedback = null })
                }
            }
        }
    }
}

/**
 * Prototype 24 dp edit/warning icon, adaptive pane placement and AMOLED outline; action colors retain native
 * preferences.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatsNewMessageFabContent(
    appState: WhiteNoiseAppState,
    missingRelays: Boolean,
    onClick: () -> Unit,
) {
    val colors = accountActionColors(appState)
    val outline = amoledOutlineBorder()
    BoxWithConstraints {
        val paneInset = ((maxWidth - WhiteNoiseContentMaxWidth) / 2).coerceAtLeast(0.dp)
        val modifier = Modifier.padding(end = paneInset).performanceTestTag(PerformanceTestTags.NEW_MESSAGE)
        FloatingActionButton(
            onClick = onClick,
            modifier = if (outline == null) modifier else modifier.border(outline, FloatingActionButtonDefaults.shape),
            containerColor = if (outline == null) colors.container else MaterialTheme.colorScheme.surface,
            contentColor = if (outline == null) colors.content else MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(
                painterResource(if (missingRelays) R.drawable.ic_warning else R.drawable.ic_edit),
                stringResource(if (missingRelays) R.string.chats_check_relays else R.string.new_message),
                Modifier.size(ChatFabIconSize),
            )
        }
    }
}

private val ChatFabIconSize = 24.dp
