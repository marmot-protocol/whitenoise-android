package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Inputs that scope one lifecycle-bound notification-settings preparation pass. */
internal data class ConversationNotificationSettingsPreparationRequest(
    val accountRef: String,
    val groupIdHex: String,
    val isDm: Boolean,
    val conversationTitle: String,
    val conversationAvatarUrl: String?,
    val primaryVibrationPattern: ConversationVibrationPattern,
    val requestedParents: List<NotificationChannelSpec>,
)

/** Exact Android-owned identifiers that a later tap may launch without preparation work. */
internal data class PreparedConversationNotificationSettingsTarget(
    val channelId: String,
    val conversationShortcutId: String,
    val operationId: Long,
)

/** UI readiness result; failures retain an operation id for fallback timing evidence. */
internal sealed interface ConversationNotificationSettingsPreparation {
    val operationId: Long

    data class Ready(
        override val operationId: Long,
        val targetsByParentChannelId: Map<String, PreparedConversationNotificationSettingsTarget>,
    ) : ConversationNotificationSettingsPreparation

    data class Failed(
        override val operationId: Long,
    ) : ConversationNotificationSettingsPreparation
}

/** Platform boundary that keeps Binder-backed shortcut and channel work testable. */
internal interface ConversationNotificationSettingsPlatform {
    /** Lists existing dynamic shortcuts so preparation can update without rate-limited pushes. */
    suspend fun dynamicShortcuts(context: Context): List<ShortcutInfoCompat>

    /** Publishes a new shortcut or refreshes an existing mutable dynamic shortcut. */
    suspend fun publishShortcut(
        context: Context,
        shortcut: ShortcutInfoCompat,
        existing: Boolean,
    )

    /** Resolves the active channel version for one prepared parent and vibration choice. */
    suspend fun ensureChannel(
        context: Context,
        parent: NotificationChannelSpec,
        shortcutId: String,
        conversationTitle: String,
        vibrationPattern: ConversationVibrationPattern,
    ): String?
}

/** Real Android implementation of the preparation platform boundary. */
internal object AndroidConversationNotificationSettingsPlatform : ConversationNotificationSettingsPlatform {
    /** Reads Android's current dynamic shortcut inventory off the main thread. */
    override suspend fun dynamicShortcuts(context: Context) = ShortcutManagerCompat.getDynamicShortcuts(context)

    /** Uses update for repeat entries and reserves rate-limited push for first publication. */
    override suspend fun publishShortcut(
        context: Context,
        shortcut: ShortcutInfoCompat,
        existing: Boolean,
    ) {
        val accepted =
            if (existing) {
                ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))
            } else {
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            }
        check(accepted) {
            "Android rejected the conversation shortcut"
        }
    }

    /** Delegates channel creation/version resolution to the notification channel owner. */
    override suspend fun ensureChannel(
        context: Context,
        parent: NotificationChannelSpec,
        shortcutId: String,
        conversationTitle: String,
        vibrationPattern: ConversationVibrationPattern,
    ): String? =
        ConversationNotificationChannels.ensureConversationChannel(
            context = context,
            parentChannelId = parent.id,
            conversationShortcutId = shortcutId,
            conversationTitle = conversationTitle,
            vibrationPattern = vibrationPattern,
        )
}

/** Opaque cross-process timing identity retained from preparation through Settings launch. */
internal data class ConversationNotificationSettingsClickTrace(
    val operationId: Long,
    val clickedAtElapsedMs: Long,
)

/** Result returned immediately after dispatching the preferred or fallback Settings intent. */
internal data class ConversationNotificationSettingsLaunchAttempt(
    val opened: Boolean,
    val usedFallback: Boolean,
    val clickTrace: ConversationNotificationSettingsClickTrace,
)

/**
 * Privacy-safe timing recorder for the preparation and launch boundary.
 *
 * Messages contain only a process-local opaque id, a fixed stage name, a
 * duration, and an outcome. Conversation identifiers, titles, and channel ids
 * are deliberately excluded.
 */
internal class ConversationNotificationSettingsTrace(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val logger: (String) -> Unit = { message -> Log.i(TRACE_TAG, message) },
) {
    private val operationCounter = AtomicLong(0L)

    /** Starts a preparation operation without recording any conversation data. */
    fun beginPreparation(): Long =
        nextOperationId().also { operationId ->
            record(operationId, STAGE_PREPARE_BEGIN, durationMs = 0L, outcome = OUTCOME_OK)
        }

    /** Records a fixed stage around synchronous or suspending platform work. */
    suspend fun <T> measure(
        operationId: Long,
        stage: String,
        block: suspend () -> T,
    ): T {
        val startedAt = elapsedRealtime()
        Trace.beginSection("WNConversationSettings:$stage")
        return try {
            val result = runCatching { block() }
            result.onSuccess {
                recordElapsed(operationId, stage, startedAt, OUTCOME_OK)
            }
            result.onFailure { failure ->
                recordElapsed(
                    operationId = operationId,
                    stage = stage,
                    startedAt = startedAt,
                    outcome = if (failure is CancellationException) OUTCOME_CANCELLED else OUTCOME_FAILED,
                )
            }
            result.getOrThrow()
        } finally {
            Trace.endSection()
        }
    }

    /** Records the terminal preparation outcome. */
    fun finishPreparation(
        operationId: Long,
        startedAtElapsedMs: Long,
        outcome: String,
    ) {
        recordElapsed(operationId, STAGE_PREPARE_TOTAL, startedAtElapsedMs, outcome)
    }

    /** Starts the user-tap interval, reusing preparation identity when available. */
    fun clickReceived(operationId: Long? = null): ConversationNotificationSettingsClickTrace {
        val trace =
            ConversationNotificationSettingsClickTrace(
                operationId = operationId ?: nextOperationId(),
                clickedAtElapsedMs = elapsedRealtime(),
            )
        record(trace.operationId, STAGE_CLICK_RECEIVED, durationMs = 0L, outcome = OUTCOME_OK)
        Trace.beginSection("WNConversationSettings:click_to_start_activity")
        return trace
    }

    /** Records the app-side interval immediately before calling `startActivity`. */
    fun startActivityCalled(clickTrace: ConversationNotificationSettingsClickTrace) {
        recordElapsed(
            operationId = clickTrace.operationId,
            stage = STAGE_START_ACTIVITY,
            startedAt = clickTrace.clickedAtElapsedMs,
            outcome = OUTCOME_OK,
        )
        Trace.endSection()
    }

    /** Records how long the platform `startActivity` call itself occupied the caller. */
    fun startActivityReturned(
        clickTrace: ConversationNotificationSettingsClickTrace,
        callStartedAtElapsedMs: Long,
        opened: Boolean,
    ) {
        recordElapsed(
            operationId = clickTrace.operationId,
            stage = STAGE_START_ACTIVITY_RETURN,
            startedAt = callStartedAtElapsedMs,
            outcome = if (opened) OUTCOME_OK else OUTCOME_FAILED,
        )
    }

    /** Records the separately measured system transition when Settings first renders. */
    fun firstSettingsFrame(clickTrace: ConversationNotificationSettingsClickTrace): Long {
        val duration = (elapsedRealtime() - clickTrace.clickedAtElapsedMs).coerceAtLeast(0L)
        record(clickTrace.operationId, STAGE_FIRST_SETTINGS_FRAME, duration, OUTCOME_OK)
        return duration
    }

    /** Current monotonic clock used to bound a full preparation pass. */
    fun now(): Long = elapsedRealtime()

    /** Converts a monotonic start timestamp into the common opaque record shape. */
    private fun recordElapsed(
        operationId: Long,
        stage: String,
        startedAt: Long,
        outcome: String,
    ) {
        record(operationId, stage, (elapsedRealtime() - startedAt).coerceAtLeast(0L), outcome)
    }

    /** Emits the only allowed privacy-safe log fields for one timing event. */
    private fun record(
        operationId: Long,
        stage: String,
        durationMs: Long,
        outcome: String,
    ) {
        logger("operation_id=$operationId stage=$stage duration_ms=$durationMs outcome=$outcome")
    }

    /** Allocates a process-local nonzero identity and wraps safely after exhaustion. */
    private fun nextOperationId(): Long =
        operationCounter.updateAndGet { current ->
            if (current == Long.MAX_VALUE) 1L else current + 1L
        }

    internal companion object {
        const val OUTCOME_OK = "ok"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_CANCELLED = "cancelled"
        private const val TRACE_TAG = "ConversationSettings"
        private const val STAGE_PREPARE_BEGIN = "prepare_begin"
        private const val STAGE_PREPARE_TOTAL = "prepare_total"
        private const val STAGE_CLICK_RECEIVED = "click_received"
        private const val STAGE_START_ACTIVITY = "start_activity"
        private const val STAGE_START_ACTIVITY_RETURN = "start_activity_return"
        private const val STAGE_FIRST_SETTINGS_FRAME = "first_settings_frame"
    }
}

internal val defaultConversationNotificationSettingsTrace = ConversationNotificationSettingsTrace()

/**
 * Publishes one scoped shortcut and resolves every requested active child on a
 * background dispatcher before the user can tap a category row.
 */
internal class ConversationNotificationSettingsPreparer(
    private val platform: ConversationNotificationSettingsPlatform = AndroidConversationNotificationSettingsPlatform,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val trace: ConversationNotificationSettingsTrace = defaultConversationNotificationSettingsTrace,
) {
    /** Completes all mutable Android work before publishing tappable settings rows. */
    suspend fun prepare(
        context: Context,
        request: ConversationNotificationSettingsPreparationRequest,
    ): ConversationNotificationSettingsPreparation =
        withContext(dispatcher) {
            val operationId = trace.beginPreparation()
            val preparationStartedAt = trace.now()
            try {
                val shortcut = prepareShortcut(context, request, operationId)
                val targets = prepareChannels(context, request, shortcut, operationId)
                trace.finishPreparation(
                    operationId,
                    preparationStartedAt,
                    ConversationNotificationSettingsTrace.OUTCOME_OK,
                )
                ConversationNotificationSettingsPreparation.Ready(operationId, targets)
            } catch (cancelled: CancellationException) {
                trace.finishPreparation(
                    operationId,
                    preparationStartedAt,
                    ConversationNotificationSettingsTrace.OUTCOME_CANCELLED,
                )
                throw cancelled
            } catch (_: Exception) {
                trace.finishPreparation(
                    operationId,
                    preparationStartedAt,
                    ConversationNotificationSettingsTrace.OUTCOME_FAILED,
                )
                ConversationNotificationSettingsPreparation.Failed(operationId)
            }
        }

    /** Resolves and refreshes the single account-scoped conversation shortcut. */
    private suspend fun prepareShortcut(
        context: Context,
        request: ConversationNotificationSettingsPreparationRequest,
        operationId: Long,
    ): ShortcutInfoCompat {
        currentCoroutineContext().ensureActive()
        val shortcutId = requireNotNull(conversationShortcutId(request.accountRef, request.groupIdHex))
        val existing =
            trace.measure(operationId, STAGE_SHORTCUT_LOOKUP) {
                platform.dynamicShortcuts(context).firstOrNull { shortcut -> shortcut.id == shortcutId }
            }
        currentCoroutineContext().ensureActive()
        val shortcut =
            trace.measure(operationId, STAGE_SHORTCUT_BUILD) {
                conversationSettingsShortcut(
                    context = context,
                    shortcutId = shortcutId,
                    accountRef = request.accountRef,
                    groupIdHex = request.groupIdHex,
                    title = request.conversationTitle,
                    avatarUrl = request.conversationAvatarUrl,
                    existing = existing,
                )
            }
        currentCoroutineContext().ensureActive()
        trace.measure(
            operationId,
            if (existing == null) STAGE_SHORTCUT_PUSH else STAGE_SHORTCUT_UPDATE,
        ) {
            platform.publishShortcut(context, shortcut, existing = existing != null)
        }
        return shortcut
    }

    /** Ensures only required and explicitly requested children, returning their active versions. */
    private suspend fun prepareChannels(
        context: Context,
        request: ConversationNotificationSettingsPreparationRequest,
        shortcut: ShortcutInfoCompat,
        operationId: Long,
    ): Map<String, PreparedConversationNotificationSettingsTarget> {
        currentCoroutineContext().ensureActive()
        val primaryParent = ConversationNotificationChannels.primaryMessageParent(request.isDm)
        val parents =
            (listOf(primaryParent) + request.requestedParents)
                .distinctBy(NotificationChannelSpec::id)
        return trace.measure(operationId, STAGE_CHANNEL_ENSURE) {
            parents.associate { parent ->
                currentCoroutineContext().ensureActive()
                val vibrationPattern =
                    if (parent == primaryParent) {
                        request.primaryVibrationPattern
                    } else {
                        ConversationVibrationPattern.SYSTEM_DEFAULT
                    }
                val activeChannelId =
                    requireNotNull(
                        platform.ensureChannel(
                            context = context,
                            parent = parent,
                            shortcutId = shortcut.id,
                            conversationTitle = shortcut.longLabel.toString(),
                            vibrationPattern = vibrationPattern,
                        ),
                    ) { "The active Android notification channel is unavailable" }
                parent.id to
                    PreparedConversationNotificationSettingsTarget(
                        channelId = activeChannelId,
                        conversationShortcutId = shortcut.id,
                        operationId = operationId,
                    )
            }
        }
    }

    private companion object {
        const val STAGE_SHORTCUT_LOOKUP = "shortcut_lookup"
        const val STAGE_SHORTCUT_BUILD = "shortcut_build"
        const val STAGE_SHORTCUT_PUSH = "shortcut_push"
        const val STAGE_SHORTCUT_UPDATE = "shortcut_update"
        const val STAGE_CHANNEL_ENSURE = "channel_ensure"
    }
}

/** Main-thread gate that coalesces rapid taps until the app resumes or dispatch fails. */
internal class ConversationNotificationSettingsLaunchGate {
    private var launchInFlight = false

    /** Returns true only for the first tap in the current launch interval. */
    fun tryBegin(): Boolean {
        if (launchInFlight) return false
        launchInFlight = true
        return true
    }

    /** Allows a future launch after returning from Android Settings. */
    fun onResumed() {
        launchInFlight = false
    }

    /** Allows an immediate retry when no Settings activity accepted the intent. */
    fun onLaunchFailed() {
        launchInFlight = false
    }
}
