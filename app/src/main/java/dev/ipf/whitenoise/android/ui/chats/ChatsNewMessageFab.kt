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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.loadAccountRelayLists
import dev.ipf.whitenoise.android.ui.common.ControlMorphSurface
import dev.ipf.whitenoise.android.ui.common.LocalControlHandoffEndpointCurrent
import dev.ipf.whitenoise.android.ui.common.LocalFinishControlMorph
import dev.ipf.whitenoise.android.ui.common.SurfaceMorphSource
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseContentMaxWidth
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.motionBoundsOnScreen
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPenDialogMotion
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerActionRowHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerOuterVerticalPadding
import dev.ipf.whitenoise.android.ui.keyboard.HardwareShortcutCommand
import dev.ipf.whitenoise.android.ui.keyboard.KeyboardShortcut
import dev.ipf.whitenoise.android.ui.navigation.ComposerFabEndpoint
import dev.ipf.whitenoise.android.ui.navigation.LocalComposerFabHandoff
import dev.ipf.whitenoise.android.ui.navigation.LocalFloatingControlShown
import dev.ipf.whitenoise.android.ui.navigation.composerFabSurface
import dev.ipf.whitenoise.android.ui.navigation.floatingDictationAnchor
import dev.ipf.whitenoise.android.ui.settings.RelaysScreen
import dev.ipf.whitenoise.android.ui.settings.SupportRelayState
import dev.ipf.whitenoise.android.ui.settings.settingsPenSource
import dev.ipf.whitenoise.android.ui.settings.supportRelayState
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseElevation
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.floatingControlFabElevation
import kotlinx.coroutines.CancellationException

/** Only actual known-missing receiving configuration changes New Message into a recovery route. */
internal fun chatsFabNeedsRelays(lists: AccountRelayListsFfi?): Boolean {
    val relayState = supportRelayState(lists)
    return relayState == SupportRelayState.Missing
}

internal data class NewMessageFlowLaunch(
    val origin: Rect?,
    val pen: MeasuredPenDialogMotion? = null,
)

/**
 * The FAB opens New Message or receiving-relay recovery using their existing owners. Ctrl+N takes the same New
 * Message path while the FAB offers it.
 */
@Suppress("FunctionNaming", "TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun ChatsNewMessageFab(
    appState: WhiteNoiseAppState,
    onNewMessage: () -> Unit,
    loadRelays: suspend (String) -> AccountRelayListsFfi? = appState::loadAccountRelayLists,
    onNewMessageOrigin: (MeasuredPenDialogMotion?) -> Unit = {},
    hidePen: () -> Boolean = { false },
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
    var relaysChecked by remember(appState, account, runtime) { mutableStateOf(false) }
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
        if (current()) {
            missing = chatsFabNeedsRelays(result)
            relaysChecked = true
        }
    }
    var relayOrigin by remember(appState, account, runtime) { mutableStateOf<Rect?>(null) }
    var fabBounds by remember { mutableStateOf<Rect?>(null) }
    val scheme = MaterialTheme.colorScheme
    val penSurface =
        with(LocalDensity.current) {
            ControlMorphSurface(scheme.surfaceContainerHigh, scheme.outlineVariant, 16.dp.toPx(), 1.dp.toPx(), 1f)
        }
    // Ctrl+N takes the button's own path, only while the button is shown and its relay check found New Message
    // usable, so a key cannot skip the Check relays state. A shown button is waiting during its check (#4005).
    val shown = LocalFloatingControlShown.current && current()
    KeyboardShortcut(
        HardwareShortcutCommand.NewMessage,
        available = shown && relaysChecked && !missing,
        waiting = shown && !relaysChecked,
    ) {
        // Rechecked when the key arrives, as a tap is, since the owner can change before the next composition.
        if (current() && relaysChecked && !missing) {
            onNewMessageOrigin(null)
            onNewMessage()
        }
    }
    ChatsNewMessageFabContent(missing, onBounds = { fabBounds = it }, hidePen = hidePen, onClick = {
        if (current()) {
            if (missing) {
                relayOrigin = fabBounds
                relayOpening = Any()
            } else {
                onNewMessageOrigin(
                    fabBounds?.let { bounds ->
                        MeasuredPenDialogMotion(SurfaceMorphSource(bounds, penSurface)) {
                            current() && !missing && fabBounds == bounds
                        }
                    },
                )
                onNewMessage()
            }
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
            MeasuredPlaybackDialog(
                origin = relayOrigin,
                ownerKey = relayToken,
                onDismissRequest = close,
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                RelaysScreen(appState, onBack = dismiss, onFeedback = { result ->
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
    onBounds: (Rect) -> Unit = {},
    hidePen: () -> Boolean = { false },
    onClick: () -> Unit,
) {
    if (!LocalControlHandoffEndpointCurrent.current) {
        Box(Modifier.size(if (missingRelays) 56.dp else ComposerActionRowHeight))
        return
    }
    // Keep the compact home pen on its bottom action baseline under current insets.
    // The measured handoff retains the source rectangle for the morph.
    val view = LocalView.current
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val penFill = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = if (missingRelays) amoledOutlineBorder() else BorderStroke(1.dp, outlineVariant)
    val finishControlMorph = LocalFinishControlMorph.current
    val morphing = !missingRelays && LocalComposerFabHandoff.current?.active == true
    BoxWithConstraints {
        val paneInset = ((maxWidth - WhiteNoiseContentMaxWidth) / 2).coerceAtLeast(0.dp)
        val modifier =
            Modifier
                .padding(end = paneInset)
                .offset(y = if (missingRelays) 0.dp else 16.dp - ComposerOuterVerticalPadding)
                .floatingDictationAnchor(followsComposerFab = !missingRelays)
                .onGloballyPositioned { onBounds(it.motionBoundsOnScreen(view)) }
                .settingsPenSource(!missingRelays, 16.dp, penFill, outlineVariant)
                .drawWithContent { if (missingRelays || !hidePen()) drawContent() }
                .performanceTestTag(PerformanceTestTags.NEW_MESSAGE)
        val chrome =
            if (morphing || outline == null) modifier else modifier.border(outline, FloatingActionButtonDefaults.shape)
        FloatingActionButton(
            onClick = {
                finishControlMorph()
                onClick()
            },
            modifier = chrome.chatsFabChrome(missingRelays, morphing, outline),
            shape = if (morphing) RectangleShape else FloatingActionButtonDefaults.shape,
            containerColor = chatsFabContainerColor(morphing, MaterialTheme.colorScheme.surfaceContainerHigh),
            contentColor = MaterialTheme.colorScheme.onSurface,
            elevation = chatsFabElevation(morphing),
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

@Composable
private fun Modifier.chatsFabChrome(
    missingRelays: Boolean,
    morphing: Boolean,
    outline: BorderStroke?,
): Modifier =
    if (missingRelays) {
        explainOnLongPress(
            chatsExplanation(
                R.string.chats_check_relays,
                R.string.explanation_chats_fab_check_relays,
            ),
        )
    } else {
        size(ComposerActionRowHeight).composerFabSurface(
            ComposerFabEndpoint.NewMessage,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            outline?.brush?.let { MaterialTheme.colorScheme.outlineVariant },
            radiusDp = 16f,
            morphing = morphing,
        )
    }

/** Lets the shared morph paint its fill until the settled Material FAB takes ownership. */
private fun chatsFabContainerColor(
    morphing: Boolean,
    settledColor: Color,
): Color = if (morphing) Color.Transparent else settledColor

private val ChatFabIconSize = 24.dp

/**
 * The pen's one floating elevation, the same at rest and pressed (#4101). The morph surface that carries the
 * pen into the composer is flat, so while [morphing] the pen casts no shadow in any state.
 */
@Composable
private fun chatsFabElevation(morphing: Boolean) =
    if (morphing) {
        FloatingActionButtonDefaults.elevation(
            defaultElevation = WhiteNoiseElevation.None,
            pressedElevation = WhiteNoiseElevation.None,
            focusedElevation = WhiteNoiseElevation.None,
            hoveredElevation = WhiteNoiseElevation.None,
        )
    } else {
        floatingControlFabElevation()
    }
