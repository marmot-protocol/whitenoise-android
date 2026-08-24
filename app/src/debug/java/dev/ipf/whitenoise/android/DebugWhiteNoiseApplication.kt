package dev.ipf.whitenoise.android

import android.os.StrictMode

/** Debug-only process entry point; release bytecode contains no StrictMode references. */
class DebugWhiteNoiseApplication : WhiteNoiseApplication() {
    override fun onCreate() {
        DebugStrictModePolicy.install()
        RuntimePolicyHooks.install(DebugStrictModePolicy)
        super.onCreate()
    }
}

internal object DebugStrictModePolicy : RuntimePolicyBridge {
    fun install() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectUnbufferedIo()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }

    override fun noteSlowCall(operation: String) {
        StrictMode.noteSlowCall(operation)
    }

    override fun <T> allowThreadDiskReads(block: () -> T): T {
        val previous = StrictMode.allowThreadDiskReads()
        return try {
            block()
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }
}
