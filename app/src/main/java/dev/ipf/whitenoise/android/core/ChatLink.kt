package dev.ipf.whitenoise.android.core

import java.net.URI

/** What an inbound link says about a chat: not a chat link, a chat link with an unusable id, or a group id. */
sealed interface ChatLinkParse {
    data object NotChatLink : ChatLinkParse

    data object Malformed : ChatLinkParse

    data class Valid(
        val groupIdHex: String,
    ) : ChatLinkParse
}

/**
 * Parses `marmot://chat/<group-id>` and the flavor-scheme form (#3658).
 *
 * The group id is the MLS group id in hex, compared lowercase everywhere else, so an upper-case link is
 * normalized rather than rejected. Anything else after `chat` is malformed, so a bad link can never open a
 * different conversation.
 */
object ChatLink {
    private const val CHAT_HOST = "chat"

    // MDK accepts chosen group ids of 1 to 255 bytes; created groups use 16.
    private const val MAX_GROUP_ID_BYTES = 255
    private val HEX = Regex("^[0-9a-f]+$")

    /** The canonical link for a chat, in the form [parse] reads back; shared from chat details (#3658). */
    fun uri(groupIdHex: String): String = "${ProfileLink.MARMOT_SCHEME}://$CHAT_HOST/${groupIdHex.lowercase()}"

    fun parse(raw: String): ChatLinkParse {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        return when {
            uri == null -> if (looksLikeChatLink(trimmed)) ChatLinkParse.Malformed else ChatLinkParse.NotChatLink
            uri.scheme?.lowercase() !in ProfileLink.PROFILE_SCHEMES -> ChatLinkParse.NotChatLink
            else -> parseRoute(uri)
        }
    }

    private fun parseRoute(uri: URI): ChatLinkParse {
        val segments = routeSegments(uri)
        if (!segments.firstOrNull().equals(CHAT_HOST, ignoreCase = true)) return ChatLinkParse.NotChatLink
        val groupIdHex = segments.drop(1).singleOrNull()?.lowercase()
        val plainAuthority = uri.isOpaque || (uri.userInfo == null && uri.port == -1)
        return if (plainAuthority && groupIdHex != null && isGroupIdHex(groupIdHex)) {
            ChatLinkParse.Valid(groupIdHex)
        } else {
            ChatLinkParse.Malformed
        }
    }

    private fun routeSegments(uri: URI): List<String> =
        if (uri.isOpaque) {
            uri.rawSchemeSpecificPart
                .orEmpty()
                .substringBefore('?')
                .split('/')
                .filter(String::isNotEmpty)
        } else {
            listOfNotNull(uri.host) +
                uri.rawPath
                    .orEmpty()
                    .split('/')
                    .filter(String::isNotEmpty)
        }

    /** A link that names the chat route but is not a parseable URI, such as one with a space in its id. */
    private fun looksLikeChatLink(value: String): Boolean {
        val scheme = value.substringBefore(':', missingDelimiterValue = "").lowercase()
        val route = value.substringAfter(':').trimStart('/')
        return scheme in ProfileLink.PROFILE_SCHEMES &&
            route.startsWith(CHAT_HOST, ignoreCase = true) &&
            route.getOrNull(CHAT_HOST.length).let { it == null || it == '/' || it == '?' || it == '#' }
    }

    private fun isGroupIdHex(value: String): Boolean {
        val evenLengthWithinLimit = value.length % 2 == 0 && value.length <= MAX_GROUP_ID_BYTES * 2
        return evenLengthWithinLimit && HEX.matches(value)
    }
}

/** Which account a chat link opens in, given every account that holds the group. */
sealed interface ChatLinkDestination {
    data object NotFound : ChatLinkDestination

    data class Open(
        val accountRef: String,
    ) : ChatLinkDestination

    data class Choose(
        val accountRefs: List<String>,
    ) : ChatLinkDestination
}

/** The active account wins when it holds the chat; one other holder opens directly; several ask. */
fun resolveChatLinkDestination(
    holders: List<String>,
    activeAccountRef: String?,
): ChatLinkDestination =
    when {
        holders.isEmpty() -> ChatLinkDestination.NotFound
        activeAccountRef != null && activeAccountRef in holders -> ChatLinkDestination.Open(activeAccountRef)
        holders.size == 1 -> ChatLinkDestination.Open(holders.single())
        else -> ChatLinkDestination.Choose(holders)
    }
