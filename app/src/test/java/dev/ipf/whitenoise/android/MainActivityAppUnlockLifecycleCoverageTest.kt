package dev.ipf.whitenoise.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityAppUnlockLifecycleCoverageTest {
    private val source by lazy {
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/MainActivity.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/MainActivity.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing MainActivity.kt source file")
    }
    private val appSource by lazy {
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing WhiteNoiseApp.kt source file")
    }

    @Test
    fun biometricPromptIsInstalledBeforeComposeAndNotConstructedByTheLaunchFunction() {
        val installIndex = source.indexOf("installAppUnlockPrompt()")
        val composeIndex = source.indexOf("installComposeContent()")
        val requestBody = functionBody("requestAppUnlock")
        val launchBody = functionBody("launchAppUnlockPrompt")

        assertTrue("BiometricPrompt callback must be attached early during onCreate", installIndex >= 0)
        assertTrue(
            "BiometricPrompt callback must be attached before Compose requests authentication",
            composeIndex > installIndex,
        )
        assertFalse("requestAppUnlock must reuse the lifecycle-bound prompt", requestBody.contains("BiometricPrompt("))
        assertTrue(launchBody.contains("appUnlockPrompt.authenticate(promptInfo, cryptoObject)"))
    }

    @Test
    fun successfulUnlockRequiresKeystoreBackedCryptoBeforeCompletingTheSession() {
        val launchBody = functionBody("launchAppUnlockPrompt")
        val callbackBody = functionBody("createAppUnlockPrompt")
        val ownershipCheckIndex = callbackBody.indexOf("appState.appUnlockSessions.owns(sessionId, hostId)")
        val cryptoVerificationIndex = callbackBody.indexOf("appUnlockCryptoGate.verify(result, expectedCipher)")
        val sessionCompletionIndex = callbackBody.indexOf("appState.appUnlockSessions.complete(")

        assertTrue(launchBody.contains("appUnlockCryptoGate.createCryptoObject()"))
        assertTrue(launchBody.contains("appUnlockPromptHostState.expectedCipher = cryptoObject.cipher"))
        assertTrue(launchBody.contains("appUnlockPrompt.authenticate(promptInfo, cryptoObject)"))
        assertTrue("Session ownership must be checked before consuming crypto", ownershipCheckIndex >= 0)
        assertTrue("AuthenticationResult crypto must be verified", cryptoVerificationIndex >= 0)
        assertTrue(cryptoVerificationIndex > ownershipCheckIndex)
        assertTrue(
            "Keystore-backed crypto must succeed before the app unlock session completes",
            sessionCompletionIndex > cryptoVerificationIndex,
        )
    }

    @Test
    fun recreatedActivityReattachesWithoutLaunchingTheSameSessionAgain() {
        val installBody = functionBody("installAppUnlockPrompt")
        val requestBody = functionBody("requestAppUnlock")

        assertTrue(installBody.contains("shouldReattachAppUnlockPrompt("))
        assertTrue(installBody.contains("retainedHostSessionMatches = appUnlockPromptHostState.sessionId == sessionId"))
        assertTrue(installBody.contains("appState.appUnlockSessions.activeSessionId?.takeIf { sessionId ->"))
        assertTrue(installBody.contains("replaceExistingHost = true"))
        assertTrue(installBody.contains("appState.appUnlockSessions.clear()"))
        assertTrue(installBody.contains("prompt-not-retained"))
        assertTrue(installBody.contains("if (abandonedSession)"))
        assertTrue(installBody.contains("appState.requestAppUnlock()"))
        assertTrue(installBody.contains("requestAppUnlock()"))
        assertTrue(requestBody.contains("attachedAppUnlockSessionId == sessionId"))
        assertTrue(requestBody.contains("replaceExistingHost = false"))
    }

    @Test
    fun everyTerminalCallbackIsBoundToTheCurrentSession() {
        val callbackBody = functionBody("createAppUnlockPrompt")

        assertTrue(callbackBody.contains("sessionId ?: return"))
        assertTrue(callbackBody.contains("hostId ?: return"))
        assertTrue(callbackBody.contains("appState.appUnlockSessions.complete("))
        assertTrue(callbackBody.contains("appState.markAppUnlockSucceeded("))
        assertTrue(callbackBody.contains("appState.appUnlockSessions.terminate("))
        assertTrue(
            callbackBody.contains(
                "foregroundReturnExpiryElapsedRealtime(",
            ),
        )
        assertTrue(callbackBody.contains("stale-success-ignored"))
        assertTrue(callbackBody.contains("stale-error-ignored"))
        assertTrue(source.contains("appState.appUnlockSessions.clearForegroundReturn()"))
    }

    @Test
    fun unlockOwnershipAndDiagnosticsUseDedicatedPrivacySafeBoundaries() {
        assertTrue(source.contains("appUnlockHostIds.incrementAndGet()"))
        assertTrue(source.contains("hostId = appUnlockHostId"))
        assertTrue(source.contains("createAppUnlockPrompt(sessionId = sessionId, hostId = appUnlockHostId)"))
        assertTrue(source.contains("if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) return"))
        assertFalse(source.contains("authentication_type="))
        assertFalse(source.contains("error_code="))
    }

    @Test
    fun protectedRetryCannotReplaceAnActivePlatformPrompt() {
        assertTrue(appSource.contains("onRetry = { appState.requestAppUnlock() }"))
        assertFalse(appSource.contains("retryAppUnlock"))
    }

    private fun functionBody(name: String): String {
        val signature = "private fun $name("
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing $signature" }
        val bodyStart = source.indexOf('{', start)
        check(bodyStart >= 0) { "Missing body for $signature" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(bodyStart + 1, index)
                }
            }
        }
        error("Unterminated body for $signature")
    }
}
