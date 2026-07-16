package dev.ipf.whitenoise.android.state

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainThreadAssertTest {
    @Test
    fun mainThreadCallSucceedsWhenCheckingEnabled() {
        val failure = AtomicReference<Throwable>()
        Handler(Looper.getMainLooper()).post {
            runCatching { assertMainThread(checkingEnabled = true) { "test context" } }.onFailure(failure::set)
        }
        shadowOf(Looper.getMainLooper()).idle()
        failure.get()?.let { throw it }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun offMainThreadThrowsWhenCheckingEnabled() {
        newSingleThreadContext("background").use { dispatcher ->
            val error =
                runBlocking(dispatcher) {
                    assertThrows(IllegalStateException::class.java) {
                        assertMainThread(checkingEnabled = true) { "publishSuppressionDepth" }
                    }
                }
            assertTrue(error.message.orEmpty().startsWith("Expected main thread but was background"))
            assertTrue(error.message.orEmpty().endsWith(": publishSuppressionDepth"))
        }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun offMainThreadThrowsDefaultMessageWhenContextOmitted() {
        newSingleThreadContext("background").use { dispatcher ->
            val error =
                runBlocking(dispatcher) {
                    assertThrows(IllegalStateException::class.java) {
                        assertMainThread(checkingEnabled = true)
                    }
                }
            assertTrue(error.message.orEmpty().startsWith("Expected main thread but was background"))
        }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun disabledCheckingIsNoOpOffMainWithoutEvaluatingContext() {
        val contextEvaluated = AtomicBoolean(false)
        newSingleThreadContext("background").use { dispatcher ->
            runBlocking(dispatcher) {
                assertMainThread(checkingEnabled = false) {
                    contextEvaluated.set(true)
                    "ignored"
                }
            }
        }
        assertFalse(contextEvaluated.get())
    }
}
