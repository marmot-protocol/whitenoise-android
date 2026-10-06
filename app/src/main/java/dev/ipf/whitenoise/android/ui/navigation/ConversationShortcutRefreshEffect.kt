@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.whitenoise.android.core.AvatarCacheChanges
import kotlinx.coroutines.delay

/**
 * Coalesces projection and decoded-avatar changes without loading any media or retaining a second snapshot.
 * Projection changes republish Direct Share and pins together; decoded pixels only touch the pins, so a chat
 * list decoding its avatars cannot re-issue the whole Direct Share inventory once per image.
 */
@Composable
internal fun ConversationShortcutRefreshEffect(
    owner: Any,
    accountRef: String?,
    ready: Boolean,
    targetRevision: Long,
    profileRevision: Any,
    publish: () -> Unit,
    refreshPins: () -> Unit,
) {
    val latestPublish by rememberUpdatedState(publish)
    val latestRefreshPins by rememberUpdatedState(refreshPins)
    LaunchedEffect(owner, accountRef, ready, targetRevision, profileRevision) {
        if (ready && accountRef != null) {
            delay(SHORTCUT_REFRESH_COALESCE_MILLIS)
            latestPublish()
        }
    }
    val pixels by AvatarCacheChanges.revision.collectAsStateWithLifecycle()
    // The publication above already reads the pixels present at mount; only later decodes refresh the pins.
    val mountPixels = remember(owner, accountRef) { pixels }
    LaunchedEffect(owner, accountRef, ready, pixels) {
        if (ready && accountRef != null && pixels !== mountPixels) {
            delay(SHORTCUT_REFRESH_COALESCE_MILLIS)
            latestRefreshPins()
        }
    }
}

private const val SHORTCUT_REFRESH_COALESCE_MILLIS = 100L
