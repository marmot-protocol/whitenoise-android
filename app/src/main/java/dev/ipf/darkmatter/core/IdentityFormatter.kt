package dev.ipf.darkmatter.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object IdentityFormatter {
    fun short(
        value: String,
        prefix: Int = 8,
        suffix: Int = 4,
    ): String {
        if (value.length <= prefix + suffix + 1) return value
        return "${value.take(prefix)}...${value.takeLast(suffix)}"
    }

    fun initials(name: String): String {
        val words =
            name
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
        val letters =
            when {
                words.size >= 2 -> "${words[0].first()}${words[1].first()}"
                words.size == 1 -> words[0].take(2)
                else -> "DM"
            }
        return letters.uppercase()
    }

    fun relativeTime(epochSeconds: ULong): String {
        if (epochSeconds == 0uL) return ""
        val instant = Instant.ofEpochSecond(epochSeconds.toLong())
        val now = Instant.now()
        val delta = now.epochSecond - instant.epochSecond
        return when {
            // Sender clock skew or a relay-provided future timestamp.
            delta < 0 -> "future"
            delta < 60 -> "now"
            delta < 3_600 -> "${delta / 60}m"
            delta < 86_400 -> "${delta / 3_600}h"
            delta < 604_800 -> "${delta / 86_400}d"
            else ->
                DateTimeFormatter
                    .ofPattern("MMM d")
                    .withZone(ZoneId.systemDefault())
                    .format(instant)
        }
    }
}
