package dev.ipf.whitenoise.android.state

internal class LocalDeleteRecoveryObserver(
    val recoverTransport: suspend () -> Unit = {},
    val onProgress: (LocalDeletePhase, Int, Boolean?, Boolean) -> Unit = { _, _, _, _ -> },
)
