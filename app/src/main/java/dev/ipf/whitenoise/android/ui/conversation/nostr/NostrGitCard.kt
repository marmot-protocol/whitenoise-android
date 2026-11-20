package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.nostr.NostrEvent

private const val KIND_GIT_REPOSITORY = 30_617
private const val KIND_GIT_REFS = 30_618
internal const val KIND_GIT_PATCH = 1_617
private const val KIND_GIT_PULL_REQUEST = 1_618
private const val KIND_GIT_PULL_REQUEST_UPDATE = 1_619
private const val KIND_GIT_ISSUE = 1_621
private const val KIND_GIT_OPEN = 1_630
private const val KIND_GIT_APPLIED = 1_631
private const val KIND_GIT_CLOSED = 1_632
private const val KIND_GIT_DRAFT = 1_633
private const val KIND_GIT_SERVICES = 10_317
internal const val KIND_GIT_COMMENT = 1_111
private const val MAX_GIT_REFS = 8
private const val MAX_GIT_LINKS = 8
private const val MAX_GIT_LINK_LENGTH = 512
private const val GIT_ROOT_MARKER_INDEX = 3
private const val GIT_COORDINATE_PARTS = 3

internal fun NostrEvent.isGitCard(): Boolean =
    when (kind) {
        KIND_GIT_COMMENT -> gitCommentRoot() != null
        else -> gitKindLabel(kind) != null
    }

internal fun gitCardLabel(kind: Int): Int = gitKindLabel(kind) ?: R.string.nostr_git_comment

private fun gitKindLabel(kind: Int): Int? =
    when (kind) {
        KIND_GIT_REPOSITORY -> R.string.nostr_git_repository
        KIND_GIT_REFS -> R.string.nostr_git_refs
        KIND_GIT_PATCH -> R.string.nostr_git_patch
        KIND_GIT_PULL_REQUEST -> R.string.nostr_git_pr
        KIND_GIT_PULL_REQUEST_UPDATE -> R.string.nostr_git_update
        KIND_GIT_ISSUE -> R.string.nostr_git_issue
        KIND_GIT_OPEN -> R.string.nostr_git_open
        KIND_GIT_APPLIED -> R.string.nostr_git_applied
        KIND_GIT_CLOSED -> R.string.nostr_git_closed
        KIND_GIT_DRAFT -> R.string.nostr_git_draft
        KIND_GIT_SERVICES -> R.string.nostr_git_services
        else -> null
    }

internal fun NostrEvent.gitCardMetadata(): List<String> =
    buildList {
        gitRepositoryContext()?.let(::add)
        when (kind) {
            KIND_GIT_REPOSITORY -> gitRepositoryIdentifier()?.let(::add)
            KIND_GIT_REFS -> {
                firstSafeTag("HEAD")?.let(::add)
                tags
                    .asSequence()
                    .filter { it.firstOrNull()?.startsWith("refs/") == true && !it.getOrNull(1).isNullOrBlank() }
                    .take(MAX_GIT_REFS)
                    .forEach { add("${it[0].safeField()} · ${it[1].safeField()}") }
            }
            KIND_GIT_PATCH -> firstSafeTag("commit")?.let { add("commit $it") }
            KIND_GIT_PULL_REQUEST, KIND_GIT_PULL_REQUEST_UPDATE -> addAll(gitPullRequestMetadata())
            in KIND_GIT_OPEN..KIND_GIT_DRAFT ->
                tags
                    .firstOrNull { it.firstOrNull() == "e" && it.getOrNull(GIT_ROOT_MARKER_INDEX) == "root" }
                    ?.getOrNull(1)
                    ?.safeField()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            KIND_GIT_COMMENT ->
                listOfNotNull(gitCommentRoot(), firstSafeTag("e") ?: firstSafeTag("a"))
                    .distinct()
                    .let(::addAll)
        }
    }

private fun NostrEvent.gitPullRequestMetadata(): List<String> =
    listOfNotNull(
        firstSafeTag("branch-name")?.let { "branch $it" },
        firstSafeTag("c")?.let { "commit $it" },
        firstSafeTag("merge-base")?.let { "merge-base $it" },
        firstSafeTag(if (kind == KIND_GIT_PULL_REQUEST_UPDATE) "E" else "e"),
    )

/**
 * The `d` identifier of an announcement whose card title is a different, human-readable name. When no name is
 * present the identifier already is the title.
 */
private fun NostrEvent.gitRepositoryIdentifier(): String? {
    val identifier = firstSafeTag("d") ?: return null
    val title = firstSafeTag("subject") ?: firstSafeTag("title") ?: firstSafeTag("name")
    return identifier.takeIf { title != null && title != it }
}

/** The repository named by the first valid `30617:<pubkey>:<d>` coordinate in an `a` tag, if any. */
private fun NostrEvent.gitRepositoryContext(): String? =
    tags
        .asSequence()
        .filter { it.firstOrNull() == "a" }
        .mapNotNull { tag ->
            val parts = tag.getOrNull(1)?.split(':', limit = GIT_COORDINATE_PARTS)
            parts
                ?.takeIf { it.size == GIT_COORDINATE_PARTS && it[0] == KIND_GIT_REPOSITORY.toString() }
                ?.takeIf { gitEventId.matches(it[1]) }
                ?.get(2)
                ?.safeField()
                ?.takeIf(String::isNotBlank)
        }.firstOrNull()

/**
 * Exact advertised URLs for the reader's Copy and confirmed Open actions.
 * Rendering never opens them or starts a Git operation.
 */
internal fun NostrEvent.gitCardLinks(): List<String> {
    val allowedTags =
        when (kind) {
            KIND_GIT_REPOSITORY -> setOf("clone", "web", "relays")
            KIND_GIT_PULL_REQUEST, KIND_GIT_PULL_REQUEST_UPDATE -> setOf("clone")
            KIND_GIT_SERVICES -> setOf("g")
            else -> return emptyList()
        }
    return tags
        .asSequence()
        .filter { it.firstOrNull() in allowedTags }
        .flatMap { it.asSequence().drop(1) }
        .filter { it.length <= MAX_GIT_LINK_LENGTH && it == it.safeReaderBody() }
        .filter(::isCopyableCloneAddress)
        .distinct()
        .take(MAX_GIT_LINKS)
        .toList()
}

private fun isCopyableCloneAddress(value: String): Boolean {
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return scpCloneAddress.matches(value)
    val scheme = uri.scheme?.lowercase()
    val userInfo = uri.rawUserInfo
    val userAllowed =
        when {
            userInfo == null -> true
            scheme == "nostr" -> nip05LocalPart.matches(userInfo)
            scheme == "ssh" -> sshUserName.matches(userInfo)
            else -> false
        }
    return uri.host != null && scheme in gitUrlSchemes && userAllowed
}

private val gitUrlSchemes = setOf("https", "http", "wss", "ws", "ssh", "git", "nostr")
private val nip05LocalPart = Regex("[a-z0-9_.-]+")

/** A username without a password, as in `ssh://git@host/path`; copy-only, never opened. */
private val sshUserName = Regex("[A-Za-z0-9_.-]+")

/** SCP-style `user@host:path` clone address; copy-only. Host and path cannot start with `-` or `/`. */
private val scpCloneAddress =
    Regex("[A-Za-z0-9_.-]+@[A-Za-z0-9][A-Za-z0-9.-]*:[A-Za-z0-9_.~][A-Za-z0-9_.~/+-]*")
private val gitObjectId = Regex("[0-9a-fA-F]{40}")
private val gitEventId = Regex("[0-9a-fA-F]{64}")

private fun NostrEvent.gitCommentRoot(): String? {
    if (firstTagValue("K")?.toIntOrNull() !in setOf(KIND_GIT_PATCH, KIND_GIT_PULL_REQUEST, KIND_GIT_ISSUE)) return null
    return firstTagValue("E")?.takeIf(gitEventId::matches)
}

/** Repository addresses carry a public key between the kind and repository name. */
internal fun gitPreviewField(value: String): String? {
    if (gitEventId.matches(value) || gitObjectId.matches(value)) return null
    val parts = value.split(':', limit = 3)
    return if (parts.size == 3 &&
        parts[0].toIntOrNull() == KIND_GIT_REPOSITORY &&
        gitEventId.matches(parts[1]) &&
        parts[2].isNotBlank()
    ) {
        parts[2]
    } else {
        value
    }
}
