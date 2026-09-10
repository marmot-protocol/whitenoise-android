package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import kotlinx.coroutines.runBlocking
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

    /** Pins cancellation propagation across AppState and the extracted first-post identity resolver. */
    @Test
    fun issue1457FallbackSitesUseCancellationSafeWrappers() {
        val appState = appStateSource().readText()
        val forwardText = appState.functionBody("forwardText")
        val wrapperStart = forwardText.indexOf("runCatchingCancellable {")
        assertTrue("forwardText must use cancellation-safe result handling", wrapperStart >= 0)
        val wrapperBrace = forwardText.indexOf('{', wrapperStart)
        val wrappedBlock = forwardText.kotlinBlockFrom(wrapperBrace, "forwardText cancellation-safe wrapper")
        val failureHandler = forwardText.indexOf(".onFailure", wrapperBrace + wrappedBlock.length)

        assertTrue("forwardText send must stay inside the cancellation-safe wrapper", "sendText(" in wrappedBlock)
        assertTrue("forwardText failure handler must follow the wrapped send", failureHandler >= 0)
        assertFalse("forwardText must not use plain runCatching around sendText", Regex("""runCatching\s*\{[^}]*sendText""").containsMatchIn(forwardText))

        val compactAppState =
            appState
                .replace(Regex("""\s+"""), " ")
                .replace(Regex("""\s+\."""), ".")
        listOf(
            "runCatching { marmot().displayName(senderIdHex) }.getOrNull()",
            "runCatching { marmot().displayName(accountIdHex) }.getOrNull()",
            "runCatching { marmot().userProfile(id) }.getOrNull()",
            "runCatching { marmot().displayName(id) }.getOrNull()",
        ).forEach { unsafe ->
            assertFalse("legacy inner fallback must stay migrated: $unsafe", unsafe in compactAppState)
        }

        listOf(
            "runCatching { marmotIo(MarmotTraceSection.ACCOUNT_LIST) { listAccounts() } }.getOrDefault(emptyList())" to
                "runCatchingCancellable { " +
                "marmotIo(MarmotTraceSection.ACCOUNT_LIST) { listAccounts() } }.getOrDefault(emptyList())",
            "runCatching { marmotIo { accountRelayLists(account) } }.getOrNull()" to
                "runCatchingCancellable { marmotIo { accountRelayLists(account) } }.getOrNull()",
            "runCatching { " +
                "marmotIo(MarmotTraceSection.DISPLAY_NAME_READ) { displayName(accountIdHex) } }.getOrNull()" to
                "runCatchingCancellable { " +
                "marmotIo(MarmotTraceSection.DISPLAY_NAME_READ) { displayName(accountIdHex) } }.getOrNull()",
        ).forEach { (unsafe, safe) ->
            assertFalse("unsafe fallback must stay migrated: $unsafe", unsafe in compactAppState)
            assertTrue("missing cancellation-safe fallback: $safe", safe in compactAppState)
        }

        assertNotificationIdentityReadPreservesCancellation(appState)
        assertLocalProfileReadUsesExtractedBoundary(appState)

        val controllers = controllersSource().readText()
        val unsafeRelayHealth = "runCatching { appState.marmotIo { relayHealth() } }.getOrNull()"
        val safeRelayHealth = "runCatchingCancellable { appState.marmotIo { relayHealth() } }.getOrNull()"
        assertFalse("relay-health fallback must stay migrated", unsafeRelayHealth in controllers)
        assertTrue("relay-health fallback must propagate cancellation", safeRelayHealth in controllers)
    }

    /** A cancelled primary read must escape without invoking the display-name fallback. */
    @Test
    fun localProfileCancellationDoesNotBecomeAMiss() {
        val cancellation = CancellationException("cancelled profile read")
        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    readLocalAccountProfileSeed(
                        id = "synthetic",
                        readProfile = { throw cancellation },
                        readDisplayName = { error("Cancellation must not reach fallback") },
                    )
                }
            }
        assertSame(cancellation, thrown)
    }

    /** Cancellation in the secondary read must escape instead of publishing an empty seed. */
    @Test
    fun localDisplayNameCancellationDoesNotBecomeAMiss() {
        val cancellation = CancellationException("cancelled display-name read")
        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    readLocalAccountProfileSeed(
                        id = "synthetic",
                        readProfile = { null },
                        readDisplayName = { throw cancellation },
                    )
                }
            }
        assertSame(cancellation, thrown)
    }

    /** Pins production adapter wiring while executable tests audit the extracted fallback behavior. */
    private fun assertLocalProfileReadUsesExtractedBoundary(appState: String) {
        assertTrue("local profile adapter must exist", "private suspend fun loadAccountSwitchProfileSeed(" in appState)
        val adapter =
            appState
                .substringAfter("private suspend fun loadAccountSwitchProfileSeed(")
                .substringBefore("/**")
                .replace(Regex("""\s+"""), " ")
        assertTrue("local profile reads must use the audited helper", "readLocalAccountProfileSeed(" in adapter)
        assertTrue(
            "profile reads must remain off-main",
            "marmotIo(MarmotTraceSection.PROFILE_READ) { userProfile(it) }" in adapter,
        )
        assertTrue(
            "display-name reads must remain off-main",
            "marmotIo(MarmotTraceSection.DISPLAY_NAME_READ) { displayName(it) }" in adapter,
        )
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

    private fun sourceFile(fileName: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/$fileName"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/$fileName"),
        ).firstOrNull(File::exists)
            ?: error("Missing $fileName source file")

    private fun controllersSource(): File = sourceFile("Controllers.kt")

    private fun appStateSource(): File = sourceFile("AppState.kt")

    /** Audits owner-adapter wiring and the extracted identity fallback's cancellation boundary. */
    private fun assertNotificationIdentityReadPreservesCancellation(appState: String) {
        assertTrue(
            "AppState must construct notification resolution with its owner-scoped read adapter",
            "createNotificationContentResolutionServices(appContext, NotificationContentReads())" in appState,
        )
        val adapterStart = appState.indexOf("private inner class NotificationContentReads : NotificationContentSource")
        assertTrue("notification reads must remain scoped to the AppState owner", adapterStart >= 0)
        val adapter =
            appState
                .kotlinBlockFrom(appState.indexOf('{', adapterStart), "notification content read adapter")
                .replace(Regex("""\s+"""), " ")
        val expectedRead =
            "marmotIo( MarmotTraceSection.DISPLAY_NAME_READ, ) { displayName(accountIdHex) }"
        assertTrue(
            "AppState must keep the local notification identity read on the cancellable MDK boundary",
            expectedRead in adapter,
        )
        val resolution = notificationFirstPostResolutionSource().readText()
        val declarationStart =
            resolution.indexOf("private suspend fun bestEffortDisplayName")
        val nextResolverStart =
            resolution.indexOf(
                "/** Localized structured group-system projection for notifications. */",
                startIndex = declarationStart,
            )
        assertTrue(
            "the extracted identity declaration must remain available for cancellation auditing",
            declarationStart >= 0 && nextResolverStart > declarationStart,
        )
        val declaration = resolution.substring(declarationStart, nextResolverStart)
        assertTrue(
            "the extracted identity resolver must perform the injected display-name read",
            "source.readDisplayName(accountIdHex)" in declaration,
        )
        assertTrue(
            "the extracted identity resolver must rethrow cancellation",
            "catch (cancellation: CancellationException)" in declaration &&
                "throw cancellation" in declaration,
        )
        val cancellationCatch = declaration.indexOf("catch (cancellation: CancellationException)")
        val ordinaryFailureCatch = declaration.indexOf("catch (_: Throwable)")
        assertTrue(
            "cancellation must be handled before the ordinary binding-failure fallback",
            cancellationCatch < ordinaryFailureCatch,
        )
    }

    /** Locates the extracted notification identity resolver audited above. */
    private fun notificationFirstPostResolutionSource(): File = sourceFile("NotificationFirstPostResolution.kt")
}
