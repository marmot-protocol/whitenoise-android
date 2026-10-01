package dev.ipf.whitenoise.android.ui.common

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.twoFrameGif
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared profile-avatar animation runs only while visible, foreground and allowed to move. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedProfileAvatarLayerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val decoded = mutableListOf<FakeAnimation>()
    private val lifecycleOwner = TestLifecycleOwner()

    /** Each decode returns a fresh fake so restarts are distinguishable from resumed owners. */
    private val decode: suspend (ByteArray, Int) -> Drawable? = { _, _ -> FakeAnimation().also(decoded::add) }

    /** Restores the global settings and avatar cache these tests touch. */
    @After
    fun tearDown() {
        AvatarImageLoader.clear()
        setAnimatorScale(1f)
    }

    /** A visible, started layer decodes once and starts its animation. */
    @Test
    fun startsWhenVisibleAndForeground() {
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        setLayer(animationsEnabled = { true })

        composeRule.onNodeWithTag(ANIMATED_PROFILE_AVATAR_TAG).assertExists()
        assertEquals(1, decoded.size)
        assertTrue(decoded.single().running)
    }

    /** With motion off the layer never decodes, leaving the static first frame alone. */
    @Test
    fun reducedMotionNeverStartsAnAnimation() {
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        setLayer(animationsEnabled = { false })

        composeRule.onNodeWithTag(ANIMATED_PROFILE_AVATAR_TAG).assertDoesNotExist()
        assertTrue(decoded.isEmpty())
    }

    /** Turning motion off while mounted stops and detaches the running animation. */
    @Test
    fun liveReducedMotionChangeStopsTheMountedAnimation() {
        var enabled by mutableStateOf(true)
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        setLayer(animationsEnabled = { enabled })
        val first = decoded.single()

        enabled = false
        composeRule.waitForIdle()

        assertFalse(first.running)
        assertNull(first.callback)
        composeRule.onNodeWithTag(ANIMATED_PROFILE_AVATAR_TAG).assertDoesNotExist()
    }

    /** Backgrounding stops the owner; returning starts one fresh owner while the stale one stays stopped. */
    @Test
    fun backgroundStopsAndForegroundRestartsWithoutStaleOwners() {
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        setLayer(animationsEnabled = { true })
        val first = decoded.single()

        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.CREATED) }
        composeRule.waitForIdle()
        assertFalse(first.running)
        assertNull(first.callback)

        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.RESUMED) }
        composeRule.waitForIdle()
        assertEquals(2, decoded.size)
        assertFalse(first.running)
        assertTrue(decoded.last().running)
    }

    /** A new source in the same visible slot stops the old owner and starts exactly one new one. */
    @Test
    fun replacingTheSourceInPlaceRestartsTheAnimation() {
        var source by mutableStateOf(twoFrameGif())
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        composeRule.setContent { Harness { Layer(animationsEnabled = true, source = source) } }
        composeRule.waitForIdle()
        val first = decoded.single()

        source = twoFrameGif(width = 2, height = 2)
        composeRule.waitForIdle()

        assertEquals(2, decoded.size)
        assertFalse(first.running)
        assertNull(first.callback)
        assertTrue(decoded.last().running)
    }

    /** A decode still in flight when motion is turned off is cancelled and never publishes a drawable. */
    @Test
    fun pendingDecodeIsCancelledWhenMotionTurnsOff() {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var enabled by mutableStateOf(true)
        val slowDecode: suspend (ByteArray, Int) -> Drawable? = { _, _ ->
            started.complete(Unit)
            release.await()
            FakeAnimation().also(decoded::add)
        }
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        composeRule.setContent { Harness { Layer(animationsEnabled = enabled, decoder = slowDecode) } }
        composeRule.waitUntil(DECODE_TIMEOUT_MS) { started.isCompleted }

        enabled = false
        composeRule.waitForIdle()
        release.complete(Unit)
        composeRule.waitForIdle()

        assertTrue(decoded.isEmpty())
        composeRule.onNodeWithTag(ANIMATED_PROFILE_AVATAR_TAG).assertDoesNotExist()
    }

    /** Leaving composition stops the animation and clears its callback. */
    @Test
    fun disposalStopsTheAnimation() {
        var shown by mutableStateOf(true)
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        setLayer(animationsEnabled = { true }, shown = { shown })
        val first = decoded.single()

        shown = false
        composeRule.waitForIdle()

        assertFalse(first.running)
        assertNull(first.callback)
    }

    /** Scrolling the avatar out of the viewport stops it even though it stays composed. */
    @Test
    fun scrollingOffScreenStopsTheAnimation() {
        lateinit var scope: CoroutineScope
        val scroll = ScrollState(0)
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        composeRule.setContent {
            scope = rememberCoroutineScope()
            Harness {
                Column(Modifier.height(100.dp).verticalScroll(scroll)) {
                    Layer(animationsEnabled = true)
                    Spacer(Modifier.height(1_000.dp))
                }
            }
        }
        composeRule.waitForIdle()
        val first = decoded.single()
        assertTrue(first.running)

        composeRule.runOnIdle { scope.launch { scroll.scrollTo(scroll.maxValue) } }
        composeRule.waitForIdle()

        assertFalse(first.running)
    }

    /** The real system setting is read live: a zero animator scale stops a mounted avatar. */
    @Test
    fun systemAnimatorScaleChangeIsObservedWhileMounted() {
        setAnimatorScale(1f)
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        lateinit var context: android.content.Context
        composeRule.setContent {
            context = LocalContext.current
            Harness { Layer(animationsEnabled = rememberSystemAnimationsEnabled()) }
        }
        composeRule.waitForIdle()
        val first = decoded.single()
        assertTrue(first.running)

        composeRule.runOnIdle {
            setAnimatorScale(0f)
            val scaleUri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
            context.contentResolver.notifyChange(scaleUri, null)
        }
        composeRule.waitForIdle()

        assertFalse(first.running)
    }

    /** Avatar animates a cached GIF source, and keeps a static picture's first frame untouched. */
    @Test
    fun avatarOverlaysOnlyCachedGifSources() {
        val firstFrame = ImageBitmap(4, 4)
        AvatarImageLoader.putCachedAnimated("https://profiles.example/animated", firstFrame, twoFrameGif())
        AvatarImageLoader.putCached("https://profiles.example/static", ImageBitmap(4, 4))
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        var url by mutableStateOf("https://profiles.example/animated")
        composeRule.setContent {
            Harness { Avatar(title = "Alice", seed = "alice", size = 40.dp, pictureUrl = url) }
        }
        // The real decoder runs off the main thread, outside the compose idling resource.
        composeRule.waitUntil(DECODE_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(ANIMATED_PROFILE_AVATAR_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        url = "https://profiles.example/static"
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ANIMATED_PROFILE_AVATAR_TAG).assertDoesNotExist()
    }

    /** A stored picture animates only when its caller names it as a person's picture. */
    @Test
    fun storedPictureAnimatesOnlyWithAPersonPictureKey() {
        val key = "marmot-avatar:owner:peer@1"
        val firstFrame = ImageBitmap(4, 4)
        AvatarImageLoader.putCachedAnimated(key, firstFrame, twoFrameGif())
        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        var animationKey by mutableStateOf<String?>(null)
        composeRule.setContent {
            Harness {
                Avatar(title = "Peer", seed = "peer", size = 40.dp, picture = firstFrame, animationKey = animationKey)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ANIMATED_PROFILE_AVATAR_TAG).assertDoesNotExist()

        animationKey = key

        composeRule.waitUntil(DECODE_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(ANIMATED_PROFILE_AVATAR_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The gate needs all three conditions at once. */
    @Test
    fun gateRequiresMotionVisibilityAndForeground() {
        assertTrue(shouldAnimateProfileAvatar(animationsEnabled = true, visible = true, foreground = true))
        assertFalse(shouldAnimateProfileAvatar(animationsEnabled = false, visible = true, foreground = true))
        assertFalse(shouldAnimateProfileAvatar(animationsEnabled = true, visible = false, foreground = true))
        assertFalse(shouldAnimateProfileAvatar(animationsEnabled = true, visible = true, foreground = false))
    }

    /** Mounts one layer under the test lifecycle, optionally removable. */
    private fun setLayer(
        animationsEnabled: () -> Boolean,
        shown: () -> Boolean = { true },
    ) {
        composeRule.setContent {
            Harness { if (shown()) Layer(animationsEnabled()) }
        }
        composeRule.waitForIdle()
    }

    /** Provides the controllable lifecycle around [content]. */
    @Composable
    private fun Harness(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
            Box(Modifier.size(200.dp)) { content() }
        }
    }

    /** The layer under test with the fake decoder. */
    @Composable
    private fun Layer(
        animationsEnabled: Boolean,
        source: ByteArray = DEFAULT_SOURCE,
        decoder: suspend (ByteArray, Int) -> Drawable? = decode,
    ) {
        AnimatedProfileAvatarLayer(
            source = source,
            maxEdgePx = 48,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp),
            animationsEnabled = animationsEnabled,
            decode = decoder,
        )
    }

    /** Writes the global animator scale Robolectric exposes to the app. */
    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>()
                .contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            scale,
        )
    }

    /** A drawable that only records whether it is running. */
    private class FakeAnimation :
        Drawable(),
        Animatable {
        var running = false

        /** Marks the fake as running. */
        override fun start() {
            running = true
        }

        /** Marks the fake as stopped. */
        override fun stop() {
            running = false
        }

        /** Reports the recorded running state. */
        override fun isRunning(): Boolean = running

        /** Fills with one colour so the layer is visible if captured. */
        override fun draw(canvas: Canvas) = canvas.drawColor(android.graphics.Color.MAGENTA)

        /** Alpha is irrelevant to the fake. */
        override fun setAlpha(alpha: Int) = Unit

        /** Colour filters are irrelevant to the fake. */
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        /** The fake paints opaquely. */
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private companion object {
        const val DECODE_TIMEOUT_MS = 5_000L

        /** One stable array, so recompositions never look like a source change. */
        val DEFAULT_SOURCE = twoFrameGif()
    }

    /** A lifecycle the test moves by hand. */
    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)

        /** The hand-driven registry. */
        override val lifecycle: Lifecycle get() = registry

        /** Moves the lifecycle to [state], dispatching every intermediate event. */
        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
