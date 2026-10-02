package dev.ipf.whitenoise.android.ui.common

import android.content.ContentResolver
import android.database.ContentObserver
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.MAX_ANIMATED_PROFILE_AVATAR_EDGE
import dev.ipf.whitenoise.android.core.PROFILE_AVATAR_MAX_DIMENSION
import dev.ipf.whitenoise.android.media.MediaPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Test tag on the animated layer, present only while a decoded animation is attached. */
internal const val ANIMATED_PROFILE_AVATAR_TAG = "animated-profile-avatar"

/** Any visible pixel counts, so a partly scrolled row keeps moving until it is fully gone. */
private const val VISIBLE_FRACTION = 0.01f

/** An animation runs only with system motion on, a visible surface, and a started lifecycle. */
internal fun shouldAnimateProfileAvatar(
    animationsEnabled: Boolean,
    visible: Boolean,
    foreground: Boolean,
): Boolean = animationsEnabled && visible && foreground

/** False when the user turned system animations off (animator duration scale of zero). */
internal fun systemAnimationsEnabled(resolver: ContentResolver): Boolean {
    val scale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return scale != 0f
}

/** Tracks [systemAnimationsEnabled] live, so a mounted avatar stops the moment motion is turned off. */
@Composable
internal fun rememberSystemAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember(resolver) { mutableStateOf(systemAnimationsEnabled(resolver)) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    enabled = systemAnimationsEnabled(resolver)
                }
            }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        // A change between the first read and registration would otherwise be missed.
        enabled = systemAnimationsEnabled(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** Decodes [source] off the main thread into a drawable no larger than [maxEdgePx] on its long edge. */
internal suspend fun decodeAnimatedProfileAvatar(
    source: ByteArray,
    maxEdgePx: Int,
): Drawable? = withContext(Dispatchers.Default) { MediaPipeline.decodeAnimatedDrawable(source, maxEdgePx) }

/**
 * Overlays the cached animation for [url] at no more than [maxEdgePx], on top of the first frame the
 * caller already draws. Static, uncached and evicted avatars compose nothing, and an account clear drops
 * the source with the rest of the cache. [firstFrame] re-reads the source once a pending load lands.
 */
@Composable
@Suppress("FunctionNaming")
internal fun AnimatedProfileAvatarOverlay(
    url: String?,
    firstFrame: ImageBitmap?,
    modifier: Modifier = Modifier,
    maxEdgePx: Int = PROFILE_AVATAR_MAX_DIMENSION,
) {
    val lifetime = AvatarImageLoader.currentCacheLifetime()
    val source =
        remember(url, firstFrame, lifetime) {
            firstFrame?.let { AvatarImageLoader.peekAnimatedSource(url) }
        } ?: return
    AnimatedProfileAvatarLayer(
        source = source,
        maxEdgePx = maxEdgePx.coerceIn(1, PROFILE_AVATAR_MAX_DIMENSION),
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * Plays [source] only while [shouldAnimateProfileAvatar] holds. Losing visibility, the foreground or
 * system motion stops the animation and releases the decoded drawable, so memory scales with what is on
 * screen; returning decodes again from the first frame. A failed decode leaves the first frame showing.
 */
@Composable
// Decode and motion seams keep the lifecycle deterministic under test.
@Suppress("FunctionNaming", "LongParameterList")
internal fun AnimatedProfileAvatarLayer(
    source: ByteArray,
    maxEdgePx: Int,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    animationsEnabled: Boolean = rememberSystemAnimationsEnabled(),
    decode: suspend (ByteArray, Int) -> Drawable? = ::decodeAnimatedProfileAvatar,
) {
    // Not keyed on the source: the visibility node only reports changes, so a reset here would leave a
    // replacement picture in the same slot believing it is off-screen until the next scroll.
    var visible by remember { mutableStateOf(false) }
    // Plain collection: a lifecycle-scoped collector would stop before it ever saw the background.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val animate = shouldAnimateProfileAvatar(animationsEnabled, visible, foreground)
    val drawable by produceState<Drawable?>(null, source, maxEdgePx, animate) {
        value = null
        if (animate) value = decode(source, maxEdgePx.coerceAtMost(MAX_ANIMATED_PROFILE_AVATAR_EDGE))
    }
    Box(
        modifier.onVisibilityChanged(minFractionVisible = VISIBLE_FRACTION) { visible = it },
    ) {
        val current = drawable ?: return@Box
        val painter = remember(current) { AnimatedDrawablePainter(current) }
        DisposableEffect(current, painter) {
            current.callback = painter.callback
            (current as? Animatable)?.start()
            onDispose {
                (current as? Animatable)?.stop()
                current.callback = null
                painter.cancelScheduled()
            }
        }
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.matchParentSize().testTag(ANIMATED_PROFILE_AVATAR_TAG),
        )
    }
}

/** Draws a self-invalidating [Drawable], redrawing on each frame it reports. */
private class AnimatedDrawablePainter(
    private val drawable: Drawable,
) : Painter() {
    private var frame by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())
    private val token = Any()

    /** Routes the drawable's frame requests to Compose redraws and main-thread scheduling. */
    val callback =
        object : Drawable.Callback {
            /** Each new frame bumps the counter the draw reads. */
            override fun invalidateDrawable(who: Drawable) {
                frame++
            }

            /** Posts the drawable's next frame step on the main thread, tagged for bulk cancellation. */
            override fun scheduleDrawable(
                who: Drawable,
                what: Runnable,
                `when`: Long,
            ) {
                handler.postAtTime(what, token, `when`.coerceAtLeast(SystemClock.uptimeMillis()))
            }

            /** Removes one pending frame step. */
            override fun unscheduleDrawable(
                who: Drawable,
                what: Runnable,
            ) {
                handler.removeCallbacks(what, token)
            }
        }

    /** The decoded, already target-sized drawable dimensions. */
    override val intrinsicSize: Size
        get() = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    /** Drops any frame callback still queued once the drawable is detached. */
    fun cancelScheduled() = handler.removeCallbacksAndMessages(token)

    /** Draws the current frame scaled to the painter's bounds. */
    override fun DrawScope.onDraw() {
        // Reading the frame counter subscribes this draw to the drawable's invalidations.
        if (frame < 0) return
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}
