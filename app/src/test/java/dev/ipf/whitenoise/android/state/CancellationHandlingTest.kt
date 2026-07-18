package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class CancellationHandlingTest {
    @Test
    fun runCatchingCancellableRethrowsCancellationException() {
        val cancellation = CancellationException("cancelled")

        val thrown =
            assertThrows(CancellationException::class.java) {
                runCatchingCancellable { throw cancellation }
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun runCatchingCancellableCapturesNonCancellationFailure() {
        val failure = IllegalStateException("boom")

        val result = runCatchingCancellable<Unit> { throw failure }

        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun runCatchingCancellableReturnsSuccessfulValue() {
        val result = runCatchingCancellable { "ok" }

        assertEquals("ok", result.getOrThrow())
    }

    @Test
    fun issue1457FallbackSitesUseCancellationSafeWrappers() {
        val appState = appStateSource().readText()
        val forwardText = appState.functionBody("forwardText")
        assertTrue("forwardText must use cancellation-safe isSuccess handling", "runCatchingCancellable {" in forwardText && ".isSuccess" in forwardText)
        assertFalse("forwardText must not use plain runCatching around sendText", Regex("""runCatching\s*\{[^}]*sendText""").containsMatchIn(forwardText))

        listOf(
            "runCatching { marmotIo { listAccounts() } }.getOrDefault(emptyList())" to
                "runCatchingCancellable { marmotIo { listAccounts() } }.getOrDefault(emptyList())",
            "runCatching { marmotIo { accountRelayLists(account) } }.getOrNull()" to
                "runCatchingCancellable { marmotIo { accountRelayLists(account) } }.getOrNull()",
            "runCatching { marmot().displayName(senderIdHex) }.getOrNull()" to
                "runCatchingCancellable { marmot().displayName(senderIdHex) }.getOrNull()",
            "runCatching { marmot().displayName(accountIdHex) }.getOrNull()" to
                "runCatchingCancellable { marmot().displayName(accountIdHex) }.getOrNull()",
            "runCatching { marmot().userProfile(id) }.getOrNull()" to
                "runCatchingCancellable { marmot().userProfile(id) }.getOrNull()",
            "runCatching { marmot().displayName(id) }.getOrNull()" to
                "runCatchingCancellable { marmot().displayName(id) }.getOrNull()",
        ).forEach { (unsafe, safe) ->
            assertFalse("unsafe fallback must stay migrated: $unsafe", unsafe in appState)
            assertTrue("missing cancellation-safe fallback: $safe", safe in appState)
        }

        val controllers = controllersSource().readText()
        val unsafeRelayHealth = "runCatching { appState.marmotIo { relayHealth() } }.getOrNull()"
        val safeRelayHealth = "runCatchingCancellable { appState.marmotIo { relayHealth() } }.getOrNull()"
        assertFalse("relay-health fallback must stay migrated", unsafeRelayHealth in controllers)
        assertTrue("relay-health fallback must propagate cancellation", safeRelayHealth in controllers)
    }

    @Test
    fun watchAgentTextStreamKeepsTheStreamLoopInsideCancellationSafeWrapper() {
        val body = controllersSource().readText().functionBody("watchAgentTextStream")
        val wrapperStart = body.indexOf("runCatchingCancellable {")
        assertTrue("agent stream must use cancellation-safe result handling", wrapperStart >= 0)

        val wrapperBrace = body.indexOf('{', wrapperStart)
        val wrappedBlock = body.kotlinBlockFrom(wrapperBrace, "watchAgentTextStream cancellation-safe wrapper")
        val failureHandler = body.indexOf(".onFailure", wrapperBrace + wrappedBlock.length)

        assertTrue("agent stream loop must stay inside the cancellation-safe wrapper", "while (true)" in wrappedBlock)
        assertTrue("ordinary failure handling must remain outside the wrapped loop", failureHandler >= 0)
    }

    @Test
    fun rethrowsCancellationException() {
        assertThrows(CancellationException::class.java) {
            rethrowIfCancellation(CancellationException("cancelled"))
        }
    }

    @Test
    fun ignoresNonCancellationThrowables() {
        // Must return normally so callers fall through to their error handling.
        rethrowIfCancellation(RuntimeException("boom"))
        rethrowIfCancellation(IllegalStateException("nope"))
    }

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull(File::exists)
            ?: error("Missing Controllers.kt source file")

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists)
            ?: error("Missing AppState.kt source file")
}
