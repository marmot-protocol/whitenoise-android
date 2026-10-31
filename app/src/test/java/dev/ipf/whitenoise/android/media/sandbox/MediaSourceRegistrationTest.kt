package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Forces the actual closed-check→lazy-scheduler interleaving without opening a provider or decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MediaSourceRegistrationTest {
    @Test
    fun closeBeforeTimeoutRegistrationCannotCreateALateExecutor() {
        val resources = MediaParentResources()
        val source = MediaSourceAccess(ApplicationProvider.getApplicationContext<Context>(), resources)
        val invoked = AtomicBoolean()
        val result = AtomicReference<Result<Any?>?>()
        val operation =
            Thread {
                result.set(
                    runCatching {
                        source.withProviderDeadline { _, _ ->
                            invoked.set(true)
                            Any()
                        }
                    },
                )
            }
        val timer =
            MediaSourceAccess::class.java
                .getDeclaredField("sourceTimer")
                .apply { isAccessible = true }
                .get(source) as Lazy<*>

        @Suppress("UNCHECKED_CAST")
        val active =
            MediaSourceAccess::class.java
                .getDeclaredField("activeSource")
                .apply { isAccessible = true }
                .get(source) as AtomicReference<*>
        try {
            synchronized(resources) {
                operation.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (active.get() == null && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue("Both closed checks must have passed before closing", active.get() != null)
                // The operation is blocked in resources.task(), immediately before scheduler registration.
                source.close()
                assertFalse(timer.isInitialized())
            }
            operation.join(2000)
            assertFalse(operation.isAlive)
            assertTrue(checkNotNull(result.get()).isSuccess)
            assertNull(checkNotNull(result.get()).getOrNull())
            assertFalse(invoked.get())
            assertTrue(resources.available())
            source.close()
            assertFalse("A closed AV source must not leave a late scheduler alive", timer.isInitialized())
        } finally {
            operation.interrupt()
            operation.join(2000)
            // R3 fails the final assertion; stop only that synthetic executor for fixture hygiene.
            if (timer.isInitialized()) (timer.value as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }
}
