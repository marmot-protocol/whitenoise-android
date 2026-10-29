package dev.ipf.whitenoise.android.ui.common

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import dev.ipf.whitenoise.android.media.AnimationStartupException
import dev.ipf.whitenoise.android.media.AnimationTermination
import dev.ipf.whitenoise.android.media.PreemptibleAnimation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises the actual playback owner, including successful first-frame crash loops and cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AvatarFaultRecoveryTest {
    @Test
    fun successfulFirstFramesNeverReplenishTwoFaultReplacements() =
        runTest {
            val opens = mutableListOf<Long>()
            val retired = mutableListOf<SessionDrawable>()
            var unavailable = 0
            AvatarFaultBudget().playAvatarAnimation(true, decode = {
                opens += testScheduler.currentTime
                SessionDrawable(AnimationTermination.WorkerLostUnknown).also { retired += it }
            }, publish = {}, onDecodeUnavailable = { unavailable++ })
            assertEquals(listOf(0L, 250L, 1250L), opens)
            assertEquals(1, unavailable)
            assertTrue(retired.all { it.closed })
        }

    @Test
    fun transportStartupRecoversButKnownCodecAndNullNeverRetry() =
        runTest {
            var opens = 0
            var unavailable = 0
            AvatarFaultBudget().playAvatarAnimation(true, decode = {
                if (++opens == 1) throw AnimationStartupException(AnimationTermination.TransportUnavailable)
                SessionDrawable(AnimationTermination.CodecRejected)
            }, publish = {}, onDecodeUnavailable = { unavailable++ })
            assertEquals(2, opens)
            assertEquals(250L, testScheduler.currentTime)
            assertEquals(1, unavailable)
            var nullOpens = 0
            AvatarFaultBudget().playAvatarAnimation(true, decode = {
                nullOpens++
                null
            }, publish = {}, onDecodeUnavailable = {})
            assertEquals(1, nullOpens)
        }

    @Test
    fun preemptionDoesNotSpendOrResetTheFaultBudget() =
        runTest {
            val reasons =
                listOf(
                    AnimationTermination.WorkerLostUnknown,
                    AnimationTermination.Preempted,
                    AnimationTermination.TransportUnavailable,
                    AnimationTermination.Preempted,
                    AnimationTermination.WorkerLostUnknown,
                )
            var opens = 0
            AvatarFaultBudget().playAvatarAnimation(
                true,
                decode = { SessionDrawable(reasons[opens++]) },
                publish = {},
                onDecodeUnavailable = {},
            )
            assertEquals(5, opens)
            assertEquals(1250L, testScheduler.currentTime)
        }

    @Test
    fun cancellingBackoffNeverReopensOrMarksTheOldOwnerUnavailable() =
        runTest {
            var opens = 0
            var unavailable = 0
            val job =
                async {
                    AvatarFaultBudget().playAvatarAnimation(true, decode = {
                        opens++
                        throw AnimationStartupException(AnimationTermination.TransportUnavailable)
                    }, publish = {}, onDecodeUnavailable = { unavailable++ })
                }
            runCurrent()
            assertEquals(1, opens)
            advanceTimeBy(249)
            job.cancelAndJoin()
            advanceTimeBy(2000)
            assertEquals(1, opens)
            assertEquals(0, unavailable)
        }

    @Test
    fun replacingDecoderGeometryDoesNotResetTheSourceOwnerFaultAllowance() =
        runTest {
            val budget = AvatarFaultBudget()
            var opens = 0
            val firstOwner =
                async {
                    budget.playAvatarAnimation(true, decode = {
                        opens++
                        throw AnimationStartupException(AnimationTermination.TransportUnavailable)
                    }, publish = {}, onDecodeUnavailable = {})
                }
            runCurrent() // First replacement is spent before a geometry-driven cancellation.
            firstOwner.cancelAndJoin()
            budget.playAvatarAnimation(true, decode = {
                opens++
                SessionDrawable(AnimationTermination.WorkerLostUnknown)
            }, publish = {}, onDecodeUnavailable = {})
            assertEquals(3, opens) // Original attempt, then at most two further attempts across both coroutines.
            assertEquals(1000L, testScheduler.currentTime)
        }

    /** A delivered drawable can terminate immediately after valid initial pixels. */
    private class SessionDrawable(
        private val reason: AnimationTermination,
    ) : Drawable(),
        PreemptibleAnimation,
        java.io.Closeable {
        var closed = false

        override suspend fun awaitTermination() = reason

        override suspend fun awaitPreemption() = Unit

        override fun close() {
            closed = true
        }

        override fun draw(canvas: Canvas) = Unit

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
