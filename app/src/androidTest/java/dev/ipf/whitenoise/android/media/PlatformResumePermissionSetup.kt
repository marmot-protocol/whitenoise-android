package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.state.NativeAttachmentPermissions
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Job

/** Waits for the requested real setup and the receiver's approval without creating or refreshing permission. */
internal suspend fun awaitPlatformResumePermissionSetup(
    state: WhiteNoiseAppState,
    account: String,
    refresh: Job?,
) {
    requireNotNull(refresh).join()
    val permissions =
        WhiteNoiseAppState::class.java
            .getDeclaredField("nativeAttachmentPermissions")
            .apply { isAccessible = true }
            .get(state) as NativeAttachmentPermissions
    permissions.awaitApplied(account) { it.files }
}
