package dev.ipf.whitenoise.android.media.sandbox

/** Matches only bounded root-UID-filtered Zygote output; it never substitutes for observed kernel absence. */
internal object NativeSandboxExitEvidence {
    const val MAX_BYTES = 65536
    private val record =
        Regex(
            """^\s*([0-9]+(?:\.[0-9]+)?)\s+[0-9]+\s+[0-9]+\s+I\s+Zygote\s*:\s*""" +
                """Process ([0-9]+) exited due to signal ([0-9]+) \([^\r\n]*\)(?:; core dumped)?\s*$""",
        )

    fun matches(
        output: String,
        pid: Int,
        signal: Int,
        startedAtMs: Long,
        endedAtMs: Long,
    ): Boolean {
        if (output.length > MAX_BYTES || pid <= 0 || signal <= 0 || startedAtMs > endedAtMs) return false
        return output.lineSequence().any { line ->
            val match = record.matchEntire(line)
            val timestamp =
                match
                    ?.groupValues
                    ?.get(1)
                    ?.toDoubleOrNull()
                    ?.times(1000)
            match != null &&
                timestamp != null &&
                timestamp >= startedAtMs &&
                timestamp <= endedAtMs &&
                match.groupValues[2].toIntOrNull() == pid &&
                match.groupValues[3].toIntOrNull() == signal
        }
    }
}
