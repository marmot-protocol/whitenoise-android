package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.nostr.NostrEvent

internal fun NostrEvent.isGitCard(): Boolean =
    when (kind) {
        30617, 30618, 1617, 1618, 1619, 1621, in 1630..1633, 10317 -> true
        1111 -> gitCommentRoot() != null
        else -> false
    }

internal fun gitCardLabel(kind: Int): Int =
    when (kind) {
        30617 -> R.string.nostr_git_repository
        30618 -> R.string.nostr_git_refs
        1617 -> R.string.nostr_git_patch
        1618 -> R.string.nostr_git_pr
        1619 -> R.string.nostr_git_update
        1621 -> R.string.nostr_git_issue
        1630 -> R.string.nostr_git_open
        1631 -> R.string.nostr_git_applied
        1632 -> R.string.nostr_git_closed
        1633 -> R.string.nostr_git_draft
        10317 -> R.string.nostr_git_services
        else -> R.string.nostr_git_comment
    }

internal fun NostrEvent.gitCardMetadata(): List<String> =
    buildList {
        firstSafeTag("a")?.let(::add)
        when (kind) {
            30618 -> {
                firstSafeTag("HEAD")?.let(::add)
                tags
                    .asSequence()
                    .filter { it.firstOrNull()?.startsWith("refs/") == true && !it.getOrNull(1).isNullOrBlank() }
                    .take(8)
                    .forEach { add("${it[0].safeField()} · ${it[1].safeField()}") }
            }
            1617 -> firstSafeTag("commit")?.let(::add)
            1618, 1619 ->
                listOfNotNull(
                    firstSafeTag("branch-name"),
                    firstSafeTag("c"),
                    firstSafeTag("merge-base"),
                    firstSafeTag(if (kind == 1619) "E" else "e"),
                ).let(::addAll)
            in 1630..1633 ->
                tags
                    .firstOrNull { it.firstOrNull() == "e" && it.getOrNull(3) == "root" }
                    ?.getOrNull(1)
                    ?.safeField()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            1111 ->
                listOfNotNull(gitCommentRoot(), firstSafeTag("e") ?: firstSafeTag("a"))
                    .distinct()
                    .let(::addAll)
        }
    }

/** Copy exact advertised URLs on demand. Rendering never opens them or starts a Git operation. */
internal fun NostrEvent.gitCardLinks(): List<String> {
    val allowedTags =
        when (kind) {
            30617 -> setOf("clone", "web", "relays")
            1618, 1619 -> setOf("clone")
            10317 -> setOf("g")
            else -> return emptyList()
        }
    return tags
        .asSequence()
        .filter { it.firstOrNull() in allowedTags }
        .flatMap { it.asSequence().drop(1) }
        .filter { it.length <= 512 && it == it.safeReaderBody() }
        .filter { value ->
            val uri = runCatching { java.net.URI(value) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            val userInfo = uri?.rawUserInfo
            uri?.host != null &&
                scheme in gitUrlSchemes &&
                (userInfo == null || scheme == "nostr" && nip05LocalPart.matches(userInfo))
        }.distinct()
        .take(8)
        .toList()
}

private val gitUrlSchemes = setOf("https", "http", "wss", "ws", "ssh", "git", "nostr")
private val nip05LocalPart = Regex("[a-z0-9_.-]+")
private val gitEventId = Regex("[0-9a-fA-F]{64}")

private fun NostrEvent.gitCommentRoot(): String? {
    if (firstTagValue("K")?.toIntOrNull() !in setOf(1617, 1618, 1621)) return null
    return firstTagValue("E")?.takeIf(gitEventId::matches)
}
