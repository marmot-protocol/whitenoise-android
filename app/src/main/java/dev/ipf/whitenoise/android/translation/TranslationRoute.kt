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
import dev.ipf.whitenoise.android.R
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
                {
                    active &&
                        account != null &&
                        appState.activeAccountRef == account &&
                        appState.runtimeGeneration == runtime &&
                        !appState.appLockScreenVisible &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                },
            )
        }
    val preferencesRevision by preferences.changes.collectAsState()
    var ownedNotice by remember(session) { mutableStateOf<TransientNotice?>(null) }
    LaunchedEffect(session, preferencesRevision, routeActive, appState.appLockScreenVisible) { session.clear() }
    LaunchedEffect(session.failure) {
        ownedNotice?.let { appState.clearTransientNotice(it) }
        ownedNotice = null
        if (session.failure != null && account != null) {
            ownedNotice = appState.presentConversationTransient(account, group, R.string.translation_recovery_notice)
        }
    }
    DisposableEffect(session, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) session.clear()
                if (event == Lifecycle.Event.ON_RESUME) session.clear()
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    session.clear()
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
            session.clear()
            ownedNotice?.let { appState.clearTransientNotice(it) }
        }
    }
    return session
}
