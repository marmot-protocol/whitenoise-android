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
        val state = ProfileGroupForegroundState().apply { open(candidate) }

        assertEquals(
            ProfileForegroundRoute.NewGroup(candidate),
            profileForegroundRoute(
                pendingProfileNpub = null,
                startGroupMember = state.initialMember,
                conversationOpen = false,
            ),
        )
    }

    @Test
    fun conversationProfileStartGroupPromotesPickerAboveConversation() {
        val candidate = viewedCandidate()
        val state = ProfileGroupForegroundState().apply { open(candidate) }

        assertEquals(
            ProfileForegroundRoute.NewGroup(candidate),
            profileForegroundRoute(
                pendingProfileNpub = null,
                startGroupMember = state.initialMember,
                conversationOpen = true,
            ),
        )
    }

    @Test
    fun closingPickerClearsShellForegroundRoute() {
        val candidate = viewedCandidate()
        val state = ProfileGroupForegroundState().apply { open(candidate) }

        assertEquals(
            ProfileForegroundRoute.NewGroup(candidate),
            profileForegroundRoute(null, state.initialMember, conversationOpen = false),
        )

        state.close()

        assertEquals(
            ProfileForegroundRoute.None,
            profileForegroundRoute(null, state.initialMember, conversationOpen = false),
        )
    }

    @Test
    fun closingPickerClearsConversationForegroundRoute() {
        val candidate = viewedCandidate()
        val state = ProfileGroupForegroundState().apply { open(candidate) }

        assertEquals(
            ProfileForegroundRoute.NewGroup(candidate),
            profileForegroundRoute(null, state.initialMember, conversationOpen = true),
        )

        state.close()

        assertEquals(
            ProfileForegroundRoute.None,
            profileForegroundRoute(null, state.initialMember, conversationOpen = true),
        )
    }

    @Test
    fun profileSheetDelegatesGroupStartInsteadOfComposingTheForegroundFlow() {
        val source = sourceFile("ui/profile/ProfileSheet.kt").readText()
        val actionStart = source.indexOf("title = stringResource(R.string.profile_start_new_group_with")
        val action = source.substring(actionStart, source.indexOf("SettingsActionRow(", actionStart + 1))

        assertFalse(
            "ProfileSheet must not emit a full-screen group flow from behind its modal",
            source.contains("NewGroupFlow("),
        )
        assertTrue("the profile action must invoke the shell handoff", action.contains("onStartGroup("))
        assertTrue(
            "the shell handoff must retain the viewed member",
            action.contains("RecipientSearch.Candidate("),
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
        val closeBlock =
            shellSource.substringAfter("            onClose = {").substringBefore("            },")

        assertTrue("MainShell must render the promoted group flow", flowIndex >= 0)
        assertTrue(
            "picker back must invoke the tested foreground-state cleanup",
            closeBlock.contains("profileGroupForegroundState.close()"),
        )
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
