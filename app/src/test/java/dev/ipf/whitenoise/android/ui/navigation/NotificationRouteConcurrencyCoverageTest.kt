package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Trace wiring coverage for inactive-account notification routing (#586). */
class NotificationRouteConcurrencyCoverageTest {
    @Test
    fun productionRouteEmitsEveryAttributionPhase() {
        val source = mainShellSource().readText() + conversationSource().readText()

        listOf(
            "NotificationRouteTraceSection.ACCOUNT_ACTIVATION",
            "NotificationRouteTraceSection.GROUP_DETAILS",
            "NotificationRouteTraceSection.CONTROLLER_BIND",
            "NotificationRouteTraceSection.FIRST_CONVERSATION_FRAME",
        ).forEach { phase ->
            assertTrue("missing production trace phase $phase", phase in source)
        }
        assertTrue(
            "the first-frame callback must finish the request trace",
            "NotificationRouteTrace.finishRequest(requestId)" in source,
        )
    }

    private fun mainShellSource(): File = source("ui/navigation/MainShell.kt")

    private fun conversationSource(): File = source("ui/conversation/ConversationScreen.kt")

    private fun source(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists) ?: error("Missing source file: $relativePath")
}
