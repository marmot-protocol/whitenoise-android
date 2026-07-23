package dev.ipf.whitenoise.android.share

import dev.ipf.whitenoise.android.state.AppPhase

/** True when inbound share routing/presentation may run (not while app-locked). */
fun shouldPresentInboundShare(
    phase: AppPhase,
    appLockScreenVisible: Boolean,
): Boolean = phase == AppPhase.Ready && !appLockScreenVisible
