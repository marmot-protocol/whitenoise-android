package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainShellShareRoutingCoverageTest {
    /** Persistence must precede every phase/account/readiness gate and ordinary shares promote immediately. */
    @Test
    fun inboundRequestIsDurableBeforeReadinessAndOrdinaryPickerPromotion() {
        val source = mainShellSource().readText()
        val routing =
            source
                .substringAfter("val request =\n            inboundShareRequest", missingDelimiterValue = "")
                .substringBefore("\n    // Upgrade a provisional open", missingDelimiterValue = "")

        val saveIndex = routing.indexOf("persisted = store.save(request)")
        val presentationGateIndex = routing.indexOf("shouldPresentInboundShare(")
        val ordinaryBranchIndex = routing.indexOf("request.shortcutId.isNullOrBlank()")
        val chatReadinessIndex = routing.indexOf("val chatListReady =")
        assertTrue("the external payload must be encrypted before route readiness checks", saveIndex >= 0)
        assertTrue(saveIndex < presentationGateIndex)
        assertTrue(presentationGateIndex < ordinaryBranchIndex)
        assertTrue("ordinary shares must not wait for chat-list readiness", ordinaryBranchIndex < chatReadinessIndex)
    }

    /** Direct shortcut routing prepares off-main, clears ownership once, then opens. */
    @Test
    fun directShareStagesOffMainThenClearsBeforeOpening() {
        val directShareBranch =
            mainShellSource()
                .readText()
                .substringAfter("if (directGroupId != null) {", missingDelimiterValue = "")
                .substringBefore("} else {", missingDelimiterValue = "")

        val stageIndex = directShareBranch.indexOf("stageInboundShareForFirstFrame(")
        val clearIndex = directShareBranch.indexOf("clearSharePickerRequest()")
        val openIndex = directShareBranch.indexOf("openAfterStagedShare(")

        assertTrue("direct shares must prepare without Main-thread provider I/O", stageIndex >= 0)
        assertTrue("direct shares must clear an existing picker", clearIndex >= 0)
        assertTrue("durable local staging must precede acknowledgement", stageIndex < clearIndex)
        assertTrue("the destination opens only after staging and acknowledgement", clearIndex < openIndex)
        assertTrue(
            "direct routing must not acknowledge the same request a second time",
            "onShareRequestHandled(request)" !in directShareBranch,
        )
    }

    /** The shared cleanup callback owns the single acknowledgement for raw intents. */
    @Test
    fun pickerCleanupAcknowledgesOnlyTheMatchingUnpromotedRequest() {
        val source = mainShellSource().readText()
        val cleanup =
            source
                .substringAfter("val clearSharePickerRequest: () -> Unit = {", missingDelimiterValue = "")
                .substringBefore("    val stageShareToChats:", missingDelimiterValue = "")

        val clearIndex = cleanup.indexOf("shellStateHolder.clearPendingShareRequest(requestId)")
        val matchIndex = cleanup.indexOf("inboundShareRequest?.requestId == request.requestId")
        val acknowledgeIndex = cleanup.indexOf("onShareRequestHandled(request)")
        assertTrue(clearIndex >= 0)
        assertTrue(matchIndex > clearIndex)
        assertTrue(acknowledgeIndex > matchIndex)
    }

    @Test
    fun pickerAccountFlowsExplicitlyIntoStagingAndCrossAccountNavigation() {
        val source = mainShellSource().readText()
        val pickerCallback =
            source
                .substringAfter("onStage = { accountRef, groupIds ->", missingDelimiterValue = "")
                .substringBefore("                },", missingDelimiterValue = "")
        val shareStageSection =
            source
                .substringAfter("val openAfterStagedShare:", missingDelimiterValue = "")
                .substringBefore("    LaunchedEffect(\n        pendingStagedShareOpen,", missingDelimiterValue = "")
        val nonActiveAccountBranch =
            shareStageSection.substringAfter("        } else {", missingDelimiterValue = "")
        val pendingOpenEffect =
            source
                .substringAfter("    LaunchedEffect(\n        pendingStagedShareOpen,", missingDelimiterValue = "")
                .substringBefore("\n\n    LaunchedEffect(", missingDelimiterValue = "")

        assertTrue(
            "picker callback must forward its chosen account",
            "stageShareToChats(request, accountRef, groupIds)" in pickerCallback,
        )
        assertTrue(
            "staging must not fall back to the globally active account",
            "appState.stageInboundShare(accountRef, groupIds, request.payload)" in shareStageSection,
        )
        val pendingIndex = nonActiveAccountBranch.indexOf("pendingStagedShareOpen = pending")
        val switchIndex = nonActiveAccountBranch.indexOf("appState.setActiveAccount(accountRef)")
        val failedSwitchGuardIndex = nonActiveAccountBranch.indexOf("appState.activeAccountRef != accountRef")
        assertTrue(
            "non-active staging must retain its open before switching accounts",
            pendingIndex >= 0 && pendingIndex < switchIndex,
        )
        assertTrue(
            "a failed switch must discard only its own pending open",
            switchIndex < failedSwitchGuardIndex && "pendingStagedShareOpen === pending" in nonActiveAccountBranch,
        )
        val activeAccountGuardIndex =
            pendingOpenEffect.indexOf("if (appState.activeAccountRef != pending.accountRef) return@LaunchedEffect")
        val openIndex = pendingOpenEffect.indexOf("openChatForShare(allChats, pending.groupIdHex)")
        assertTrue(
            "the staged chat must open only after its account becomes active",
            activeAccountGuardIndex >= 0 && activeAccountGuardIndex < openIndex,
        )
    }

    private fun mainShellSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MainShell.kt source file")
}
