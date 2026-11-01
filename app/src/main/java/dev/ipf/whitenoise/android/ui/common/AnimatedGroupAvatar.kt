// Animation source ownership and its Compose renderers form one bounded presentation unit.
@file:Suppress("MatchingDeclarationName")

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.core.profileAvatarAnimationSource
import dev.ipf.whitenoise.android.media.AnimationSourceAdmission
import dev.ipf.whitenoise.android.media.admitAnimationSource
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A group-owned source pinned to its account, runtime and authoritative image revision. */
internal data class GroupAvatarAnimation(
    val key: String,
    val readBytes: suspend () -> ByteArray?,
)

/** At most three source reads; the shared isolated-worker admission separately permits one decoder. */
internal const val MAX_PLAYING_GROUP_AVATARS = 3
internal val groupAvatarAnimationPermits = Semaphore(MAX_PLAYING_GROUP_AVATARS)

/**
 * Thumbnail-only admission. Invisible/background/reduced-motion avatars neither read encoded bytes nor
 * hold a decoder. Waiting surfaces retain their static first frame; a free slot admits the next one.
 * The permit covers the original-byte read and the complete mounted animation, not just decoding.
 * Only one admitted group can play at once. Other admitted groups wait for its actual retirement;
 * fourth and later visible groups stay static until an existing source owner becomes inactive.
 */
@Composable
@Suppress("FunctionNaming")
internal fun AnimatedGroupAvatarOverlay(
    animation: GroupAvatarAnimation,
    modifier: Modifier = Modifier,
    maxEdgePx: Int,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
    decode: (suspend (ByteArray, Int) -> android.graphics.drawable.Drawable?)? = null,
) {
    key(animation.key) {
        GroupAvatarPlayback(animation, modifier, maxEdgePx, animationsEnabled, decode)
    }
}

/** Keeps static pixels on decoder refusal and releases the source permit when playback becomes inactive. */
@Composable
@Suppress("FunctionNaming")
private fun GroupAvatarPlayback(
    animation: GroupAvatarAnimation,
    modifier: Modifier,
    maxEdgePx: Int,
    animationsEnabled: Boolean,
    decode: (suspend (ByteArray, Int) -> android.graphics.drawable.Drawable?)?,
) {
    val visible = remember { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsState()
    val active =
        shouldAnimateProfileAvatar(
            animationsEnabled,
            visible.value,
            lifecycle.isAtLeast(Lifecycle.State.STARTED),
        )
    var unavailable by remember(animation.key, active) { mutableStateOf(false) }
    val source by produceState<ByteArray?>(null, animation.key, active, unavailable) {
        value = null
        if (active && !unavailable) {
            groupAvatarAnimationPermits.withPermit {
                val bytes =
                    dev.ipf.whitenoise.android.state
                        .runCatchingCancellable {
                            animation.readBytes()
                        }.getOrNull()
                value =
                    profileAvatarAnimationSource(bytes)?.takeIf {
                        (admitAnimationSource(it) as? AnimationSourceAdmission.Admitted)?.frameCount?.let { frames ->
                            frames > 1
                        } == true
                    }
                // Unsupported/static images relinquish the scarce slot immediately.
                if (value != null) awaitDispose { }
            }
        }
    }
    Box(modifier.onVisibilityChanged(minFractionVisible = 0.01f) { visible.value = it }) {
        source?.takeIf { active && !unavailable }?.let { bytes ->
            AnimatedProfileAvatarLayer(
                source = bytes,
                maxEdgePx = maxEdgePx,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                groupThumbnail = true,
                animationsEnabled = animationsEnabled,
                groupDecode = { source, edge, demand ->
                    if (decode != null) decode(source, edge) else decodeAnimatedGroupThumbnail(source, edge, demand)
                },
                onDecodeUnavailable = { unavailable = true },
            )
        }
    }
}
