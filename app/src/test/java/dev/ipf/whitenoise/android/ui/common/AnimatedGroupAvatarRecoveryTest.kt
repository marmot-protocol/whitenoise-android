package dev.ipf.whitenoise.android.ui.common

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.core.twoFrameGif
import dev.ipf.whitenoise.android.media.PreemptibleAnimation
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared group surface resumes after interactive preemption without escaping its source/lifecycle owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedGroupAvatarRecoveryTest {
    @get:Rule val composeRule = createComposeRule()

    /** Returning from a viewer recovers the same group from retained bytes, without a second source read. */
    @Test
    fun preemptedGroupWaitsForViewerThenResumes() {
        val owner = TestOwner()
        val first = TestAnimation()
        val second = TestAnimation()
        val viewerReleased = CompletableDeferred<Unit>()
        var reads = 0
        var decodes = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AnimatedGroupAvatarOverlay(
                    GroupAvatarAnimation("group") {
                        reads++
                        twoFrameGif()
                    },
                    Modifier.size(40.dp),
                    maxEdgePx = 40,
                    animationsEnabled = true,
                    decode = { _, _ ->
                        if (++decodes == 1) {
                            first
                        } else {
                            viewerReleased.await()
                            second
                        }
                    },
                )
            }
        }
        composeRule.waitUntil { decodes == 1 }
        composeRule.runOnIdle { first.preempted.complete(Unit) }
        composeRule.waitUntil { decodes == 2 }
        assertTrue(first.closed)
        composeRule.onNodeWithTag(ANIMATED_PROFILE_AVATAR_TAG).assertDoesNotExist()
        composeRule.runOnIdle { viewerReleased.complete(Unit) }
        composeRule.waitUntil { second.callback != null }
        assertEquals(1, reads)
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitUntil { second.closed }
        assertEquals(MAX_PLAYING_GROUP_AVATARS, groupAvatarAnimationPermits.availablePermits)
    }

    /** A classified bind failure recovers this mounted key from retained bytes, without another read. */
    @Test
    fun transientStartupRecoversSameMountedSource() {
        val owner = TestOwner()
        val recovered = TestAnimation()
        var reads = 0
        var decodes = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AnimatedGroupAvatarOverlay(
                    GroupAvatarAnimation("transport") {
                        reads++
                        twoFrameGif()
                    },
                    Modifier.size(40.dp),
                    maxEdgePx = 40,
                    animationsEnabled = true,
                    decode = { _, _ ->
                        if (++decodes == 1) {
                            throw dev.ipf.whitenoise.android.media.AnimationStartupException(
                                dev.ipf.whitenoise.android.media.AnimationTermination.TransportUnavailable,
                            )
                        }
                        recovered
                    },
                )
            }
        }
        composeRule.waitUntil { recovered.callback != null }
        assertEquals(2, decodes)
        assertEquals(1, reads)
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitUntil { recovered.closed }
        assertEquals(MAX_PLAYING_GROUP_AVATARS, groupAvatarAnimationPermits.availablePermits)
    }

    /** A replaced owner cannot resume after a delayed viewer release, even if the old wait completes later. */
    @Test
    @Suppress("LongMethod") // One fixture keeps old/new ownership and delayed completion in the same scenario.
    fun replacementCancelsPreemptedOwnerAndKeepsNewGroup() {
        val owner = TestOwner()
        var key by mutableStateOf("old")
        val first = TestAnimation()
        val replacement = TestAnimation()
        val viewerReleased = CompletableDeferred<Unit>()
        var oldDecodes = 0
        var newDecodes = 0
        var oldWaitCancelled = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.size(200.dp)) {
                    val current = key
                    AnimatedGroupAvatarOverlay(
                        GroupAvatarAnimation(current) { twoFrameGif() },
                        Modifier.size(40.dp),
                        maxEdgePx = 40,
                        animationsEnabled = true,
                        decode = { _, _ ->
                            if (current == "new") {
                                newDecodes++
                                replacement
                            } else if (++oldDecodes == 1) {
                                first
                            } else {
                                try {
                                    viewerReleased.await()
                                    error("retired owner must not resume")
                                } finally {
                                    oldWaitCancelled = true
                                }
                            }
                        },
                    )
                }
            }
        }
        composeRule.waitUntil { oldDecodes == 1 }
        composeRule.runOnIdle { first.preempted.complete(Unit) }
        composeRule.waitUntil { oldDecodes == 2 }
        composeRule.runOnIdle { key = "new" }
        // The replacement needs Robolectric's measure/layout pass before visibility can admit it.
        composeRule.waitForIdle()
        assertTrue("Replacing the owner cancels its pending decode", oldWaitCancelled)
        composeRule.waitUntil { newDecodes == 1 }
        composeRule.runOnIdle { viewerReleased.complete(Unit) }
        composeRule.waitForIdle()
        assertEquals(2, oldDecodes)
        assertTrue(first.closed)
        assertTrue(replacement.callback != null)
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitUntil { replacement.closed }
    }

    /** Raw-pixel drawable whose explicit preemption and cleanup are observable without a native codec. */
    private class TestAnimation :
        Drawable(),
        PreemptibleAnimation,
        java.io.Closeable {
        val preempted = CompletableDeferred<Unit>()
        var closed = false

        /** Suspends until the test explicitly models a viewer takeover, independently of codec timing. */
        override suspend fun awaitPreemption() {
            preempted.await()
        }

        /** Records disposal so lifecycle and owner-change assertions can detect a leaked drawable. */
        override fun close() {
            closed = true
        }

        /** Keeps pixel rendering out of this fixture; assertions observe real Compose ownership instead. */
        override fun draw(canvas: Canvas) = Unit

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        /** Provides measurable geometry without allocating a bitmap or invoking a platform decoder. */
        override fun getIntrinsicWidth(): Int = 2

        /** Keeps the fixture square and measurable while pixel decoding is deliberately absent. */
        override fun getIntrinsicHeight(): Int = 2
    }

    /** Drives the real Compose lifecycle eligibility gate. */
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
