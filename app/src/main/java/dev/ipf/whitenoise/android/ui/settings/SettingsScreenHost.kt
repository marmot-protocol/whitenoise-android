package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.navigation.ShellRoute

/**
 * Settings root hosting the hub and every detail destination. [stack] is the section's open pages, home first, and
 * [onStackChange] receives the next one, see [rememberSettingsRouteRenderer].
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun SettingsScreen(
    appState: WhiteNoiseAppState,
    onBackToChats: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSupportChat: (ChatListItem) -> Unit,
    stack: List<ShellRoute.Settings>,
    onStackChange: (List<ShellRoute.Settings>) -> Unit,
    homeViewport: SettingsHomeViewport,
    onHomeViewportChange: (SettingsHomeViewport) -> Unit,
    suppressMotion: Boolean = false,
    selfUpdateEnabled: Boolean = BuildConfig.SELF_UPDATE_ENABLED,
    firstRunHelpRoot: SettingsDetail? = null,
) {
    val render =
        rememberSettingsRouteRenderer(
            appState = appState,
            onBackToChats = onBackToChats,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenSupportChat = onOpenSupportChat,
            stack = stack,
            onStackChange = onStackChange,
            homeViewport = homeViewport,
            onHomeViewportChange = onHomeViewportChange,
            suppressMotion = suppressMotion,
            selfUpdateEnabled = selfUpdateEnabled,
            firstRunHelpRoot = firstRunHelpRoot,
        )
    val detail = stack.lastOrNull()?.detail
    SettingsRouteMotion(
        target = detail,
        depth = render.depthOf(detail),
        ownerKey = appState.activeAccountRef to appState.runtimeGeneration,
        suppressMotion = suppressMotion,
        contentVisible = !appState.appLock.screenVisible,
        cardMorph = true,
    ) { route -> render.content(route) }
}

/** One Settings route renderer: the motion depth of each route, which a walkthrough can deepen, and its content. */
internal class SettingsRouteRenderer(
    val depthOf: (SettingsDetail?) -> Int,
    val content: @Composable (SettingsDetail?) -> Unit,
)

/** Back handler: [onDetailBack] from an open [detail], [onHomeBack] from home, blocked during sign-out. */
@Composable
internal fun settingsBackHandler(
    signOutInProgress: Boolean,
    detail: SettingsDetail?,
    onHomeBack: () -> Unit,
    onDetailBack: () -> Unit,
) {
    BackHandler {
        if (signOutInProgress) return@BackHandler
        if (detail == null) onHomeBack() else onDetailBack()
    }
}
