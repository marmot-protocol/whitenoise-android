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
                .substringAfter("if (directGroupId != null) {")
                .substringBefore("} else {")

        val clearIndex = directShareBranch.indexOf("clearSharePickerRequest()")
        val handledIndex = directShareBranch.indexOf("onShareRequestHandled(request)")
        val stageIndex = directShareBranch.indexOf("stageShareToChats(request")

        assertTrue("direct shares must clear an existing picker", clearIndex >= 0)
        assertTrue("picker cleanup must happen before the request is acknowledged", clearIndex < handledIndex)
        assertTrue("the request must be acknowledged before its payload is staged", handledIndex < stageIndex)
    }

    private fun mainShellSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MainShell.kt source file")
}
