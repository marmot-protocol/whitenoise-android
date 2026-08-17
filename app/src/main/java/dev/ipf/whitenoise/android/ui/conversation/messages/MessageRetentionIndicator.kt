package dev.ipf.whitenoise.android.ui.conversation.messages

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.retentionIndicatorVisible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.TimeZone

internal data class RetentionIndicatorInput(
    val controllerKey: Any,
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String,
    val sourceEpoch: ULong?,
    val durationSeconds: ULong,
    val expiresAtEpochSeconds: ULong?,
)

internal sealed interface RetentionIndicatorPresentation {
    data object Hidden : RetentionIndicatorPresentation

    /** Retention is enabled, but the engine has not projected a valid expiry interval yet. */
    data object Waiting : RetentionIndicatorPresentation

    data class Running(
        val remainingFraction: Float,
        val remainingMillis: Long,
        val expiresAtEpochMillis: Long,
        val refreshAfterMillis: Long?,
    ) : RetentionIndicatorPresentation
}

internal fun AppMessageRecordFfi.retentionIndicatorInput(
    controllerKey: Any,
    accountRef: String?,
    deleted: Boolean,
): RetentionIndicatorInput? =
    retentionIndicatorInput(
        controllerKey = controllerKey,
        accountRef = accountRef,
        groupIdHex = groupIdHex,
        messageIdHex = messageIdHex,
        sourceEpoch = sourceEpoch,
        durationSeconds = retentionSeconds,
        expiresAtEpochSeconds = retentionExpiresAt,
        deleted = deleted,
    )

internal fun retentionIndicatorInput(
    controllerKey: Any,
    accountRef: String?,
    groupIdHex: String,
    messageIdHex: String,
    sourceEpoch: ULong?,
    durationSeconds: ULong?,
    expiresAtEpochSeconds: ULong?,
    deleted: Boolean,
): RetentionIndicatorInput? {
    val duration = durationSeconds?.takeIf { retentionIndicatorVisible(it) }
    return if (accountRef == null || duration == null || deleted) {
        null
    } else {
        RetentionIndicatorInput(
            controllerKey = controllerKey,
            accountRef = accountRef,
            groupIdHex = groupIdHex,
            messageIdHex = messageIdHex,
            sourceEpoch = sourceEpoch,
            durationSeconds = duration,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
        )
    }
}

internal fun retentionIndicatorPresentation(
    input: RetentionIndicatorInput?,
    nowEpochMillis: Long,
): RetentionIndicatorPresentation {
    if (input == null) return RetentionIndicatorPresentation.Hidden
    val durationMillis = input.durationSeconds.toEpochMillisOrNull()
    val expiryMillis =
        input.expiresAtEpochSeconds
            ?.takeIf { it > 0uL }
            ?.toEpochMillisOrNull()
    return when {
        durationMillis == null || durationMillis <= 0L -> RetentionIndicatorPresentation.Waiting
        expiryMillis == null || expiryMillis < durationMillis -> RetentionIndicatorPresentation.Waiting
        else -> {
            val startMillis = expiryMillis - durationMillis
            val boundedNow = nowEpochMillis.coerceAtLeast(0L).coerceIn(startMillis, expiryMillis)
            val remainingMillis = expiryMillis - boundedNow
            val remainingFraction =
                (remainingMillis.toDouble() / durationMillis.toDouble())
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            RetentionIndicatorPresentation.Running(
                remainingFraction = remainingFraction,
                remainingMillis = remainingMillis,
                expiresAtEpochMillis = expiryMillis,
                refreshAfterMillis = retentionRefreshAfterMillis(remainingMillis),
            )
        }
    }
}

/**
 * Drives one composed retained row. Lazy-list disposal cancels this loop, so off-screen rows do
 * not keep clocks alive and no expiry state is persisted outside the engine projection.
 */
internal suspend fun runRetentionIndicatorTicker(
    input: RetentionIndicatorInput?,
    nowEpochMillis: () -> Long,
    waitMillis: suspend (Long) -> Unit,
    emit: (RetentionIndicatorPresentation) -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        val presentation = retentionIndicatorPresentation(input, nowEpochMillis())
        emit(presentation)
        val refreshAfter = (presentation as? RetentionIndicatorPresentation.Running)?.refreshAfterMillis ?: return
        waitMillis(refreshAfter)
    }
}

@Composable
internal fun rememberRetentionIndicatorPresentation(
    input: RetentionIndicatorInput?,
    nowEpochMillis: () -> Long = System::currentTimeMillis,
): RetentionIndicatorPresentation {
    if (input == null) return RetentionIndicatorPresentation.Hidden
    val initial = retentionIndicatorPresentation(input, nowEpochMillis())
    val currentClock by rememberUpdatedState(nowEpochMillis)
    val presentation by
        produceState(initialValue = initial, key1 = input) {
            runRetentionIndicatorTicker(
                input = input,
                nowEpochMillis = { currentClock() },
                waitMillis = { delay(it) },
                emit = { value = it },
            )
        }
    return presentation
}

@Composable
@Suppress("FunctionNaming") // Compose UI entry point.
internal fun MessageRetentionIndicator(
    presentation: RetentionIndicatorPresentation,
    color: Color,
) {
    val label = stringResource(R.string.disappearing_message)
    when (presentation) {
        RetentionIndicatorPresentation.Hidden -> Unit
        RetentionIndicatorPresentation.Waiting ->
            Icon(
                imageVector = Icons.Outlined.Timer,
                contentDescription = label,
                modifier = Modifier.size(14.dp),
                tint = color.copy(alpha = 0.76f),
            )
        is RetentionIndicatorPresentation.Running ->
            RunningRetentionIndicator(presentation, color, label)
    }
}

@Composable
@Suppress("FunctionNaming") // Compose UI entry point.
private fun RunningRetentionIndicator(
    presentation: RetentionIndicatorPresentation.Running,
    color: Color,
    label: String,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val timezoneId = TimeZone.getDefault().id
    val expiry =
        remember(locale, timezoneId, presentation.expiresAtEpochMillis) {
            DateUtils.formatDateTime(
                context,
                presentation.expiresAtEpochMillis,
                DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_SHOW_YEAR or
                    DateUtils.FORMAT_ABBREV_MONTH,
            )
        }
    val expiryState =
        remember(locale, presentation.remainingMillis, expiry) {
            formatRetentionExpiryState(locale, presentation.remainingMillis, expiry)
        }
    RetentionProgressRing(presentation.remainingFraction, color, label, expiryState)
}

@Composable
@Suppress("FunctionNaming") // Compose UI entry point.
private fun RetentionProgressRing(
    remainingFraction: Float,
    color: Color,
    label: String,
    expiryState: String,
) {
    val indicatorColor = color.copy(alpha = 0.82f)
    Canvas(
        modifier =
            Modifier
                .size(14.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = expiryState
                },
    ) {
        val strokeWidth = 1.35.dp.toPx()
        val radius = (size.minDimension - strokeWidth) / 2f
        drawCircle(
            color = color.copy(alpha = 0.24f),
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth),
        )
        if (remainingFraction > 0f) {
            drawArc(
                color = indicatorColor,
                startAngle = -90f,
                sweepAngle = FULL_CIRCLE_DEGREES * remainingFraction,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        drawCircle(
            color = indicatorColor,
            radius = 1.1.dp.toPx(),
            center = center,
        )
    }
}

private fun ULong.toEpochMillisOrNull(): Long? {
    if (this > MAX_EPOCH_SECONDS) return null
    return toLong() * MILLIS_PER_SECOND
}

internal fun retentionRefreshAfterMillis(remainingMillis: Long): Long? {
    if (remainingMillis <= 0L) return null
    val step =
        when {
            remainingMillis > MILLIS_PER_DAY -> 15L * MILLIS_PER_MINUTE
            remainingMillis > MILLIS_PER_HOUR -> 5L * MILLIS_PER_MINUTE
            remainingMillis > 5L * MILLIS_PER_MINUTE -> MILLIS_PER_MINUTE
            remainingMillis > MILLIS_PER_MINUTE -> 15L * MILLIS_PER_SECOND
            else -> MILLIS_PER_SECOND
        }
    val untilBoundary = ((remainingMillis - 1L) % step) + 1L
    return untilBoundary.coerceAtLeast(MIN_REFRESH_DELAY_MILLIS)
}

internal const val MILLIS_PER_SECOND = 1_000L
internal const val MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND
internal const val MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE
internal const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR
private const val MIN_REFRESH_DELAY_MILLIS = 250L
private const val FULL_CIRCLE_DEGREES = 360f
private val MAX_EPOCH_SECONDS = Long.MAX_VALUE.toULong() / MILLIS_PER_SECOND.toULong()
