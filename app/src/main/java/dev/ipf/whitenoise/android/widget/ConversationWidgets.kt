package dev.ipf.whitenoise.android.widget

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSubscription
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.FfiChatListWindowHandle
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

internal object ConversationWidgets {
    private val refreshEpoch = AtomicLong()

    fun observe(
        application: WhiteNoiseApplication,
        state: WhiteNoiseAppState,
    ) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    redact(context)
                    if (intent.action == Intent.ACTION_USER_PRESENT) {
                        application.applicationScope.launch { refresh(application, state) }
                    }
                }
            }
        ContextCompat.registerReceiver(
            application,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        application.applicationScope.launch(Dispatchers.Main) {
            collectWidgetRefreshes(
                widgetRefreshSignals(state),
            ) { redactFirst -> refresh(application, state, redactFirst) }
        }
    }

    fun redact(context: Context) {
        refreshEpoch.incrementAndGet()
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, ConversationWidgetProvider::class.java)).forEach { id ->
            manager.updateAppWidget(id, conversationWidgetViews(context, id, emptyList(), null))
        }
    }

    suspend fun refresh(
        context: Context,
        state: WhiteNoiseAppState?,
        redactFirst: Boolean = true,
    ) = withContext(Dispatchers.Main) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ConversationWidgetProvider::class.java))
        val epoch = refreshEpoch.incrementAndGet()
        if (redactFirst) redactWidgets(context, manager, ids)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val generation = state?.runtimeGeneration
        val accounts = availableAccounts(state, keyguard)
        if (accounts == null) redactWidgets(context, manager, ids)
        val refresh =
            WidgetRefresh(
                manager,
                ConversationWidgetConfiguration(context),
                keyguard,
                state,
                accounts,
                epoch,
                generation,
            )
        ids.forEach { id -> refreshWidget(context, id, refresh) }
    }

    private fun redactWidgets(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        ids.forEach { id -> manager.updateAppWidget(id, conversationWidgetViews(context, id, emptyList(), null)) }
    }

    private suspend fun availableAccounts(
        state: WhiteNoiseAppState?,
        keyguard: KeyguardManager?,
    ): Set<String>? =
        if (state != null && widgetUnlocked(state, keyguard)) {
            try {
                withTimeout(WIDGET_READ_TIMEOUT_MS) { state.marmotIo { listAccounts().map { it.label }.toSet() } }
            } catch (_: TimeoutCancellationException) {
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

    @VisibleForTesting
    internal suspend fun refreshWidget(
        context: Context,
        id: Int,
        refresh: WidgetRefresh,
    ) {
        // A newer account/configuration refresh owns both displayed rows and durable choice cleanup.
        if (!refreshContextCurrent(refresh)) return
        val choice = refresh.store.read(id)
        if (choice == null || !canRefresh(id, choice, refresh)) {
            redactWidgets(context, refresh.manager, intArrayOf(id))
            return
        }
        val state = checkNotNull(refresh.state)
        val accounts = checkNotNull(refresh.accounts)
        val result = readWidgetRows(state, choice, accounts)
        currentCoroutineContext().ensureActive()
        if (result == null && refreshEpoch.get() == refresh.epoch) {
            redactWidgets(context, refresh.manager, intArrayOf(id))
        }
        if (result != null && refreshCurrent(id, choice, refresh)) {
            val height =
                refresh.manager.getAppWidgetOptions(id).getInt(
                    AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                    DEFAULT_WIDGET_HEIGHT_DP,
                )
            val rows = selectConversationWidgetRows(choice, accounts, true, result, height / WIDGET_ROW_HEIGHT_DP)
            refresh.manager.updateAppWidget(id, conversationWidgetViews(context, id, rows, choice))
        }
    }

    private fun canRefresh(
        id: Int,
        choice: ConversationWidgetChoice,
        refresh: WidgetRefresh,
    ): Boolean {
        val available = refresh.accounts?.contains(choice.account) == true
        val unlocked = widgetUnlocked(refresh.state, refresh.keyguard)
        if (unlocked && !available && refresh.accounts != null) refresh.store.remove(id)
        return available && unlocked
    }

    private fun refreshCurrent(
        id: Int,
        choice: ConversationWidgetChoice,
        refresh: WidgetRefresh,
    ): Boolean {
        val sameChoice = refresh.store.read(id) == choice
        return refreshContextCurrent(refresh) && sameChoice && widgetUnlocked(refresh.state, refresh.keyguard)
    }

    private fun refreshContextCurrent(refresh: WidgetRefresh): Boolean {
        val sameRuntime = refreshEpoch.get() == refresh.epoch && refresh.state?.runtimeGeneration == refresh.generation
        val sameAccounts =
            refresh.accounts == null ||
                refresh.state
                    ?.accounts
                    ?.map { it.label }
                    ?.toSet() == refresh.accounts
        return sameRuntime && sameAccounts
    }

    private suspend fun readWidgetRows(
        state: WhiteNoiseAppState,
        choice: ConversationWidgetChoice,
        accounts: Set<String>,
    ): List<ConversationWidgetRow>? =
        try {
            withTimeout(WIDGET_READ_TIMEOUT_MS) { loadRows(state, choice, accounts) }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    @VisibleForTesting
    internal data class WidgetRefresh(
        val manager: AppWidgetManager,
        val store: ConversationWidgetConfiguration,
        val keyguard: KeyguardManager?,
        val state: WhiteNoiseAppState?,
        val accounts: Set<String>?,
        val epoch: Long,
        val generation: Int?,
    )

    private suspend fun loadRows(
        state: WhiteNoiseAppState,
        choice: ConversationWidgetChoice,
        accounts: Set<String>,
    ): List<ConversationWidgetRow> {
        val selected = if (choice.allAccounts) accounts.take(MAX_WIDGET_ROWS) else listOf(choice.account)
        return selected.flatMap { account ->
            var handle: ChatListWindowSubscription? = null
            try {
                state.marmotIo {
                    handle = openChatListWindow(account, ChatListViewFfi.CHATS, WIDGET_NATIVE_PAGE_SIZE)
                    widgetWindowRows(account, choice.unreadOnly, FfiChatListWindowHandle(checkNotNull(handle)))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { handle?.close() }
            }
        }
    }
}

internal fun widgetUnlocked(
    state: WhiteNoiseAppState?,
    keyguard: KeyguardManager?,
): Boolean {
    if (state == null) return false
    val appUnlocked = !state.appLockScreenVisible && !state.requireAppUnlock && !state.appUnlockEvaluationPending
    return appUnlocked && keyguard?.isDeviceLocked == false
}

internal const val WIDGET_READ_TIMEOUT_MS = 4_000L
private const val DEFAULT_WIDGET_HEIGHT_DP = 160
private const val WIDGET_ROW_HEIGHT_DP = 52
