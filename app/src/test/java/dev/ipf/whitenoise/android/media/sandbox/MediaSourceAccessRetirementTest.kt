package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MediaSourceAccessRetirementTest {
    @Test
    fun retiredProviderReadFailureCannotEscapeOrBlockTheReplacementSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transport = IsolatedMediaTransport(context)
        val sources = sessionSources(transport)
        val reading = CountDownLatch(1)
        val capabilityClosed = CountDownLatch(1)
        val outcome = AtomicReference<Result<Boolean?>>()
        val old =
            Thread {
                outcome.set(
                    runCatching {
                        sources.withProviderDeadline<Boolean> { _, opened ->
                            opened.set(AutoCloseable { capabilityClosed.countDown() })
                            reading.countDown()
                            check(capabilityClosed.await(2, TimeUnit.SECONDS))
                            throw java.io.IOException("Retired provider capability closed during read")
                        }
                    },
                )
            }
        old.start()
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            transport.activateSession()
            old.join(2_000)
            assertFalse("Retired read must finish after actual capability closure", old.isAlive)
            assertEquals(0L, capabilityClosed.count)
            assertNull(checkNotNull(outcome.get()).getOrThrow())
            val replacement = sessionSources(transport)
            assertNotSame(sources, replacement)
            assertEquals(true, replacement.withProviderDeadline { _, _ -> true })
        } finally {
            old.interrupt()
            old.join(2_000)
            transport.close()
        }
    }

    @Test
    fun activeProviderReadFailurePropagatesAndReleasesAdmission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transport = IsolatedMediaTransport(context)
        val sources = sessionSources(transport)
        val failure = java.io.IOException("Active provider read failed")
        try {
            val result = runCatching { sources.withProviderDeadline<Boolean> { _, _ -> throw failure } }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(true, sources.withProviderDeadline { _, _ -> true })
        } finally {
            transport.close()
        }
    }

    private fun sessionSources(transport: IsolatedMediaTransport): MediaSourceAccess {
        val session =
            checkNotNull(
                transport.javaClass
                    .getDeclaredField("session")
                    .apply { isAccessible = true }
                    .get(transport),
            )
        return session.javaClass
            .getDeclaredField("sources")
            .apply { isAccessible = true }
            .get(session) as MediaSourceAccess
    }
}
