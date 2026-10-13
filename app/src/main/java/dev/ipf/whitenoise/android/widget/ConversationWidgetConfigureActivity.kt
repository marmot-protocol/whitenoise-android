@file:Suppress("FunctionNaming") // Unit-returning Compose controls follow the app convention.

package dev.ipf.whitenoise.android.widget

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.AppFont
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ConversationWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID ||
            manager.getAppWidgetInfo(id)?.provider != ComponentName(this, ConversationWidgetProvider::class.java)
        ) {
            finish()
            return
        }
        val application = application as WhiteNoiseApplication
        val state = application.initializedAppState()
        val keyguard = getSystemService(KeyguardManager::class.java)
        setContent {
            WidgetConfigurationHost(state, keyguard) { choice ->
                lifecycleScope.launch { saveWidget(id, application, state, keyguard, choice) }
            }
        }
    }

    @Composable
    private fun WidgetConfigurationHost(
        state: WhiteNoiseAppState?,
        keyguard: KeyguardManager?,
        onSave: (ConversationWidgetChoice) -> Unit,
    ) {
        val systemDark = isSystemInDarkTheme()
        val dark = state?.themeMode?.resolveDarkTheme(systemDark) ?: systemDark
        val profiles = widgetProfiles(state, widgetUnlocked(state, keyguard))
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        WhiteNoiseTheme(
            darkTheme = dark,
            amoled = state?.themeMode?.isAmoled == true,
            fontScale = state?.fontScale?.factor ?: 1f,
            appFont = state?.appFont ?: AppFont.System,
        ) {
            ConversationWidgetConfigurationContent(
                accounts = profiles,
                onCancel = { finish() },
                onOpenApp = { startActivity(Intent(this, MainActivity::class.java)) },
                onSave = onSave,
            )
        }
    }

    @Composable
    private fun widgetProfiles(
        state: WhiteNoiseAppState?,
        available: Boolean,
    ): List<WidgetAccountPresentation> {
        val profileIds = if (available) state?.accounts?.map { it.accountIdHex }.orEmpty() else emptyList()
        LaunchedEffect(state, profileIds) {
            try {
                state?.warmProfilePresentationsBlocking(profileIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Existing cached names/initials remain usable when local hydration fails.
            }
        }
        return remember(
            state?.accounts?.toList(),
            state?.profileRevisionForCompose,
            state?.activeAccountRef,
            available,
        ) {
            if (available && state != null) {
                state.accounts.mapIndexed { index, account ->
                    WidgetAccountPresentation(
                        reference = account.label,
                        seed = account.accountIdHex,
                        name =
                            widgetProfileTitle(
                                state.accountDisplayNameCached(account.accountIdHex),
                                getString(R.string.profile),
                                index,
                            ),
                        pictureUrl = state.avatarUrl(account.accountIdHex),
                        active = account.label == state.activeAccountRef,
                    )
                }
            } else {
                emptyList()
            }
        }
    }

    private suspend fun saveWidget(
        id: Int,
        application: WhiteNoiseApplication,
        state: WhiteNoiseAppState?,
        keyguard: KeyguardManager?,
        choice: ConversationWidgetChoice,
    ) {
        val generation = state?.runtimeGeneration
        val valid =
            if (widgetUnlocked(state, keyguard)) {
                runCatching {
                    withTimeout(WIDGET_READ_TIMEOUT_MS) {
                        state?.marmotIo { listAccounts().any { it.label == choice.account } } == true
                    }
                }.getOrDefault(false)
            } else {
                false
            }
        if (valid && widgetUnlocked(state, keyguard) && generation == state?.runtimeGeneration) {
            val saved =
                withContext(Dispatchers.IO) {
                    runCatching {
                        ConversationWidgetConfiguration(this@ConversationWidgetConfigureActivity).save(id, choice)
                    }.getOrDefault(false)
                }
            if (!saved || !widgetUnlocked(state, keyguard) || generation != state?.runtimeGeneration) return
            application.applicationScope.launch { ConversationWidgets.refresh(application, state) }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations) finish()
    }
}

/** A display fallback only; the account reference never becomes a visible title. */
internal fun widgetProfileTitle(
    name: String,
    fallback: String,
    index: Int,
): String =
    name.takeUnless { it.isBlank() || it.startsWith("npub1") || it.matches(Regex("[a-fA-F0-9]{64}")) }
        ?: "$fallback ${index + 1}"
