package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import org.junit.jupiter.api.Tag

/** Harness target used only by `scripts/fuzz-triage.sh --self-check`. */
@Tag("fuzz-triage-selfcheck")
class FuzzTriageSelfCheck {
    @FuzzTest
    fun fuzzTriageSelfCheck(data: ByteArray) {
        if (data.size < MAGIC.size) return
        for (index in MAGIC.indices) {
            if ((data[index].toInt() and 0xFF) != MAGIC[index]) return
        }
        throw IllegalStateException("fuzz-triage self-check")
    }

    private companion object {
        private val MAGIC = intArrayOf(0xFE, 0xED, 0xF0, 0x0D)
    }
}
