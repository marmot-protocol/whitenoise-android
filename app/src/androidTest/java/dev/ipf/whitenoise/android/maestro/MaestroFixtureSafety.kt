package dev.ipf.whitenoise.android.maestro

import java.util.concurrent.TimeUnit

/** Preserve the emulator restriction without claiming Android's accessibility connection. */
internal fun requireMaestroEmulator() {
    val process = ProcessBuilder("/system/bin/getprop", "ro.kernel.qemu").start()
    try {
        check(process.waitFor(5L, TimeUnit.SECONDS)) { "Emulator property read timed out" }
        check(process.exitValue() == 0) { "Emulator property read failed" }
        val qemu = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(qemu == "1") { "Disposable emulator required" }
    } finally {
        process.destroyForcibly()
    }
}
