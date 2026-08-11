package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.state.runNotificationStartupReceiverBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationStartupOrderingTest {
    @Test
    fun noReplayReceiverAttachesBeforeStartupCanEmit() =
        runTest {
            var runtimeStarted = false
            var receiverAttached = false
            val received = mutableListOf<String>()

            val ready =
                runNotificationStartupReceiverBoundary(
                    startRuntime = { runtimeStarted = true },
                    awaitReceiverActive = {
                        assertTrue(runtimeStarted)
                        receiverAttached = true
                        true
                    },
                    continueStartup = {
                        if (receiverAttached) received += "startup-update"
                    },
                )

            assertTrue(ready)
            assertEquals(listOf("startup-update"), received)
        }

    @Test
    fun receiverFailureDoesNotRunRemainingStartupWork() =
        runTest {
            var continued = false

            val ready =
                runNotificationStartupReceiverBoundary(
                    startRuntime = {},
                    awaitReceiverActive = { false },
                    continueStartup = { continued = true },
                )

            assertFalse(ready)
            assertFalse(continued)
        }

    @Test(expected = CancellationException::class)
    fun receiverCancellationPropagates() =
        runTest {
            runNotificationStartupReceiverBoundary(
                startRuntime = {},
                awaitReceiverActive = { throw CancellationException("cancelled") },
                continueStartup = { error("must not continue") },
            )
        }

    @Test
    fun productionBootstrapUsesTheReceiverBoundaryBeforeAccountWork() {
        val bootstrap = appStateSource().functionBody("bootstrapLocked")
        val channels = bootstrap.indexOf("localNotificationPresenter.ensureChannels()")
        val boundary = bootstrap.indexOf("runNotificationStartupReceiverBoundary(")
        val accounts = bootstrap.indexOf("refreshAccountsForBootstrap()")

        assertTrue(channels >= 0)
        assertTrue(boundary > channels)
        assertTrue(accounts > boundary)
        assertFalse(bootstrap.contains("startNotificationListener()"))
    }

    private fun appStateSource(): String =
        sequenceOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing AppState.kt")
}

private fun String.functionBody(functionName: String): String {
    val signature = indexOf("fun $functionName(")
    check(signature >= 0) { "Missing function $functionName" }
    val openBrace = indexOf('{', signature)
    check(openBrace >= 0) { "Missing body for $functionName" }
    var depth = 0
    for (index in openBrace until length) {
        when (this[index]) {
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return substring(openBrace + 1, index)
            }
        }
    }
    error("Unterminated body for $functionName")
}
