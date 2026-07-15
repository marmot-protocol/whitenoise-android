package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-driven coverage of the unified deletion-capability matrix. This is the
 * single model both the delete surface and the controller mutation path
 * consult, so these rows are the product's deletion permission contract:
 *
 *  - Own message: delete for me or for everyone, direct or group.
 *  - Someone else's direct message: delete for me only — even when the DM's
 *    underlying two-member MLS group flags the current user as admin (DM
 *    creators usually are), and regardless of any admin role held in other
 *    groups (which never reaches this function: each conversation's
 *    controller passes only its own membership role).
 *  - Another member's group message: for everyone only for this group's
 *    admins/owners; regular members delete for me only.
 */
class MessageDeleteAuthorizationTest {
    private data class Row(
        val name: String,
        val isDirectConversation: Boolean,
        val mine: Boolean,
        val selfIsAdmin: Boolean,
        val localDeleteSupported: Boolean = true,
        val remoteDeleteSupported: Boolean = true,
        val alreadyDeleted: Boolean = false,
        val expectForMe: Boolean,
        val expectForEveryone: Boolean,
    )

    private val matrix =
        listOf(
            Row(
                name = "direct + own message",
                isDirectConversation = true,
                mine = true,
                selfIsAdmin = false,
                expectForMe = true,
                expectForEveryone = true,
            ),
            Row(
                name = "direct + another participant's message",
                isDirectConversation = true,
                mine = false,
                selfIsAdmin = false,
                expectForMe = true,
                expectForEveryone = false,
            ),
            Row(
                // The admin-role-must-not-leak rule. A role held in another
                // group can never reach this call (the controller passes its
                // own conversation's role), and even a true admin flag on the
                // DM's own two-member MLS group grants no moderation here.
                name = "direct + another participant's message + admin flag",
                isDirectConversation = true,
                mine = false,
                selfIsAdmin = true,
                expectForMe = true,
                expectForEveryone = false,
            ),
            Row(
                name = "group + own message",
                isDirectConversation = false,
                mine = true,
                selfIsAdmin = false,
                expectForMe = true,
                expectForEveryone = true,
            ),
            Row(
                name = "group + another member's message as admin/owner",
                isDirectConversation = false,
                mine = false,
                selfIsAdmin = true,
                expectForMe = true,
                expectForEveryone = true,
            ),
            Row(
                name = "group + another member's message as regular member",
                isDirectConversation = false,
                mine = false,
                selfIsAdmin = false,
                expectForMe = true,
                expectForEveryone = false,
            ),
            Row(
                name = "already deleted message offers nothing",
                isDirectConversation = false,
                mine = true,
                selfIsAdmin = true,
                alreadyDeleted = true,
                expectForMe = false,
                expectForEveryone = false,
            ),
            Row(
                name = "no usable message id supports no deletion",
                isDirectConversation = false,
                mine = true,
                selfIsAdmin = true,
                localDeleteSupported = false,
                remoteDeleteSupported = false,
                expectForMe = false,
                expectForEveryone = false,
            ),
            Row(
                // Membership unverified / publish path unavailable: local
                // removal still works, but nothing may be published —
                // including moderation, so a transiently misclassified roster
                // can never leak a remote delete.
                name = "group admin without a publish path deletes locally only",
                isDirectConversation = false,
                mine = false,
                selfIsAdmin = true,
                remoteDeleteSupported = false,
                expectForMe = true,
                expectForEveryone = false,
            ),
            Row(
                name = "own message without a publish path deletes locally only",
                isDirectConversation = true,
                mine = true,
                selfIsAdmin = false,
                remoteDeleteSupported = false,
                expectForMe = true,
                expectForEveryone = false,
            ),
        )

    @Test
    fun capabilityMatrix() {
        matrix.forEach { row ->
            val capability =
                messageDeleteCapability(
                    isDirectConversation = row.isDirectConversation,
                    mine = row.mine,
                    selfIsAdmin = row.selfIsAdmin,
                    localDeleteSupported = row.localDeleteSupported,
                    remoteDeleteSupported = row.remoteDeleteSupported,
                    alreadyDeleted = row.alreadyDeleted,
                    // The matrix is the product contract; it is exercised with
                    // moderation enabled so the policy stays pinned while the
                    // runtime support flag is off.
                    moderationDeleteSupported = true,
                )
            assertEquals("${row.name}: canDeleteForMe", row.expectForMe, capability.canDeleteForMe)
            assertEquals("${row.name}: canDeleteForEveryone", row.expectForEveryone, capability.canDeleteForEveryone)
        }
    }

    @Test
    fun canDeleteAtAllIsTheUnionOfBothScopes() {
        assertEquals(
            false,
            MessageDeleteCapability(canDeleteForMe = false, canDeleteForEveryone = false).canDeleteAtAll,
        )
        assertEquals(
            true,
            MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = false).canDeleteAtAll,
        )
        assertEquals(
            true,
            MessageDeleteCapability(canDeleteForMe = false, canDeleteForEveryone = true).canDeleteAtAll,
        )
    }

    @Test
    fun moderationStaysOffUntilTheRuntimeCanDeliverIt() {
        // The runtime currently honours only self-authored deletes, so with
        // the shipped default a group admin is NOT offered delete-for-everyone
        // on another member's message — offering it would delete locally while
        // every other member silently keeps the message.
        assertEquals(false, GROUP_MODERATION_DELETE_SUPPORTED)
        val capability =
            messageDeleteCapability(
                isDirectConversation = false,
                mine = false,
                selfIsAdmin = true,
                localDeleteSupported = true,
                remoteDeleteSupported = true,
                alreadyDeleted = false,
            )
        assertEquals(true, capability.canDeleteForMe)
        assertEquals(false, capability.canDeleteForEveryone)
    }
}
