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
import androidx.compose.runtime.compositionLocalOf
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
import dev.ipf.whitenoise.android.media.AnimationStartupException
import dev.ipf.whitenoise.android.media.AnimationTermination
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.PreemptibleAnimation
import dev.ipf.whitenoise.android.media.sandbox.AnimationAdmission
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

/**
 * One live [systemAnimationsEnabled] value for the theme's subtree, so dense lists read it instead of
 * registering a settings observer per row. Unset outside the theme, where callers observe it themselves.
 */
internal val LocalSystemAnimationsEnabled = compositionLocalOf<Boolean?> { null }

/** Tracks [systemAnimationsEnabled] live, so a mounted avatar stops the moment motion is turned off. */
@Composable
internal fun rememberSystemAnimationsEnabled(): Boolean =
    when (val provided = LocalSystemAnimationsEnabled.current) {
        null -> observeSystemAnimationsEnabled()
        else -> provided
    }

/** Registers one settings observer for this call site; the theme provides its result to every descendant. */
@Composable
internal fun observeSystemAnimationsEnabled(): Boolean {
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
): Drawable? =
    dev.ipf.whitenoise.android.media.decodeOwnedMedia(
        decode = { MediaPipeline.decodeAnimatedDrawable(source, maxEdgePx) },
        discard = { (it as? java.io.Closeable)?.close() },
    )

/** Group owners retain one explicit demand ticket across their finite decode waits. */
internal suspend fun decodeAnimatedGroupThumbnail(
    source: ByteArray,
    maxEdgePx: Int,
): Drawable? = decodeAnimatedGroupThumbnail(source, maxEdgePx, null)

/**
 * Decodes a group thumbnail on IO against [demand]'s FIFO position, surfacing typed startup and admission-busy faults
 * to the recovery loop, and closes a drawable that lands after the caller was cancelled.
 */
internal suspend fun decodeAnimatedGroupThumbnail(
    source: ByteArray,
    maxEdgePx: Int,
    demand: AnimationAdmission.GroupDemand?,
): Drawable? =
    dev.ipf.whitenoise.android.media.decodeOwnedMedia(
        decode = {
            MediaPipeline.decodeGroupThumbnailAnimation(source, maxEdgePx, demand)
        },
        discard = { (it as? java.io.Closeable)?.close() },
    )

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
    // This layer cannot animate without explicit viewer intent.
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
    explicitViewer: Boolean = false,
    groupThumbnail: Boolean = false,
    onDecodeUnavailable: () -> Unit = {},
    decode: suspend (ByteArray, Int) -> Drawable? = ::decodeAnimatedProfileAvatar,
    groupDecode: (suspend (ByteArray, Int, AnimationAdmission.GroupDemand?) -> Drawable?)? = null,
) {
    // Not keyed on the source: the visibility node only reports changes, so a reset here would leave a
    // replacement picture in the same slot believing it is off-screen until the next scroll.
    var visible by remember { mutableStateOf(false) }
    // Plain collection: a lifecycle-scoped collector would stop before it ever saw the background.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsState()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val animate =
        (explicitViewer || groupThumbnail) && shouldAnimateProfileAvatar(animationsEnabled, visible, foreground)
    // Geometry changes may restart a decoder, but do not grant this same eligible source more faults.
    val faultBudget = remember(source, animate) { AvatarFaultBudget() }
    val drawable by produceState<Drawable?>(null, source, maxEdgePx, animate) {
        value = null
        if (animate) {
            val demand = if (groupThumbnail) IsolatedMediaDecoder.groupDemand() else null
            try {
                faultBudget.playAvatarAnimation(
                    resumeAfterPreemption = groupThumbnail,
                    decode = {
                        val edge = maxEdgePx.coerceAtMost(MAX_ANIMATED_PROFILE_AVATAR_EDGE)
                        if (groupThumbnail && groupDecode != null) {
                            groupDecode(source, edge, demand)
                        } else {
                            decode(source, edge)
                        }
                    },
                    publish = { value = it },
                    onDecodeUnavailable = onDecodeUnavailable,
                    demand = demand,
                )
            } finally {
                demand?.close()
            }
        }
    }
    AnimatedAvatarPixels(
        drawable,
        contentScale,
        modifier.onVisibilityChanged(minFractionVisible = VISIBLE_FRACTION) { visible = it },
    )
}

/** Renders and disposes the existing raw-pixel drawable without owning retry or source state. */
@Composable
@Suppress("FunctionNaming")
private fun AnimatedAvatarPixels(
    drawable: Drawable?,
    contentScale: ContentScale,
    modifier: Modifier,
) {
    Box(modifier) {
        val current = drawable ?: return@Box
        val painter = remember(current) { AnimatedDrawablePainter(current) }
        DisposableEffect(current, painter) {
            current.callback = painter.callback
            (current as? Animatable)?.start()
            onDispose {
                (current as? Animatable)?.stop()
                (current as? java.io.Closeable)?.close()
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

/** Retained by the eligible source owner, independently of decoder geometry or first-frame success. */
internal class AvatarFaultBudget {
    private var replacements = 0

    /** 250 ms before the first fault replacement, 1 s before the second, then null once the budget is spent. */
    fun nextBackoff(): Long? =
        when (replacements) {
            0 -> 250L.also { replacements++ }
            1 -> 1000L.also { replacements++ }
            else -> null
        }
}

/** Two fault replacements per continuous owner; successful first frames do not replenish the budget. */
internal suspend fun AvatarFaultBudget.playAvatarAnimation(
    resumeAfterPreemption: Boolean,
    decode: suspend () -> Drawable?,
    publish: (Drawable?) -> Unit,
    onDecodeUnavailable: () -> Unit,
    demand: AnimationAdmission.GroupDemand? = null,
) {
    while (true) {
        currentCoroutineContext().ensureActive()
        var produced: Drawable? = null
        val reason =
            try {
                produced = decodeAvatarWhenAvailable(decode)
                currentCoroutineContext().ensureActive()
                publish(produced)
                if (produced == null) {
                    onDecodeUnavailable()
                    return
                }
                if (resumeAfterPreemption && produced is PreemptibleAnimation) {
                    produced.awaitTermination()
                } else {
                    awaitCancellation()
                }
            } catch (startup: AnimationStartupException) {
                startup.reason
            } finally {
                publish(null)
                (produced as? java.io.Closeable)?.close()
            }
        currentCoroutineContext().ensureActive()
        if (resumeAfterPreemption && reason == AnimationTermination.Preempted) continue
        val fault =
            reason == AnimationTermination.TransportUnavailable ||
                reason == AnimationTermination.WorkerLostUnknown
        val backoff = if (resumeAfterPreemption && fault) nextBackoff() else null
        if (backoff == null) {
            onDecodeUnavailable()
            return
        }
        demand?.beginBackoff()
        delay(backoff)
        currentCoroutineContext().ensureActive()
        demand?.endBackoff()
    }
}

/** Only transient worker contention parks playback; unsupported/corrupt sources keep static fallback. */
internal suspend fun decodeAvatarWhenAvailable(decode: suspend () -> Drawable?): Drawable? {
    while (true) {
        val attempt =
            dev.ipf.whitenoise.android.state
                .runCatchingCancellable { decode() }
        val failure = attempt.exceptionOrNull()
        if (failure is AnimationStartupException) throw failure
        val busy = failure as? dev.ipf.whitenoise.android.media.AnimationAdmissionBusyException
        if (busy == null) return attempt.getOrNull()
        busy.awaitAvailability()
        currentCoroutineContext().ensureActive()
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
