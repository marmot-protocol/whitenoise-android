package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression coverage for issue #1698's local-ready account-switch boundary. */
class AccountSwitchLocalSnapshotOrderingTest {
    @Test
    fun bindPublishesLocalSnapshotsAndAFrameBeforeCatchUp() {
        val body = controllersSource().readText().kotlinFunctionBody("bind")
        val firstSnapshot = body.indexOf("chatListStream.snapshot()")
        val secondSnapshot = body.indexOf("chatStream.snapshot()")
        val publishReady = body.indexOf("isLoading = false", startIndex = secondSnapshot)
        val renderFrame = body.indexOf("awaitRenderedChatListFrame()", startIndex = publishReady)
        val catchUp = body.indexOf("appState.launchCatchUpAccounts()")

        assertTrue("chat-list snapshot must be read", firstSnapshot >= 0)
        assertTrue("chats snapshot must follow the chat-list snapshot", secondSnapshot > firstSnapshot)
        assertTrue("local rows must be published before waiting for a frame", publishReady > secondSnapshot)
        assertTrue("the local snapshot must get a rendered frame before catch-up", renderFrame > publishReady)
        assertTrue("relay catch-up must start only after the rendered local frame", catchUp > renderFrame)
        assertTrue("one bind must start catch-up only once", "if (!catchUpStarted)" in body)
        assertTrue(
            "live consumers must start after catch-up is launched",
            body.indexOf("runUntilFirstLiveSubscriptionEnds(") > catchUp,
        )
    }

    @Test
    fun catchUpLaunchIsProcessScopedAndDeduplicated() {
        val body = appStateSource().readText().kotlinFunctionBody("launchCatchUpAccounts")

        assertTrue("a blocked catch-up must run outside the controller bind job", "notificationScope.launch" in body)
        assertTrue("rapid account rebinds must share an active catch-up", "accountCatchUpJob?.isActive == true" in body)
        assertTrue(
            "the background job must run the result-bearing best-effort catch-up",
            "catchUpAccountsBestEffort()" in body,
        )
    }

    @Test
    fun activationCallbackPrecedesBestEffortPostSwitchWork() {
        val source = appStateSource().readText()
        val start = source.indexOf("suspend fun setActiveAccount(")
        val end = source.indexOf("internal fun recordAccountSwitchLocalSnapshotRendered", startIndex = start)
        check(start >= 0 && end > start) { "Missing setActiveAccount section" }
        val body = source.substring(start, end)
        val activeRef = body.indexOf("activeAccountRef = label")
        val localUiState = body.indexOf("reloadMediaAutoDownloadMatrix()", startIndex = activeRef)
        val activated = body.indexOf("onActivated()", startIndex = localUiState)
        val profile = body.indexOf("warmProfile(it)", startIndex = activated)
        val privacy = body.indexOf("configurePrivacyRuntime()", startIndex = activated)
        val notifications = body.indexOf("refreshLocalNotificationSettings()", startIndex = activated)
        val push = body.indexOf("syncNativePushRegistrationIfEnabled()", startIndex = activated)

        assertTrue(
            "target account context must flip before the activation callback",
            activeRef >= 0 && localUiState > activeRef,
        )
        assertTrue("the activation callback must follow local account UI state", activated > localUiState)
        listOf(profile, privacy, notifications, push).forEach { postSwitchIndex ->
            assertTrue(
                "network/best-effort switch work must follow the activation callback",
                postSwitchIndex > activated,
            )
        }
    }

    @Test
    fun selectorDismissesAtActivationBoundaryInsteadOfAwaitingPostSwitchWork() {
        val body = accountSelectorSource().readText().kotlinFunctionBody("AccountSelectorSheet")

        val switchCall =
            body
                .substringAfter("appState.setActiveAccount(accountLabel) {")
                .substringBefore("\n                    }")
        val dismiss = switchCall.indexOf("onDismiss()")
        val reset = switchCall.indexOf("onAccountSwitched()")

        assertTrue(
            "dismiss/reset must be passed into setActiveAccount's activation boundary",
            dismiss >= 0 && reset > dismiss,
        )
        assertFalse(
            "dismiss must not wait until the entire setActiveAccount call returns",
            Regex("""setActiveAccount\(accountLabel\)\s*onDismiss\(\)""").containsMatchIn(body),
        )
    }

    @Test
    fun firstLocalFrameTimingTraceIsTargetScopedAndPrivacySafe() {
        val appState = appStateSource().readText()
        val recorder = appState.kotlinFunctionBody("recordAccountSwitchLocalSnapshotRendered")
        val bind = controllersSource().readText().kotlinFunctionBody("bind")

        assertTrue(
            "trace must reject a stale controller from another account",
            "trace.accountRef != accountRef" in recorder,
        )
        assertTrue(
            "trace must reject a controller that is no longer active",
            "activeAccountRef != accountRef" in recorder,
        )
        assertTrue(
            "a stale controller must be rejected before publishing startup readiness",
            recorder.indexOf("activeAccountRef != accountRef") <
                recorder.indexOf("recordStartupLocalSnapshotRendered()"),
        )
        assertTrue(
            "trace must use a monotonic elapsed clock",
            "SystemClock.elapsedRealtime() - trace.startedAtMs" in recorder,
        )
        assertTrue("trace must report only timing and a small row count", "rows=\$rowCount" in recorder)
        assertFalse(
            "trace output must not include an account identifier",
            "trace.accountRef" in recorder.substringAfter("appStateDebug"),
        )
        assertTrue(
            "the trace must be recorded only after the local frame boundary",
            bind.indexOf("recordAccountSwitchLocalSnapshotRendered") >
                bind.indexOf("awaitRenderedChatListFrame()"),
        )
    }

    private fun controllersSource(): File = source("state/Controllers.kt")

    private fun appStateSource(): File = source("state/AppState.kt")

    private fun accountSelectorSource(): File = source("ui/account/AccountSelectorSheet.kt")

    private fun source(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists) ?: error("Missing source file: $relativePath")
}
