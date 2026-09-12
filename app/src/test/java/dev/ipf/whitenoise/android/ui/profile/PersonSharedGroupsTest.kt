package dev.ipf.whitenoise.android.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared group membership and callback lifetime are authoritative, separate from profile or roster presentation. */
class PersonSharedGroupsTest {
    /** DMs, pending invitations and absent membership cannot be presented as a shared group. */
    @Test fun sharedGroupsRequireBothMembersAndConfirmedNonDirectState() {
        val rows =
            listOf(
                personTestGroup("shared", "Group", members = listOf("self", "target", "third")),
                personTestGroup("dm", "", members = listOf("self", "target")),
                personTestGroup("pending", "Invite", members = listOf("self", "target"), pending = true),
                personTestGroup("absent", "Other", members = listOf("self", "third")),
                personTestGroup("left", "Left", members = listOf("target", "third")),
            )
        assertEquals(listOf("shared"), personSharedGroups(rows, "self", "target").groups.map { it.group.groupIdHex })
    }

    /** Missing current roster is unresolved even if old display counts look populated. */
    @Test fun missingRosterDoesNotBecomeAnEmptyOrInventedGroupList() {
        val row = personTestGroup("unknown", "Group", members = null).copy(presentationMemberCount = 9)
        val state = personSharedGroups(listOf(row), "self", "target")
        assertTrue(state.groups.isEmpty())
        assertEquals(setOf("unknown"), state.unresolvedGroupIds)
    }

    /** Membership and duplicate IDs normalize casing without rewriting the authoritative row. */
    @Test fun caseInsensitiveRosterAndDuplicateIdsKeepOnlyOneGroup() {
        val row = personTestGroup("ABC", "Group", members = listOf("SELF", "TARGET", "third"))
        assertEquals(listOf(row), personSharedGroups(listOf(row, row), "self", "target").groups)
    }

    /** Self or missing identity never produces contact-only group actions. */
    @Test fun selfAndUnresolvedProfileHaveNoGroupProjection() {
        val row = personTestGroup("group", "Group", members = listOf("self", "target"))
        assertTrue(personSharedGroups(listOf(row), "self", "SELF").groups.isEmpty())
        assertTrue(personSharedGroups(listOf(row), null, "target").groups.isEmpty())
    }

    /** A dismissed window cannot revive its callbacks if the account becomes available later. */
    @Test fun disposalPermanentlyRevokesCallbacks() {
        val owner = PersonProfileOwner { true }
        assertTrue(owner.canAct())
        owner.dispose()
        assertFalse(owner.canAct())
    }

    /** Native admission checks the live owner immediately before a later stage begins. */
    @Test fun ownerReplacementRevokesNativeAdmission() {
        var available = true
        val owner = PersonProfileOwner { available }
        available = false
        assertFalse(owner.canAct())
        assertTrue(runCatching { owner.requireCurrent() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
