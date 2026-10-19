package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.sandbox.AnimationSessionControl
import dev.ipf.whitenoise.android.media.sandbox.IsolatedAnimationDrawable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises real raw-pixel presentation and row ownership; isolated native decoding has device coverage. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class InlineAttachmentImageTest {
    @get:Rule val composeRule = createComposeRule()
    private val lifecycleOwner = TestLifecycleOwner()
    private var focused by mutableStateOf(true)
    private val still = DecodedAttachmentPresentation.Static(pixels(Color.RED), animationDeferred = true)
    private val decoded = mutableListOf<IsolatedAnimationDrawable>()
    private val decode: suspend (ByteArray) -> Drawable? = { newAnimation() }

    /** Restores the system preference and retires any fixture pixels after each test. */
    @After
    fun cleanUp() {
        composeRule.runOnIdle { decoded.forEach { it.close() } }
        setMotion(true)
    }

    /** A granted row presents changed pixels; pause retires it and returning creates a new owner. */
    @Test
    fun grantChangesPixelsAndPauseRejectsLateFramesBeforeResume() {
        var enabled by mutableStateOf(true)
        composeRule.setContent { Harness { RowImage(enabled = enabled) } }
        composeRule.waitForIdle()
        val first = decoded.single()
        composeRule.runOnIdle {
            assertTrue(first.isRunning)
            first.accept(pixels(Color.BLUE))
            assertEquals(Color.BLUE, drawnColor(first))
            enabled = false
        }
        composeRule.runOnIdle {
            assertFalse(first.isRunning)
            val late = pixels(Color.GREEN)
            first.accept(late)
            assertTrue(late.isRecycled)
            assertEquals(2L, first.presentedFrame)
            enabled = true
        }
        composeRule.runOnIdle {
            assertEquals(2, decoded.size)
            assertTrue(decoded.last().isRunning)
            assertFalse(first.isRunning)
        }
    }

    /** Queued rows keep their still, make no local read, and get the next grant when the owner leaves. */
    @Test
    fun twoRowsShareOneGrantAndDisposalHandsItToTheWaiter() {
        var firstShown by mutableStateOf(true)
        var reads = 0
        composeRule.setContent {
            Harness {
                Column {
                    if (firstShown) RowImage(identity = "first")
                    RowImage(identity = "second", read = {
                        reads++
                        byteArrayOf(2)
                    })
                }
            }
        }
        composeRule.runOnIdle {
            assertEquals(1, decoded.size)
            assertEquals(0, reads)
            firstShown = false
        }
        composeRule.runOnIdle {
            assertEquals(2, decoded.size)
            assertEquals(1, reads)
            assertFalse(decoded.first().isRunning)
            assertTrue(decoded.last().isRunning)
        }
    }

    /** Window focus, foreground and live system motion each release the worker and permit a later restart. */
    @Test
    fun focusLifecycleAndMotionChangesRetireEachOwner() {
        composeRule.setContent { Harness { RowImage() } }
        composeRule.runOnIdle { focused = false }
        composeRule.runOnIdle {
            assertFalse(decoded.single().isRunning)
            focused = true
        }
        composeRule.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.CREATED) }
        composeRule.runOnIdle {
            assertFalse(decoded.last().isRunning)
            lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        }
        composeRule.runOnIdle { setMotion(false) }
        composeRule.runOnIdle {
            assertFalse(decoded.last().isRunning)
            setMotion(true)
        }
        composeRule.runOnIdle {
            assertEquals(4, decoded.size)
            assertTrue(decoded.last().isRunning)
        }
    }

    /** A retained but scrolled-out row releases its grant, then rereads only local bytes when visible again. */
    @Test
    fun scrollingOffscreenRetiresTheAnimation() {
        val scroll = ScrollState(0)
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            scope = rememberCoroutineScope()
            Harness {
                Column(Modifier.height(100.dp).verticalScroll(scroll)) {
                    RowImage()
                    Spacer(Modifier.height(1_000.dp))
                }
            }
        }
        composeRule.runOnIdle { scope.launch { scroll.scrollTo(500) } }
        composeRule.runOnIdle {
            assertFalse(decoded.single().isRunning)
            scope.launch { scroll.scrollTo(0) }
        }
        composeRule.runOnIdle {
            assertEquals(2, decoded.size)
            assertTrue(decoded.last().isRunning)
        }
    }

    /** A cancelled non-cooperative decoder cannot publish into a new row or keep the shared permit. */
    @Test
    fun replacementRejectsLateDecodeAndThenAdmitsNewOwner() {
        val release = CompletableDeferred<Unit>()
        var identity by mutableStateOf("old")
        val late = newAnimation()
        val heldDecode: suspend (ByteArray) -> Drawable? = {
            withContext(NonCancellable) { release.await() }
            late
        }
        composeRule.setContent {
            Harness { RowImage(identity = identity, decoder = if (identity == "old") heldDecode else decode) }
        }
        composeRule.runOnIdle { identity = "new" }
        composeRule.runOnIdle { release.complete(Unit) }
        composeRule.runOnIdle {
            assertEquals(2, decoded.size)
            assertFalse(late.isRunning)
            val rejected = pixels(Color.BLUE)
            late.accept(rejected)
            assertTrue(rejected.isRecycled)
            assertTrue(decoded.last().isRunning)
        }
    }

    /** Static, missing, disabled, and refused sources keep a stable still without repeated decoding. */
    @Test
    fun staticOrIneligibleSourcesNeverReadAndRefusalDoesNotSpin() {
        var enabled by mutableStateOf(false)
        var attempts = 0
        val refused: suspend (ByteArray) -> Drawable? = {
            attempts++
            null
        }
        composeRule.setContent {
            Harness {
                Column {
                    RowImage(identity = "static", image = still.copy(animationDeferred = false), decoder = refused)
                    RowImage(identity = "candidate", enabled = enabled, decoder = refused)
                }
            }
        }
        composeRule.runOnIdle {
            assertEquals(0, attempts)
            enabled = true
        }
        composeRule.runOnIdle { assertEquals(1, attempts) }
        composeRule.waitForIdle()
        assertEquals(1, attempts)
    }

    /** A mounted row loses eligibility on account or runtime replacement, and cannot adopt the new owner. */
    @Test
    fun accountAndRuntimeReplacementInvalidateTheCapturedSource() {
        val fixture =
            dev.ipf.whitenoise.android.state
                .MediaDownloadIntegrationFixture()
        var source: InlineAnimationSource? = null
        showInlineTestConversation(fixture.state, "group")
        try {
            composeRule.setContent {
                source =
                    rememberInlineAnimationSource("row", fixture.state, fixture.state.activeAccountRef, "group") {
                        byteArrayOf(1)
                    }
            }
            composeRule.runOnIdle {
                assertTrue(requireNotNull(source).enabled)
                showInlineTestConversation(fixture.state, "other")
            }
            composeRule.runOnIdle {
                assertFalse(requireNotNull(source).enabled)
                showInlineTestConversation(fixture.state, "group")
            }
            composeRule.runOnIdle {
                assertTrue(requireNotNull(source).enabled)
                fixture.state.javaClass
                    .getDeclaredMethod("setActiveAccountRef", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(fixture.state, "replacement")
            }
            composeRule.runOnIdle {
                assertFalse(requireNotNull(source).enabled)
                fixture.state.javaClass
                    .getDeclaredMethod("setActiveAccountRef", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(
                        fixture.state,
                        dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.ACCOUNT,
                    )
                fixture.state.javaClass
                    .getDeclaredMethod("setRuntimeGeneration", Int::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }
                    .invoke(fixture.state, fixture.state.runtimeGeneration + 1)
            }
            composeRule.runOnIdle { assertFalse(requireNotNull(source).enabled) }
        } finally {
            fixture.close()
        }
    }

    /** Supplies deterministic foreground and focus signals while exercising the real visibility modifier. */
    @Composable
    private fun Harness(content: @Composable () -> Unit) {
        val window =
            object : WindowInfo {
                override val isWindowFocused: Boolean get() = focused
            }
        CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner, LocalWindowInfo provides window) {
            Box(Modifier.size(200.dp)) { content() }
        }
    }

    /** Mounts the production presentation seam with a local-only source and controllable decoder. */
    @Composable
    private fun RowImage(
        identity: String = "row",
        enabled: Boolean = true,
        image: DecodedAttachmentPresentation.Static = still,
        read: suspend () -> ByteArray? = { byteArrayOf(1) },
        decoder: suspend (ByteArray) -> Drawable? = decode,
    ) {
        InlineAttachmentImage(
            image,
            InlineAnimationSource(identity, enabled, read),
            "GIF",
            ContentScale.Crop,
            Modifier.size(64.dp),
            decoder,
        )
    }

    /** Produces only copied pixels; no fake native decoder is installed into production. */
    private fun newAnimation(): IsolatedAnimationDrawable {
        val session =
            object : AnimationSessionControl {
                private var open = true

                override fun play() = Unit

                override fun pause() = Unit

                override fun isOpen() = open

                override fun close() {
                    open = false
                }
            }
        return IsolatedAnimationDrawable(pixels(Color.RED), session).also(decoded::add)
    }

    /** Reads actual app-owned pixels after a frame delivery, not just an Animatable flag. */
    private fun drawnColor(drawable: Drawable): Int {
        val output = pixels(Color.TRANSPARENT)
        return try {
            drawable.setBounds(0, 0, 2, 2)
            drawable.draw(Canvas(output))
            output.getPixel(0, 0)
        } finally {
            output.recycle()
        }
    }

    /** Generates a bounded raw frame with an unmistakably different colour. */
    private fun pixels(color: Int): Bitmap =
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }

    /** Changes the same setting observed by the production layer. */
    private fun setMotion(enabled: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            if (enabled) 1f else 0f,
        )
    }

    /** Drives lifecycle edges independently of window focus and row visibility. */
    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
