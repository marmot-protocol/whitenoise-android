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

        assertTrue(
            "picker callback must forward its chosen account",
            "onStage = { accountRef, groupIds ->" in source &&
                "stageShareToChats(request, accountRef, groupIds)" in source,
        )
        assertTrue(
            "staging must not fall back to the globally active account",
            "appState.stageInboundShare(accountRef, groupIds, request.payload)" in source,
        )
        assertTrue(
            "a non-active chosen account must switch before opening its staged chat",
            "pendingStagedShareOpen" in source && "appState.setActiveAccount(accountRef)" in source,
        )
    }

    private fun mainShellSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MainShell.kt source file")
}
