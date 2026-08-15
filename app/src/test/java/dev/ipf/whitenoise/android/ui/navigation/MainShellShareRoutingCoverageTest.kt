package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainShellShareRoutingCoverageTest {
    @Test
    fun directShareClearsExistingPickerBeforeHandlingAndStaging() {
        val directShareBranch =
            mainShellSource()
                .readText()
                .substringAfter("if (directGroupId != null) {", missingDelimiterValue = "")
                .substringBefore("} else {", missingDelimiterValue = "")

        val clearIndex = directShareBranch.indexOf("clearSharePickerRequest()")
        val handledIndex = directShareBranch.indexOf("onShareRequestHandled(request)")
        val stageIndex = directShareBranch.indexOf("stageShareToChats(request")

        assertTrue("direct shares must clear an existing picker", clearIndex >= 0)
        assertTrue("picker cleanup must happen before the request is acknowledged", clearIndex < handledIndex)
        assertTrue("the request must be acknowledged before its payload is staged", handledIndex < stageIndex)
    }

    @Test
    fun pickerAccountFlowsExplicitlyIntoStagingAndCrossAccountNavigation() {
        val source = mainShellSource().readText()
        val pickerCallback =
            source
                .substringAfter("onStage = { accountRef, groupIds ->", missingDelimiterValue = "")
                .substringBefore("                },", missingDelimiterValue = "")
        val stageShareBranch =
            source
                .substringAfter("val stageShareToChats:", missingDelimiterValue = "")
                .substringBefore("    LaunchedEffect(\n        pendingStagedShareOpen,", missingDelimiterValue = "")
        val nonActiveAccountBranch =
            stageShareBranch.substringAfter("            } else {", missingDelimiterValue = "")
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
            "appState.stageInboundShare(accountRef, groupIds, request.payload)" in stageShareBranch,
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
