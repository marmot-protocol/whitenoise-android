package dev.ipf.whitenoise.android.ui.common

import android.graphics.drawable.ColorDrawable
import dev.ipf.whitenoise.android.media.AnimationAdmissionBusyException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production lifecycle retry boundary, separate from codec failure and cancellation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AvatarAdmissionRecoveryTest {
    @Test
    fun busyGroupRecoversOnlyAfterAnOwnershipChange() =
        runBlocking {
            val generation = MutableStateFlow(0L)
            val drawable = ColorDrawable()
            var attempts = 0
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    decodeAvatarWhenAvailable {
                        attempts++
                        if (attempts == 1) throw AnimationAdmissionBusyException(generation, 0)
                        drawable
                    }
                }
            assertEquals(1, attempts)
            assertFalse(result.isCompleted)
            generation.value = 1L
            assertSame(drawable, result.await())
            assertEquals(2, attempts)
        }

    @Test
    fun corruptSourceDoesNotEnterContentionRecovery() =
        runBlocking {
            var attempts = 0
            assertNull(
                decodeAvatarWhenAvailable {
                    attempts++
                    null
                },
            )
            assertEquals(1, attempts)
        }

    @Test
    fun cancellingParkedGroupPreventsLaterDecode() =
        runBlocking {
            val generation = MutableStateFlow(0L)
            var attempts = 0
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    decodeAvatarWhenAvailable {
                        attempts++
                        throw AnimationAdmissionBusyException(generation, 0)
                    }
                }
            result.cancelAndJoin()
            generation.value = 1L
            assertEquals(1, attempts)
        }
}
