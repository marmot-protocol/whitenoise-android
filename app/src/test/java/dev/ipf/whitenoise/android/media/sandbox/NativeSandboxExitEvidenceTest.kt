package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSandboxExitEvidenceTest {
    private val killed = "1793519366.996 root 337 337 I Zygote : Process 7941 exited due to signal 9 (Killed)"
    private val syscall =
        "1793519373.099 root 337 337 I Zygote : Process 7979 exited due to signal 31 (Bad system call); core dumped"
    private val started = 1793519360000L
    private val ended = 1793519380000L

    /** Exact Kernel Signal Records Cover Kill And Fatal Syscall. */
    @Test
    fun exactKernelSignalRecordsCoverKillAndFatalSyscall() {
        assertTrue(NativeSandboxExitEvidence.matches(killed, 7941, 9, started, ended))
        assertTrue(NativeSandboxExitEvidence.matches(syscall, 7979, 31, started, ended))
    }

    /** Another Worker Signal Or Time Cannot Qualify This Probe. */
    @Test
    fun anotherWorkerSignalOrTimeCannotQualifyThisProbe() {
        assertFalse(NativeSandboxExitEvidence.matches(killed, 7979, 9, started, ended))
        assertFalse(NativeSandboxExitEvidence.matches(killed, 7941, 31, started, ended))
        assertFalse(NativeSandboxExitEvidence.matches(killed, 7941, 9, ended, ended + 1000))
        assertFalse(NativeSandboxExitEvidence.matches(killed, 7941, 9, started, started + 1000))
        assertFalse(NativeSandboxExitEvidence.matches(killed, 7941, 9, ended, started))
    }

    /** Malformed Other Tags And Unbounded Output Are Not Signal Evidence. */
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

    /** The command pins the API-30-compatible output protocol and exact probe window. */
    @Test
    fun commandUsesSupportedExplicitPrintableUidFormatAndProbeWindow() {
        assertEquals(
            "logcat -d -b main -v threadtime,epoch,uid,printable -T 1793519360.007 -s Zygote:I",
            NativeSandboxExitEvidence.logcatCommand(1793519360007L),
        )
        assertTrue(NativeSandboxExitEvidence.logcatCommand(0).contains("-T 0.000 "))
    }

    /** Printed logger identity and escaped message boundaries cannot be substituted by app text. */
    @Test
    fun onlyRootLoggerUidQualifiesAndRawCarriageReturnFailsClosed() {
        assertTrue(NativeSandboxExitEvidence.matches(killed.replace(" root ", " 0 "), 7941, 9, started, ended))
        for (uid in listOf("1000", "10001", "u0_a1", "rooted", "00")) {
            assertFalse(NativeSandboxExitEvidence.matches(killed.replace(" root ", " $uid "), 7941, 9, started, ended))
        }
        assertFalse(
            NativeSandboxExitEvidence.matches(
                killed.replace(" root ", " 10001 ") + "\r" + killed,
                7941,
                9,
                started,
                ended,
            ),
        )
        val appPrefix = "1793519366.996 10001 123 123 I Zygote : "
        assertFalse(NativeSandboxExitEvidence.matches(appPrefix + "fake\\r" + killed, 7941, 9, started, ended))
        assertFalse(
            NativeSandboxExitEvidence.matches(appPrefix + "fake\n" + appPrefix + killed, 7941, 9, started, ended),
        )
    }

    /** Invalid time cannot be interpreted as another logcat option or record-count request. */
    @Test(expected = IllegalArgumentException::class)
    fun negativeProbeTimeCannotBecomeACommand() {
        NativeSandboxExitEvidence.logcatCommand(-1)
    }

    /** Real logcat column widths and untrusted multiline payloads retain the logger identity. */
    @Test
    fun paddedRootAndNumericUidColumnsPreserveLoggerIdentity() {
        val padded = "1793519366.996  root   337   337 I Zygote  : Process 7941 exited due to signal 9 (Killed)"
        val numeric = "1793519366.996     0   337   337 I Zygote  : Process 7941 exited due to signal 9 (Killed)"
        assertTrue(NativeSandboxExitEvidence.matches(padded, 7941, 9, started, ended))
        assertTrue(NativeSandboxExitEvidence.matches(numeric, 7941, 9, started, ended))
        val appPrefix = "1793519366.996 10001   123   123 I Zygote  : "
        val appPayload = appPrefix + padded + "\n" + appPrefix + numeric
        assertFalse(NativeSandboxExitEvidence.matches(appPayload, 7941, 9, started, ended))
    }
}
