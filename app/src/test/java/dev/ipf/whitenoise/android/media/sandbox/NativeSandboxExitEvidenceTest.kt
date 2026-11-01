package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSandboxExitEvidenceTest {
    private val killed = "1793519366.996 337 337 I Zygote : Process 7941 exited due to signal 9 (Killed)"
    private val syscall =
        "1793519373.099 337 337 I Zygote : Process 7979 exited due to signal 31 (Bad system call); core dumped"
    private val started = 1793519360000L
    private val ended = 1793519380000L

    @Test
    fun exactKernelSignalRecordsCoverKillAndFatalSyscall() {
        assertTrue(NativeSandboxExitEvidence.matches(killed, 7941, 9, started, ended))
        assertTrue(NativeSandboxExitEvidence.matches(syscall, 7979, 31, started, ended))
    }

    @Test
    fun anotherWorkerSignalOrTimeCannotQualifyThisProbe() {
        assertFalse(NativeSandboxExitEvidence.matches(killed, 7979, 9, started, ended))
        assertFalse(NativeSandboxExitEvidence.matches(killed, 7941, 31, started, ended))
        assertFalse(NativeSandboxExitEvidence.matches(killed, 7941, 9, ended, ended + 1000))
        assertFalse(NativeSandboxExitEvidence.matches(killed, 7941, 9, started, started + 1000))
        assertFalse(NativeSandboxExitEvidence.matches(killed, 7941, 9, ended, started))
    }

    @Test
    fun malformedOtherTagsAndUnboundedOutputAreNotSignalEvidence() {
        for (output in listOf(
            killed.replace("I Zygote", "I Other"),
            killed.replace("1793519366.996", "not-a-time"),
            killed.replace("7941", "79410"),
            killed.replace("9 (Killed)", "9"),
            "Process 7941 exited due to signal 9 (Killed)",
            "x".repeat(NativeSandboxExitEvidence.MAX_BYTES) + killed,
        )) {
            assertFalse(NativeSandboxExitEvidence.matches(output, 7941, 9, started, ended))
        }
    }
}
