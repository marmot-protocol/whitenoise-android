package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.LocalControlHandoffEndpointCurrent
import dev.ipf.whitenoise.android.ui.common.LocalFinishControlMorph
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseContentMaxWidth
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerActionRowHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerOuterVerticalPadding
import dev.ipf.whitenoise.android.ui.navigation.ComposerFabEndpoint
import dev.ipf.whitenoise.android.ui.navigation.LocalComposerFabHeight
import dev.ipf.whitenoise.android.ui.navigation.LocalComposerFabHandoff
import dev.ipf.whitenoise.android.ui.navigation.composerFabSurface
import dev.ipf.whitenoise.android.ui.navigation.floatingDictationAnchor
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
    minimumTopInWindow: State<Float?>? = null,
) {
    val account = appState.activeAccountRef
    val runtime = appState.runtimeGeneration
    var active by remember(appState, account, runtime) { mutableStateOf(true) }
    DisposableEffect(appState, account, runtime) { onDispose { active = false } }

    /** True while the FAB still belongs to the account and runtime that composed it. */
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
    ChatsNewMessageFabContent(missing, minimumTopInWindow = minimumTopInWindow, onClick = {
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
 * Composer-colored edit/warning button, with adaptive pane placement and matching composer chrome.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatsNewMessageFabContent(
    missingRelays: Boolean,
    minimumTopInWindow: State<Float?>? = null,
    onClick: () -> Unit,
) {
    if (!LocalControlHandoffEndpointCurrent.current) {
        Box(Modifier.size(if (missingRelays) 56.dp else ComposerActionRowHeight))
        return
    }
    // Keep the pen one action row tall, aligned with the last composer's upper edge.
    // Retaining height rather than window coordinates lets current insets remain authoritative.
    val composerHeight = LocalComposerFabHeight.current
    val outline =
        if (missingRelays) amoledOutlineBorder() else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val finishControlMorph = LocalFinishControlMorph.current
    val morphing = !missingRelays && LocalComposerFabHandoff.current?.active == true
    val placementTopInWindow = remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(Modifier.onPlaced { placementTopInWindow.value = it.positionInWindow().y }) {
        val paneInset = ((maxWidth - WhiteNoiseContentMaxWidth) / 2).coerceAtLeast(0.dp)
        val modifier =
            Modifier
                .padding(end = paneInset)
                .alignWithComposer(composerHeight, minimumTopInWindow, placementTopInWindow, maxHeight, !missingRelays)
                .floatingDictationAnchor(followsComposerFab = !missingRelays)
                .performanceTestTag(PerformanceTestTags.NEW_MESSAGE)
        val chrome =
            if (morphing || outline == null) modifier else modifier.border(outline, FloatingActionButtonDefaults.shape)
        FloatingActionButton(
            onClick = {
                finishControlMorph()
                onClick()
            },
            modifier =
                if (missingRelays) {
                    chrome
                } else {
                    chrome.size(ComposerActionRowHeight).composerFabSurface(
                        ComposerFabEndpoint.NewMessage,
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        outline?.brush?.let { MaterialTheme.colorScheme.outlineVariant },
                        radiusDp = 16f,
                        morphing = morphing,
                    )
                },
            shape = if (morphing) RectangleShape else FloatingActionButtonDefaults.shape,
            containerColor = chatsFabContainerColor(morphing, MaterialTheme.colorScheme.surfaceContainerHigh),
            contentColor = MaterialTheme.colorScheme.onSurface,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
        ) {
            Icon(
                painterResource(if (missingRelays) R.drawable.ic_warning else R.drawable.ic_edit),
                stringResource(if (missingRelays) R.string.chats_check_relays else R.string.new_message),
                Modifier.size(ChatFabIconSize).drawWithContent {
                    // The shell paints the one moving glyph outside this final FAB's local clip.
                    if (!morphing) drawContent()
                },
            )
        }
    }
}

/** Aligns the pen during placement while respecting the current measured home viewport. */
private fun Modifier.alignWithComposer(
    composerHeight: State<Dp>?,
    minimumTopInWindow: State<Float?>?,
    placementTopInWindow: State<Float?>,
    maximumHeight: Dp,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this
    return offset {
        val baselineAdjustment = 16.dp - ComposerOuterVerticalPadding
        val retainedHeight =
            (composerHeight?.value ?: ComposerActionRowHeight)
                .coerceIn(ComposerActionRowHeight, maximumHeight.coerceAtLeast(ComposerActionRowHeight))
        val baseline = baselineAdjustment.roundToPx()
        val desired = (baselineAdjustment - retainedHeight + ComposerActionRowHeight).roundToPx()
        val minimum =
            if (minimumTopInWindow == null) {
                Int.MIN_VALUE
            } else {
                val top = minimumTopInWindow.value
                val origin = placementTopInWindow.value
                if (top == null || origin == null) baseline else kotlin.math.ceil(top - origin).toInt()
            }
        IntOffset(0, desired.coerceIn(minimum.coerceAtMost(baseline), baseline))
    }
}

/** Lets the shared morph paint its fill until the settled Material FAB takes ownership. */
private fun chatsFabContainerColor(
    morphing: Boolean,
    settledColor: Color,
): Color = if (morphing) Color.Transparent else settledColor

private val ChatFabIconSize = 24.dp
