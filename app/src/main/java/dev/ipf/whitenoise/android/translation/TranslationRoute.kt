package dev.ipf.whitenoise.android.translation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalTranslationPreferences = staticCompositionLocalOf<TranslationPreferences?> { null }

@Composable
internal fun rememberTranslationPreferences(): TranslationPreferences {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { TranslationPreferences(context) }
    DisposableEffect(preferences) { onDispose { preferences.close() } }
    return preferences
}

@Composable
internal fun rememberConversationTranslation(
    appState: WhiteNoiseAppState,
    account: String?,
    group: String,
    preferences: TranslationPreferences,
    routeActive: Boolean,
): ConversationTranslationSession {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val runtime = appState.runtimeGeneration
    val active by rememberUpdatedState(routeActive)
    val session =
        remember(account, group, runtime) {
            ConversationTranslationSession(
                scope,
                { withContext(Dispatchers.IO) { OfflineTranslatorClient(context.applicationContext) } },
                { active && account != null && appState.translationOwnerCurrent(account, runtime, lifecycle) },
            )
        }
    val preferencesRevision by preferences.changes.collectAsState()
    var ownedNotice by remember(session) { mutableStateOf<TransientNotice?>(null) }
    LaunchedEffect(session, preferencesRevision, routeActive, appState.appLockScreenVisible) { session.clear() }
    LaunchedEffect(session.failure) {
        ownedNotice?.let { appState.clearTransientNotice(it) }
        ownedNotice = null
        if (session.failure != null && account != null) {
            ownedNotice = appState.presentConversationTransient(account, group, recoveryNotice(session.failure))
        }
    }
    TranslationOwnerResetEffect(session, onReset = session::clear) {
        session.clear()
        ownedNotice?.let { appState.clearTransientNotice(it) }
    }
    return session
}

/** Whether translation work started for [account] in [runtime] may still change what is on screen. */
internal fun WhiteNoiseAppState.translationOwnerCurrent(
    account: String,
    runtime: Int,
    lifecycle: Lifecycle,
): Boolean =
    activeAccountRef == account &&
        runtimeGeneration == runtime &&
        !appLockScreenVisible &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

/**
 * Discards [owner]'s translation work when the screen pauses or resumes or Offline Translator is removed, replaced
 * or changed, because a result started before then may come from another engine or be shown to someone else.
 */
@Composable
@Suppress("FunctionNaming")
internal fun TranslationOwnerResetEffect(
    owner: Any,
    onReset: () -> Unit,
    onDispose: () -> Unit = onReset,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reset by rememberUpdatedState(onReset)
    val dispose by rememberUpdatedState(onDispose)
    DisposableEffect(owner, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_RESUME) reset()
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    reset()
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
                addDataSchemeSpecificPart(OFFLINE_TRANSLATOR_PACKAGE, android.os.PatternMatcher.PATTERN_LITERAL)
            }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            dispose()
        }
    }
}
