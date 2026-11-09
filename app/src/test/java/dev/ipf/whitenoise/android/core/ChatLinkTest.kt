package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLinkTest {
    private val groupId = "0123456789abcdef0123456789abcdef"

    @Test
    fun parse_marmotChatLink_returnsGroupId() {
        assertEquals(ChatLinkParse.Valid(groupId), ChatLink.parse("marmot://chat/$groupId"))
    }

    @Test
    fun parse_flavorSchemes_returnGroupId() {
        listOf("whitenoise", "whitenoise-staging", "whitenoise-dev").forEach { scheme ->
            assertEquals(scheme, ChatLinkParse.Valid(groupId), ChatLink.parse("$scheme://chat/$groupId"))
        }
    }

    @Test
    fun parse_upperCaseLink_normalizesGroupId() {
        assertEquals(
            ChatLinkParse.Valid(groupId),
            ChatLink.parse("MARMOT://CHAT/${groupId.uppercase()}"),
        )
    }

    @Test
    fun parse_opaqueForm_returnsGroupId() {
        assertEquals(ChatLinkParse.Valid(groupId), ChatLink.parse("marmot:chat/$groupId"))
    }

    @Test
    fun parse_queryFragmentTrailingSlashAndWhitespace_areIgnored() {
        listOf(
            "marmot://chat/$groupId?source=share",
            "marmot://chat/$groupId#top",
            "marmot://chat/$groupId/",
            "  marmot://chat/$groupId \n",
            "marmot:chat/$groupId?source=share",
        ).forEach { link ->
            assertEquals(link, ChatLinkParse.Valid(groupId), ChatLink.parse(link))
        }
    }

    @Test
    fun parse_largestMdkGroupId_isValid() {
        val largest = "ab".repeat(255)
        assertEquals(ChatLinkParse.Valid(largest), ChatLink.parse("marmot://chat/$largest"))
    }

    @Test
    fun parse_unusableGroupIds_areMalformed() {
        listOf(
            "marmot://chat",
            "marmot://chat/",
            "marmot:chat",
            "marmot://chat/abc",
            "marmot://chat/zz$groupId",
            "marmot://chat/${"ab".repeat(256)}",
            "marmot://chat/$groupId/extra",
            "marmot://chat/$groupId/$groupId",
            "marmot://chat/0x$groupId",
            "marmot://chat/%30%31",
            "marmot://chat/$groupId space",
            "marmot://user@chat/$groupId",
            "marmot://chat:80/$groupId",
            "whitenoise:chat/not-hex",
        ).forEach { link ->
            assertEquals(link, ChatLinkParse.Malformed, ChatLink.parse(link))
        }
    }

    @Test
    fun parse_otherLinks_areNotChatLinks() {
        listOf(
            "",
            "marmot://profile/npub1abc",
            "whitenoise://profile/npub1abc",
            "marmot://chats/$groupId",
            "marmot://chatroom/$groupId",
            "https://chat/$groupId",
            "https://whitenoise.chat/chat/$groupId",
            "nostr:npub1abc",
            "chat/$groupId",
            "not a uri at all",
        ).forEach { link ->
            assertEquals(link, ChatLinkParse.NotChatLink, ChatLink.parse(link))
        }
    }

    @Test
    fun resolveDestination_noHolder_isNotFound() {
        assertEquals(ChatLinkDestination.NotFound, resolveChatLinkDestination(emptyList(), "acct-a"))
        assertEquals(ChatLinkDestination.NotFound, resolveChatLinkDestination(emptyList(), null))
    }

    @Test
    fun resolveDestination_activeAccountHolds_opensThereEvenWithOtherHolders() {
        assertEquals(
            ChatLinkDestination.Open("acct-b"),
            resolveChatLinkDestination(listOf("acct-a", "acct-b", "acct-c"), "acct-b"),
        )
    }

    @Test
    fun resolveDestination_singleInactiveHolder_opensThere() {
        assertEquals(ChatLinkDestination.Open("acct-a"), resolveChatLinkDestination(listOf("acct-a"), "acct-b"))
        assertEquals(ChatLinkDestination.Open("acct-a"), resolveChatLinkDestination(listOf("acct-a"), null))
    }

    @Test
    fun resolveDestination_severalInactiveHolders_asks() {
        assertEquals(
            ChatLinkDestination.Choose(listOf("acct-a", "acct-c")),
            resolveChatLinkDestination(listOf("acct-a", "acct-c"), "acct-b"),
        )
    }
}
