package dev.ipf.darkmatter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupMemberMenuActionsTest {
    @Test
    fun adminViewerGetsGrantAndRemoveForOtherNonAdmin() {
        assertEquals(
            listOf(GroupMemberMenuAction.GrantAdmin, GroupMemberMenuAction.RemoveMember),
            groupMemberMenuActions(
                viewerIsMember = true,
                viewerIsAdmin = true,
                targetIsSelf = false,
                targetIsAdmin = false,
            ),
        )
    }

    @Test
    fun adminViewerGetsRevokeAndRemoveForOtherAdmin() {
        assertEquals(
            listOf(GroupMemberMenuAction.RevokeAdmin, GroupMemberMenuAction.RemoveMember),
            groupMemberMenuActions(
                viewerIsMember = true,
                viewerIsAdmin = true,
                targetIsSelf = false,
                targetIsAdmin = true,
            ),
        )
    }

    @Test
    fun adminViewerGetsStepDownOnlyForOwnAdminRow() {
        assertEquals(
            listOf(GroupMemberMenuAction.StepDownAsAdmin),
            groupMemberMenuActions(
                viewerIsMember = true,
                viewerIsAdmin = true,
                targetIsSelf = true,
                targetIsAdmin = true,
            ),
        )
    }

    @Test
    fun nonAdminViewerGetsNoMemberMenuActions() {
        assertEquals(
            emptyList<GroupMemberMenuAction>(),
            groupMemberMenuActions(
                viewerIsMember = true,
                viewerIsAdmin = false,
                targetIsSelf = false,
                targetIsAdmin = true,
            ),
        )
    }

    @Test
    fun nonMemberViewerGetsNoMemberMenuActions() {
        assertEquals(
            emptyList<GroupMemberMenuAction>(),
            groupMemberMenuActions(
                viewerIsMember = false,
                viewerIsAdmin = false,
                targetIsSelf = false,
                targetIsAdmin = false,
            ),
        )
    }
}
