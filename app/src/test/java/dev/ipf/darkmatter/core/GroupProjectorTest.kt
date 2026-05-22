package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.marmotprotocol.marmotkit.AppGroupMemberRecordFfi
import org.marmotprotocol.marmotkit.AppGroupRecordFfi

class GroupProjectorTest {
    @Test
    fun memberReferencePrefersResolvedAccountId() {
        val resolved = member(memberId = "credential-a", account = "alice")
        val unresolved = member(memberId = "credential-b", account = null)

        assertEquals("alice", GroupProjector.memberRef(resolved))
        assertEquals("credential-b", GroupProjector.memberRef(unresolved))
    }

    @Test
    fun adminStatusAcceptsAccountOrMemberCredentialIds() {
        val group = group(admins = listOf("alice", "credential-b"))

        assertTrue(GroupProjector.isAdmin(group, member(memberId = "credential-a", account = "alice")))
        assertTrue(GroupProjector.isAdmin(group, member(memberId = "credential-b", account = "bob")))
        assertFalse(GroupProjector.isAdmin(group, member(memberId = "credential-c", account = "carol")))
    }

    @Test
    fun lastAdminCannotLeaveGroup() {
        assertFalse(GroupProjector.canLeaveGroup(group(admins = listOf("alice")), activeAccountIdHex = "alice"))
        assertTrue(GroupProjector.canLeaveGroup(group(admins = listOf("alice", "bob")), activeAccountIdHex = "alice"))
        assertTrue(GroupProjector.canLeaveGroup(group(admins = listOf("alice")), activeAccountIdHex = "carol"))
    }

    @Test
    fun unnamedChatTitleUsesOtherMemberDisplayName() {
        val unnamed = group(name = "")

        assertEquals(
            "Alice",
            GroupProjector.displayTitle(
                group = unnamed,
                otherMemberAccount = "alice",
                memberCount = 2,
                displayName = { "Alice" },
            ),
        )
    }

    @Test
    fun namedChatTitleUsesGroupName() {
        val named = group(name = "Product")

        assertEquals(
            "Product",
            GroupProjector.displayTitle(
                group = named,
                otherMemberAccount = "alice",
                memberCount = 2,
                displayName = { "Alice" },
            ),
        )
    }

    private fun member(
        memberId: String,
        account: String?,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = memberId,
        account = account,
        local = local,
    )

    private fun group(
        admins: List<String> = emptyList(),
        name: String = "Test Group",
    ) = AppGroupRecordFfi(
        groupIdHex = "group",
        endpoint = "endpoint",
        name = name,
        description = "A group",
        admins = admins,
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "nostr",
        archived = false,
    )
}
