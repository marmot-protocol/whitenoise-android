@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.whitenoise.android.core.AvatarCacheChanges
import kotlinx.coroutines.delay

/** Coalesces current projection and decoded-avatar changes without loading any media or retaining a second snapshot. */
@Composable
internal fun ConversationShortcutRefreshEffect(
    owner: Any,
    accountRef: String?,
    ready: Boolean,
    targetRevision: Long,
    profileRevision: Any,
    publish: () -> Unit,
) {
    val pixels by AvatarCacheChanges.revision.collectAsStateWithLifecycle()
    val latestPublish by rememberUpdatedState(publish)
    LaunchedEffect(owner, accountRef, ready, targetRevision, profileRevision, pixels) {
        if (ready && accountRef != null) {
            delay(SHORTCUT_REFRESH_COALESCE_MILLIS)
            latestPublish()
        }
    }
}

private const val SHORTCUT_REFRESH_COALESCE_MILLIS = 100L
