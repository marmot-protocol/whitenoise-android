package dev.ipf.whitenoise.android

/**
 * Build-neutral bridge for debug runtime diagnostics. The release source set
 * never references StrictMode; its default bridge is a tiny no-op. Debug
 * installs the platform-backed implementation from `app/src/debug` (#2167).
 */
internal interface RuntimePolicyBridge {
    fun noteSlowCall(operation: String)

    fun <T> allowThreadDiskReads(block: () -> T): T
}

internal object RuntimePolicyHooks {
    private object NoOpBridge : RuntimePolicyBridge {
        override fun noteSlowCall(operation: String) = Unit

        override fun <T> allowThreadDiskReads(block: () -> T): T = block()
    }

    @Volatile
    private var bridge: RuntimePolicyBridge = NoOpBridge

    fun install(debugBridge: RuntimePolicyBridge) {
        bridge = debugBridge
    }

    fun noteSlowCall(operation: String) {
        bridge.noteSlowCall(operation)
    }

    fun <T> allowThreadDiskReads(block: () -> T): T = bridge.allowThreadDiskReads(block)

    internal fun resetForTest() {
        bridge = NoOpBridge
    }
}
