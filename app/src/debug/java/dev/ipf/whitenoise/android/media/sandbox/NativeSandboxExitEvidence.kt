package dev.ipf.whitenoise.android.media.sandbox

/**
 * Matches only bounded, printable, logger-UID-qualified Zygote output.
 * It never substitutes for observed kernel absence.
 */
internal object NativeSandboxExitEvidence {
    const val MAX_BYTES = 65536
    private const val MILLIS_PER_SECOND = 1000
    private const val SIGNAL_GROUP = 3
    private const val MILLIS_DIGITS = 3

    private val record =
        Regex(
            """^[ \t]*([0-9]+(?:\.[0-9]+)?)[ \t]+(?:root|0)[ \t]+[0-9]+[ \t]+[0-9]+[ \t]+I[ \t]+Zygote[ \t]*:[ \t]*""" +
                """Process ([0-9]+) exited due to signal ([0-9]+) \([^\r\n]*\)(?:; core dumped)?[ \t]*$""",
        )

    fun logcatCommand(startedAtMs: Long): String {
        require(startedAtMs >= 0)
        val seconds = startedAtMs / MILLIS_PER_SECOND
        val millis = (startedAtMs % MILLIS_PER_SECOND).toString().padStart(MILLIS_DIGITS, '0')
        return "logcat -d -b main -v threadtime,epoch,uid,printable -T $seconds.$millis -s Zygote:I"
    }

    fun matches(
        output: String,
        pid: Int,
        signal: Int,
        startedAtMs: Long,
        endedAtMs: Long,
    ): Boolean {
        val invalidIdentity = pid <= 0 || signal <= 0
        val invalidOutput = output.length > MAX_BYTES || '\r' in output
        if (invalidOutput || invalidIdentity || startedAtMs > endedAtMs) return false
        return output.lineSequence().any { line ->
            val match = record.matchEntire(line)
            val timestamp =
                match
                    ?.groupValues
                    ?.get(1)
                    ?.toDoubleOrNull()
                    ?.times(MILLIS_PER_SECOND)
            match != null &&
                timestamp != null &&
                timestamp >= startedAtMs &&
                timestamp <= endedAtMs &&
                match.groupValues[2].toIntOrNull() == pid &&
                match.groupValues[SIGNAL_GROUP].toIntOrNull() == signal
        }
    }
}
