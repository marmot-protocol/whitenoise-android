package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.core.RecipientSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileStartGroupNavigationTest {
    @Test
    fun shellProfileStartGroupPromotesPickerWithViewedMember() {
        val candidate = viewedCandidate()

        assertEquals(
            ProfileForegroundRoute.NewGroup(candidate),
            profileForegroundRoute(
                pendingProfileNpub = candidate.npub,
                startGroupMember = candidate,
                conversationOpen = false,
            ),
        )
    }

    @Test
    fun conversationProfileStartGroupPromotesPickerAboveConversation() {
        val candidate = viewedCandidate()

        assertEquals(
            ProfileForegroundRoute.NewGroup(candidate),
            profileForegroundRoute(
                pendingProfileNpub = candidate.npub,
                startGroupMember = candidate,
                conversationOpen = true,
            ),
        )
    }

    @Test
    fun closingPickerLeavesNoProfileOverlayAtShell() {
        assertEquals(
            ProfileForegroundRoute.None,
            profileForegroundRoute(
                pendingProfileNpub = null,
                startGroupMember = null,
                conversationOpen = false,
            ),
        )
    }

    @Test
    fun closingPickerLeavesNoProfileOverlayInConversation() {
        assertEquals(
            ProfileForegroundRoute.None,
            profileForegroundRoute(
                pendingProfileNpub = null,
                startGroupMember = null,
                conversationOpen = true,
            ),
        )
    }

    @Test
    fun profileSheetDelegatesGroupStartInsteadOfComposingTheForegroundFlow() {
        val source = sourceFile("ui/profile/ProfileSheet.kt").readText()

        assertFalse(
            "ProfileSheet must not emit a full-screen group flow from behind its modal",
            source.contains("NewGroupFlow("),
        )
    }

    @Test
    fun shellOwnsGroupFlowBeforeRenderingEitherProfileEntryPoint() {
        val shellSource = sourceFile("ui/navigation/MainShell.kt").readText()
        val conversationSource = sourceFile("ui/conversation/ConversationScreen.kt").readText()
        val flowIndex = shellSource.indexOf("        NewGroupFlow(")
        val conversationIndex = shellSource.indexOf("        ConversationScreen(")
        val promotedBlockIndex =
            shellSource.lastIndexOf("    if (profileRoute is ProfileForegroundRoute.NewGroup)", flowIndex)

        assertTrue("MainShell must render the promoted group flow", flowIndex >= 0)
        assertTrue(
            "the promoted flow must retain the owning chat surface's secure-window policy",
            shellSource.substring(promotedBlockIndex, flowIndex).contains("WindowSecureFlag("),
        )
        assertTrue("the promoted flow must replace, not sit behind, a conversation", flowIndex < conversationIndex)
        assertTrue(
            "the promoted flow must return before the conversation can render over it",
            shellSource.substring(flowIndex, conversationIndex).contains("\n        return\n"),
        )
        assertTrue(
            "the viewed profile must remain preselected in the promoted picker",
            shellSource.contains("initialMembers = listOf(profileRoute.initialMember)"),
        )
        assertTrue(
            "the in-conversation profile must delegate the same handoff to MainShell",
            conversationSource.contains("onStartGroup = onStartProfileGroup"),
        )
    }

    private fun viewedCandidate() =
        RecipientSearch.Candidate(
            accountIdHex = "a".repeat(64),
            displayName = "Alice",
            npub = "npub1alice",
        )

    private fun sourceFile(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::isFile) ?: error("Missing source file: $relativePath")
}
