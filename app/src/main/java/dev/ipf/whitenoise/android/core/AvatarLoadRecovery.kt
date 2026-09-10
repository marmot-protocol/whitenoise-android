package dev.ipf.whitenoise.android.core

import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Wakes visible missing-avatar requests after validated internet becomes usable again. */
internal object AvatarLoadRecovery {
    private val lock = Any()
    private val lifetime = StalenessGuard()
    private val changes = MutableStateFlow(lifetime.capture())
    val revision = changes.asStateFlow()

    /** The app calls this only on its validated false-to-true internet transition. */
    fun onNetworkRestored() = retireAndNotify()

    /** A request made before native initialization must not wait for a later network transition. */
    fun onFetcherAvailable() = retireAndNotify()

    /** Retire both loaders before publishing the event so resumed rows cannot join old work. */
    private fun retireAndNotify() {
        synchronized(lock) {
            AvatarImageLoader.prepareForRecovery()
            GroupAvatarImageLoader.prepareForRecovery()
            changes.value = lifetime.advance()
        }
    }
}
