package dev.ipf.whitenoise.android.widget

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationTapTokens
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** A private foreground PendingIntent target validates native identities before opening a route. */
class ConversationWidgetTapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val root =
                Intent(this@ConversationWidgetTapActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val state = (application as WhiteNoiseApplication).initializedAppState()
            val request = tapRequest()
            val keyguard = getSystemService(KeyguardManager::class.java)
            if (state != null && request != null && canOpenWidget(request, state, keyguard)) {
                val generation = state.runtimeGeneration
                val valid = validateNativeTarget(request, state)
                if (valid && targetStillCurrent(request, state, keyguard, generation)) {
                    val key = "widget:${request.widget}:${request.account}:${request.group}"
                    NotificationNavigation.applyToIntent(
                        root,
                        NotificationTarget(request.account, request.group, null, NotificationTargetKind.MESSAGE),
                        key,
                        NotificationTapTokens.create(this@ConversationWidgetTapActivity).tokenFor(key),
                    )
                }
            }
            startActivity(root)
            finish()
        }
    }

    private fun tapRequest(): WidgetTapRequest? {
        val account = intent.getStringExtra("account").orEmpty()
        val group = intent.getStringExtra("group").orEmpty()
        val widget = intent.getIntExtra("widget", 0)
        val choice = ConversationWidgetConfiguration(this).read(widget)
        if (account.isEmpty() || group.isEmpty() || choice == null) return null
        return WidgetTapRequest(account, group, widget, choice)
    }

    private fun canOpenWidget(
        request: WidgetTapRequest,
        state: WhiteNoiseAppState,
        keyguard: KeyguardManager?,
    ): Boolean {
        val configuredAccount = request.choice.allAccounts || request.account == request.choice.account
        return widgetUnlocked(state, keyguard) && configuredAccount
    }

    private suspend fun validateNativeTarget(
        request: WidgetTapRequest,
        state: WhiteNoiseAppState,
    ): Boolean =
        try {
            withTimeout(WIDGET_READ_TIMEOUT_MS) {
                state.marmotIo {
                    listAccounts().any { it.label == request.account } &&
                        groupDetails(request.account, request.group).group.groupIdHex == request.group
                }
            }
        } catch (cancelled: CancellationException) {
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
            false
        } catch (_: Exception) {
            false
        }

    private fun targetStillCurrent(
        request: WidgetTapRequest,
        state: WhiteNoiseAppState,
        keyguard: KeyguardManager?,
        generation: Int,
    ): Boolean =
        state.runtimeGeneration == generation &&
            canOpenWidget(request, state, keyguard) &&
            ConversationWidgetConfiguration(this).read(request.widget) == request.choice

    private data class WidgetTapRequest(
        val account: String,
        val group: String,
        val widget: Int,
        val choice: ConversationWidgetChoice,
    )
}
