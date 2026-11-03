package dev.ipf.whitenoise.android.ui.conversation

import android.content.res.Resources
import dev.ipf.whitenoise.android.R

private const val SECONDS_PER_MINUTE = 60

/** A read-aloud estimate split into whole minutes and the seconds left over (#3563). */
internal data class TtsRemainingTime(
    val minutes: Int,
    val seconds: Int,
)

/** Splits a remaining-seconds estimate; a negative estimate counts as zero. */
internal fun ttsRemainingTime(totalSeconds: Int): TtsRemainingTime {
    val total = totalSeconds.coerceAtLeast(0)
    return TtsRemainingTime(minutes = total / SECONDS_PER_MINUTE, seconds = total % SECONDS_PER_MINUTE)
}

/**
 * Compact transport label: seconds alone below a minute, then minutes with any leftover seconds, so a
 * five-minute estimate reads "~5m 12s left" rather than "~312s left".
 */
internal fun ttsRemainingLabel(
    resources: Resources,
    totalSeconds: Int,
): String {
    val (minutes, seconds) = ttsRemainingTime(totalSeconds)
    return when {
        minutes == 0 -> resources.getQuantityString(R.plurals.tts_bar_seconds_remaining, seconds, seconds)
        seconds == 0 -> resources.getQuantityString(R.plurals.tts_bar_minutes_remaining, minutes, minutes)
        else -> resources.getString(R.string.tts_bar_minutes_seconds_remaining, minutes, seconds)
    }
}

/** Natural-language equivalent of [ttsRemainingLabel] for TalkBack, with words in place of unit symbols. */
internal fun ttsRemainingDescription(
    resources: Resources,
    totalSeconds: Int,
): String {
    val (minutes, seconds) = ttsRemainingTime(totalSeconds)
    val spokenMinutes = resources.getQuantityString(R.plurals.tts_bar_spoken_minutes, minutes, minutes)
    val spokenSeconds = resources.getQuantityString(R.plurals.tts_bar_spoken_seconds, seconds, seconds)
    val duration =
        when {
            minutes == 0 -> spokenSeconds
            seconds == 0 -> spokenMinutes
            else -> resources.getString(R.string.tts_bar_spoken_minutes_seconds, spokenMinutes, spokenSeconds)
        }
    return resources.getString(R.string.tts_bar_remaining_spoken, duration)
}
