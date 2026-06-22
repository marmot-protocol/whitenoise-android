package dev.ipf.darkmatter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSheetAdminActionsTest {
    @Test
    fun adminViewerGetsGrantAndRemoveForOtherNonAdminMember() {
        assertEquals(
            listOf(GroupMemberMenuAction.GrantAdmin, GroupMemberMenuAction.RemoveMember),
            profileSheetAdminActions(
                viewerIsMember = true,
                viewerIsAdmin = true,
                targetIsMember = true,
                targetIsSelf = false,
                targetIsAdmin = false,
            ),
        )
    }

    @Test
    fun adminViewerGetsRevokeAndRemoveForOtherAdminMember() {
        assertEquals(
            listOf(GroupMemberMenuAction.RevokeAdmin, GroupMemberMenuAction.RemoveMember),
            profileSheetAdminActions(
                viewerIsMember = true,
                viewerIsAdmin = true,
                targetIsMember = true,
                targetIsSelf = false,
                targetIsAdmin = true,
            ),
        )
    }

    @Test
    fun selfTargetGetsNoActionsEvenWhenAdmin() {
        // Self is excluded on this surface: StepDownAsAdmin must never leak in.
        assertEquals(
            emptyList<GroupMemberMenuAction>(),
            profileSheetAdminActions(
                viewerIsMember = true,
                viewerIsAdmin = true,
                targetIsMember = true,
                targetIsSelf = true,
                targetIsAdmin = true,
            ),
        )
    }

    @Test
    fun nonMemberTargetGetsNoActions() {
        // Viewed user has no member record in this group -> scope fails.
        assertEquals(
            emptyList<GroupMemberMenuAction>(),
            profileSheetAdminActions(
                viewerIsMember = true,
                viewerIsAdmin = true,
                targetIsMember = false,
                targetIsSelf = false,
                targetIsAdmin = false,
            ),
        )
    }

    @Test
    fun nonAdminViewerGetsNoActions() {
        assertEquals(
            emptyList<GroupMemberMenuAction>(),
            profileSheetAdminActions(
                viewerIsMember = true,
                viewerIsAdmin = false,
                targetIsMember = true,
                targetIsSelf = false,
                targetIsAdmin = false,
            ),
        )
    }

    @Test
    fun nonMemberViewerGetsNoActions() {
        assertEquals(
            emptyList<GroupMemberMenuAction>(),
            profileSheetAdminActions(
                viewerIsMember = false,
                viewerIsAdmin = true,
                targetIsMember = true,
                targetIsSelf = false,
                targetIsAdmin = false,
            ),
        )
    }
}
