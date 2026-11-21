package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail

/** Settings root hosting the hub and every detail destination. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun SettingsScreen(
    appState: WhiteNoiseAppState,
    onBackToChats: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSupportChat: (ChatListItem) -> Unit,
    detail: SettingsDetail?,
    onDetailChange: (SettingsDetail?) -> Unit,
    homeViewport: SettingsHomeViewport,
    onHomeViewportChange: (SettingsHomeViewport) -> Unit,
    suppressMotion: Boolean = false,
    selfUpdateEnabled: Boolean = BuildConfig.SELF_UPDATE_ENABLED,
) {
    val render =
        rememberSettingsRouteRenderer(
            appState = appState,
            onBackToChats = onBackToChats,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenSupportChat = onOpenSupportChat,
            detail = detail,
            onDetailChange = onDetailChange,
            homeViewport = homeViewport,
            onHomeViewportChange = onHomeViewportChange,
            suppressMotion = suppressMotion,
            selfUpdateEnabled = selfUpdateEnabled,
        )
    SettingsRouteMotion(
        target = detail,
        depth = settingsDetailDepth(detail),
        ownerKey = appState.activeAccountRef to appState.runtimeGeneration,
        suppressMotion = suppressMotion,
        contentVisible = !appState.appLock.screenVisible,
        cardMorph = true,
    ) { route -> render(route) }
}

/** Back handler: nested detail, then home, blocked during sign-out. */
@Composable
internal fun settingsBackHandler(
    signOutInProgress: Boolean,
    detail: SettingsDetail?,
    parentOf: (SettingsDetail) -> SettingsDetail?,
    onBackToChats: () -> Unit,
    onDetailChange: (SettingsDetail?) -> Unit,
) {
    BackHandler {
        if (signOutInProgress) return@BackHandler
        if (detail == null) {
            onBackToChats()
        } else {
            onDetailChange(parentOf(detail))
        }
    }
}
