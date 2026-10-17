package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.translation.LocalConversationTranslation
import dev.ipf.whitenoise.android.translation.MessageTranslationDialog
import dev.ipf.whitenoise.android.translation.MessageTranslationRendition
import dev.ipf.whitenoise.android.translation.TranslationChatSettingsDialog
import dev.ipf.whitenoise.android.translation.TranslationOverride
import dev.ipf.whitenoise.android.translation.TranslationPreferences

/** Actual translation UI/session; controlled local engine outputs do not claim Binder or installed language packs. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroTranslationPresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario.startsWith("modal-translation-chat-")) {
        MaestroChatTranslationPresentation(fixture)
    } else {
        MaestroMessageTranslationPresentation(fixture)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun MaestroMessageTranslationPresentation(fixture: MaestroPresentationFixture) {
    val scope = rememberCoroutineScope()
    val state = remember(fixture) { MaestroMessageTranslationState(fixture, scope) }
    LaunchedEffect(state) { if (state.failure != null) state.session.request(state.source) }
    DisposableEffect(state) { onDispose { state.session.clear() } }
    val translatedSource = state.selected?.let { state.source.copy(target = it) }
    val rendition = translatedSource?.let { state.session.renditions[it] }
    LaunchedEffect(rendition, state.selected, state.showing) { state.verifyRendition(rendition) }
    CompositionLocalProvider(LocalConversationTranslation provides state.session) {
        if (state.showing) {
            MessageTranslationDialog(
                source = if (fixture.scenario.endsWith("disabled")) null else state.source,
                onTargetSelected = state::targetSelected,
                onDismiss = state::dismiss,
            )
        } else {
            MessageTranslationRendition(translatedSource)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun MaestroChatTranslationPresentation(fixture: MaestroPresentationFixture) {
    val context = LocalContext.current
    val account = checkNotNull(fixture.appState.activeAccount).accountIdHex
    val group = "fixture-translation-group"
    val preferences = remember(context) { TranslationPreferences(context) }
    val baseline = remember { preferences.override(account, group) to preferences.targetOverride(account, group) }
    DisposableEffect(preferences) {
        onDispose {
            preferences.setOverride(account, group, baseline.first)
            preferences.setTargetOverride(account, group, baseline.second)
            preferences.close()
        }
    }
    TranslationChatSettingsDialog(
        account = account,
        group = group,
        onDismiss = {
            val expected =
                when (fixture.scenario.substringAfterLast('-')) {
                    "on" -> TranslationOverride.ON
                    "off" -> TranslationOverride.OFF
                    "inherit" -> TranslationOverride.INHERIT
                    else -> error("Unregistered translation override")
                }
            check(preferences.override(account, group) == expected)
            fixture.finish("override-verified")
        },
    )
}
