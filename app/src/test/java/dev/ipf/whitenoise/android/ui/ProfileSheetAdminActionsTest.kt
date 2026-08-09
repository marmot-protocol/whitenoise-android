package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.group.GroupMemberMenuAction
import dev.ipf.whitenoise.android.ui.profile.profileSheetAdminActions
import dev.ipf.whitenoise.android.ui.profile.stableAdminActionTargetIsAdmin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSheetAdminActionsTest {
    @Test
    fun pendingAdminActionKeepsItsOriginalDirectionUntilCompletion() {
        assertFalse(
            stableAdminActionTargetIsAdmin(
                authoritativeAdmin = true,
                pendingAction = GroupMemberMenuAction.GrantAdmin,
            ),
        )
        assertTrue(
            stableAdminActionTargetIsAdmin(
                authoritativeAdmin = false,
                pendingAction = GroupMemberMenuAction.RevokeAdmin,
            ),
        )
    }

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
