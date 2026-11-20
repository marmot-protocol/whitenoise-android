package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.nostr.NostrEvent

private val patchSubjectPrefix = Regex("^(\\[[^\\]]*]\\s*)+")

/** The format-patch `Subject:` header of a kind 1617 patch, without its `[PATCH n/m]` tag. */
internal fun NostrEvent.patchSubject(): String? {
    val header = content.lineSequence().takeWhile(String::isNotBlank).toList()
    val start = header.indexOfFirst { it.startsWith("Subject:", ignoreCase = true) }
    return if (kind != KIND_GIT_PATCH || start < 0) {
        null
    } else {
        val folded = header.drop(start + 1).takeWhile { it.firstOrNull()?.isWhitespace() == true }
        val subject = (listOf(header[start].substringAfter(':')) + folded).joinToString(" ") { it.trim() }
        subject.replace(patchSubjectPrefix, "").safeField().takeIf(String::isNotBlank)
    }
}

/** A format-patch message without its mail headers, so the preview starts at the commit message. */
internal fun NostrEvent.patchMessageBody(): String? {
    val body = content.substringAfter("\n\n", "")
    return body.takeIf { patchSubject() != null && it.isNotBlank() }
}
