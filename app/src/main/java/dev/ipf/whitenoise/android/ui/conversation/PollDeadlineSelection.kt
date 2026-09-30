package dev.ipf.whitenoise.android.ui.conversation

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MAX_POLL_DEADLINE_SECONDS
import dev.ipf.whitenoise.android.state.POLL_DAY_SECONDS
import dev.ipf.whitenoise.android.state.POLL_HOUR_SECONDS

private const val SECONDS_PER_MINUTE = 60L
private const val DECIMAL_BASE = 10L

/** Units accepted by the custom poll duration editor. */
internal enum class PollDurationUnit(
    val seconds: Long,
    @StringRes val labelRes: Int,
) {
    SECONDS(1L, R.string.poll_unit_seconds),
    MINUTES(SECONDS_PER_MINUTE, R.string.poll_unit_minutes),
    HOURS(POLL_HOUR_SECONDS, R.string.poll_unit_hours),
    DAYS(POLL_DAY_SECONDS, R.string.poll_unit_days),
}

/** Keeps the last preset and custom draft when the creator switches between them. */
internal data class PollDeadlineSelection(
    val presetSeconds: Long? = null,
    val customSelected: Boolean = false,
    val customValue: String = "",
    val customUnit: PollDurationUnit = PollDurationUnit.MINUTES,
)

/** Explains why a custom duration cannot be sent to MDK. */
internal enum class PollDeadlineIssue { REQUIRED, INVALID, TOO_LONG }

/** A valid optional duration or an inline editor error. */
internal data class PollDeadlineValidation(
    val durationSeconds: Long?,
    val issue: PollDeadlineIssue? = null,
)

/** Converts positive decimal input without overflow or silent clamping. */
@Suppress("ReturnCount") // Invalid input exits before any unsafe conversion can reach the submit boundary.
internal fun validatePollDeadlineSelection(selection: PollDeadlineSelection): PollDeadlineValidation {
    if (!selection.customSelected) return PollDeadlineValidation(selection.presetSeconds)
    val value = selection.customValue.trim()
    if (value.isEmpty()) return PollDeadlineValidation(null, PollDeadlineIssue.REQUIRED)
    val maxUnits = MAX_POLL_DEADLINE_SECONDS / selection.customUnit.seconds
    var units = 0L
    for (character in value) {
        val digit = Character.digit(character, DECIMAL_BASE.toInt())
        if (digit < 0) return PollDeadlineValidation(null, PollDeadlineIssue.INVALID)
        if (units > (maxUnits - digit) / DECIMAL_BASE) {
            return PollDeadlineValidation(null, PollDeadlineIssue.TOO_LONG)
        }
        units = units * DECIMAL_BASE + digit
    }
    if (units == 0L) return PollDeadlineValidation(null, PollDeadlineIssue.INVALID)
    return PollDeadlineValidation(units * selection.customUnit.seconds)
}
